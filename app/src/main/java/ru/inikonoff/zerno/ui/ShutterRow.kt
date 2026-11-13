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
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.UiState

/** The bottom row: last photo (opens the gallery), shutter, switch between rear and front camera. */
@Composable
fun ShutterRow(state: UiState, adapter: CameraAdapter, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(state, onClick = adapter::openGallery)
        ShutterButton(
            enabled = state.cameraOpen,
            busy = state.takingPhoto && !state.onTimer,
            onTimer = state.onTimer,
            onClick = adapter::takePhoto,
        )
        SwitchCameraButton(
            visible = state.caps.canSwitchCamera,
            enabled = state.cameraOpen && !state.takingPhoto,
            uiRotation = state.uiRotation,
            onClick = adapter::switchCamera,
        )
    }
}

@Composable
private fun Thumbnail(state: UiState, onClick: () -> Unit) {
    val description = stringResource(R.string.zerno_gallery)
    // pulse while photos are still being processed and saved in the background
    val pulse = rememberInfiniteTransition(label = "saving")
    val pulseAlpha by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "savingAlpha")
    Box(
        Modifier
            .size(52.dp)
            .upright(state.uiRotation)
            .alpha(if (state.saving) pulseAlpha else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(ZernoColors.Chip)
            .border(1.5.dp, Color(0xCCFFFFFF), RoundedCornerShape(12.dp))
            .semantics { contentDescription = description }
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = state.thumbnail
        if (bitmap != null && !bitmap.isRecycled) {
            Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
                    onTap = { if (currentEnabled) currentOnClick() },
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
