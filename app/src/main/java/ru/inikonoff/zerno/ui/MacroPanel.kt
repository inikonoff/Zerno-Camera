package ru.inikonoff.zerno.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.MacroState
import ru.inikonoff.zerno.adapter.Scales
import ru.inikonoff.zerno.adapter.UiState

enum class MacroParam { NEAR, FAR, FRAMES }

/** The "Macro" panel: where the series of frames begins and ends, and how many frames; the
 *  frames are spread evenly between the two ends. Tapping one opens a ruler for it; for the
 *  ends the preview is focused there, so that what is sharp can be seen.
 */
@Composable
fun MacroPanel(
    state: UiState,
    adapter: CameraAdapter,
    selected: MacroParam?,
    onSelect: (MacroParam?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val min = state.caps.minFocusDistance
    if (min <= 0f) return
    val macro = state.macro
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (param in MacroParam.entries) {
                val isOpen = param == selected
                val title = stringResource(
                    when (param) {
                        MacroParam.NEAR -> R.string.zerno_macro_near
                        MacroParam.FAR -> R.string.zerno_macro_far
                        MacroParam.FRAMES -> R.string.zerno_macro_frames
                    }
                )
                val value = when (param) {
                    MacroParam.NEAR -> Scales.focusIndexOf(macro.near, min).toString()
                    MacroParam.FAR -> Scales.focusIndexOf(macro.far, min).toString()
                    MacroParam.FRAMES -> macro.frames.toString()
                }
                Column(
                    Modifier.widthIn(min = 64.dp).tap { onSelect(if (isOpen) null else param) }.padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Label(title, color = if (isOpen) ZernoColors.Accent else ZernoColors.Dim, size = 10.sp, letterSpacing = 0.6.sp)
                    Label(value, color = if (isOpen) ZernoColors.Accent else ZernoColors.OnPreview, size = 12.sp, weight = FontWeight.SemiBold)
                }
            }
        }
        when (selected) {
            null -> {}
            MacroParam.NEAR, MacroParam.FAR -> {
                val isNear = selected == MacroParam.NEAR
                DrumRuler(
                    labels = (0..Scales.FOCUS_STEPS).map { "" },
                    selectedIndex = Scales.focusIndexOf(if (isNear) macro.near else macro.far, min),
                    onSelect = {
                        val d = Scales.focusDistanceAt(it, min)
                        if (isNear) adapter.setMacroNear(d) else adapter.setMacroFar(d)
                    },
                    ticks = true,
                    tickOffset = 0,
                    startIcon = ZernoIcon.MOUNTAINS, // infinity
                    endIcon = ZernoIcon.FLOWER, // the closest focus distance
                )
            }
            MacroParam.FRAMES -> DrumRuler(
                labels = (MacroState.MIN_FRAMES..MacroState.MAX_FRAMES).map { it.toString() },
                selectedIndex = (macro.frames - MacroState.MIN_FRAMES).coerceIn(0, MacroState.MAX_FRAMES - MacroState.MIN_FRAMES),
                onSelect = { adapter.setMacroFrames(MacroState.MIN_FRAMES + it) },
            )
        }
    }
}
