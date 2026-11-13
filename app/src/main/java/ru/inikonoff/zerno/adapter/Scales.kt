package ru.inikonoff.zerno.adapter

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** The value scales shown on the "Pro" drum rulers, and how the values are written. */
object Scales {
    private val ISO_STEPS = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
        2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000,
        51200, 64000, 80000, 102400,
    )

    // shutter speeds in third stops: fractions of a second, then whole seconds
    private val SHUTTER_FRACTIONS = doubleArrayOf(
        32000.0, 25000.0, 20000.0, 16000.0, 12800.0, 10000.0, 8000.0, 6400.0, 5000.0, 4000.0, 3200.0,
        2500.0, 2000.0, 1600.0, 1250.0, 1000.0, 800.0, 640.0, 500.0, 400.0, 320.0, 250.0, 200.0,
        160.0, 125.0, 100.0, 80.0, 60.0, 50.0, 40.0, 30.0, 25.0, 20.0, 15.0, 13.0, 10.0, 8.0, 6.0,
        5.0, 4.0, 3.0, 2.5, 2.0, 1.6, 1.3,
    )
    private val SHUTTER_SECONDS = doubleArrayOf(
        1.0, 1.3, 1.6, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0, 60.0,
    )
    private val ALL_SHUTTERS: List<Long> =
        SHUTTER_FRACTIONS.map { (1e9 / it).toLong() } + SHUTTER_SECONDS.map { (it * 1e9).toLong() }

    fun isoValues(min: Int, max: Int): List<Int> {
        if (max <= min) return listOf(min)
        return (listOf(min) + ISO_STEPS.filter { it > min && it < max } + max).distinct()
    }

    fun shutterValues(minNs: Long, maxNs: Long): List<Long> {
        if (maxNs <= minNs) return listOf(minNs)
        return (listOf(minNs) + ALL_SHUTTERS.filter { it > minNs && it < maxNs } + maxNs).distinct()
    }

    /** The standard shutter speed closest (in stops) to the given exposure time. */
    fun nearestShutter(ns: Long): Long {
        if (ns <= 0L) return 0L
        return ALL_SHUTTERS.minBy { abs(ln(it.toDouble() / ns)) }
    }

    fun <T> nearestIndex(values: List<T>, target: Double, toDouble: (T) -> Double): Int {
        var best = 0
        for (i in values.indices) {
            if (abs(toDouble(values[i]) - target) < abs(toDouble(values[best]) - target)) best = i
        }
        return best
    }

    /** Index of the value closest to [target] on a logarithmic scale (for ISO and shutter). */
    fun nearestLogIndex(values: List<Number>, target: Number): Int {
        val t = target.toDouble().coerceAtLeast(1e-12)
        return nearestIndex(values, ln(t)) { ln(it.toDouble().coerceAtLeast(1e-12)) }
    }

    fun formatShutter(ns: Long): String {
        if (ns <= 0L) return "–"
        val seconds = ns / 1e9
        return if (seconds >= 0.4) {
            val rounded = (seconds * 10).roundToInt() / 10.0
            if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}\"" else "$rounded\""
        } else {
            "1/${(1.0 / seconds).roundToInt()}"
        }
    }

    fun formatEv(ev: Int, step: Float): String {
        val value = ev * step
        val rounded = (value * 10).roundToInt() / 10f
        return when {
            rounded > 0f -> "+$rounded"
            rounded == 0f -> "0"
            else -> rounded.toString().replace('-', '−')
        }
    }

    /** Formats a focus distance given in dioptres. */
    fun formatFocus(dioptres: Float): String {
        if (dioptres <= 0.001f) return "∞"
        val metres = 1f / dioptres
        return if (metres >= 1f) "${(metres * 10).roundToInt() / 10f} m" else "${(metres * 100).roundToInt()} cm"
    }

    /** The manual focus ruler: evenly spaced steps from infinity (index 0) to the closest focus
     *  distance. The spacing is quadratic in dioptres, giving finer control at long distances.
     */
    const val FOCUS_STEPS = 60

    fun focusDistanceAt(index: Int, minFocusDistance: Float): Float {
        val f = index.toFloat() / FOCUS_STEPS
        return minFocusDistance * f * f
    }

    fun focusIndexOf(distance: Float, minFocusDistance: Float): Int {
        if (minFocusDistance <= 0f) return 0
        val f = kotlin.math.sqrt((distance / minFocusDistance).coerceIn(0f, 1f))
        return (f * FOCUS_STEPS).roundToInt()
    }
}
