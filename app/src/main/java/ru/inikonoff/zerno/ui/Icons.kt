package ru.inikonoff.zerno.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Zerno's own icons. They're drawn in code (simple line art on a 24 unit grid), so the app
 *  carries no third party icon artwork.
 */
enum class ZernoIcon {
    DETAIL, HDR, GRID, LEVEL, TIMER, SETTINGS, SWITCH_CAMERA, GALLERY,
    FLASH_OFF, FLASH_AUTO, FLASH_ON, TORCH, EXPOSURE, QR, COPY, EYE, CLOSE, BACK, INFO,
    METERING_SPOT, METERING_CENTRE, METERING_MATRIX, GREY_CARD, FOCUS_CENTRE, MOUNTAINS, FLOWER, TRASH,
}

@Composable
fun ZIcon(icon: ZernoIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f // one grid unit
        val stroke = Stroke(width = 1.6f * u, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
        when (icon) {
            ZernoIcon.DETAIL -> {
                // grain: a scatter of dots of different sizes
                val dots = listOf(
                    Triple(6f, 6f, 1.7f), Triple(12f, 5f, 1.1f), Triple(18f, 7f, 1.5f),
                    Triple(5f, 12f, 1.1f), Triple(11.5f, 12f, 2.1f), Triple(18.5f, 13f, 1.0f),
                    Triple(7f, 18f, 1.4f), Triple(13f, 19f, 1.0f), Triple(18f, 18f, 1.7f),
                )
                for ((x, y, r) in dots) drawCircle(tint, r * u, p(x, y))
            }
            ZernoIcon.HDR -> {
                // a frame split into a dark and a bright half
                drawRoundRect(tint, p(3f, 6f), Size(18f * u, 12f * u), CornerRadius(2.5f * u), style = stroke)
                val half = Path().apply {
                    moveTo(12f * u, 6f * u); lineTo(18.5f * u, 6f * u)
                    quadraticTo(21f * u, 6f * u, 21f * u, 8.5f * u)
                    lineTo(21f * u, 15.5f * u)
                    quadraticTo(21f * u, 18f * u, 18.5f * u, 18f * u)
                    lineTo(12f * u, 18f * u); close()
                }
                drawPath(half, tint)
            }
            ZernoIcon.GRID -> {
                drawRoundRect(tint, p(3.5f, 3.5f), Size(17f * u, 17f * u), CornerRadius(2f * u), style = stroke)
                line(9.2f, 4f, 9.2f, 20f); line(14.8f, 4f, 14.8f, 20f)
                line(4f, 9.2f, 20f, 9.2f); line(4f, 14.8f, 20f, 14.8f)
            }
            ZernoIcon.LEVEL -> {
                line(2.5f, 12f, 7.5f, 12f); line(16.5f, 12f, 21.5f, 12f)
                drawCircle(tint, 3.6f * u, p(12f, 12f), style = stroke)
                drawCircle(tint, 1.1f * u, p(12f, 12f))
            }
            ZernoIcon.TIMER -> {
                drawCircle(tint, 7.5f * u, p(12f, 13.5f), style = stroke)
                line(12f, 13.5f, 12f, 9.5f)
                line(9.5f, 3f, 14.5f, 3f)
                line(12f, 3f, 12f, 6f)
            }
            ZernoIcon.SETTINGS -> {
                drawCircle(tint, 3.2f * u, p(12f, 12f), style = stroke)
                drawCircle(tint, 6.6f * u, p(12f, 12f), style = stroke)
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    val c = cos(a).toFloat(); val s = sin(a).toFloat()
                    line(12f + 6.6f * c, 12f + 6.6f * s, 12f + 9.3f * c, 12f + 9.3f * s)
                }
            }
            ZernoIcon.SWITCH_CAMERA -> {
                // two arrows chasing each other round a circle
                for (turn in 0..1) rotate(180f * turn, p(12f, 12f)) { halfTurnArrow(tint, u, stroke) }
            }
            ZernoIcon.FLASH_ON, ZernoIcon.FLASH_OFF, ZernoIcon.FLASH_AUTO -> {
                val dx = if (icon == ZernoIcon.FLASH_AUTO) -3f else 0f
                val bolt = Path().apply {
                    moveTo((13.5f + dx) * u, 2.5f * u); lineTo((6.5f + dx) * u, 13.5f * u); lineTo((11.5f + dx) * u, 13.5f * u)
                    lineTo((10.5f + dx) * u, 21.5f * u); lineTo((17.5f + dx) * u, 10.5f * u); lineTo((12.5f + dx) * u, 10.5f * u)
                    close()
                }
                drawPath(bolt, tint)
                if (icon == ZernoIcon.FLASH_OFF) line(4f, 4f, 20f, 20f)
                if (icon == ZernoIcon.FLASH_AUTO) {
                    // a small "A"
                    line(16.5f, 21f, 19f, 14f); line(19f, 14f, 21.5f, 21f); line(17.5f, 18.6f, 20.5f, 18.6f)
                }
            }
            ZernoIcon.TORCH -> {
                // a light bulb
                drawArc(tint, 150f, 240f, false, p(6f, 3f), Size(12f * u, 12f * u), style = stroke)
                line(8.2f, 13.2f, 9.5f, 16.5f); line(15.8f, 13.2f, 14.5f, 16.5f)
                line(9.5f, 16.5f, 14.5f, 16.5f); line(10f, 19f, 14f, 19f); line(11f, 21.5f, 13f, 21.5f)
            }
            ZernoIcon.EXPOSURE -> {
                // a sun: brightness
                drawCircle(tint, 3.6f * u, p(12f, 12f), style = stroke)
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    val c = cos(a).toFloat(); val sn = sin(a).toFloat()
                    line(12f + 6.6f * c, 12f + 6.6f * sn, 12f + 8.8f * c, 12f + 8.8f * sn)
                }
            }
            ZernoIcon.QR -> {
                for ((x, y) in listOf(3.5f to 3.5f, 14f to 3.5f, 3.5f to 14f)) {
                    drawRoundRect(tint, p(x, y), Size(6.5f * u, 6.5f * u), CornerRadius(1.2f * u), style = stroke)
                }
                for ((x, y) in listOf(14.5f to 14.5f, 19.5f to 14.5f, 17f to 17f, 14.5f to 19.5f, 19.5f to 19.5f)) {
                    drawCircle(tint, 1.1f * u, p(x, y))
                }
            }
            ZernoIcon.COPY -> {
                drawRoundRect(tint, p(8.5f, 8.5f), Size(11.5f * u, 12f * u), CornerRadius(2.2f * u), style = stroke)
                val back = Path().apply {
                    moveTo(15.5f * u, 5.5f * u); lineTo(15.5f * u, 5f * u)
                    quadraticTo(15.5f * u, 3.5f * u, 14f * u, 3.5f * u); lineTo(5.5f * u, 3.5f * u)
                    quadraticTo(4f * u, 3.5f * u, 4f * u, 5f * u); lineTo(4f * u, 14f * u)
                    quadraticTo(4f * u, 15.5f * u, 5.5f * u, 15.5f * u)
                }
                drawPath(back, tint, style = stroke)
            }
            ZernoIcon.EYE -> {
                val lid = Path().apply {
                    moveTo(2.5f * u, 12f * u); quadraticTo(12f * u, 2.5f * u, 21.5f * u, 12f * u)
                    quadraticTo(12f * u, 21.5f * u, 2.5f * u, 12f * u); close()
                }
                drawPath(lid, tint, style = stroke)
                drawCircle(tint, 2.6f * u, p(12f, 12f))
            }
            ZernoIcon.METERING_SPOT, ZernoIcon.METERING_CENTRE, ZernoIcon.METERING_MATRIX -> {
                drawRoundRect(tint, p(3f, 5f), Size(18f * u, 14f * u), CornerRadius(2.2f * u), style = stroke)
                when (icon) {
                    ZernoIcon.METERING_SPOT -> drawCircle(tint, 1.6f * u, p(12f, 12f))
                    ZernoIcon.METERING_CENTRE -> {
                        drawCircle(tint, 3.8f * u, p(12f, 12f), style = stroke)
                        drawCircle(tint, 1.2f * u, p(12f, 12f))
                    }
                    else -> {
                        // the whole frame: brackets in the corners and a centre point
                        line(6f, 8.5f, 6f, 7.5f); line(6f, 7.5f, 7.5f, 7.5f)
                        line(18f, 8.5f, 18f, 7.5f); line(18f, 7.5f, 16.5f, 7.5f)
                        line(6f, 15.5f, 6f, 16.5f); line(6f, 16.5f, 7.5f, 16.5f)
                        line(18f, 15.5f, 18f, 16.5f); line(18f, 16.5f, 16.5f, 16.5f)
                        drawCircle(tint, 1.2f * u, p(12f, 12f))
                    }
                }
            }
            ZernoIcon.TRASH -> {
                line(4f, 6.5f, 20f, 6.5f)
                line(9.5f, 6.5f, 9.5f, 4.5f); line(9.5f, 4.5f, 14.5f, 4.5f); line(14.5f, 4.5f, 14.5f, 6.5f)
                val bin = Path().apply {
                    moveTo(6f * u, 6.5f * u); lineTo(7f * u, 19f * u)
                    quadraticTo(7.1f * u, 20.5f * u, 8.6f * u, 20.5f * u); lineTo(15.4f * u, 20.5f * u)
                    quadraticTo(16.9f * u, 20.5f * u, 17f * u, 19f * u); lineTo(18f * u, 6.5f * u)
                }
                drawPath(bin, tint, style = stroke)
                line(10f, 10.5f, 10.3f, 17f); line(14f, 10.5f, 13.7f, 17f)
            }
            ZernoIcon.MOUNTAINS -> {
                val ridge = Path().apply {
                    moveTo(1.5f * u, 19.5f * u); lineTo(8.5f * u, 7f * u); lineTo(13f * u, 14f * u)
                    lineTo(16f * u, 9.5f * u); lineTo(22.5f * u, 19.5f * u); close()
                }
                drawPath(ridge, tint, style = stroke)
                // a snow cap on the big peak
                line(6.2f, 11f, 8.5f, 12.5f); line(8.5f, 12.5f, 10.5f, 10.8f)
            }
            ZernoIcon.FLOWER -> {
                // five petals round a centre
                for (i in 0 until 5) {
                    val a = (i * 72 - 90) * PI / 180
                    drawCircle(tint, 3.1f * u, p(12f + 5.2f * cos(a).toFloat(), 11.5f + 5.2f * sin(a).toFloat()), style = stroke)
                }
                drawCircle(tint, 1.9f * u, p(12f, 11.5f))
            }
            ZernoIcon.FOCUS_CENTRE -> {
                // focus-square corners around a centre point
                line(4f, 8f, 4f, 4f); line(4f, 4f, 8f, 4f)
                line(16f, 4f, 20f, 4f); line(20f, 4f, 20f, 8f)
                line(4f, 16f, 4f, 20f); line(4f, 20f, 8f, 20f)
                line(16f, 20f, 20f, 20f); line(20f, 20f, 20f, 16f)
                drawCircle(tint, 1.8f * u, p(12f, 12f))
            }
            ZernoIcon.GREY_CARD -> {
                drawRoundRect(tint, p(4f, 4f), Size(16f * u, 16f * u), CornerRadius(2.5f * u), style = stroke)
                drawRoundRect(tint.copy(alpha = tint.alpha * 0.5f), p(7f, 7f), Size(10f * u, 10f * u), CornerRadius(1.2f * u))
            }
            ZernoIcon.BACK -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            ZernoIcon.INFO -> {
                drawCircle(tint, 8.6f * u, p(12f, 12f), style = stroke)
                line(12f, 11f, 12f, 16.5f)
                drawCircle(tint, 1.1f * u, p(12f, 7.7f))
            }
            ZernoIcon.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            ZernoIcon.GALLERY -> {
                drawRoundRect(tint, p(3.5f, 5f), Size(17f * u, 14f * u), CornerRadius(2.5f * u), style = stroke)
                val hills = Path().apply {
                    moveTo(5f * u, 17f * u); lineTo(9.5f * u, 11.5f * u); lineTo(13f * u, 15.5f * u)
                    lineTo(15.5f * u, 13f * u); lineTo(19f * u, 17f * u)
                }
                drawPath(hills, tint, style = stroke)
                drawCircle(tint, 1.3f * u, p(15.5f, 9f))
            }
        }
    }
}

private fun DrawScope.halfTurnArrow(tint: Color, u: Float, stroke: Stroke) {
    val r = 7.5f * u
    drawArc(
        tint, startAngle = 200f, sweepAngle = 130f, useCenter = false,
        topLeft = Offset(12f * u - r, 12f * u - r), size = Size(2 * r, 2 * r), style = stroke,
    )
    // arrow head at the end of the arc (330 degrees)
    val a = 330.0 * PI / 180
    val tip = Offset(12f * u + r * cos(a).toFloat(), 12f * u + r * sin(a).toFloat())
    drawLine(tint, tip, tip + Offset(-3.6f * u, -0.6f * u), strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawLine(tint, tip, tip + Offset(1.0f * u, -3.6f * u), strokeWidth = stroke.width, cap = StrokeCap.Round)
}
