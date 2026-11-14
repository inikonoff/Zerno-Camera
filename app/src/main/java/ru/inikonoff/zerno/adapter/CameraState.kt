package ru.inikonoff.zerno.adapter

import android.graphics.Bitmap

/** The shooting modes offered by the mode carousel, in carousel order. */
enum class Mode { NIGHT, PHOTO, PRO }

/** "Detail" switch: how much of the driver's noise reduction and sharpening is applied. */
enum class Detail { OFF, MINIMAL, STANDARD }

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
    val qrEnabled: Boolean = true,
    val shutterSound: Boolean = true,
    val jpegQuality: Int = 90,
    val mirrorFront: Boolean = false,
) {
    companion object {
        const val ADJUST_RANGE = 5
        val JPEG_QUALITIES = listOf(85, 90, 95, 100)
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
    val manualFocus: Boolean = false,
    val minFocusDistance: Float = 0f, // in dioptres (1/metres); 0 means infinity
) {
    val hasEv: Boolean get() = evMin != 0 || evMax != 0
}

/** Manual parameters of the "Pro" panel. */
data class ProState(
    val isoAuto: Boolean = true,
    val iso: Int = 0,
    val shutterAuto: Boolean = true,
    val shutterNs: Long = 0L,
    val ev: Int = 0,
    val wb: String = "auto",
    val kelvin: Int = 5000,
    val focusAuto: Boolean = true,
    val focusDistance: Float = 0f,
    // what the camera's auto exposure is currently using, if known (0 if unknown)
    val liveIso: Int = 0,
    val liveShutterNs: Long = 0L,
) {
    /** Exposure compensation only works while the exposure is automatic. */
    val evLocked: Boolean get() = !isoAuto || !shutterAuto
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
    val focusMode: String = Focus.CONTINUOUS,
    /** A QR code currently (or very recently) seen by the camera, if any. */
    val qr: QrContent? = null,
    val zoom: Float = 1f,
    val caps: Capabilities = Capabilities(),
    val pro: ProState = ProState(),
    val takingPhoto: Boolean = false,
    val onTimer: Boolean = false,
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
    /** Vertical extent of the camera preview within the window, in pixels (0 if not yet known). */
    val previewTop: Int = 0,
    val previewBottom: Int = 0,
)
