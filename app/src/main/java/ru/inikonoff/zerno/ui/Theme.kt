package ru.inikonoff.zerno.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object ZernoColors {
    val Accent = Color(0xFFFFC53D) // warm "grain" yellow for the selected item
    val OnPreview = Color.White
    val Dim = Color(0x99FFFFFF)
    val Disabled = Color(0x55FFFFFF)
    val Scrim = Color(0x66000000)
    val Chip = Color(0x59000000)
}

/** Text drawn over the live preview: white with a soft shadow, so it stays readable on any scene. */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ZernoColors.OnPreview,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Medium,
    align: TextAlign = TextAlign.Center,
    letterSpacing: TextUnit = 0.sp,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = 1,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            textAlign = align,
            letterSpacing = letterSpacing,
            shadow = Shadow(Color(0x99000000), Offset(0f, 1f), 3f),
        ),
    )
}

/** A click without the ripple (the UI has its own pressed states). */
@Composable
fun Modifier.tap(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )

/** A tap, and a double tap (which waits a moment to tell the two apart). */
@Composable
fun Modifier.tapOrDouble(role: Role = Role.Button, onDoubleTap: () -> Unit, onTap: () -> Unit): Modifier {
    val currentTap = rememberUpdatedState(onTap)
    val currentDouble = rememberUpdatedState(onDoubleTap)
    return this.pointerInput(Unit) {
        detectTapGestures(onTap = { currentTap.value() }, onDoubleTap = { currentDouble.value() })
    }.semantics { this.role = role }
}

/** Rotates content to stay upright as the (portrait locked) device is turned, always taking the
 *  shortest way round.
 */
@Composable
fun Modifier.upright(uiRotation: Int): Modifier {
    val angle = remember { Animatable(uiRotation.toFloat()) }
    LaunchedEffect(uiRotation) {
        val current = angle.value
        var delta = (uiRotation - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        angle.animateTo(current + delta, tween(250))
    }
    return this.rotate(angle.value)
}
