package ru.inikonoff.zerno.adapter

import kotlin.math.pow

/** Builds the tone curve for the "Shadows" and "Highlights" settings.
 *
 *  A tone curve maps the sensor's linear brightness (0..1) to the brightness in the photo
 *  (0..1). When an app supplies its own curve, it replaces the camera's own rendering
 *  completely, and cameras don't tell apps what their own curve is. So Zerno measures it once
 *  per camera ("calibration"): it compares the brightness distribution of the preview as the
 *  camera renders it with the same scene rendered through a known curve (sRGB). That gives a
 *  correction which, applied on top of sRGB, reproduces the camera's own look.
 *
 *  The settings then bend the dark part and the bright part of that curve up or down, leaving
 *  black, white and the mid-tones where they were - like the "Highlight & Shadow" control on
 *  Olympus cameras.
 */
object ToneCurve {
    /** Number of points in the curve: evenly spaced, as some cameras misbehave otherwise. */
    private const val MAX_POINTS = 64

    /** Number of samples in a correction table (see [match]). */
    const val CORRECTION_SIZE = 65

    // how far one step of the setting moves the curve at its strongest point (in output
    // brightness, 0..1); moving shadows down or highlights up has less room before the curve
    // would fold over, so those steps are smaller
    private const val STEP_WIDE = 0.034f
    private const val STEP_NARROW = 0.019f

    private fun srgb(x: Float): Float =
        if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f

    private fun sampleTable(table: FloatArray, v: Float): Float {
        val position = v.coerceIn(0f, 1f) * (table.size - 1)
        val i = position.toInt().coerceAtMost(table.size - 2)
        val f = position - i
        return table[i] * (1f - f) + table[i + 1] * f
    }

    /** How strongly a given output brightness counts as "shadow" (peaks at 0.25) or as
     *  "highlight" (peaks at 0.75); both are 0 at black and at white, and peak at 1.
     */
    private fun shadowWeight(y: Float) = 9.481f * y * (1f - y).pow(3)
    private fun highlightWeight(y: Float) = 9.481f * y.pow(3) * (1f - y)

    /** @param shadows -5..5: lifts (positive) or deepens (negative) the shadows.
     *  @param highlights -5..5: brightens (positive) or holds back (negative) the highlights.
     *  @param correction the camera's correction table from [match], or null for plain sRGB.
     *  @param maxPoints the most curve points the camera accepts.
     *  @return three channels of x,y pairs for android.hardware.camera2.params.TonemapCurve.
     */
    fun build(shadows: Int, highlights: Int, correction: FloatArray?, maxPoints: Int): Array<FloatArray> {
        val n = minOf(maxPoints, MAX_POINTS).coerceAtLeast(2)
        val shadowShift = shadows * (if (shadows > 0) STEP_WIDE else STEP_NARROW)
        val highlightShift = highlights * (if (highlights > 0) STEP_NARROW else STEP_WIDE)
        val points = FloatArray(2 * n)
        var previous = 0f
        for (i in 0 until n) {
            val x = i / (n - 1f)
            val y = srgb(x).let { if (correction != null) sampleTable(correction, it) else it }
            var out = y + shadowShift * shadowWeight(y) + highlightShift * highlightWeight(y)
            // never let the curve fold over, or leave 0..1
            out = out.coerceIn(0f, 1f).coerceAtLeast(previous)
            previous = out
            points[2 * i] = x
            points[2 * i + 1] = out
        }
        return arrayOf(points, points.copyOf(), points.copyOf())
    }

    /** The known curve used for calibration: plain sRGB. */
    fun probe(maxPoints: Int): Array<FloatArray> = build(0, 0, null, maxPoints)

    /** Works out the correction that turns the sRGB rendering into the camera's own rendering,
     *  by matching brightness histograms (256 bins each) of the same scene rendered both ways.
     *  @return a table of [CORRECTION_SIZE] samples: for a brightness (0..1) in the sRGB
     *  rendering, the brightness the camera's own rendering gives. Returns null if the scene
     *  didn't have enough tonal range to measure.
     */
    fun match(cameraHistogram: IntArray, probeHistogram: IntArray): FloatArray? {
        val cameraTotal = cameraHistogram.sum().toDouble()
        val probeTotal = probeHistogram.sum().toDouble()
        if (cameraTotal <= 0 || probeTotal <= 0) return null
        val cameraCdf = DoubleArray(256)
        val probeCdf = DoubleArray(256)
        var a = 0.0
        var b = 0.0
        for (i in 0 until 256) {
            a += cameraHistogram[i] / cameraTotal; cameraCdf[i] = a
            b += probeHistogram[i] / probeTotal; probeCdf[i] = b
        }
        // the range of probe brightnesses that the scene actually contains
        val low = (0 until 256).first { probeCdf[it] >= 0.02 }
        val high = (0 until 256).first { probeCdf[it] >= 0.98 }
        if (high - low < 64) return null // too flat a scene (e.g. the lens is covered)

        // for each probe brightness, the camera brightness at the same point of the distribution
        val map = FloatArray(256)
        var u = 0
        for (v in 0 until 256) {
            while (u < 255 && cameraCdf[u] < probeCdf[v]) u++
            map[v] = u / 255f
        }
        // smooth within the measured range
        val smooth = FloatArray(256)
        for (v in low..high) {
            val from = maxOf(low, v - 6)
            val to = minOf(high, v + 6)
            var sum = 0f
            for (k in from..to) sum += map[k]
            smooth[v] = sum / (to - from + 1)
        }
        // outside the measured range, run straight to black and to white
        for (v in 0 until low) smooth[v] = smooth[low] * v / low.coerceAtLeast(1)
        for (v in high + 1 until 256) smooth[v] = smooth[high] + (1f - smooth[high]) * (v - high) / (255 - high).coerceAtLeast(1)
        smooth[255] = 1f

        val table = FloatArray(CORRECTION_SIZE)
        var previous = 0f
        for (i in 0 until CORRECTION_SIZE) {
            val v = i * 255f / (CORRECTION_SIZE - 1)
            val k = v.toInt().coerceAtMost(254)
            val f = v - k
            val value = (smooth[k] * (1f - f) + smooth[k + 1] * f).coerceIn(0f, 1f).coerceAtLeast(previous)
            table[i] = value
            previous = value
        }
        table[0] = 0f
        return table
    }

    fun encode(table: FloatArray): String = table.joinToString(",") { "%.4f".format(java.util.Locale.US, it) }

    fun decode(text: String?): FloatArray? {
        val values = text?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return null
        return if (values.size == CORRECTION_SIZE) values.toFloatArray() else null
    }

    /** A short description, for the diagnostics report. */
    fun describe(correction: FloatArray?): String {
        if (correction == null) return "not calibrated (plain sRGB)"
        return listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f).joinToString(", ") { "%.2f->%.3f".format(java.util.Locale.US, it, sampleTable(correction, it)) }
    }

    fun describeCurve(curve: Array<FloatArray>?): String {
        val green = curve?.getOrNull(1) ?: return "none (camera's own rendering)"
        val n = green.size / 2
        fun at(x: Float): Float {
            val position = x * (n - 1)
            val i = position.toInt().coerceAtMost(n - 2)
            val f = position - i
            return green[2 * i + 1] * (1f - f) + green[2 * i + 3] * f
        }
        return "$n points; " + listOf(0.01f, 0.05f, 0.18f, 0.5f, 0.9f).joinToString(", ") { "%.2f->%.3f".format(java.util.Locale.US, it, at(it)) }
    }
}
