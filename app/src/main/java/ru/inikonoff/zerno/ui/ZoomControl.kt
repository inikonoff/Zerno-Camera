package ru.inikonoff.zerno.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private const val WHEEL_HIDE_DELAY_MS = 1300L
private const val DEGREES_PER_STOP = 26f // how far the dial turns when the zoom doubles
private val LN2 = ln(2.0).toFloat()

private fun formatZoom(zoom: Float): String {
    val tenths = (zoom * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** Zoom: quick buttons for whole steps, and an arc dial for everything in between. The dial
 *  appears when you drag along the buttons, and goes away again shortly after you let go.
 */
@Composable
fun ZoomControl(
    zoom: Float,
    min: Float,
    max: Float,
    uiRotation: Int,
    onZoom: (ratio: Float, animate: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var wheel by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    // while dragging, follow the finger exactly rather than the engine's stepped zoom ratios
    var dragZoom by remember { mutableFloatStateOf(zoom) }
    val shownZoom = if (dragging) dragZoom else zoom
    val currentZoom by rememberUpdatedState(zoom)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val range by rememberUpdatedState(min to max)

    LaunchedEffect(wheel, interaction, dragging) {
        if (wheel && !dragging) {
            delay(WHEEL_HIDE_DELAY_MS)
            wheel = false
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragZoom = currentZoom
                        dragging = true
                        wheel = true
                    },
                    onDragEnd = { dragging = false; interaction++ },
                    onDragCancel = { dragging = false; interaction++ },
                ) { change, dragAmount ->
                    change.consume()
                    // dragging a third of the width doubles (or halves) the zoom
                    val stops = -dragAmount / (size.width / 3f)
                    dragZoom = (dragZoom * 2f.pow(stops)).coerceIn(range.first, range.second)
                    currentOnZoom(dragZoom, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(wheel, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "zoom") { showWheel ->
            if (showWheel) {
                ZoomWheel(shownZoom, min, max, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ZoomButtons(shownZoom, min, max, uiRotation) { onZoom(it, true) }
                }
            }
        }
    }
}

@Composable
private fun ZoomButtons(zoom: Float, min: Float, max: Float, uiRotation: Int, onSelect: (Float) -> Unit) {
    val presets = listOf(1f, 2f, 3f, 4f).filter { it >= min - 0.01f && it <= max + 0.01f }
    if (presets.isEmpty()) return
    val nearest = presets.minBy { abs(it - zoom) }
    Row(
        Modifier.clip(CircleShape).background(ZernoColors.Chip).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (preset in presets) {
            val active = preset == nearest
            Box(
                Modifier
                    .size(if (active) 40.dp else 34.dp)
                    .clip(CircleShape)
                    .background(if (active) Color(0x8C000000) else Color.Transparent)
                    .tap { onSelect(preset) },
                contentAlignment = Alignment.Center,
            ) {
                Label(
                    // the active button shows the actual zoom, which may lie between the steps
                    text = if (active) formatZoom(zoom) + "×" else formatZoom(preset),
                    modifier = Modifier.upright(uiRotation),
                    color = if (active) ZernoColors.Accent else ZernoColors.OnPreview,
                    size = if (active) 13.sp else 12.sp,
                    weight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

/** The arc dial: a scale that turns under a fixed pointer. The scale is logarithmic, so each
 *  doubling of the zoom takes the same amount of turning.
 */
@Composable
private fun ZoomWheel(zoom: Float, min: Float, max: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val valueStyle = TextStyle(color = ZernoColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Canvas(modifier) {
        val radius = size.width * 0.95f
        val centre = Offset(size.width / 2f, 30.dp.toPx() + radius)
        fun pointAt(degrees: Float, r: Float): Offset {
            val a = degrees * PI.toFloat() / 180f
            return Offset(centre.x + r * sin(a), centre.y - r * cos(a))
        }
        val tickCount = ((max - min) * 10).roundToInt()
        for (i in 0..tickCount) {
            val value = min + i / 10f
            val degrees = (ln(value / zoom) / LN2) * DEGREES_PER_STOP
            if (abs(degrees) > 38f) continue
            val whole = abs(value - value.roundToInt()) < 0.01f
            val fade = (1f - abs(degrees) / 38f).coerceIn(0f, 1f)
            val length = (if (whole) 10.dp else 5.dp).toPx()
            drawLine(
                Color.White.copy(alpha = (if (whole) 1f else 0.6f) * fade),
                pointAt(degrees, radius), pointAt(degrees, radius - length),
                strokeWidth = (if (whole) 2.dp else 1.dp).toPx(), cap = StrokeCap.Round,
            )
            if (whole && abs(degrees) > 4f) {
                val layout = measurer.measure(formatZoom(value), labelStyle)
                val at = pointAt(degrees, radius - length - 9.dp.toPx())
                drawText(layout, alpha = fade, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
            }
        }
        // fixed pointer, and the current value above it
        val tip = pointAt(0f, radius + 3.dp.toPx())
        val pointer = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - 5.dp.toPx(), tip.y - 8.dp.toPx())
            lineTo(tip.x + 5.dp.toPx(), tip.y - 8.dp.toPx())
            close()
        }
        drawPath(pointer, ZernoColors.Accent)
        val value = measurer.measure(formatZoom(zoom) + "×", valueStyle)
        drawText(value, topLeft = Offset(tip.x - value.size.width / 2f, tip.y - 9.dp.toPx() - value.size.height))
    }
}
