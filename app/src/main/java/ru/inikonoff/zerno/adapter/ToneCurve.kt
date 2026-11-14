package ru.inikonoff.zerno.adapter

import kotlin.math.pow

/** Builds the tone curve for the "Shadows" and "Highlights" settings.
 *
 *  A tone curve maps the sensor's linear brightness (0..1) to the brightness in the photo
 *  (0..1). Zerno starts from the curve the camera uses by itself (or the standard sRGB curve if
 *  the camera doesn't report its own), and then bends the dark part and the bright part of it
 *  up or down, leaving black, white and the mid-tones where they were - like the "Highlight &
 *  Shadow" control on Olympus cameras.
 */
object ToneCurve {
    /** Number of points in the curve: evenly spaced, as some cameras misbehave otherwise. */
    private const val MAX_POINTS = 64

    // how far one step of the setting moves the curve at its strongest point (in output
    // brightness, 0..1); moving shadows down or highlights up has less room before the curve
    // would fold over, so those steps are smaller
    private const val STEP_WIDE = 0.034f
    private const val STEP_NARROW = 0.019f

    private fun srgb(x: Float): Float =
        if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f

    /** Whether a curve reported by the camera looks like a real tone curve. */
    private fun plausible(points: FloatArray?): Boolean {
        if (points == null || points.size < 4 || points.size % 2 != 0) return false
        val n = points.size / 2
        if (points[1] > 0.15f || points[2 * n - 1] < 0.85f) return false
        for (i in 1 until n) {
            if (points[2 * i] < points[2 * i - 2] || points[2 * i + 1] < points[2 * i - 1] - 0.001f) return false
        }
        return true
    }

    private fun sample(points: FloatArray, x: Float): Float {
        val n = points.size / 2
        if (x <= points[0]) return points[1]
        for (i in 1 until n) {
            val x1 = points[2 * i]
            if (x <= x1) {
                val x0 = points[2 * i - 2]
                val y0 = points[2 * i - 1]
                val y1 = points[2 * i + 1]
                return if (x1 > x0) y0 + (y1 - y0) * (x - x0) / (x1 - x0) else y1
            }
        }
        return points[2 * n - 1]
    }

    /** How strongly a given output brightness counts as "shadow" (peaks at 0.25) or as
     *  "highlight" (peaks at 0.75); both are 0 at black and at white, and peak at 1.
     */
    private fun shadowWeight(y: Float) = 9.481f * y * (1f - y).pow(3)
    private fun highlightWeight(y: Float) = 9.481f * y.pow(3) * (1f - y)

    /** @param shadows -5..5: lifts (positive) or deepens (negative) the shadows.
     *  @param highlights -5..5: brightens (positive) or holds back (negative) the highlights.
     *  @param cameraCurve the camera's own curve (three channels of x,y pairs), if known.
     *  @param maxPoints the most curve points the camera accepts.
     *  @return three channels of x,y pairs, or null if both settings are 0 (leave it to the camera).
     */
    fun build(shadows: Int, highlights: Int, cameraCurve: Array<FloatArray>?, maxPoints: Int): Array<FloatArray>? {
        if (shadows == 0 && highlights == 0) return null
        val n = minOf(maxPoints, MAX_POINTS)
        if (n < 2) return null
        val shadowShift = shadows * (if (shadows > 0) STEP_WIDE else STEP_NARROW)
        val highlightShift = highlights * (if (highlights > 0) STEP_NARROW else STEP_WIDE)
        return Array(3) { channel ->
            val base = cameraCurve?.getOrNull(channel)?.takeIf { plausible(it) }
            val points = FloatArray(2 * n)
            var previous = 0f
            for (i in 0 until n) {
                val x = i / (n - 1f)
                val y = base?.let { sample(it, x) } ?: srgb(x)
                var out = y + shadowShift * shadowWeight(y) + highlightShift * highlightWeight(y)
                // never let the curve fold over, or leave 0..1
                out = out.coerceIn(0f, 1f).coerceAtLeast(previous)
                previous = out
                points[2 * i] = x
                points[2 * i + 1] = out
            }
            points
        }
    }

    /** A short description of a curve, for the diagnostics report. */
    fun describe(curve: Array<FloatArray>?): String {
        val green = curve?.getOrNull(1) ?: return "not reported"
        val n = green.size / 2
        val samples = listOf(0.01f, 0.05f, 0.18f, 0.5f, 0.9f).joinToString(", ") { "%.2f->%.3f".format(it, sample(green, it)) }
        return "$n points, ${if (plausible(green)) "usable" else "NOT usable"}; $samples"
    }
}
