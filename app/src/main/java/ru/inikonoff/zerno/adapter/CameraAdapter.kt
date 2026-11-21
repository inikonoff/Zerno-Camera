package ru.inikonoff.zerno.adapter

import android.content.SharedPreferences
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
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
        if (active && !countingFrames) {
            countingFrames = true
            windowStart = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else if (!active) {
            countingFrames = false
        }
    }

    // ---- start up -------------------------------------------------------------------------

    /** Called before the engine opens the camera: makes the engine's preferences match the
     *  Zerno settings. Only writes preferences, the camera isn't available yet.
     */
    fun prepareEnginePreferences(firstRun: Boolean) {
        priority = enumPref(KEY_PRIORITY, ExposureMode.AUTO).takeIf { it != ExposureMode.MANUAL } ?: ExposureMode.AUTO
        priorityIso = prefs.getInt(KEY_PRIORITY_ISO, 0)
        priorityShutter = prefs.getLong(KEY_PRIORITY_SHUTTER, 0L)
        loadCustomWb(activity.applicationInterface.cameraIdPref)
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
            brightness = prefs.getInt(KEY_BRIGHTNESS, 0),
            contrast = prefs.getInt(KEY_CONTRAST, 0),
            saturation = prefs.getInt(KEY_SATURATION, 0),
            chromaDenoise = prefs.getInt(KEY_CHROMA, 0).coerceIn(0, Settings.CHROMA_MAX),
            sharpness = prefs.getInt(KEY_SHARPNESS, 0).coerceIn(-Settings.ADJUST_RANGE, Settings.ADJUST_RANGE),
            raw = prefs.getString(PreferenceKeys.RawPreferenceKey, "preference_raw_no") != "preference_raw_no",
            nightFrames = prefs.getInt(KEY_NIGHT_FRAMES, 0).takeIf { it in Settings.NIGHT_FRAMES } ?: 0,
            ois = prefs.getBoolean(KEY_OIS, true),
            nightMedian = prefs.getInt(KEY_NIGHT_MEDIAN, 2).coerceIn(0, 2),
            nightStrict = prefs.getBoolean(KEY_NIGHT_STRICT, false),
            nightNaturalTone = prefs.getBoolean(KEY_NIGHT_NATURAL, false),
            focusPeaking = prefs.getBoolean(KEY_PEAKING, true),
            qrEnabled = prefs.getBoolean(KEY_QR, true),
            shutterSound = prefs.getBoolean(PreferenceKeys.ShutterSoundPreferenceKey, true),
            jpegQuality = prefs.getString(PreferenceKeys.QualityPreferenceKey, "90")?.toIntOrNull() ?: 90,
            mirrorFront = prefs.getString(PreferenceKeys.FrontCameraMirrorKey, MIRROR_NO) == MIRROR_PHOTO,
            geotag = prefs.getBoolean(PreferenceKeys.LocationPreferenceKey, false),
            zebra = prefs.getString(PreferenceKeys.ZebraStripesPreferenceKey, "0") != "0",
            volumeKeys = prefs.getString(PreferenceKeys.VolumeKeysPreferenceKey, "volume_take_photo") ?: "volume_take_photo",
        )
        applyMetering(settings.metering)
        applyImageAdjustment(settings)
        ZernoHooks.chroma_denoise = settings.chromaDenoise
        ZernoHooks.sharpness = settings.sharpness
        ZernoHooks.night_frames = settings.nightFrames
        ZernoHooks.night_median = settings.nightMedian
        ZernoHooks.night_strict = settings.nightStrict
        ZernoHooks.night_natural_tone = settings.nightNaturalTone
        ZernoHooks.ois_disabled = !settings.ois

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
        // the camera's requests were built before we knew which camera it is
        if (loadCustomWb(preview.cameraId) && controller?.whiteBalance == "manual") {
            (controller as? CameraController2)?.zernoReapplyWhiteBalance()
        }
        refreshPro()
        applyImageAdjustment(s.settings) // the preview view may be new
        preview.zernoApplyDefaultFocusArea()
        poll()
        if (changed) {
            // the stored choice isn't available on this camera: fall back, but not from inside
            // the engine's cameraSetup()
            handler.post { applyMode(mode, detail, hdr, persist = false) }
        }
    }

    private var maxFrameCameraId = -1
    private var maxFrameDuration = 0L

    /** The longest shutter speed the camera can really do. Some cameras claim exposure times
     *  longer than the longest frame they can deliver (e.g. 32s, but frames of at most 0.27s),
     *  and then cut every exposure to the frame time; the frame limit is the true one.
     */
    private fun maxShutter(preview: Preview): Long {
        if (maxFrameCameraId != preview.cameraId) {
            maxFrameCameraId = preview.cameraId
            maxFrameDuration = try {
                val manager = activity.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                manager.getCameraCharacteristics(manager.cameraIdList[preview.cameraId])
                    .get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
        val max = preview.maximumExposureTime
        return if (maxFrameDuration > preview.minimumExposureTime) minOf(max, maxFrameDuration) else max
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
            shutterValues = if (manualExposure) Scales.shutterValues(preview.minimumExposureTime, maxShutter(preview)) else emptyList(),
            evMin = if (preview.supportsExposures()) preview.minimumExposure else 0,
            evMax = if (preview.supportsExposures()) preview.maximumExposure else 0,
            evStep = preview.exposureStep,
            wbPresets = wbPresets,
            wbKelvinValues = kelvins,
            customWb = kelvins.isNotEmpty(),
            raw = preview.supportsRaw(),
            ois = preview.supportsOpticalStabilization(),
            pictureSizes = sizes.map { it.width to it.height }.distinct(),
            manualFocus = manualFocus,
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
        if (open && s.mode == Mode.PRO && controller != null && !taking) {
            val aeIso = if (controller.captureResultHasIso()) controller.captureResultIso() else 0
            val aeShutter = if (controller.captureResultHasExposureTime()) controller.captureResultExposureTime() else 0L
            computePriority(pro.exposureMode, aeIso, aeShutter)
            // round, so that noise in the auto exposure doesn't cause constant recomposition
            pro = when (pro.exposureMode) {
                ExposureMode.ISO_PRIORITY -> pro.copy(liveIso = 0, liveShutterNs = Scales.nearestShutter(computedShutter))
                ExposureMode.SHUTTER_PRIORITY -> pro.copy(liveIso = computedIso, liveShutterNs = 0L)
                else -> pro.copy(liveIso = aeIso, liveShutterNs = Scales.nearestShutter(aeShutter))
            }
        }
        pollGreyCard()
        updatePreviewPosition()
        val (top, bottom) = previewExtent()
        pollTimings(open && preview.isPreviewStarted, taking, s)
        pollQr(open && !taking && !onTimer && s.mode == Mode.PHOTO && !s.settingsOpen && !s.zernoSettingsOpen && s.settings.qrEnabled)
        _state.update {
            it.copy(
                cameraOpen = open,
                takingPhoto = taking,
                onTimer = onTimer,
                focusPointSet = (open && preview.hasFocusArea()).also { set -> if (set && !s.focusPointSet) maybeShowFocusHint() },
                timerSeconds = if (onTimer) ((preview.timerEndTime - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0) else 0,
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
        _state.update { it.copy(diagnostics = Diagnostics.report(activity, t, smoothness.toList(),
            "touches reaching the camera view: ${ZernoHooks.touch_events}, taps from the Zerno UI: ${ZernoHooks.zerno_taps}\nlast: ${ZernoHooks.last_touch_focus}\nfocus point now: ${if (preview?.hasFocusArea() == true) "set by touch" else "centre"}")) }
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
        // ISO / shutter priority: the photo is taken with manual values worked out from what the
        // auto exposure is using right now (with the flash on, the camera's own exposure is used)
        val controller = controller
        val priorityShot = s.mode == Mode.PRO && controller != null &&
                (s.pro.exposureMode == ExposureMode.ISO_PRIORITY || s.pro.exposureMode == ExposureMode.SHUTTER_PRIORITY) &&
                (s.flash == Flash.OFF || s.flash == Flash.TORCH) &&
                controller.captureResultHasIso() && controller.captureResultHasExposureTime()
        if (priorityShot && controller != null) {
            computePriority(s.pro.exposureMode, controller.captureResultIso(), controller.captureResultExposureTime())
        }
        val override = priorityShot && computedIso > 0 && computedShutter > 0
        ZernoHooks.still_iso = if (override) computedIso else 0
        ZernoHooks.still_exposure_time = if (override) computedShutter else 0L
        if (override) timings = timings.copy(shotLabel = timings.shotLabel + ", ${s.pro.exposureMode} -> ISO $computedIso, ${Scales.formatShutter(computedShutter)}")
        // copies the processing settings (colour noise, sharpness, brightness...) for this photo, so that
        // later changes, while it waits to be saved, don't affect it or what its EXIF says
        ZernoHooks.beginShot(exifDescription(s))
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
    /** A tap on the preview, at a position in window coordinates. */
    fun tapPreview(windowX: Float, windowY: Float) {
        val view = preview?.view ?: return
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val x = windowX - location[0]
        val y = windowY - location[1]
        if (x < 0f || y < 0f || x > view.width || y > view.height) return // outside the preview
        preview?.zernoTap(x, y)
        poll()
    }

    /** Pinch on the preview: scales the zoom. */
    fun pinchZoom(factor: Float) {
        val s = _state.value
        if (!s.caps.hasZoom) return
        setZoom(s.zoom * factor)
    }

    /** The first time the user picks a focus point, says (once) how to get back to the centre. */
    private fun maybeShowFocusHint() {
        if (prefs.getBoolean(KEY_FOCUS_HINT_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_FOCUS_HINT_SHOWN, true).apply()
        handler.post { _state.update { it.copy(focusHint = true) } }
        handler.postDelayed({ _state.update { it.copy(focusHint = false) } }, FOCUS_HINT_MS)
    }

    /** Back to focusing and metering on the centre of the frame. */
    fun resetFocusPoint() {
        preview?.zernoResetFocusPoint()
        poll()
    }

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
        if (s.settings.sharpness != 0) add("sharpness=%+d".format(s.settings.sharpness))
        if (s.mode == Mode.NIGHT && s.settings.nightFrames > 0) add("frames=${s.settings.nightFrames}")
        if (s.mode == Mode.NIGHT && s.settings.nightMedian != 2) add("median=${if (s.settings.nightMedian == 0) "off" else "weak"}")
        if (s.mode == Mode.NIGHT && s.settings.nightStrict) add("merge=strict")
        if (s.mode == Mode.NIGHT && s.settings.nightNaturalTone) add("tone=natural")
        if (s.mode == Mode.PHOTO && s.hdr != Hdr.OFF) add("hdr=${s.hdr.name.lowercase()}")
        if (s.settings.brightness != 0 || s.settings.contrast != 0 || s.settings.saturation != 0) {
            add("adjust=brightness %+d, contrast %+d, saturation %+d".format(s.settings.brightness, s.settings.contrast, s.settings.saturation))
        }
        if (s.mode == Mode.PRO) add("exposure=${s.pro.exposureMode.name.lowercase().replace('_', '-')}")
        if (s.mode == Mode.PRO && s.pro.customWb) add("wb=grey card")
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
            geotag = prefs.getBoolean(PreferenceKeys.LocationPreferenceKey, false),
            zebra = prefs.getString(PreferenceKeys.ZebraStripesPreferenceKey, "0") != "0",
            volumeKeys = prefs.getString(PreferenceKeys.VolumeKeysPreferenceKey, "volume_take_photo") ?: "volume_take_photo",
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

    /** Sharpness (-5..+5) is applied to each photo as it is saved (see ZernoSharpen). */
    fun setSharpness(level: Int) {
        val value = level.coerceIn(-Settings.ADJUST_RANGE, Settings.ADJUST_RANGE)
        prefs.edit().putInt(KEY_SHARPNESS, value).apply()
        ZernoHooks.sharpness = value
        _state.update { it.copy(settings = it.settings.copy(sharpness = value)) }
    }

    /** Saves a RAW (DNG) file next to each JPEG. The camera is set up again, as RAW needs its
     *  own image stream. (The engine doesn't save RAW in the Night and HDR modes.)
     */
    fun setRaw(on: Boolean) {
        prefs.edit().putString(PreferenceKeys.RawPreferenceKey, if (on) "preference_raw_yes" else "preference_raw_no").apply()
        _state.update { it.copy(settings = it.settings.copy(raw = on)) }
        updateEngine()
    }

    /** Frames in a Night burst (0 = automatic). Applies from the next photo. */
    fun setNightFrames(frames: Int) {
        val value = frames.takeIf { it in Settings.NIGHT_FRAMES } ?: 0
        prefs.edit().putInt(KEY_NIGHT_FRAMES, value).apply()
        ZernoHooks.night_frames = value
        _state.update { it.copy(settings = it.settings.copy(nightFrames = value)) }
    }

    /** Night median filter strength: 0 off, 1 weak, 2 standard. */
    fun setNightMedian(level: Int) {
        val value = level.coerceIn(0, 2)
        prefs.edit().putInt(KEY_NIGHT_MEDIAN, value).apply()
        ZernoHooks.night_median = value
        _state.update { it.copy(settings = it.settings.copy(nightMedian = value)) }
    }

    /** Night merge tolerance: strict or engine standard. */
    fun setNightStrict(on: Boolean) {
        prefs.edit().putBoolean(KEY_NIGHT_STRICT, on).apply()
        ZernoHooks.night_strict = on
        _state.update { it.copy(settings = it.settings.copy(nightStrict = on)) }
    }

    /** Night tone: natural (no histogram redistribution) or engine. */
    fun setNightNaturalTone(on: Boolean) {
        prefs.edit().putBoolean(KEY_NIGHT_NATURAL, on).apply()
        ZernoHooks.night_natural_tone = on
        _state.update { it.copy(settings = it.settings.copy(nightNaturalTone = on)) }
    }

    /** Optical image stabilisation on or off (off can help on a tripod). */
    fun setOis(on: Boolean) {
        prefs.edit().putBoolean(KEY_OIS, on).apply()
        ZernoHooks.ois_disabled = !on
        (controller as? CameraController2)?.zernoReapplyStabilization()
        _state.update { it.copy(settings = it.settings.copy(ois = on)) }
    }

    /** Chooses a photo resolution (one of [Capabilities.pictureSizes]). */
    fun setResolution(width: Int, height: Int) {
        if (_state.value.takingPhoto) return
        activity.applicationInterface.setCameraResolutionPref(width, height)
        updateEngine()
    }

    fun setQrEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_QR, on).apply()
        _state.update { it.copy(settings = it.settings.copy(qrEnabled = on)) }
    }

    fun setShutterSound(on: Boolean) {
        prefs.edit().putBoolean(PreferenceKeys.ShutterSoundPreferenceKey, on).apply()
        _state.update { it.copy(settings = it.settings.copy(shutterSound = on)) }
    }

    /** Geotagging. Switching it on makes the engine start listening for the location, and ask
     *  for the location permission if it doesn't have it yet.
     */
    fun setGeotag(on: Boolean) {
        prefs.edit().putBoolean(PreferenceKeys.LocationPreferenceKey, on).apply()
        _state.update { it.copy(settings = it.settings.copy(geotag = on)) }
        activity.initLocation()
    }

    fun setVolumeKeys(action: String) {
        prefs.edit().putString(PreferenceKeys.VolumeKeysPreferenceKey, action).apply()
        _state.update { it.copy(settings = it.settings.copy(volumeKeys = action)) }
    }

    fun setFocusPeakingEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_PEAKING, on).apply()
        _state.update { it.copy(settings = it.settings.copy(focusPeaking = on)) }
        setFocusPeaking(_state.value.focusMode == Focus.MANUAL)
    }

    /** Zebra stripes are an engine feature; "on" uses the threshold chosen in the Open Camera
     *  settings, or about 97% brightness if none was.
     */
    fun setZebra(on: Boolean) {
        val current = prefs.getString(PreferenceKeys.ZebraStripesPreferenceKey, "0") ?: "0"
        val value = if (!on) "0" else if (current != "0") current else ZEBRA_DEFAULT
        prefs.edit().putString(PreferenceKeys.ZebraStripesPreferenceKey, value).apply()
        activity.applicationInterface.drawPreview.updateSettings()
        _state.update { it.copy(settings = it.settings.copy(zebra = on)) }
    }

    // ---- smoothness (for the diagnostics report) ------------------------------------------

    /** How many frames the UI drew in each of the last few seconds on the camera screen, and
     *  the longest gap between two frames in that second.
     */
    private val smoothness = ArrayDeque<String>()
    private var windowStart = 0L
    private var windowFrames = 0
    private var windowLongestGap = 0L
    private var lastFrame = 0L
    private var countingFrames = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!countingFrames) return
            val now = frameTimeNanos / 1_000_000
            val s = _state.value
            // only the camera screen itself is of interest
            if (s.zernoSettingsOpen || s.settingsOpen || s.diagnostics != null) {
                windowStart = 0L
            } else {
                if (windowStart == 0L) {
                    windowStart = now; windowFrames = 0; windowLongestGap = 0L
                } else {
                    windowFrames++
                    windowLongestGap = maxOf(windowLongestGap, now - lastFrame)
                    if (now - windowStart >= 1000) {
                        smoothness.addLast("$windowFrames frames, longest gap $windowLongestGap ms (mode ${s.mode}, focus ${s.focusMode.removePrefix("focus_mode_")}, " +
                                "peaking ${s.settings.focusPeaking && s.focusMode == Focus.MANUAL}, zebra ${s.settings.zebra}, " +
                                "shutter ${if (s.pro.shutterAuto) "auto" else Scales.formatShutter(s.pro.shutterNs)})")
                        while (smoothness.size > 4) smoothness.removeFirst()
                        windowStart = now; windowFrames = 0; windowLongestGap = 0L
                    }
                }
                lastFrame = now
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
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
        val engineManual = iso != CameraController.ISO_DEFAULT && _state.value.caps.manualExposure
        val exposureMode = if (engineManual) ExposureMode.MANUAL else if (_state.value.caps.manualExposure) priority else ExposureMode.AUTO
        val isoAuto = exposureMode == ExposureMode.AUTO || exposureMode == ExposureMode.SHUTTER_PRIORITY
        val shutterAuto = exposureMode == ExposureMode.AUTO || exposureMode == ExposureMode.ISO_PRIORITY
        val wb = controller?.whiteBalance ?: WB_AUTO
        val manualFocus = preview.currentFocusValue == FOCUS_MANUAL
        val size = if (controller != null) preview.currentPictureSize else null
        _state.update {
            it.copy(
                flash = preview.currentFlashValue ?: Flash.OFF,
                focusMode = preview.currentFocusValue ?: Focus.CONTINUOUS,
                ratio = size?.let { sz -> Ratio.entries.firstOrNull { r -> abs(sz.width.toDouble() / sz.height - r.value) < 0.02 } },
                pictureSize = size?.let { sz -> sz.width to sz.height },
                pro = it.pro.copy(
                    exposureMode = exposureMode,
                    isoAuto = isoAuto,
                    shutterAuto = shutterAuto,
                    iso = when (exposureMode) {
                        ExposureMode.MANUAL -> preview.parseManualISOValue(iso).coerceAtLeast(0)
                        ExposureMode.ISO_PRIORITY -> priorityIso
                        else -> 0
                    },
                    shutterNs = when (exposureMode) {
                        ExposureMode.MANUAL -> prefs.getLong(PreferenceKeys.ExposureTimePreferenceKey, CameraController.EXPOSURE_TIME_DEFAULT)
                        ExposureMode.SHUTTER_PRIORITY -> priorityShutter
                        else -> 0L
                    },
                    ev = if (controller != null && preview.supportsExposures()) preview.currentExposure else 0,
                    wb = wb,
                    customWb = wb == "manual" && ZernoHooks.custom_wb_gains != null,
                    kelvin = prefs.getInt(PreferenceKeys.WhiteBalanceTemperaturePreferenceKey, 5000),
                    focusAuto = !manualFocus,
                    focusDistance = prefs.getFloat(PreferenceKeys.FocusDistancePreferenceKey, 0f),
                )
            )
        }
    }

    // ---- exposure: auto, ISO priority, shutter priority, manual ----------------------------

    /** The priority mode chosen (AUTO, ISO_PRIORITY or SHUTTER_PRIORITY), used while the engine
     *  is on automatic exposure; MANUAL is the engine's own manual exposure.
     */
    private var priority = ExposureMode.AUTO
    private var priorityIso = 0
    private var priorityShutter = 0L
    /** The values the next photo would be taken with in a priority mode (0 if not known). */
    private var computedIso = 0
    private var computedShutter = 0L

    /** Camera2 (before Android 16) can't fix just one of ISO and shutter speed. So in the
     *  priority modes the preview keeps running on the camera's auto exposure, which measures the
     *  scene (with the metering mode and exposure compensation), and the photo is taken with
     *  manual values that give the same exposure: the fixed one as set, the other worked out.
     */
    private fun computePriority(mode: ExposureMode, aeIso: Int, aeShutter: Long) {
        computedIso = 0
        computedShutter = 0L
        val preview = preview ?: return
        if (aeIso <= 0 || aeShutter <= 0L) return
        val exposure = aeIso.toDouble() * aeShutter // ISO x nanoseconds
        when (mode) {
            ExposureMode.ISO_PRIORITY -> if (priorityIso > 0) {
                computedIso = priorityIso
                computedShutter = (exposure / priorityIso).toLong().coerceIn(preview.minimumExposureTime, maxShutter(preview))
            }
            ExposureMode.SHUTTER_PRIORITY -> if (priorityShutter > 0L) {
                computedShutter = priorityShutter
                computedIso = (exposure / priorityShutter).roundToInt().coerceIn(preview.minimumISO, preview.maximumISO)
            }
            else -> {}
        }
    }

    /** Sets ISO, or null for automatic. With shutter speed automatic this gives ISO priority. */
    fun setIso(iso: Int?) {
        val s = _state.value
        if (!s.caps.manualExposure) return
        when (s.pro.exposureMode) {
            ExposureMode.AUTO -> if (iso != null) setPriority(ExposureMode.ISO_PRIORITY, iso = iso)
            ExposureMode.ISO_PRIORITY -> if (iso == null) setPriority(ExposureMode.AUTO) else setPriority(ExposureMode.ISO_PRIORITY, iso = iso)
            ExposureMode.SHUTTER_PRIORITY -> if (iso != null) enterManual(iso, priorityShutter)
            ExposureMode.MANUAL -> if (iso == null) {
                leaveManual(ExposureMode.SHUTTER_PRIORITY, shutter = s.pro.shutterNs)
            } else if (iso != s.pro.iso) {
                ZernoHooks.suppressToastsFor(TOAST_QUIET_MS)
                preview?.setISO(iso)
                refreshPro()
            }
        }
    }

    /** Sets the shutter speed, or null for automatic. With ISO automatic this gives shutter priority. */
    fun setShutter(shutterNs: Long?) {
        val s = _state.value
        if (!s.caps.manualExposure) return
        when (s.pro.exposureMode) {
            ExposureMode.AUTO -> if (shutterNs != null) setPriority(ExposureMode.SHUTTER_PRIORITY, shutter = shutterNs)
            ExposureMode.SHUTTER_PRIORITY -> if (shutterNs == null) setPriority(ExposureMode.AUTO) else setPriority(ExposureMode.SHUTTER_PRIORITY, shutter = shutterNs)
            ExposureMode.ISO_PRIORITY -> if (shutterNs != null) enterManual(priorityIso, shutterNs)
            ExposureMode.MANUAL -> if (shutterNs == null) {
                leaveManual(ExposureMode.ISO_PRIORITY, iso = s.pro.iso)
            } else if (shutterNs != s.pro.shutterNs) {
                ZernoHooks.suppressToastsFor(TOAST_QUIET_MS)
                preview?.setExposureTime(shutterNs)
                refreshPro()
            }
        }
    }

    /** A priority mode needs no change in the engine: the preview stays on auto exposure. */
    private fun setPriority(mode: ExposureMode, iso: Int = priorityIso, shutter: Long = priorityShutter) {
        priority = mode
        priorityIso = iso
        priorityShutter = shutter
        prefs.edit().putString(KEY_PRIORITY, mode.name).putInt(KEY_PRIORITY_ISO, iso).putLong(KEY_PRIORITY_SHUTTER, shutter).apply()
        refreshPro()
        poll()
    }

    /** Both ISO and shutter speed fixed: the engine's manual exposure (the preview shows it too). */
    private fun enterManual(iso: Int, shutter: Long) {
        val preview = preview ?: return
        priority = ExposureMode.AUTO
        prefs.edit()
            .putString(KEY_PRIORITY, ExposureMode.AUTO.name)
            .putString(PreferenceKeys.ISOPreferenceKey, iso.coerceIn(preview.minimumISO, preview.maximumISO).toString())
            .putLong(PreferenceKeys.ExposureTimePreferenceKey, shutter.coerceIn(preview.minimumExposureTime, maxShutter(preview)))
            .apply()
        updateEngine()
    }

    /** One of ISO and shutter speed back to automatic: back to the camera's auto exposure, with
     *  the other one kept as a priority.
     */
    private fun leaveManual(mode: ExposureMode, iso: Int = priorityIso, shutter: Long = priorityShutter) {
        priority = mode
        priorityIso = iso
        priorityShutter = shutter
        prefs.edit()
            .putString(KEY_PRIORITY, mode.name).putInt(KEY_PRIORITY_ISO, iso).putLong(KEY_PRIORITY_SHUTTER, shutter)
            .remove(PreferenceKeys.ISOPreferenceKey)
            .remove(PreferenceKeys.ExposureTimePreferenceKey)
            .apply()
        updateEngine()
    }

    /** Steps the exposure compensation (long press and slide on the exposure button). */
    fun stepEv(delta: Int) {
        val s = _state.value
        if (!s.caps.hasEv || s.pro.evLocked) return
        val ev = (s.pro.ev + delta).coerceIn(s.caps.evMin, s.caps.evMax)
        if (ev != s.pro.ev) setEv(ev)
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
        clearCustomWb()
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
        val wasCustom = clearCustomWb()
        preview.setWhiteBalanceTemperature(kelvin)
        // the temperature may be unchanged, in which case the engine wouldn't redo the gains
        if (wasCustom) (controller as? CameraController2)?.zernoReapplyWhiteBalance()
        refreshPro()
    }

    // ---- white balance from a grey card -----------------------------------------------------

    /** Loads the grey-card gains for a camera (if a grey-card white balance is in use). */
    private fun loadCustomWb(cameraId: Int): Boolean {
        val gains = if (prefs.getBoolean(KEY_WB_CUSTOM, false)) decodeGains(prefs.getString(KEY_WB_GAINS + cameraId, null)) else null
        ZernoHooks.custom_wb_gains = gains
        return gains != null
    }

    /** Stops using the grey-card gains; returns whether they were in use. */
    private fun clearCustomWb(): Boolean {
        val was = ZernoHooks.custom_wb_gains != null
        ZernoHooks.custom_wb_gains = null
        prefs.edit().putBoolean(KEY_WB_CUSTOM, false).apply()
        return was
    }

    private fun decodeGains(text: String?): FloatArray? =
        text?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }?.toFloatArray()

    /** What to go back to if a measurement is cancelled or fails. */
    private var greyCardBefore: Pair<String, FloatArray?>? = null
    private var greyCardIteration = 0
    private var greyCardAt = 0L
    private var greyCardActive = false

    /** Shows the grey-card target. */
    fun openGreyCard() {
        val controller = controller ?: return
        if (!_state.value.caps.customWb) return
        greyCardBefore = (controller.whiteBalance ?: WB_AUTO) to ZernoHooks.custom_wb_gains?.copyOf()
        _state.update { it.copy(greyCard = GreyCardState(hasMeasurement = it.pro.customWb)) }
    }

    /** Measures the white balance from what's in the target. Done in a few rounds: each round
     *  corrects the gains by how far the grey card still is from neutral in the preview.
     */
    fun measureGreyCard() {
        val controller = controller as? CameraController2 ?: return
        val start = ZernoHooks.custom_wb_gains ?: ZernoHooks.last_wb_gains ?: floatArrayOf(2f, 1f, 1f, 2f)
        ZernoHooks.custom_wb_gains = start.copyOf()
        if (controller.whiteBalance != "manual") {
            controller.setWhiteBalance("manual")
            activity.applicationInterface.setWhiteBalancePref("manual")
        }
        controller.zernoReapplyWhiteBalance()
        greyCardIteration = 0
        greyCardAt = SystemClock.uptimeMillis() + GREY_CARD_SETTLE_MS
        greyCardActive = true
        _state.update { it.copy(greyCard = it.greyCard?.copy(measuring = true, message = null)) }
    }

    fun cancelGreyCard() {
        greyCardActive = false
        restoreBeforeGreyCard()
        _state.update { it.copy(greyCard = null) }
        refreshPro()
    }

    private fun restoreBeforeGreyCard() {
        val (wb, gains) = greyCardBefore ?: return
        val controller = controller ?: return
        ZernoHooks.custom_wb_gains = gains
        prefs.edit().putBoolean(KEY_WB_CUSTOM, gains != null).apply()
        controller.setWhiteBalance(wb)
        activity.applicationInterface.setWhiteBalancePref(wb)
        if (wb == "manual") (controller as? CameraController2)?.zernoReapplyWhiteBalance()
    }

    private fun pollGreyCard() {
        if (!greyCardActive || SystemClock.uptimeMillis() < greyCardAt) return
        val controller = controller as? CameraController2
        val gains = ZernoHooks.custom_wb_gains?.copyOf()
        if (controller == null || gains == null) {
            greyCardActive = false
            return
        }
        val patch = greyPatch()
        val problem = when {
            patch == null -> R.string.zerno_grey_failed
            patch.tooDark -> R.string.zerno_grey_too_dark
            patch.tooBright -> R.string.zerno_grey_too_bright
            else -> null
        }
        if (problem != null || patch == null) {
            greyCardActive = false
            restoreBeforeGreyCard()
            _state.update { it.copy(greyCard = it.greyCard?.copy(measuring = false, message = problem)) }
            return
        }
        val toRed = patch.g / patch.r
        val toBlue = patch.g / patch.b
        if ((abs(toRed - 1f) < 0.01f && abs(toBlue - 1f) < 0.01f) || greyCardIteration >= GREY_CARD_ROUNDS) {
            // done
            greyCardActive = false
            val cameraId = preview?.cameraId ?: 0
            prefs.edit()
                .putBoolean(KEY_WB_CUSTOM, true)
                .putString(KEY_WB_GAINS + cameraId, gains.joinToString(",") { "%.4f".format(java.util.Locale.US, it) })
                .apply()
            _state.update { it.copy(greyCard = null) }
            refreshPro()
            return
        }
        gains[0] = (gains[0] * toRed).coerceIn(0.25f, 10f)
        gains[3] = (gains[3] * toBlue).coerceIn(0.25f, 10f)
        ZernoHooks.custom_wb_gains = gains
        controller.zernoReapplyWhiteBalance()
        greyCardIteration++
        greyCardAt = SystemClock.uptimeMillis() + GREY_CARD_SETTLE_MS
    }

    private class Patch(val r: Float, val g: Float, val b: Float, val tooDark: Boolean, val tooBright: Boolean)

    /** The average linear colour inside the grey-card target, in the middle of the preview. */
    private fun greyPatch(): Patch? {
        val view = preview?.view as? TextureView ?: return null
        if (!view.isAvailable || view.width == 0 || view.height == 0) return null
        return try {
            val width = 200
            val height = (width.toLong() * view.height / view.width).toInt().coerceIn(50, 800)
            val bitmap = view.getBitmap(width, height) ?: return null
            val side = (GREY_CARD_DP * activity.resources.displayMetrics.density / view.width * width).roundToInt().coerceIn(6, minOf(width, height))
            val x0 = (width - side) / 2
            val y0 = (height - side) / 2
            val pixels = IntArray(side * side)
            bitmap.getPixels(pixels, 0, side, x0, y0, side, side)
            bitmap.recycle()
            var r = 0.0
            var g = 0.0
            var b = 0.0
            var used = 0
            var clipped = 0
            var dark = 0
            for (c in pixels) {
                val cr = (c shr 16) and 0xff
                val cg = (c shr 8) and 0xff
                val cb = c and 0xff
                when {
                    maxOf(cr, cg, cb) >= 250 -> clipped++
                    maxOf(cr, cg, cb) < 12 -> dark++
                    else -> {
                        r += SRGB_TO_LINEAR[cr]
                        g += SRGB_TO_LINEAR[cg]
                        b += SRGB_TO_LINEAR[cb]
                        used++
                    }
                }
            }
            if (used < pixels.size / 3) Patch(0f, 0f, 0f, tooDark = dark >= clipped, tooBright = clipped > dark)
            else Patch((r / used).toFloat(), (g / used).toFloat(), (b / used).toFloat(), tooDark = false, tooBright = false)
        } catch (e: RuntimeException) {
            null
        }
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

    /** Moves the manual focus by [delta] steps of the focus ruler (+1 towards macro, -1 towards
     *  infinity). From autofocus it starts at the distance the camera is focused at now. This is
     *  what a long press and slide on the focus button does, without opening the ruler.
     */
    fun stepFocus(delta: Int) {
        val s = _state.value
        if (!s.caps.manualFocus || controller == null) return
        val current = if (s.focusMode == Focus.MANUAL) s.pro.focusDistance
        else controller?.takeIf { it.captureResultHasFocusDistance() }?.captureResultFocusDistance() ?: 0f
        val index = (Scales.focusIndexOf(current, s.caps.minFocusDistance) + delta).coerceIn(0, Scales.FOCUS_STEPS)
        setFocusDistance(Scales.focusDistanceAt(index, s.caps.minFocusDistance))
    }

    /** [manual]: whether manual focus is in use; the highlighting is only shown then, and only
     *  if it's switched on in the settings (it costs some preview smoothness).
     */
    private fun setFocusPeaking(manual: Boolean) {
        val on = manual && _state.value.settings.focusPeaking
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
        private const val KEY_BRIGHTNESS = "zerno_brightness"
        private const val KEY_CONTRAST = "zerno_contrast"
        private const val KEY_SATURATION = "zerno_saturation"
        private const val KEY_QR = "zerno_qr"
        private const val KEY_SHARPNESS = "zerno_sharpness"
        private const val KEY_NIGHT_FRAMES = "zerno_night_frames"
        private const val KEY_NIGHT_MEDIAN = "zerno_night_median"
        private const val KEY_NIGHT_STRICT = "zerno_night_strict"
        private const val KEY_NIGHT_NATURAL = "zerno_night_natural_tone"
        private const val KEY_OIS = "zerno_ois"
        private const val KEY_FOCUS_HINT_SHOWN = "zerno_focus_hint_shown"
        private const val FOCUS_HINT_MS = 3500L
        private const val KEY_PRIORITY = "zerno_exposure_priority"
        private const val KEY_PRIORITY_ISO = "zerno_priority_iso"
        private const val KEY_PRIORITY_SHUTTER = "zerno_priority_shutter"
        private const val KEY_WB_CUSTOM = "zerno_wb_grey_card"
        private const val KEY_WB_GAINS = "zerno_wb_gains_"
        private const val GREY_CARD_SETTLE_MS = 450L
        private const val GREY_CARD_ROUNDS = 4
        /** The side of the grey-card target on screen. */
        const val GREY_CARD_DP = 72

        private val SRGB_TO_LINEAR = DoubleArray(256) {
            val v = it / 255.0
            if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        private const val KEY_PEAKING = "zerno_focus_peaking"
        private const val ZEBRA_DEFAULT = "247"
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
