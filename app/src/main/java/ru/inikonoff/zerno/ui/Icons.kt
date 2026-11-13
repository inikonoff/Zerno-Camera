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
enum class ZernoIcon { DETAIL, HDR, GRID, LEVEL, TIMER, SETTINGS, SWITCH_CAMERA, GALLERY }

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
