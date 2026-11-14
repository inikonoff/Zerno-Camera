package ru.inikonoff.zerno.adapter

import android.content.SharedPreferences
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.sourceforge.opencamera.BuildConfig
import net.sourceforge.opencamera.MainActivity
import net.sourceforge.opencamera.PreferenceKeys
import net.sourceforge.opencamera.R
import net.sourceforge.opencamera.ZernoHooks
import net.sourceforge.opencamera.cameracontroller.CameraController
import net.sourceforge.opencamera.cameracontroller.CameraController2
import net.sourceforge.opencamera.preview.Preview
import ru.inikonoff.zerno.viewer.ViewerActivity
import kotlin.math.abs
import kotlin.math.roundToInt

/** The adapter between the Zerno UI and the Open Camera engine.
 *
 *  It plays the role of the view model: the UI observes [state] and calls the action methods
 *  below; the adapter translates those into engine preferences and engine calls. It is a plain
 *  class rather than an androidx ViewModel, because everything it talks to (Preview,
 *  CameraController, MyApplicationInterface) is owned by, and has the lifetime of, MainActivity.
 *
 *  All methods must be called on the main thread.
 */
class CameraAdapter(private val activity: MainActivity) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val preview: Preview? get() = activity.preview
    private val controller: CameraController? get() = activity.preview?.cameraController

    private var zoomRatios: List<Float> = emptyList()
    private var ratioSizes: Map<Ratio, CameraController.Size> = emptyMap()

    private val qrScanner = QrScanner()
    private var qrLastSeen = 0L
    private var qrPinned = false // the QR sheet is open: keep the result, and don't scan
    private var pollCount = 0

    private var timings = Timings()
    /** A press of the shutter that came before the camera was ready: taken as soon as it is. */
    private var pendingShotUntil = 0L

    /** How far the device is turned clockwise from portrait, snapped to 0/90/180/270. The camera
     *  screen is locked to portrait, so the UI turns its icons itself.
     */
    private var deviceOrientation = 0
    private val orientationListener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            var diff = abs(orientation - deviceOrientation)
            if (diff > 180) diff = 360 - diff
            // only turn once the device is clearly in the new orientation (same rule as the engine)
            if (diff > 60) {
                deviceOrientation = (orientation + 45) / 90 * 90 % 360
                _state.update { it.copy(uiRotation = (360 - deviceOrientation) % 360) }
            }
        }
    }

    /** Called when the camera screen becomes active / inactive. */
    fun setActive(active: Boolean) {
        if (active && orientationListener.canDetectOrientation()) orientationListener.enable()
        else orientationListener.disable()
    }

    // ---- start up -------------------------------------------------------------------------

    /** Called before the engine opens the camera: makes the engine's preferences match the
     *  Zerno settings. Only writes preferences, the camera isn't available yet.
     */
    fun prepareEnginePreferences(firstRun: Boolean) {
        val editor = prefs.edit()
        if (!prefs.getBoolean(KEY_DEFAULTS_DONE, false)) {
            // one-off defaults for a fresh install
            if (activity.supportsCamera2()) {
                // manual exposure, noise reduction control and the Night mode all need Camera2
                editor.putString(PreferenceKeys.CameraAPIPreferenceKey, "preference_camera_api_camera2")
            }
            // the engine's on-preview read-outs are replaced by the Zerno UI
            editor.putBoolean(PreferenceKeys.ShowZoomPreferenceKey, false)
            editor.putBoolean(PreferenceKeys.ShowISOPreferenceKey, false)
            editor.putBoolean(PreferenceKeys.ShowBatteryPreferenceKey, false)
            editor.putBoolean(PreferenceKeys.ShowTimePreferenceKey, false)
            editor.putBoolean(PreferenceKeys.ShowFreeMemoryPreferenceKey, false)
            editor.putBoolean(PreferenceKeys.ShowCameraIDPreferenceKey, false)
            editor.putBoolean(PreferenceKeys.ShowAnglePreferenceKey, false)
            editor.putBoolean(PreferenceKeys.ThumbnailAnimationPreferenceKey, false)
            editor.putBoolean(KEY_DEFAULTS_DONE, true)
        }
        // Zerno is photo only
        editor.putBoolean(PreferenceKeys.IsVideoPreferenceKey, false)

        val mode = storedMode()
        val detail = storedDetail()
        val hdr = storedHdr()
        writeModePreferences(editor, mode, detail, hdr)
        editor.apply()

        val settings = Settings(
            metering = enumPref(KEY_METERING, Metering.SPOT),
            shadows = prefs.getInt(KEY_SHADOWS, 0),
            highlights = prefs.getInt(KEY_HIGHLIGHTS, 0),
            brightness = prefs.getInt(KEY_BRIGHTNESS, 0),
            contrast = prefs.getInt(KEY_CONTRAST, 0),
            saturation = prefs.getInt(KEY_SATURATION, 0),
            chromaDenoise = prefs.getInt(KEY_CHROMA, 0).coerceIn(0, Settings.CHROMA_MAX),
            qrEnabled = prefs.getBoolean(KEY_QR, true),
            shutterSound = prefs.getBoolean(PreferenceKeys.ShutterSoundPreferenceKey, true),
            jpegQuality = prefs.getString(PreferenceKeys.QualityPreferenceKey, "90")?.toIntOrNull() ?: 90,
            mirrorFront = prefs.getString(PreferenceKeys.FrontCameraMirrorKey, MIRROR_NO) == MIRROR_PHOTO,
        )
        applyMetering(settings.metering)
        applyImageAdjustment(settings)
        ZernoHooks.chroma_denoise = settings.chromaDenoise

        _state.update {
            it.copy(
                settings = settings,
                mode = mode, detail = detail, hdr = hdr,
                grid = prefs.getString(PreferenceKeys.ShowGridPreferenceKey, GRID_NONE) != GRID_NONE,
                level = prefs.getBoolean(PreferenceKeys.ShowAngleLinePreferenceKey, false),
                timer = timerFromPref(),
            )
        }
    }

    /** Called whenever a camera has been (re)opened: reads what it can do. */
    fun onCameraSetup() {
        val preview = preview ?: return
        if (timings.cameraOpen < ZernoHooks.getMark(ZernoHooks.MARK_RESUME)) {
            // the first set up since the app came to the front
            timings = timings.copy(cameraOpen = SystemClock.uptimeMillis(), previewStarted = 0, firstFrame = 0)
        }
        val caps = readCapabilities(preview)
        zoomRatios = if (preview.supportsZoom()) (0..preview.maxZoom).map { preview.getZoomRatio(it) } else emptyList()

        var s = _state.value
        var mode = s.mode
        var detail = s.detail
        var hdr = s.hdr
        if (mode !in caps.modes) mode = Mode.PHOTO
        if (detail !in caps.details) detail = caps.details.first()
        if (hdr !in caps.hdrs) hdr = Hdr.OFF
        val changed = mode != s.mode || detail != s.detail || hdr != s.hdr
        s = s.copy(caps = caps, mode = mode, detail = detail, hdr = hdr, cameraOpen = true)
        _state.value = s
        refreshPro()
        applyImageAdjustment(s.settings) // the preview view may be new
        startToneCurve()
        preview.zernoApplyDefaultFocusArea()
        poll()
        if (changed) {
            // the stored choice isn't available on this camera: fall back, but not from inside
            // the engine's cameraSetup()
            handler.post { applyMode(mode, detail, hdr, persist = false) }
        }
    }

    private fun readCapabilities(preview: Preview): Capabilities {
        val modes = buildList {
            if (activity.supportsNoiseReduction()) add(Mode.NIGHT)
            add(Mode.PHOTO)
            if (preview.supportsISORange() && preview.supportsExposureTime()) add(Mode.PRO)
            else if (preview.supportsFocus() && preview.supportedFocusValues?.contains(FOCUS_MANUAL) == true) add(Mode.PRO)
        }
        val nrModes = preview.supportedNoiseReductionModes ?: emptyList()
        val details = buildList {
            if ("off" in nrModes) add(Detail.OFF)
            if ("minimal" in nrModes) add(Detail.MINIMAL)
            if ("fast" in nrModes) add(Detail.FAST)
            add(Detail.STANDARD)
        }
        val hdrs = buildList {
            add(Hdr.OFF)
            if (activity.supportsDRO()) add(Hdr.DRO)
            if (activity.supportsHDR()) add(Hdr.HDR)
        }
        val manualExposure = preview.supportsISORange() && preview.supportsExposureTime()
        val wbValues = preview.supportedWhiteBalances ?: emptyList()
        val wbPresets = WB_PRESETS.filter { it in wbValues }
        val kelvins = if (preview.supportsWhiteBalanceTemperature() && "manual" in wbValues) {
            val lo = (preview.minimumWhiteBalanceTemperature + 99) / 100 * 100
            val hi = preview.maximumWhiteBalanceTemperature / 100 * 100
            if (hi > lo) (lo..hi step 100).toList() else emptyList()
        } else emptyList()
        val focusValues = (if (preview.supportsFocus()) preview.supportedFocusValues else null) ?: emptyList()
        val manualFocus = FOCUS_MANUAL in focusValues && preview.minimumFocusDistance > 0f
        val flashValues = preview.supportedFlashValues ?: emptyList()
        // for each aspect ratio, the largest resolution the camera offers in the current mode
        val sizes = preview.getSupportedPictureSizes(true) ?: emptyList()
        ratioSizes = Ratio.entries.mapNotNull { ratio ->
            sizes.filter { abs(it.width.toDouble() / it.height - ratio.value) < 0.02 }
                .maxByOrNull { it.width.toLong() * it.height }
                ?.let { ratio to it }
        }.toMap()
        return Capabilities(
            modes = modes,
            details = details,
            hdrs = hdrs,
            flashes = Flash.ORDER.filter { it in flashValues }.takeIf { it.size > 1 } ?: emptyList(),
            ratios = ratioSizes.keys.toList().takeIf { it.size > 1 } ?: emptyList(),
            focusModes = Focus.ORDER.filter { it in focusValues && (it != FOCUS_MANUAL || manualFocus) }.takeIf { it.size > 1 } ?: emptyList(),
            canSwitchCamera = preview.cameraControllerManager.numberOfCameras > 1,
            hasZoom = preview.supportsZoom(),
            zoomMin = if (preview.supportsZoom()) maxOf(1f, preview.minZoomRatio) else 1f,
            zoomMax = if (preview.supportsZoom()) preview.maxZoomRatio else 1f,
            manualExposure = manualExposure,
            isoValues = if (manualExposure) Scales.isoValues(preview.minimumISO, preview.maximumISO) else emptyList(),
            shutterValues = if (manualExposure) Scales.shutterValues(preview.minimumExposureTime, preview.maximumExposureTime) else emptyList(),
            evMin = if (preview.supportsExposures()) preview.minimumExposure else 0,
            evMax = if (preview.supportsExposures()) preview.maximumExposure else 0,
            evStep = preview.exposureStep,
            wbPresets = wbPresets,
            wbKelvinValues = kelvins,
            manualFocus = manualFocus,
            toneCurve = toneCurvePoints(preview) >= 16,
            minFocusDistance = preview.minimumFocusDistance,
        )
    }

    // ---- polling --------------------------------------------------------------------------

    /** Reads the engine's transient state. Called a few times per second while the camera
     *  screen is showing: this keeps the engine free of UI callbacks.
     */
    fun poll() {
        val preview = preview ?: return
        val controller = preview.cameraController
        val open = controller != null
        val taking = open && preview.isTakingPhoto
        val onTimer = open && preview.isOnTimer
        val s = _state.value
        val multiFrame = s.mode == Mode.NIGHT || (s.mode == Mode.PHOTO && s.hdr == Hdr.HDR)
        var pro = s.pro
        if (open && s.mode == Mode.PRO && controller != null) {
            val liveIso = if (controller.captureResultHasIso()) controller.captureResultIso() else 0
            val liveShutter = if (controller.captureResultHasExposureTime()) controller.captureResultExposureTime() else 0L
            // round, so that noise in the auto exposure doesn't cause constant recomposition
            pro = pro.copy(liveIso = liveIso, liveShutterNs = Scales.nearestShutter(liveShutter))
        }
        updatePreviewPosition()
        val (top, bottom) = previewExtent()
        pollTimings(open && preview.isPreviewStarted, taking, s)
        pollToneCurve()
        pollQr(open && !taking && !onTimer && s.mode == Mode.PHOTO && !s.settingsOpen && !s.zernoSettingsOpen && s.settings.qrEnabled)
        _state.update {
            it.copy(
                cameraOpen = open,
                takingPhoto = taking,
                onTimer = onTimer,
                holdStill = taking && !onTimer && multiFrame,
                saving = activity.applicationInterface.imageSaver.nRealImagesToSave > 0,
                frontCamera = controller?.facing == CameraController.Facing.FACING_FRONT,
                zoom = if (open && preview.supportsZoom()) preview.zoomRatio else 1f,
                pro = pro,
                previewTop = top,
                previewBottom = bottom,
            )
        }
    }

    // ---- timing and diagnostics ----------------------------------------------------------

    private fun pollTimings(previewRunning: Boolean, taking: Boolean, s: UiState) {
        val now = SystemClock.uptimeMillis()
        var t = timings
        if (previewRunning && t.previewStarted == 0L && t.cameraOpen > 0L) t = t.copy(previewStarted = now)
        if (t.previewStarted > 0L && t.firstFrame == 0L && controller?.shouldCoverPreview() == false) t = t.copy(firstFrame = now)
        if (t.press > 0L) {
            if (t.ready == 0L && !taking && ZernoHooks.getMark(ZernoHooks.MARK_CAPTURE_COMPLETED) >= t.press) t = t.copy(ready = now)
            if (t.saved == 0L && t.ready > 0L && activity.applicationInterface.imageSaver.nRealImagesToSave == 0) t = t.copy(saved = now)
        }
        timings = t
        // a shutter press that came too early
        if (pendingShotUntil > 0L) {
            if (now > pendingShotUntil) pendingShotUntil = 0L
            else if (previewRunning && !taking) {
                pendingShotUntil = 0L
                takePhoto()
            }
        }
    }

    fun showDiagnostics(show: Boolean) {
        if (!show) {
            _state.update { it.copy(diagnostics = null) }
            return
        }
        val t = timings.copy(
            processStart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Process.getStartUptimeMillis() else 0L,
            create = ZernoHooks.getMark(ZernoHooks.MARK_CREATE),
            firstResume = ZernoHooks.getMark(ZernoHooks.MARK_FIRST_RESUME),
            resume = ZernoHooks.getMark(ZernoHooks.MARK_RESUME),
            captureRequested = ZernoHooks.getMark(ZernoHooks.MARK_CAPTURE_REQUESTED).takeIf { it >= timings.press } ?: 0L,
            captureStarted = ZernoHooks.getMark(ZernoHooks.MARK_CAPTURE_STARTED).takeIf { it >= timings.press } ?: 0L,
            imageReceived = ZernoHooks.getMark(ZernoHooks.MARK_IMAGE_RECEIVED).takeIf { it >= timings.press } ?: 0L,
            captureCompleted = ZernoHooks.getMark(ZernoHooks.MARK_CAPTURE_COMPLETED).takeIf { it >= timings.press } ?: 0L,
        )
        _state.update { it.copy(diagnostics = Diagnostics.report(activity, t, toneCurveInfo())) }
    }

    // ---- QR codes ------------------------------------------------------------------------

    /** In Photo mode the preview is checked for QR codes about twice a second. The frame is
     *  read back from the engine's TextureView, so the engine itself isn't involved.
     */
    private fun pollQr(allowed: Boolean) {
        pollCount++
        val now = SystemClock.uptimeMillis()
        if (allowed && !qrPinned && pollCount % QR_POLL_EVERY == 0) {
            val view = preview?.view as? TextureView
            if (view != null && view.isAvailable) {
                qrScanner.scan(view) { content ->
                    handler.post {
                        if (!qrPinned) {
                            qrLastSeen = SystemClock.uptimeMillis()
                            _state.update { it.copy(qr = content) }
                        }
                    }
                }
            }
        }
        if (!qrPinned && _state.value.qr != null && (!allowed || now - qrLastSeen > QR_LINGER_MS)) {
            _state.update { it.copy(qr = null) }
        }
    }

    /** Keeps the current QR result while its sheet is open. */
    fun setQrPinned(pinned: Boolean) {
        qrPinned = pinned
        if (!pinned) {
            qrLastSeen = 0L
            _state.update { it.copy(qr = null) }
        }
    }

    // ---- preview placement ----------------------------------------------------------------

    /** The engine centres the preview in the window. Like the Honor camera, Zerno instead puts
     *  it just below the top bar, leaving the spare room at the bottom for the controls. This is
     *  done with a translation, so the engine's own layout code is untouched.
     */
    private fun updatePreviewPosition() {
        val frame = activity.findViewById<View>(R.id.preview) ?: return
        val root = frame.parent as? View ?: return
        if (frame.height == 0 || root.height == 0) return
        val spare = root.height - frame.height
        val translation = if (spare > 0) {
            val density = activity.resources.displayMetrics.density
            val insets = ViewCompat.getRootWindowInsets(root)
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val wantedTop = (insets?.top ?: 0) + (TOP_BAR_DP * density).roundToInt()
            (minOf(wantedTop, spare) - frame.top).toFloat()
        } else 0f
        if (frame.translationY != translation) frame.translationY = translation
    }

    private fun previewExtent(): Pair<Int, Int> {
        val frame = activity.findViewById<View>(R.id.preview) ?: return 0 to 0
        if (frame.height == 0) return 0 to 0
        val top = frame.top + frame.translationY.roundToInt()
        return top to top + frame.height
    }

    // ---- shooting -------------------------------------------------------------------------

    fun takePhoto() {
        flushEngineUpdate() // a mode change that hasn't reached the engine yet
        val preview = preview
        if (controller == null || preview == null || !preview.isPreviewStarted) {
            // the camera is still starting: the engine would silently ignore the press, so
            // remember it, and take the photo the moment the camera is ready
            pendingShotUntil = SystemClock.uptimeMillis() + PENDING_SHOT_MS
            return
        }
        val s = _state.value
        if (!s.takingPhoto && !s.onTimer) {
            timings = timings.copy(
                press = SystemClock.uptimeMillis(), ready = 0, saved = 0,
                shotLabel = "mode ${s.mode}, detail ${s.detail}, hdr ${s.hdr}, focus ${s.focusMode}, flash ${s.flash}, " +
                        "timer ${s.timer.seconds}, chroma ${s.settings.chromaDenoise}, " +
                        "adjust ${s.settings.brightness}/${s.settings.contrast}/${s.settings.saturation}, size ${preview.currentPictureSize?.let { "${it.width}x${it.height}" }}",
            )
        }
        ZernoHooks.exif_description = exifDescription(s)
        ZernoHooks.exif_software = "Zerno ${BuildConfig.VERSION_NAME}"
        activity.takePicture(false)
        poll()
    }

    fun switchCamera() {
        ZernoHooks.suppressToastsFor(TOAST_QUIET_MS)
        activity.clickedSwitchCamera(null)
    }

    /** Opens Zerno's own viewer on the folder that photos are saved to. (If the save location
     *  was chosen through the Storage Access Framework, there may be no ordinary folder path:
     *  then the system gallery is opened instead, as before.)
     */
    fun openGallery() {
        val folder = saveFolder()
        if (folder != null) ViewerActivity.start(activity, folder, trash = false) else activity.clickedGallery(null)
    }

    /** Opens the viewer on the photos from the save folder that are in the trash. */
    fun openTrash() {
        val folder = saveFolder() ?: return
        showSettings(false)
        ViewerActivity.start(activity, folder, trash = true)
    }

    private fun saveFolder(): String? {
        val storage = activity.storageUtils
        return if (storage.isUsingSAF) null else storage.imageFolderPath
    }

    /** Zerno's settings for the photo about to be taken, written into its EXIF (and shown by
     *  the viewer). Plain ASCII: "Zerno: key=value; key=value".
     */
    private fun exifDescription(s: UiState): String = buildList {
        add("mode=${s.mode.name.lowercase()}")
        if (s.mode != Mode.NIGHT) add("detail=${s.detail.name.lowercase()}")
        add("chroma=${if (s.settings.chromaDenoise == 0) "off" else "${s.settings.chromaDenoise}/${Settings.CHROMA_MAX}"}")
        if (s.mode == Mode.PHOTO && s.hdr != Hdr.OFF) add("hdr=${s.hdr.name.lowercase()}")
        if (s.settings.shadows != 0) add("shadows=%+d".format(s.settings.shadows))
        if (s.settings.highlights != 0) add("highlights=%+d".format(s.settings.highlights))
        if (s.settings.brightness != 0 || s.settings.contrast != 0 || s.settings.saturation != 0) {
            add("adjust=brightness %+d, contrast %+d, saturation %+d".format(s.settings.brightness, s.settings.contrast, s.settings.saturation))
        }
        add("metering=${s.settings.metering.name.lowercase()}")
        add("focus=${s.focusMode.removePrefix("focus_mode_")}")
        if (s.zoom > 1.01f) add("zoom=%.1fx".format(java.util.Locale.US, s.zoom))
    }.joinToString("; ", prefix = "Zerno: ")

    fun openSettings() = showSettings(true)

    /** The engine owns its thumbnail bitmaps and recycles each one as soon as the next photo's
     *  thumbnail arrives - which can happen while Compose is still drawing the old one (drawing
     *  a recycled bitmap crashes the app). So the UI gets its own private copy.
     */
    fun onGalleryThumbnail(thumbnail: Bitmap?) {
        val copy = try {
            if (thumbnail == null || thumbnail.isRecycled) null
            else thumbnail.copy(thumbnail.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (e: RuntimeException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        _state.update { it.copy(thumbnail = copy) }
    }

    // ---- crash reports --------------------------------------------------------------------

    /** Shows the report of the previous crash, if there was one. */
    fun loadCrashReport() {
        val report = CrashLog.read(activity) ?: return
        _state.update { it.copy(crashReport = report) }
    }

    fun dismissCrashReport() {
        CrashLog.clear(activity)
        _state.update { it.copy(crashReport = null) }
    }

    fun onSettingsVisible(visible: Boolean) = _state.update {
        // the Open Camera settings screen may have changed settings that Zerno's own screen shows too
        val settings = if (visible) it.settings else it.settings.copy(
            shutterSound = prefs.getBoolean(PreferenceKeys.ShutterSoundPreferenceKey, true),
            jpegQuality = prefs.getString(PreferenceKeys.QualityPreferenceKey, "90")?.toIntOrNull() ?: 90,
            mirrorFront = prefs.getString(PreferenceKeys.FrontCameraMirrorKey, MIRROR_NO) == MIRROR_PHOTO,
        )
        it.copy(settingsOpen = visible, settings = settings)
    }

    // ---- Zerno settings ------------------------------------------------------------------

    fun showSettings(show: Boolean) = _state.update { it.copy(zernoSettingsOpen = show) }

    /** Opens the full Open Camera settings screen ("Advanced"). */
    fun openAdvancedSettings() {
        showSettings(false)
        activity.openSettings()
    }

    fun setMetering(metering: Metering) {
        prefs.edit().putString(KEY_METERING, metering.name).apply()
        applyMetering(metering)
        preview?.zernoReapplyFocusArea()
        _state.update { it.copy(settings = it.settings.copy(metering = metering)) }
    }

    private fun applyMetering(metering: Metering) {
        ZernoHooks.metering_mode = when (metering) {
            Metering.SPOT -> ZernoHooks.METERING_SPOT
            Metering.CENTRE -> ZernoHooks.METERING_CENTRE
            Metering.MATRIX -> ZernoHooks.METERING_MATRIX
        }
    }

    fun setImageAdjustment(brightness: Int, contrast: Int, saturation: Int) {
        val range = -Settings.ADJUST_RANGE..Settings.ADJUST_RANGE
        val settings = _state.value.settings.copy(
            brightness = brightness.coerceIn(range), contrast = contrast.coerceIn(range), saturation = saturation.coerceIn(range),
        )
        prefs.edit()
            .putInt(KEY_BRIGHTNESS, settings.brightness)
            .putInt(KEY_CONTRAST, settings.contrast)
            .putInt(KEY_SATURATION, settings.saturation)
            .apply()
        applyImageAdjustment(settings)
        _state.update { it.copy(settings = settings) }
    }

    /** Brightness, contrast and saturation are one colour matrix. The engine applies it to each
     *  photo as it is saved; the same matrix is put on the preview, so the preview shows what
     *  the photo will look like.
     */
    private fun applyImageAdjustment(settings: Settings) {
        val matrix = if (settings.brightness == 0 && settings.contrast == 0 && settings.saturation == 0) null else {
            val scale = 1f + settings.contrast * 0.06f
            // contrast pivots around mid grey; brightness is a straight offset
            val offset = 127.5f * (1f - scale) + settings.brightness * 7f
            ColorMatrix().apply {
                setSaturation(1f + settings.saturation * 0.12f)
                postConcat(ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, offset,
                    0f, scale, 0f, 0f, offset,
                    0f, 0f, scale, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                )))
            }.array.copyOf()
        }
        ZernoHooks.color_matrix = matrix
        (preview?.view as? TextureView)?.setLayerPaint(
            matrix?.let { Paint().apply { colorFilter = ColorMatrixColorFilter(it) } }
        )
    }

    /** Colour noise removal is done by Zerno itself when a photo is saved (the camera driver
     *  has no separate control for it), so it works whatever the "Detail" switch is set to.
     */
    fun setChromaDenoise(level: Int) {
        val value = level.coerceIn(0, Settings.CHROMA_MAX)
        prefs.edit().putInt(KEY_CHROMA, value).apply()
        ZernoHooks.chroma_denoise = value
        _state.update { it.copy(settings = it.settings.copy(chromaDenoise = value)) }
    }

    // ---- shadows and highlights (tone curve) --------------------------------------------

    private var toneCurveMaxPoints = 0
    /** The current camera's correction table (see [ToneCurve.match]), once calibrated. */
    private var toneCorrection: FloatArray? = null
    // calibration: 0 = idle, 1 = waiting to measure the camera's own rendering,
    // 2 = waiting to measure the rendering through the known curve
    private var calibrationStep = 0
    private var calibrationAt = 0L
    private var calibrationGiveUpAt = 0L
    private var cameraHistogram: IntArray? = null

    /** How many tone curve points the current camera accepts (0 if it takes no custom curve). */
    private fun toneCurvePoints(preview: Preview): Int {
        toneCurveMaxPoints = try {
            val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val c = manager.getCameraCharacteristics(manager.cameraIdList[preview.cameraId])
            val modes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            if (preview.usingCamera2API() && modes != null && CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in modes) {
                c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
            } else 0
        } catch (e: Exception) {
            0
        }
        return toneCurveMaxPoints
    }

    /** Called when a camera has been set up. */
    private fun startToneCurve() {
        val cameraId = preview?.cameraId ?: return
        calibrationStep = 0
        toneCorrection = ToneCurve.decode(prefs.getString(KEY_TONE_CORRECTION + cameraId, null))
        applyToneCurve()
    }

    private fun setEngineToneCurve(curve: Array<FloatArray>?) {
        val changed = curve != null || ZernoHooks.tone_curve != null
        ZernoHooks.tone_curve = curve
        if (changed) (controller as? CameraController2)?.zernoApplyToneCurve()
    }

    private fun applyToneCurve() {
        val settings = _state.value.settings
        if (toneCurveMaxPoints < 16 || (settings.shadows == 0 && settings.highlights == 0)) {
            // nothing to do: tone mapping is left entirely to the camera
            calibrationStep = 0
            setEngineToneCurve(null)
            return
        }
        if (toneCorrection == null) {
            startCalibration()
            return
        }
        setEngineToneCurve(ToneCurve.build(settings.shadows, settings.highlights, toneCorrection, toneCurveMaxPoints))
    }

    /** Measures how this camera renders tones (see [ToneCurve]). Takes about a second, during
     *  which the preview may flicker slightly; best done with the camera held still.
     */
    private fun startCalibration() {
        if (calibrationStep != 0) return
        setEngineToneCurve(null)
        val now = SystemClock.uptimeMillis()
        calibrationStep = 1
        calibrationAt = now + CALIBRATION_SETTLE_MS
        calibrationGiveUpAt = now + CALIBRATION_TIMEOUT_MS
    }

    fun recalibrateTone() {
        val cameraId = preview?.cameraId ?: return
        if (toneCurveMaxPoints < 16) return
        prefs.edit().remove(KEY_TONE_CORRECTION + cameraId).apply()
        toneCorrection = null
        calibrationStep = 0
        startCalibration()
    }

    private fun pollToneCurve() {
        if (calibrationStep == 0) return
        val now = SystemClock.uptimeMillis()
        if (now < calibrationAt) return
        val preview = preview
        val ready = preview != null && controller != null && preview.isPreviewStarted && !preview.isTakingPhotoOrOnTimer
        val histogram = if (ready) previewHistogram() else null
        if (histogram == null) {
            if (now > calibrationGiveUpAt) finishCalibration(null) else calibrationAt = now + 300
            return
        }
        if (calibrationStep == 1) {
            cameraHistogram = histogram
            setEngineToneCurve(ToneCurve.probe(toneCurveMaxPoints))
            calibrationStep = 2
            calibrationAt = now + CALIBRATION_SETTLE_MS
        } else {
            finishCalibration(cameraHistogram?.let { ToneCurve.match(it, histogram) })
        }
    }

    private fun finishCalibration(correction: FloatArray?) {
        calibrationStep = 0
        cameraHistogram = null
        val cameraId = preview?.cameraId
        if (correction != null && cameraId != null) {
            prefs.edit().putString(KEY_TONE_CORRECTION + cameraId, ToneCurve.encode(correction)).apply()
            toneCorrection = correction
        } else {
            // couldn't measure (e.g. a featureless scene): use plain sRGB for now, and measure
            // again the next time the camera is opened
            toneCorrection = FloatArray(ToneCurve.CORRECTION_SIZE) { it / (ToneCurve.CORRECTION_SIZE - 1f) }
        }
        applyToneCurve()
    }

    /** The brightness histogram (256 bins) of the current preview frame. */
    private fun previewHistogram(): IntArray? {
        val view = preview?.view as? TextureView ?: return null
        if (!view.isAvailable || view.width == 0 || view.height == 0) return null
        return try {
            val width = 120
            val height = (width * view.height / view.width).coerceIn(60, 360)
            val bitmap = view.getBitmap(width, height) ?: return null
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            val histogram = IntArray(256)
            for (c in pixels) {
                val luma = (77 * ((c shr 16) and 0xff) + 150 * ((c shr 8) and 0xff) + 29 * (c and 0xff)) shr 8
                histogram[luma.coerceIn(0, 255)]++
            }
            histogram
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun toneCurveInfo(): String =
        "calibration: ${ToneCurve.describe(toneCorrection)}${if (calibrationStep != 0) " (calibrating now)" else ""}\n" +
                "curve in use: ${ToneCurve.describeCurve(ZernoHooks.tone_curve)}"

    fun setTone(shadows: Int, highlights: Int) {
        val range = -Settings.ADJUST_RANGE..Settings.ADJUST_RANGE
        val settings = _state.value.settings.copy(shadows = shadows.coerceIn(range), highlights = highlights.coerceIn(range))
        prefs.edit().putInt(KEY_SHADOWS, settings.shadows).putInt(KEY_HIGHLIGHTS, settings.highlights).apply()
        _state.update { it.copy(settings = settings) }
        if (calibrationStep == 0) applyToneCurve() // otherwise it's applied once calibration is done
    }

    fun setQrEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_QR, on).apply()
        _state.update { it.copy(settings = it.settings.copy(qrEnabled = on)) }
    }

    fun setShutterSound(on: Boolean) {
        prefs.edit().putBoolean(PreferenceKeys.ShutterSoundPreferenceKey, on).apply()
        _state.update { it.copy(settings = it.settings.copy(shutterSound = on)) }
    }

    fun setJpegQuality(quality: Int) {
        prefs.edit().putString(PreferenceKeys.QualityPreferenceKey, quality.toString()).apply()
        _state.update { it.copy(settings = it.settings.copy(jpegQuality = quality)) }
    }

    fun setMirrorFront(on: Boolean) {
        prefs.edit().putString(PreferenceKeys.FrontCameraMirrorKey, if (on) MIRROR_PHOTO else MIRROR_NO).apply()
        _state.update { it.copy(settings = it.settings.copy(mirrorFront = on)) }
    }

    // ---- zoom -----------------------------------------------------------------------------

    /** Zooms to the supported zoom ratio closest to [ratio]. */
    fun setZoom(ratio: Float, animate: Boolean = false) {
        val preview = preview ?: return
        if (zoomRatios.isEmpty() || controller == null) return
        val caps = _state.value.caps
        val target = ratio.coerceIn(caps.zoomMin, caps.zoomMax)
        var best = 0
        for (i in zoomRatios.indices) {
            if (abs(zoomRatios[i] - target) < abs(zoomRatios[best] - target)) best = i
        }
        if (best != controller?.zoom) {
            preview.zoomTo(best, false, animate)
        }
        _state.update { it.copy(zoom = if (animate) it.zoom else zoomRatios[best]) }
    }

    // ---- modes and top bar switches -------------------------------------------------------

    fun setMode(mode: Mode) {
        val s = _state.value
        if (mode == s.mode || mode !in s.caps.modes || s.takingPhoto) return
        applyMode(mode, s.detail, s.hdr, persist = true)
    }

    fun setDetail(detail: Detail) {
        val s = _state.value
        if (detail == s.detail || detail !in s.caps.details) return
        applyMode(s.mode, detail, s.hdr, persist = true)
    }

    fun setHdr(hdr: Hdr) {
        val s = _state.value
        if (hdr == s.hdr || hdr !in s.caps.hdrs) return
        applyMode(s.mode, s.detail, hdr, persist = true)
    }

    fun setGrid(on: Boolean) {
        prefs.edit().putString(PreferenceKeys.ShowGridPreferenceKey, if (on) GRID_THIRDS else GRID_NONE).apply()
        activity.applicationInterface.drawPreview.updateSettings()
        _state.update { it.copy(grid = on) }
    }

    fun setLevel(on: Boolean) {
        prefs.edit().putBoolean(PreferenceKeys.ShowAngleLinePreferenceKey, on).apply()
        activity.applicationInterface.drawPreview.updateSettings()
        _state.update { it.copy(level = on) }
    }

    fun setTimer(timer: Timer) {
        prefs.edit().putString(PreferenceKeys.TimerPreferenceKey, timer.seconds.toString()).apply()
        _state.update { it.copy(timer = timer) }
    }

    fun setFlash(value: String) {
        val preview = preview ?: return
        if (value !in _state.value.caps.flashes) return
        ZernoHooks.suppressToastsFor(TOAST_QUIET_MS)
        preview.updateFlash(value)
        refreshPro()
    }

    fun setRatio(ratio: Ratio) {
        val size = ratioSizes[ratio] ?: return
        if (ratio == _state.value.ratio || _state.value.takingPhoto) return
        activity.applicationInterface.setCameraResolutionPref(size.width, size.height)
        updateEngine()
    }

    /** Selects a focus mode (an engine value, see [Focus]). Focus peaking is on while focusing
     *  manually.
     */
    fun setFocusMode(value: String) {
        val preview = preview ?: return
        if (controller == null || value !in _state.value.caps.focusModes) return
        ZernoHooks.suppressToastsFor(TOAST_QUIET_MS)
        val manual = value == FOCUS_MANUAL
        preview.updateFocus(value, true, !manual)
        setFocusPeaking(manual)
        refreshPro()
    }

    private fun applyMode(mode: Mode, detail: Detail, hdr: Hdr, persist: Boolean) {
        val old = _state.value.mode
        val editor = prefs.edit()
        if (persist) {
            editor.putString(KEY_MODE, mode.name)
            editor.putString(KEY_DETAIL, detail.name)
            editor.putString(KEY_HDR, hdr.name)
        }
        if (old == Mode.PRO && mode != Mode.PRO) {
            stashProSettings(editor)
        } else if (old != Mode.PRO && mode == Mode.PRO) {
            restoreProSettings(editor)
        }
        writeModePreferences(editor, mode, detail, hdr)
        val before = engineModeSnapshot()
        editor.apply()
        _state.update { it.copy(mode = mode, detail = detail, hdr = hdr) }
        if (engineModeSnapshot() == before && !engineUpdatePending) {
            // nothing changes for the engine (e.g. Photo <-> Pro with automatic exposure):
            // no need to restart the camera preview
            refreshPro()
        } else {
            // let the carousel finish moving first, and restart the preview only once if the
            // user swipes through several modes
            scheduleEngineUpdate(MODE_SETTLE_MS)
        }
    }

    private var engineUpdatePending = false
    private val engineUpdate = Runnable {
        engineUpdatePending = false
        updateEngine()
    }

    private fun scheduleEngineUpdate(delayMs: Long) {
        engineUpdatePending = true
        handler.removeCallbacks(engineUpdate)
        handler.postDelayed(engineUpdate, delayMs)
    }

    private fun flushEngineUpdate() {
        if (engineUpdatePending) {
            handler.removeCallbacks(engineUpdate)
            engineUpdate.run()
        }
    }

    /** The engine preferences that a mode change can alter. */
    private fun engineModeSnapshot(): List<Any?> = listOf(
        prefs.getString(PreferenceKeys.PhotoModePreferenceKey, null),
        prefs.getString(PreferenceKeys.CameraNoiseReductionModePreferenceKey, null),
        prefs.getString(PreferenceKeys.EdgeModePreferenceKey, null),
        prefs.getString(PreferenceKeys.ISOPreferenceKey, null),
        prefs.getLong(PreferenceKeys.ExposureTimePreferenceKey, -1L),
        prefs.getString(PreferenceKeys.WhiteBalancePreferenceKey, null),
    )

    /** Makes the engine pick up changed preferences (this re-runs the camera set up, which in
     *  turn calls back into [onCameraSetup]).
     */
    private fun updateEngine() {
        if (preview?.isPreviewStarting == true) {
            // the preview is restarting in the background: the engine would ignore an update
            // now, so try again shortly
            scheduleEngineUpdate(ENGINE_RETRY_MS)
            return
        }
        ZernoHooks.suppressToastsFor(TOAST_QUIET_MS)
        activity.applicationInterface.drawPreview.updateSettings()
        activity.updateForSettings(true, "", false, false)
        refreshPro()
    }

    private fun writeModePreferences(editor: SharedPreferences.Editor, mode: Mode, detail: Detail, hdr: Hdr) {
        val photoMode = when (mode) {
            Mode.NIGHT -> "preference_photo_mode_noise_reduction"
            Mode.PRO -> "preference_photo_mode_std"
            Mode.PHOTO -> when (hdr) {
                Hdr.OFF -> "preference_photo_mode_std"
                Hdr.DRO -> "preference_photo_mode_dro"
                Hdr.HDR -> "preference_photo_mode_hdr"
            }
        }
        editor.putString(PreferenceKeys.PhotoModePreferenceKey, photoMode)
        // in Night mode the noise is removed by merging the burst, so the driver's own noise
        // reduction is always off
        val effective = if (mode == Mode.NIGHT) Detail.OFF else detail
        val (noiseReduction, edge) = when (effective) {
            Detail.OFF -> "off" to "off"
            Detail.MINIMAL -> "minimal" to "off"
            // the driver's quick noise reduction, without its sharpening
            Detail.FAST -> "fast" to "off"
            Detail.STANDARD -> CameraController.NOISE_REDUCTION_MODE_DEFAULT to CameraController.EDGE_MODE_DEFAULT
        }
        editor.putString(PreferenceKeys.CameraNoiseReductionModePreferenceKey, noiseReduction)
        editor.putString(PreferenceKeys.EdgeModePreferenceKey, edge)
    }

    // ---- "Pro" manual parameters ----------------------------------------------------------

    /** Leaving Pro: remember the manual exposure and white balance, and return them to automatic.
     *  (Exposure compensation and the focus mode are available in every mode, so they stay.)
     */
    private fun stashProSettings(editor: SharedPreferences.Editor) {
        editor.putString(KEY_PRO_ISO, prefs.getString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT))
        editor.putLong(KEY_PRO_SHUTTER, prefs.getLong(PreferenceKeys.ExposureTimePreferenceKey, CameraController.EXPOSURE_TIME_DEFAULT))
        editor.putString(KEY_PRO_WB, prefs.getString(PreferenceKeys.WhiteBalancePreferenceKey, WB_AUTO))

        editor.remove(PreferenceKeys.ISOPreferenceKey)
        editor.remove(PreferenceKeys.ExposureTimePreferenceKey)
        editor.remove(PreferenceKeys.WhiteBalancePreferenceKey)
    }

    /** Entering Pro: bring back the manual parameters used last time. */
    private fun restoreProSettings(editor: SharedPreferences.Editor) {
        val caps = _state.value.caps
        val iso = prefs.getString(KEY_PRO_ISO, CameraController.ISO_DEFAULT) ?: CameraController.ISO_DEFAULT
        if (caps.manualExposure && iso != CameraController.ISO_DEFAULT) {
            editor.putString(PreferenceKeys.ISOPreferenceKey, iso)
            editor.putLong(PreferenceKeys.ExposureTimePreferenceKey, prefs.getLong(KEY_PRO_SHUTTER, CameraController.EXPOSURE_TIME_DEFAULT))
        }
        val wb = prefs.getString(KEY_PRO_WB, WB_AUTO) ?: WB_AUTO
        if (wb == "manual" && caps.wbKelvinValues.isNotEmpty() || wb in caps.wbPresets) {
            editor.putString(PreferenceKeys.WhiteBalancePreferenceKey, wb)
        }
    }

    /** Re-reads the manual parameters, and the other settings the engine may adjust, from the engine. */
    private fun refreshPro() {
        val preview = preview ?: return
        val iso = prefs.getString(PreferenceKeys.ISOPreferenceKey, CameraController.ISO_DEFAULT) ?: CameraController.ISO_DEFAULT
        val auto = iso == CameraController.ISO_DEFAULT || !_state.value.caps.manualExposure
        val manualFocus = preview.currentFocusValue == FOCUS_MANUAL
        val size = if (controller != null) preview.currentPictureSize else null
        _state.update {
            it.copy(
                flash = preview.currentFlashValue ?: Flash.OFF,
                focusMode = preview.currentFocusValue ?: Focus.CONTINUOUS,
                ratio = size?.let { sz -> Ratio.entries.firstOrNull { r -> abs(sz.width.toDouble() / sz.height - r.value) < 0.02 } },
                pro = it.pro.copy(
                    isoAuto = auto,
                    shutterAuto = auto,
                    iso = if (auto) 0 else preview.parseManualISOValue(iso).coerceAtLeast(0),
                    shutterNs = if (auto) 0L else prefs.getLong(PreferenceKeys.ExposureTimePreferenceKey, CameraController.EXPOSURE_TIME_DEFAULT),
                    ev = if (controller != null && preview.supportsExposures()) preview.currentExposure else 0,
                    wb = controller?.whiteBalance ?: WB_AUTO,
                    kelvin = prefs.getInt(PreferenceKeys.WhiteBalanceTemperaturePreferenceKey, 5000),
                    focusAuto = !manualFocus,
                    focusDistance = prefs.getFloat(PreferenceKeys.FocusDistancePreferenceKey, 0f),
                )
            )
        }
    }

    /** Camera2 has a single switch for automatic exposure, so ISO and shutter speed are either
     *  both automatic or both manual. Choosing a manual value for one of them fixes the other at
     *  whatever the auto exposure was using at that moment.
     */
    fun setIso(iso: Int?) = setManualExposure(iso = iso, shutterNs = _state.value.pro.shutterNs.takeIf { !_state.value.pro.isoAuto }, autoRequested = iso == null)

    fun setShutter(shutterNs: Long?) = setManualExposure(iso = _state.value.pro.iso.takeIf { !_state.value.pro.isoAuto }, shutterNs = shutterNs, autoRequested = shutterNs == null)

    private fun setManualExposure(iso: Int?, shutterNs: Long?, autoRequested: Boolean) {
        val preview = preview ?: return
        val controller = controller ?: return
        val s = _state.value
        if (!s.caps.manualExposure) return
        ZernoHooks.suppressToastsFor(TOAST_QUIET_MS)
        if (autoRequested) {
            if (s.pro.isoAuto) return
            prefs.edit()
                .remove(PreferenceKeys.ISOPreferenceKey)
                .remove(PreferenceKeys.ExposureTimePreferenceKey)
                .apply()
            updateEngine()
            return
        }
        if (s.pro.isoAuto) {
            // auto -> manual: start from what the auto exposure is using right now
            val newIso = iso
                ?: (if (controller.captureResultHasIso()) controller.captureResultIso() else 400)
            val newShutter = shutterNs
                ?: (if (controller.captureResultHasExposureTime()) controller.captureResultExposureTime() else CameraController.EXPOSURE_TIME_DEFAULT)
            prefs.edit()
                .putString(PreferenceKeys.ISOPreferenceKey, newIso.coerceIn(preview.minimumISO, preview.maximumISO).toString())
                .putLong(PreferenceKeys.ExposureTimePreferenceKey, newShutter.coerceIn(preview.minimumExposureTime, preview.maximumExposureTime))
                .apply()
            updateEngine()
        } else {
            // already manual: the engine can change these directly, without a camera restart
            if (iso != null && iso != s.pro.iso) preview.setISO(iso)
            if (shutterNs != null && shutterNs != s.pro.shutterNs) preview.setExposureTime(shutterNs)
            refreshPro()
        }
    }

    fun setEv(ev: Int) {
        val preview = preview ?: return
        if (_state.value.pro.evLocked) return
        ZernoHooks.suppressToastsFor(TOAST_QUIET_MS)
        preview.setExposure(ev)
        refreshPro()
    }

    /** Selects a white balance preset (an engine value such as "auto" or "daylight"). */
    fun setWhiteBalance(value: String) {
        val controller = controller ?: return
        controller.setWhiteBalance(value)
        activity.applicationInterface.setWhiteBalancePref(value)
        refreshPro()
    }

    /** Selects a manual white balance temperature, in kelvin. */
    fun setKelvin(kelvin: Int) {
        val preview = preview ?: return
        val controller = controller ?: return
        if (_state.value.caps.wbKelvinValues.isEmpty()) return
        ZernoHooks.suppressToastsFor(TOAST_QUIET_MS)
        if (controller.whiteBalance != "manual") {
            controller.setWhiteBalance("manual")
            activity.applicationInterface.setWhiteBalancePref("manual")
        }
        preview.setWhiteBalanceTemperature(kelvin)
        refreshPro()
    }

    /** Sets the manual focus distance in dioptres (0 = infinity), or null for autofocus. Focus
     *  peaking is switched on while focusing manually.
     */
    fun setFocusDistance(distance: Float?) {
        val preview = preview ?: return
        if (controller == null || !_state.value.caps.manualFocus) return
        ZernoHooks.suppressToastsFor(TOAST_QUIET_MS)
        val wasManual = preview.currentFocusValue == FOCUS_MANUAL
        if (distance == null) {
            if (wasManual) setFocusMode(FOCUS_CONTINUOUS)
        } else {
            if (!wasManual) {
                preview.updateFocus(FOCUS_MANUAL, true, false)
                setFocusPeaking(true)
            }
            preview.setFocusDistance(distance, false, false)
        }
        refreshPro()
    }

    private fun setFocusPeaking(on: Boolean) {
        prefs.edit().putString(PreferenceKeys.FocusPeakingPreferenceKey, if (on) PEAKING_ON else PEAKING_OFF).apply()
        activity.applicationInterface.drawPreview.updateSettings()
    }

    // ---- stored Zerno settings ------------------------------------------------------------

    private fun storedMode(): Mode = enumPref(KEY_MODE, Mode.PHOTO).let {
        // manual parameters are only kept in the engine while in Pro, so always start elsewhere
        // in a consistent state: Pro is re-entered through applyMode()
        it
    }

    private fun storedDetail(): Detail = enumPref(KEY_DETAIL, Detail.OFF)

    private fun storedHdr(): Hdr = enumPref(KEY_HDR, Hdr.OFF)

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private fun timerFromPref(): Timer {
        val seconds = prefs.getString(PreferenceKeys.TimerPreferenceKey, "0")?.toIntOrNull() ?: 0
        return Timer.entries.firstOrNull { it.seconds == seconds }
            ?: if (seconds > 3) Timer.S10 else Timer.S3
    }

    companion object {
        /** Height reserved for the top bar above the preview. */
        const val TOP_BAR_DP = 56

        private const val TOAST_QUIET_MS = 2500L
        private const val PENDING_SHOT_MS = 4000L
        private const val MODE_SETTLE_MS = 320L
        private const val ENGINE_RETRY_MS = 120L
        private const val QR_POLL_EVERY = 5 // polls, i.e. about twice a second
        private const val QR_LINGER_MS = 2500L

        private const val KEY_DEFAULTS_DONE = "zerno_defaults_done"
        private const val KEY_MODE = "zerno_mode"
        private const val KEY_DETAIL = "zerno_detail"
        private const val KEY_HDR = "zerno_hdr"
        private const val KEY_METERING = "zerno_metering"
        private const val KEY_SHADOWS = "zerno_shadows"
        private const val KEY_HIGHLIGHTS = "zerno_highlights"
        private const val KEY_BRIGHTNESS = "zerno_brightness"
        private const val KEY_TONE_CORRECTION = "zerno_tone_correction_"
        private const val CALIBRATION_SETTLE_MS = 700L
        private const val CALIBRATION_TIMEOUT_MS = 8000L
        private const val KEY_CONTRAST = "zerno_contrast"
        private const val KEY_SATURATION = "zerno_saturation"
        private const val KEY_QR = "zerno_qr"
        private const val KEY_CHROMA = "zerno_chroma_denoise"
        private const val MIRROR_NO = "preference_front_camera_mirror_no"
        private const val MIRROR_PHOTO = "preference_front_camera_mirror_photo"
        private const val KEY_PRO_ISO = "zerno_pro_iso"
        private const val KEY_PRO_SHUTTER = "zerno_pro_shutter"
        private const val KEY_PRO_WB = "zerno_pro_wb"

        private const val GRID_NONE = "preference_grid_none"
        private const val GRID_THIRDS = "preference_grid_3x3"
        private const val PEAKING_ON = "preference_focus_peaking_on"
        private const val PEAKING_OFF = "preference_focus_peaking_off"
        private const val FOCUS_MANUAL = "focus_mode_manual2"
        private const val FOCUS_CONTINUOUS = "focus_mode_continuous_picture"
        const val WB_AUTO = "auto"

        /** The white balance presets offered, in display order (engine values). */
        val WB_PRESETS = listOf(WB_AUTO, "daylight", "cloudy-daylight", "incandescent", "fluorescent")
    }
}
