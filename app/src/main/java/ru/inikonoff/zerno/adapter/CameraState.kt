package ru.inikonoff.zerno.adapter

import android.graphics.Bitmap

/** The shooting modes offered by the mode carousel, in carousel order. MACRO takes a series of
 *  photos with the focus stepped evenly between two ends (for stacking afterwards).
 */
enum class Mode { NIGHT, PHOTO, PRO, MACRO }

/** "Detail" switch: how much of the driver's noise reduction and sharpening is applied. */
enum class Detail { OFF, MINIMAL, FAST, STANDARD }

/** Dynamic range processing, only offered in [Mode.PHOTO]. */
enum class Hdr { OFF, DRO, HDR }

enum class Timer(val seconds: Int) { OFF(0), S3(3), S10(10) }

/** Photo aspect ratios; the largest resolution with that ratio is used. */
enum class Ratio(val label: String, val value: Double) {
    R4_3("4:3", 4.0 / 3.0), R16_9("16:9", 16.0 / 9.0), R1_1("1:1", 1.0)
}

/** What the exposure is metered on. */
enum class Metering { SPOT, CENTRE, MATRIX }

/** Zerno's own settings, shown on the settings screen. The image adjustments run from -5 to +5. */
data class Settings(
    val metering: Metering = Metering.SPOT,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    /** Sharpness: -5 (soft) to +5 (sharp). */
    val sharpness: Int = 0,
    /** Save a RAW (DNG) file next to each JPEG, if the camera can. */
    val raw: Boolean = false,
    /** Frames in a Night burst; 0 = automatic (by how dark the scene is). */
    val nightFrames: Int = 0,
    /** Night median filter: 0 off, 1 weak, 2 standard (engine). */
    val nightMedian: Int = 2,
    /** Night merge: strict tolerance keeps detail that moved or misaligned slightly. */
    val nightStrict: Boolean = false,
    /** Night tone: natural = no histogram redistribution after the merge. */
    val nightNaturalTone: Boolean = false,
    /** Optical image stabilisation (only offered if the camera has it). */
    val ois: Boolean = true,
    /** Colour noise removal: 0 (off) to [CHROMA_MAX]. */
    val chromaDenoise: Int = 0,
    val qrEnabled: Boolean = true,
    val shutterSound: Boolean = true,
    val jpegQuality: Int = 90,
    val mirrorFront: Boolean = false,
    val geotag: Boolean = false,
    /** Highlight in-focus edges while focusing manually. */
    val focusPeaking: Boolean = true,
    /** Stripes over (nearly) blown-out areas of the preview. */
    val zebra: Boolean = false,
    /** What the volume keys do (an engine value, see [VOLUME_KEY_ACTIONS]). */
    val volumeKeys: String = "volume_take_photo",
) {
    companion object {
        const val ADJUST_RANGE = 5
        const val CHROMA_MAX = 3
        val NIGHT_FRAMES = listOf(0, 4, 8, 16, 32)
        val JPEG_QUALITIES = listOf(85, 90, 95, 100)
        /** The volume key actions offered (engine values): shutter, zoom, exposure, device volume. */
        val VOLUME_KEY_ACTIONS = listOf("volume_take_photo", "volume_zoom", "volume_exposure", "volume_nothing")
    }
}

/** Engine values for the flash and focus modes that Zerno offers. */
object Flash {
    const val OFF = "flash_off"
    const val AUTO = "flash_auto"
    const val ON = "flash_on"
    const val TORCH = "flash_torch"
    // the front camera lights the scene with the screen instead
    const val SCREEN_AUTO = "flash_frontscreen_auto"
    const val SCREEN_ON = "flash_frontscreen_on"
    const val SCREEN_TORCH = "flash_frontscreen_torch"
    val ORDER = listOf(OFF, AUTO, SCREEN_AUTO, ON, SCREEN_ON, TORCH, SCREEN_TORCH)
}

object Focus {
    const val CONTINUOUS = "focus_mode_continuous_picture"
    const val AUTO = "focus_mode_auto"
    const val MACRO = "focus_mode_macro"
    const val INFINITY = "focus_mode_infinity"
    const val MANUAL = "focus_mode_manual2"
    val ORDER = listOf(CONTINUOUS, AUTO, MACRO, INFINITY, MANUAL)
}

/** What the currently open camera (and the engine on this device) can do. The UI hides whatever
 *  isn't supported.
 */
data class Capabilities(
    val modes: List<Mode> = listOf(Mode.PHOTO),
    val details: List<Detail> = listOf(Detail.STANDARD),
    val hdrs: List<Hdr> = listOf(Hdr.OFF),
    val flashes: List<String> = emptyList(), // engine flash values, see [Flash]
    val ratios: List<Ratio> = emptyList(),
    /** All photo resolutions the camera offers in the current mode (width, height). */
    val pictureSizes: List<Pair<Int, Int>> = emptyList(),
    /** The camera has optical image stabilisation. */
    val ois: Boolean = false,
    val focusModes: List<String> = emptyList(), // engine focus values, see [Focus]
    val canSwitchCamera: Boolean = false,
    val hasZoom: Boolean = false,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    val manualExposure: Boolean = false,
    val isoValues: List<Int> = emptyList(),
    val shutterValues: List<Long> = emptyList(), // exposure times in nanoseconds, shortest first
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 0f,
    val wbPresets: List<String> = emptyList(), // engine white balance values, "auto" first
    val wbKelvinValues: List<Int> = emptyList(), // empty if manual temperature isn't supported
    /** White balance can be measured from a grey card (needs manual white balance). */
    val customWb: Boolean = false,
    /** The camera can deliver RAW (DNG). */
    val raw: Boolean = false,
    val manualFocus: Boolean = false,
    val minFocusDistance: Float = 0f, // in dioptres (1/metres); 0 means infinity
) {
    val hasEv: Boolean get() = evMin != 0 || evMax != 0
}

