package ru.inikonoff.zerno.adapter

import android.graphics.Bitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.sourceforge.opencamera.MainActivity
import net.sourceforge.opencamera.R
import net.sourceforge.opencamera.ZernoHooks
import ru.inikonoff.zerno.ui.ZernoScreen
import java.util.WeakHashMap

/** Receives the engine's hooks (see [ZernoHooks]) and wires each MainActivity instance to its
 *  own [CameraAdapter] and Compose UI.
 */
object ZernoBridge : ZernoHooks.Listener {
    private const val POLL_INTERVAL_MS = 100L

    private val adapters = WeakHashMap<MainActivity, CameraAdapter>()

    private fun adapterFor(activity: MainActivity): CameraAdapter =
        adapters.getOrPut(activity) { CameraAdapter(activity) }

    override fun onBeforePreviewCreated(activity: MainActivity, first_run: Boolean) {
        adapterFor(activity).prepareEnginePreferences(first_run)
    }

    override fun onActivityCreated(activity: MainActivity) {
        val adapter = adapterFor(activity)
        val composeView = activity.findViewById<ComposeView>(R.id.zerno_ui)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent { ZernoScreen(adapter) }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                adapter.setActive(true)
                try {
                    while (true) {
                        adapter.poll()
                        delay(POLL_INTERVAL_MS)
                    }
                } finally {
                    adapter.setActive(false)
                }
            }
        }
    }

    override fun onCameraSetup(activity: MainActivity) {
        adapters[activity]?.onCameraSetup()
    }

    override fun onGalleryThumbnail(activity: MainActivity, thumbnail: Bitmap?) {
        adapters[activity]?.onGalleryThumbnail(thumbnail)
    }

    override fun onSettingsVisible(activity: MainActivity, visible: Boolean) {
        adapters[activity]?.onSettingsVisible(visible)
    }

    override fun onActivityDestroyed(activity: MainActivity) {
        adapters.remove(activity)
    }
}
