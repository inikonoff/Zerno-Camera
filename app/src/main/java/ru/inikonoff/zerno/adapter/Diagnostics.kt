package ru.inikonoff.zerno.adapter

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import net.sourceforge.opencamera.BuildConfig

/** Timings of the last start-up and the last photo, in SystemClock.uptimeMillis() (0 = not
 *  known).
 */
data class Timings(
    val processStart: Long = 0,
    val create: Long = 0,
    val firstResume: Long = 0,
    val resume: Long = 0,
    val cameraOpen: Long = 0,
    val previewStarted: Long = 0,
    val firstFrame: Long = 0,
    val shotLabel: String = "",
    val press: Long = 0,
    val captureRequested: Long = 0,
    val captureStarted: Long = 0,
    val imageReceived: Long = 0,
    val captureCompleted: Long = 0,
    val ready: Long = 0,
    val saved: Long = 0,
)

/** Builds the plain text diagnostics report: how long things take, and what the cameras of
 *  this phone tell apps they can do. It is only ever shown to the user (who may copy it).
 */
object Diagnostics {
    fun report(context: Context, timings: Timings): String = buildString {
        appendLine("Zerno ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
        appendLine("== START-UP ==")
        with(timings) {
            // only a cold start has a meaningful process start time
            if (processStart in 1..create && create - processStart < 10_000) line("process start -> app created", processStart, create)
            line("app created -> first shown", create, firstResume)
            // if the app has been left and returned to, the lines below are for the latest return
            appendLine(if (resume > firstResume) "(latest return to the app:)" else "(first start:)")
            line("shown -> camera open", resume, cameraOpen)
            line("camera open -> preview running", cameraOpen, previewStarted)
            line("preview running -> first frame visible", previewStarted, firstFrame)
            line("TOTAL shown -> first frame visible", resume, firstFrame)
            appendLine()
            appendLine("== LAST PHOTO ==")
            if (press == 0L) appendLine("(no photo taken yet)") else {
                appendLine(shotLabel)
                line("shutter pressed -> photo requested (focusing)", press, captureRequested)
                line("photo requested -> exposure started (camera)", captureRequested, captureStarted)
                line("exposure started -> image received", captureStarted, imageReceived)
                line("image received -> capture completed", imageReceived, captureCompleted)
                line("shutter pressed -> ready for next photo", press, ready)
                line("shutter pressed -> saved", press, saved)
            }
        }
        appendLine()
        appendLine("== TONE CURVE ==")
        appendLine("camera's own curve: ${ToneCurve.describe(net.sourceforge.opencamera.ZernoHooks.default_tone_curve)}")
        appendLine("Zerno curve in use: ${net.sourceforge.opencamera.ZernoHooks.tone_curve?.let { ToneCurve.describe(it) } ?: "none"}")
        appendLine()
        appendLine("== CAMERAS ==")
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in manager.cameraIdList) {
                try {
                    describeCamera(id, manager.getCameraCharacteristics(id))
                } catch (e: Exception) {
                    appendLine("camera $id: ${e.javaClass.simpleName}: ${e.message}")
                }
                appendLine()
            }
        } catch (e: Exception) {
            appendLine("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun StringBuilder.line(name: String, from: Long, to: Long) {
        appendLine(if (from > 0 && to >= from) "$name: ${to - from} ms" else "$name: -")
    }

    private fun sizes(list: Array<Size>?): String =
        list?.sortedByDescending { it.width.toLong() * it.height }?.take(6)?.joinToString(", ") { "${it.width}x${it.height}" } ?: "-"

    private fun StringBuilder.describeCamera(id: String, c: CameraCharacteristics) {
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            else -> "external"
        }
        val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "?"
        }
        appendLine("camera $id ($facing), hardware level $level")
        val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        appendLine("capabilities: ${capabilities.joinToString(",")}")
        appendLine("  RAW: ${3 in capabilities}, manual sensor: ${1 in capabilities}, manual post-processing: ${2 in capabilities}")
        appendLine("  ultra high resolution sensor: ${16 in capabilities}, remosaic reprocessing: ${17 in capabilities}, logical multi-camera: ${11 in capabilities}")
        appendLine("sensor pixel array: ${c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}")
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        appendLine("JPEG sizes: ${sizes(map?.getOutputSizes(ImageFormat.JPEG))}")
        appendLine("JPEG high-res (slow) sizes: ${sizes(map?.getHighResolutionOutputSizes(ImageFormat.JPEG))}")
        appendLine("RAW sizes: ${sizes(map?.getOutputSizes(ImageFormat.RAW_SENSOR))}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appendLine("max-resolution sensor pixel array: ${c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)}")
            val maxMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            appendLine("max-resolution JPEG sizes: ${sizes(maxMap?.getOutputSizes(ImageFormat.JPEG))}")
            appendLine("max-resolution RAW sizes: ${sizes(maxMap?.getOutputSizes(ImageFormat.RAW_SENSOR))}")
            appendLine("physical cameras: ${c.physicalCameraIds.joinToString(",").ifEmpty { "-" }}")
        }
        appendLine("noise reduction modes: ${c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.joinToString(",")}")
        appendLine("edge modes: ${c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.joinToString(",")}")
        appendLine("tonemap modes: ${c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.joinToString(",")}, max curve points: ${c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS)}")
        appendLine("metering regions: AE ${c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)}, AF ${c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)}")
        appendLine("ISO range: ${c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)}, exposure range (ns): ${c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)}")
        appendLine("focal lengths: ${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(",")}, apertures: ${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString(",")}")
    }
}