/** How the exposure is set in Pro: like the P / Sv / S / M modes of a camera, with ISO taking
 *  the place of the aperture.
 */
enum class ExposureMode { AUTO, ISO_PRIORITY, SHUTTER_PRIORITY, MANUAL }

/** The Macro series: the two ends of the focus range (in dioptres, 0 = infinity; in the order
 *  the frames are taken) and the number of frames, spread evenly between them.
 */
data class MacroState(
    val near: Float = 0f,
    val far: Float = 0f,
    val frames: Int = DEFAULT_FRAMES,
    /** The pause before each frame after the first, for the lens to settle (ms). */
    val pauseMs: Int = DEFAULT_PAUSE_MS,
    /** For finding what slows the frames down: which changes to the camera's requests are on (see ZernoHooks.macro_variant). */
    val variant: Int = 0,
) {
    companion object {
        val VARIANTS = listOf(0, 1, 2, 4, 7)
        const val DEFAULT_PAUSE_MS = 200
        val PAUSES_MS = listOf(0, 50, 100, 150, 200, 300, 500)
        const val MIN_FRAMES = 2
        const val MAX_FRAMES = 40
        const val DEFAULT_FRAMES = 10
    }
}

/** The grey-card white balance measurement, while it is on screen. */
data class GreyCardState(
    val measuring: Boolean = false,
    /** A message about the last attempt (a string resource), if any. */
    val message: Int? = null,
    /** A measurement from before is in use, so "cancel" keeps it. */
    val hasMeasurement: Boolean = false,
)

/** Manual parameters of the "Pro" panel. */
data class ProState(
    val exposureMode: ExposureMode = ExposureMode.AUTO,
    val isoAuto: Boolean = true,
    val iso: Int = 0,
    val shutterAuto: Boolean = true,
    val shutterNs: Long = 0L,
    val ev: Int = 0,
    val wb: String = "auto",
    val kelvin: Int = 5000,
    /** The white balance comes from a grey-card measurement. */
    val customWb: Boolean = false,
    val focusAuto: Boolean = true,
    val focusDistance: Float = 0f,
    // the values that the automatic part of the exposure is currently choosing, if known (0 if unknown)
    val liveIso: Int = 0,
    val liveShutterNs: Long = 0L,
) {
    /** Exposure compensation works while at least one of ISO and shutter speed is automatic. */
    val evLocked: Boolean get() = !isoAuto && !shutterAuto
}

data class UiState(
    val cameraOpen: Boolean = false,
    val mode: Mode = Mode.PHOTO,
    val detail: Detail = Detail.OFF,
    val hdr: Hdr = Hdr.OFF,
    val grid: Boolean = false,
    val level: Boolean = false,
    val timer: Timer = Timer.OFF,
    val flash: String = Flash.OFF,
    val ratio: Ratio? = null,
    /** The photo resolution in use (width, height). */
    val pictureSize: Pair<Int, Int>? = null,
    val focusMode: String = Focus.CONTINUOUS,
    /** A QR code currently (or very recently) seen by the camera, if any. */
    val qr: QrContent? = null,
    val zoom: Float = 1f,
    val caps: Capabilities = Capabilities(),
    val pro: ProState = ProState(),
    val macro: MacroState = MacroState(),
    val takingPhoto: Boolean = false,
    val onTimer: Boolean = false,
    /** Whole seconds left on the self-timer (0 when it isn't running). */
    val timerSeconds: Int = 0,
    /** A multi-frame capture (Night, HDR) is in progress: the phone must be held still. */
    val holdStill: Boolean = false,
    val saving: Boolean = false,
    val frontCamera: Boolean = false,
    val thumbnail: Bitmap? = null,
    /** How far the device is rotated from portrait (0, 90, 180 or 270): icons counter-rotate. */
    val uiRotation: Int = 0,
    val settingsOpen: Boolean = false,
    val settings: Settings = Settings(),
    /** Zerno's own settings screen is showing. */
    val zernoSettingsOpen: Boolean = false,
    /** The stack trace of the crash that ended the previous run, if it hasn't been dismissed. */
    val crashReport: String? = null,
    val greyCard: GreyCardState? = null,
    /** A focus point was chosen by touching the preview (rather than the default centre). */
    val focusPointSet: Boolean = false,
    /** Showing the one-time hint that a double tap brings the focus point back to the centre. */
    val focusHint: Boolean = false,
    /** The diagnostics report, while it is being shown. */
    val diagnostics: String? = null,
    /** Vertical extent of the camera preview within the window, in pixels (0 if not yet known). */
    val previewTop: Int = 0,
    val previewBottom: Int = 0,
)
