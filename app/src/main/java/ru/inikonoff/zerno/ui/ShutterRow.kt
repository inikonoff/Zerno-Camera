package ru.inikonoff.zerno.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.sourceforge.opencamera.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.Focus
import ru.inikonoff.zerno.adapter.Scales
import ru.inikonoff.zerno.adapter.UiState

/** The bottom row: last photo (opens the gallery), shutter, switch between rear and front camera. */
@Composable
fun ShutterRow(
    state: UiState,
    adapter: CameraAdapter,
    exposureOpen: Boolean,
    focusOpen: Boolean,
    onExposure: () -> Unit,
    onExposureReset: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(state, onClick = adapter::openGallery)
        // exposure compensation, to the left of the shutter
        SideButton(
            visible = state.caps.hasEv,
            description = stringResource(R.string.zerno_exposure),
            active = exposureOpen || state.pro.ev != 0,
            onClick = onExposure,
            onDoubleClick = onExposureReset, // a double tap sets the compensation back to 0
            onLongDrag = adapter::stepEv, // press, hold and slide: darker to the left, brighter to the right
        ) { tint ->
            if (state.pro.ev != 0) {
                Label(Scales.formatEv(state.pro.ev, state.caps.evStep), Modifier.upright(state.uiRotation), color = tint, size = 12.sp, weight = FontWeight.Bold)
            } else {
                ZIcon(ZernoIcon.EXPOSURE, tint, Modifier.size(22.dp))
            }
        }
        ShutterButton(
            enabled = state.cameraOpen,
            busy = state.takingPhoto && !state.onTimer,
            onTimer = state.onTimer,
            onClick = adapter::takePhoto,
        )
        // focus mode, to the right of the shutter
        SideButton(
            visible = state.caps.focusModes.isNotEmpty(),
            description = stringResource(R.string.zerno_focus),
            active = focusOpen || state.focusMode != Focus.CONTINUOUS,
            onClick = onFocus,
            onDoubleClick = { adapter.setFocusMode(Focus.CONTINUOUS) }, // a double tap goes back to AF-C
        ) { tint ->
            Label(focusShortName(state.focusMode), Modifier.upright(state.uiRotation), color = tint, size = 12.sp, weight = FontWeight.Bold)
        }
        SwitchCameraButton(
            visible = state.caps.canSwitchCamera,
            enabled = state.cameraOpen && !state.takingPhoto,
            uiRotation = state.uiRotation,
            onClick = adapter::switchCamera,
        )
    }
}

/** Short names of the focus modes, as shown on the focus button. */
fun focusShortName(mode: String): String = when (mode) {
    Focus.CONTINUOUS -> "AF-C"
    Focus.AUTO -> "AF-S"
    Focus.MACRO -> "MACRO"
    Focus.INFINITY -> "∞"
    Focus.MANUAL -> "MF"
    else -> "AF"
}

@Composable
private fun SideButton(
    visible: Boolean,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
    /** Long press, then slide: called with +1 / -1 for each step to the right / left. */
    onLongDrag: ((Int) -> Unit)? = null,
    content: @Composable (tint: Color) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val currentDrag by rememberUpdatedState(onLongDrag)
    Box(
        Modifier
            .size(44.dp)
            .alpha(if (visible) 1f else 0f)
            .clip(CircleShape)
            .background(ZernoColors.Chip)
            .semantics { contentDescription = description }
            .then(
                if (!visible) Modifier
                else if (onDoubleClick != null) Modifier.tapOrDouble(onDoubleTap = onDoubleClick, onTap = onClick)
                else Modifier.tap(onClick = onClick)
            )
            .then(
                if (!visible || onLongDrag == null) Modifier
                else Modifier.pointerInput(Unit) {
                    val step = 14.dp.toPx()
                    var travel = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            travel = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    ) { change, drag ->
                        change.consume()
                        travel += drag.x
                        while (travel >= step) {
                            travel -= step
                            currentDrag?.invoke(1)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        while (travel <= -step) {
                            travel += step
                            currentDrag?.invoke(-1)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        content(if (active) ZernoColors.Accent else ZernoColors.OnPreview)
    }
}

@Composable
private fun Thumbnail(state: UiState, onClick: () -> Unit) {
    val description = stringResource(R.string.zerno_gallery)
    // while photos are still being processed and saved in the background, a bright segment runs
    // round the frame (the save time isn't known in advance, so there's no percentage)
    val running = rememberInfiniteTransition(label = "saving")
    val phase by running.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "savingPhase")
    val saving = state.saving
    Box(
        Modifier
            .size(52.dp)
            .upright(state.uiRotation)
            .clip(RoundedCornerShape(12.dp))
            .background(ZernoColors.Chip)
            .border(1.5.dp, Color(0xCCFFFFFF), RoundedCornerShape(12.dp))
            .drawWithContent {
                drawContent()
                if (saving) {
                    val stroke = 3.dp.toPx()
                    val frame = Path().apply {
                        addRoundRect(
                            RoundRect(
                                stroke / 2f, stroke / 2f, size.width - stroke / 2f, size.height - stroke / 2f,
                                CornerRadius(12.dp.toPx() - stroke / 2f),
                            )
                        )
                    }
                    val measure = PathMeasure().apply { setPath(frame, true) }
                    val length = measure.length
                    val start = phase * length
                    val end = start + length * 0.3f
                    val segment = Path()
                    measure.getSegment(start, minOf(end, length), segment, true)
                    if (end > length) measure.getSegment(0f, end - length, segment, true)
                    drawPath(segment, ZernoColors.Accent, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            .semantics { contentDescription = description }
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = state.thumbnail
        if (bitmap != null && !bitmap.isRecycled) {
            Image(remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            ZIcon(ZernoIcon.GALLERY, ZernoColors.Dim, Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, busy: Boolean, onTimer: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.zerno_shutter)
    var pressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentEnabled by rememberUpdatedState(enabled)
    val innerScale by animateFloatAsState(
        when {
            onTimer -> 0.45f // a small square-ish stop button: tapping again cancels the timer
            pressed || busy -> 0.82f
            else -> 1f
        },
        tween(120), label = "shutterScale",
    )
    Box(
        Modifier
            .size(78.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .border(4.dp, Color.White, CircleShape)
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick { currentOnClick(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    // also while the camera is still starting: the press is remembered
                    onTap = { currentOnClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .scale(innerScale)
                .clip(if (onTimer) RoundedCornerShape(14.dp) else CircleShape)
                .background(if (busy) Color(0xFFBDBDBD) else Color.White)
        )
    }
}

@Composable
private fun SwitchCameraButton(visible: Boolean, enabled: Boolean, uiRotation: Int, onClick: () -> Unit) {
    val description = stringResource(R.string.zerno_switch_camera)
    val spin = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(52.dp)
            .alpha(if (!visible) 0f else if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(ZernoColors.Chip)
            .semantics { contentDescription = description }
            .tap(enabled = visible && enabled) {
                scope.launch { spin.animateTo(spin.value + 180f, tween(300)) }
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        ZIcon(ZernoIcon.SWITCH_CAMERA, ZernoColors.OnPreview, Modifier.size(26.dp).upright(uiRotation).rotate(spin.value))
    }
}
