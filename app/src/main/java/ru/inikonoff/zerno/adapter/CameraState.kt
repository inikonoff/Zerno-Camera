package ru.inikonoff.zerno.adapter

import android.graphics.Bitmap

/** The shooting modes offered by the mode carousel, in carousel order. */
enum class Mode { NIGHT, PHOTO, PRO }

/** "Detail" switch: how much of the driver's noise reduction and sharpening is applied. */
enum class Detail { OFF, MINIMAL, STANDARD }

/** Dynamic range processing, only offered in [Mode.PHOTO]. */
enum class Hdr { OFF, DRO, HDR }

enum class Timer(val seconds: Int) { OFF(0), S3(3), S10(10) }

/** What the currently open camera (and the engine on this device) can do. The UI hides whatever
 *  isn't supported.
 */
data class Capabilities(
    val modes: List<Mode> = listOf(Mode.PHOTO),
    val details: List<Detail> = listOf(Detail.STANDARD),
    val hdrs: List<Hdr> = listOf(Hdr.OFF),
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
    /** Vertical extent of the camera preview within the window, in pixels (0 if not yet known). */
    val previewTop: Int = 0,
    val previewBottom: Int = 0,
)
