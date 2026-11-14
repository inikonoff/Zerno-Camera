package ru.inikonoff.zerno.viewer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.sourceforge.opencamera.R

/** Zerno's built-in photo viewer. It is not exported and has no intent filters, so it can only
 *  be opened from the camera screen; it shows only the folder the camera saves to (or, in trash
 *  mode, the photos from that folder that are in the trash).
 */
class ViewerActivity : ComponentActivity() {
    private lateinit var repository: MediaRepository
    private var trashMode = false

    private var items by mutableStateOf<List<MediaItem>>(emptyList())
    private var loaded by mutableStateOf(false)
    /** The photos most recently moved to the trash, for "Undo". */
    private var undoable by mutableStateOf<List<MediaItem>>(emptyList())
    private var brightness by mutableStateOf(0.5f)

    // Android asks the user to confirm changes to photos that this installation didn't create
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload() }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The photo turns with the phone, whatever the phone's own auto-rotate setting says
        // (the same way the camera app screen does), including upside down.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        val folder = intent.getStringExtra(EXTRA_FOLDER)
        if (folder == null) {
            finish()
            return
        }
        trashMode = intent.getBooleanExtra(EXTRA_TRASH, false)
        repository = MediaRepository(applicationContext, folder)
        brightness = currentBrightness()
        askForPermissionOnce()
        reload()

        setContent {
            ViewerScreen(
                items = items,
                loaded = loaded,
                trashMode = trashMode,
                hasTrash = repository.hasTrash,
                repository = repository,
                canUndo = undoable.isNotEmpty(),
                brightness = brightness,
                onBrightness = ::applyBrightness,
                onBack = ::finish,
                onRemove = ::remove,
                onRestore = { item -> change(listOf(item)) { repository.setTrashed(it, false) } },
                onDeleteForever = { item -> change(listOf(item)) { repository.delete(it) } },
                onEmptyTrash = { change(items) { repository.delete(it) } },
                onUndo = {
                    val restore = undoable
                    undoable = emptyList()
                    change(restore, optimistic = false) { repository.setTrashed(it, false) }
                },
                onUndoExpired = { undoable = emptyList() },
            )
        }
    }

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { repository.load(trashMode) }
            items = list
            loaded = true
        }
    }

    /** A photo swiped away: to the trash where there is one, otherwise deleted (after the
     *  screen has asked for confirmation).
     */
    private fun remove(item: MediaItem) {
        if (repository.hasTrash) {
            undoable = listOf(item)
            change(listOf(item)) { repository.setTrashed(it, true) }
        } else {
            change(listOf(item)) { repository.delete(it) }
        }
    }

    /** Applies a change to some photos. With [optimistic], they disappear from the screen at
     *  once; the list is re-read afterwards either way, so a failed change brings them back.
     */
    private fun change(targets: List<MediaItem>, optimistic: Boolean = true, action: (List<MediaItem>) -> ChangeResult) {
        if (targets.isEmpty()) return
        if (optimistic) {
            val ids = targets.map { it.id }.toSet()
            items = items.filter { it.id !in ids }
        }
        lifecycleScope.launch {
            when (val result = withContext(Dispatchers.IO) { action(targets) }) {
                ChangeResult.Done -> reload()
                is ChangeResult.NeedsConsent -> {
                    undoable = emptyList()
                    try {
                        consentLauncher.launch(IntentSenderRequest.Builder(result.intent).build())
                    } catch (e: RuntimeException) {
                        reload()
                    }
                }
                ChangeResult.Failed -> {
                    undoable = emptyList()
                    Toast.makeText(this@ViewerActivity, R.string.zerno_viewer_failed, Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        }
    }

    // ---- brightness -----------------------------------------------------------------------

    private fun currentBrightness(): Float = try {
        (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f).coerceIn(0.05f, 1f)
    } catch (e: Exception) {
        0.5f
    }

    /** Brightness of this screen only (0..1); the system setting is not touched. */
    private fun applyBrightness(value: Float) {
        brightness = value.coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = brightness }
    }

    // ---- permission -----------------------------------------------------------------------

    /** Without the permission the viewer still shows every photo this installation took. The
     *  permission adds photos already in the folder from before (e.g. from before a reinstall),
     *  so it is asked for once, and a refusal is respected.
     */
    private fun askForPermissionOnce() {
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.READ_EXTERNAL_STORAGE
            else -> return // the camera itself already has storage access there
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(KEY_ASKED_PERMISSION, false)) return
        prefs.edit().putBoolean(KEY_ASKED_PERMISSION, true).apply()
        permissionLauncher.launch(permission)
    }

    companion object {
        private const val EXTRA_FOLDER = "folder"
        private const val EXTRA_TRASH = "trash"
        private const val KEY_ASKED_PERMISSION = "zerno_viewer_asked_permission"

        /** @param folder the absolute path of the folder the camera saves to. */
        fun start(context: Context, folder: String, trash: Boolean) {
            context.startActivity(
                Intent(context, ViewerActivity::class.java).putExtra(EXTRA_FOLDER, folder).putExtra(EXTRA_TRASH, trash)
            )
        }
    }
}
