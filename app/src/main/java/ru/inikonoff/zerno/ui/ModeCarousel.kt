package ru.inikonoff.zerno.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.Mode
import kotlin.math.abs
import kotlin.math.roundToInt

/** The mode carousel: the selected mode sits in the centre, above the shutter. Swipe along it,
 *  or tap a name, to change mode.
 */
@Composable
fun ModeCarousel(modes: List<Mode>, selected: Mode, onSelect: (Mode) -> Unit, modifier: Modifier = Modifier) {
    val slot = 92.dp
    val slotPx = with(LocalDensity.current) { slot.toPx() }
    val index = modes.indexOf(selected).coerceAtLeast(0)
    val position = remember { Animatable(index.toFloat()) } // in slots
    var settle by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val currentModes by rememberUpdatedState(modes)
    val currentOnSelect by rememberUpdatedState(onSelect)

    LaunchedEffect(index, settle) {
        position.animateTo(index.toFloat(), spring(stiffness = Spring.StiffnessMediumLow))
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val target = position.value.roundToInt().coerceIn(0, currentModes.lastIndex)
                        currentOnSelect(currentModes[target])
                        settle++ // glide to wherever the selection ended up
                    },
                    onDragCancel = { settle++ },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        position.snapTo((position.value - dragAmount / slotPx).coerceIn(-0.35f, currentModes.lastIndex + 0.35f))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        modes.forEachIndexed { i, mode ->
            val distance = abs(i - position.value).coerceAtMost(1f)
            Label(
                text = stringResource(
                    when (mode) {
                        Mode.NIGHT -> R.string.zerno_mode_night
                        Mode.PHOTO -> R.string.zerno_mode_photo
                        Mode.PRO -> R.string.zerno_mode_pro
                    }
                ),
                modifier = Modifier
                    .offset { IntOffset(((i - position.value) * slotPx).roundToInt(), 0) }
                    .width(slot)
                    .tap(role = Role.Tab) { onSelect(mode) },
                color = lerp(ZernoColors.Accent, ZernoColors.OnPreview, distance),
                size = 14.sp,
                weight = if (distance < 0.5f) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 1.2.sp,
            )
        }
    }
}
