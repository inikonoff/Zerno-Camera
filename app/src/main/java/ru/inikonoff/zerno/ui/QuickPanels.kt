package ru.inikonoff.zerno.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.Focus
import ru.inikonoff.zerno.adapter.Scales
import ru.inikonoff.zerno.adapter.UiState

/** Exposure compensation ruler, opened by the button to the left of the shutter. */
@Composable
fun ExposurePanel(state: UiState, adapter: CameraAdapter, modifier: Modifier = Modifier) {
    val caps = state.caps
    if (state.pro.evLocked) {
        Box(modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            Label(stringResource(R.string.zerno_ev_locked), color = ZernoColors.Dim, size = 12.sp)
        }
    } else {
        DrumRuler(
            labels = (caps.evMin..caps.evMax).map { Scales.formatEv(it, caps.evStep) },
            selectedIndex = (state.pro.ev - caps.evMin).coerceIn(0, caps.evMax - caps.evMin),
            onSelect = { adapter.setEv(caps.evMin + it) },
            modifier = modifier,
        )
    }
}

/** Focus modes, opened by the button to the right of the shutter; in manual focus a distance
 *  ruler appears underneath.
 */
@Composable
fun FocusPanel(state: UiState, adapter: CameraAdapter, modifier: Modifier = Modifier) {
    val caps = state.caps
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (mode in caps.focusModes) {
                val isSelected = mode == state.focusMode
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) ZernoColors.Accent.copy(alpha = 0.22f) else ZernoColors.Chip)
                        .tap { adapter.setFocusMode(mode) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Label(
                        if (mode == Focus.MACRO) stringResource(R.string.zerno_focus_macro) else focusShortName(mode),
                        color = if (isSelected) ZernoColors.Accent else ZernoColors.OnPreview,
                        size = 13.sp,
                        weight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        if (state.focusMode == Focus.MANUAL && caps.manualFocus) {
            DrumRuler(
                labels = (0..Scales.FOCUS_STEPS).map { Scales.formatFocus(Scales.focusDistanceAt(it, caps.minFocusDistance)) },
                selectedIndex = Scales.focusIndexOf(state.pro.focusDistance, caps.minFocusDistance),
                onSelect = { adapter.setFocusDistance(Scales.focusDistanceAt(it, caps.minFocusDistance)) },
            )
        }
    }
}
