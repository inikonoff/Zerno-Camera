package ru.inikonoff.zerno.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.Detail
import ru.inikonoff.zerno.adapter.Flash
import ru.inikonoff.zerno.adapter.Hdr
import ru.inikonoff.zerno.adapter.Mode
import ru.inikonoff.zerno.adapter.Ratio
import ru.inikonoff.zerno.adapter.Timer
import ru.inikonoff.zerno.adapter.UiState

private enum class Chooser { DETAIL, HDR, FLASH, RATIO, TIMER }

/** The top bar: quick switches. A switch with two positions toggles in place; one with three
 *  positions opens a row of choices in place of the bar.
 */
@Composable
fun TopBar(state: UiState, adapter: CameraAdapter, modifier: Modifier = Modifier) {
    var chooser by remember { mutableStateOf<Chooser?>(null) }
    val showDetail = state.mode != Mode.NIGHT && state.caps.details.size > 1
    val showHdr = state.mode == Mode.PHOTO && state.caps.hdrs.size > 1
    // a chooser whose switch has just disappeared (e.g. after a mode change) closes itself
    val open = chooser.takeIf {
        when (it) {
            Chooser.DETAIL -> showDetail
            Chooser.HDR -> showHdr
            Chooser.FLASH -> state.caps.flashes.isNotEmpty()
            Chooser.RATIO -> state.caps.ratios.isNotEmpty()
            else -> true
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (open) {
            null -> Row(
                Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.caps.flashes.isNotEmpty()) {
                    BarIcon(flashIcon(state.flash), stringResource(R.string.zerno_flash), state.flash != Flash.OFF, state.uiRotation) { chooser = Chooser.FLASH }
                }
                if (showDetail) {
                    BarIcon(ZernoIcon.DETAIL, stringResource(R.string.zerno_detail), state.detail != Detail.STANDARD, state.uiRotation) { chooser = Chooser.DETAIL }
                }
                if (showHdr) {
                    BarIcon(ZernoIcon.HDR, stringResource(R.string.zerno_hdr), state.hdr != Hdr.OFF, state.uiRotation) { chooser = Chooser.HDR }
                }
                if (state.caps.ratios.isNotEmpty()) {
                    BarText(state.ratio?.label ?: "–", stringResource(R.string.zerno_ratio), state.uiRotation) { chooser = Chooser.RATIO }
                }
                BarIcon(ZernoIcon.GRID, stringResource(R.string.zerno_grid), state.grid, state.uiRotation) { adapter.setGrid(!state.grid) }
                BarIcon(ZernoIcon.LEVEL, stringResource(R.string.zerno_level), state.level, state.uiRotation) { adapter.setLevel(!state.level) }
                BarIcon(ZernoIcon.TIMER, stringResource(R.string.zerno_timer), state.timer != Timer.OFF, state.uiRotation) { chooser = Chooser.TIMER }
                BarIcon(ZernoIcon.SETTINGS, stringResource(R.string.zerno_settings), false, state.uiRotation) { adapter.openSettings() }
            }
            Chooser.DETAIL -> ChoiceRow(
                title = stringResource(R.string.zerno_detail),
                options = state.caps.details.map {
                    it to stringResource(
                        when (it) {
                            Detail.OFF -> R.string.zerno_detail_off
                            Detail.MINIMAL -> R.string.zerno_detail_minimal
                            Detail.STANDARD -> R.string.zerno_detail_standard
                        }
                    )
                },
                selected = state.detail,
                onClose = { chooser = null },
            ) { adapter.setDetail(it); chooser = null }
            Chooser.HDR -> ChoiceRow(
                title = stringResource(R.string.zerno_hdr),
                options = state.caps.hdrs.map {
                    it to stringResource(
                        when (it) {
                            Hdr.OFF -> R.string.zerno_hdr_off
                            Hdr.DRO -> R.string.zerno_hdr_dro
                            Hdr.HDR -> R.string.zerno_hdr_hdr
                        }
                    )
                },
                selected = state.hdr,
                onClose = { chooser = null },
            ) { adapter.setHdr(it); chooser = null }
            Chooser.FLASH -> ChoiceRow(
                title = stringResource(R.string.zerno_flash),
                options = state.caps.flashes.map {
                    it to stringResource(
                        when (it) {
                            Flash.AUTO, Flash.SCREEN_AUTO -> R.string.zerno_flash_auto
                            Flash.ON, Flash.SCREEN_ON -> R.string.zerno_flash_on
                            Flash.TORCH, Flash.SCREEN_TORCH -> R.string.zerno_flash_torch
                            else -> R.string.zerno_flash_off
                        }
                    )
                },
                selected = state.flash,
                onClose = { chooser = null },
            ) { adapter.setFlash(it); chooser = null }
            Chooser.RATIO -> ChoiceRow(
                title = stringResource(R.string.zerno_ratio),
                options = state.caps.ratios.map<Ratio, Pair<Ratio?, String>> { it to it.label },
                selected = state.ratio,
                onClose = { chooser = null },
            ) { if (it != null) adapter.setRatio(it); chooser = null }
            Chooser.TIMER -> ChoiceRow(
                title = stringResource(R.string.zerno_timer),
                options = Timer.entries.map {
                    it to if (it == Timer.OFF) stringResource(R.string.zerno_timer_off)
                    else stringResource(R.string.zerno_timer_seconds, it.seconds)
                },
                selected = state.timer,
                onClose = { chooser = null },
            ) { adapter.setTimer(it); chooser = null }
        }
    }
}

private fun flashIcon(flash: String): ZernoIcon = when (flash) {
    Flash.AUTO, Flash.SCREEN_AUTO -> ZernoIcon.FLASH_AUTO
    Flash.ON, Flash.SCREEN_ON -> ZernoIcon.FLASH_ON
    Flash.TORCH, Flash.SCREEN_TORCH -> ZernoIcon.TORCH
    else -> ZernoIcon.FLASH_OFF
}

/** A top bar item that shows its current value as text (the aspect ratio). */
@Composable
private fun RowScope.BarText(text: String, description: String, uiRotation: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(48.dp)
            .semantics { contentDescription = description }
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, Modifier.upright(uiRotation), size = 13.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun RowScope.BarIcon(icon: ZernoIcon, description: String, active: Boolean, uiRotation: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(48.dp)
            .semantics { contentDescription = description }
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZIcon(icon, if (active) ZernoColors.Accent else ZernoColors.OnPreview, Modifier.size(24.dp).upright(uiRotation))
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onClose: () -> Unit,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxSize().tap(onClick = onClose).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, color = ZernoColors.Dim, size = 13.sp)
        Spacer(Modifier.width(8.dp))
        for ((value, name) in options) {
            val isSelected = value == selected
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) ZernoColors.Accent.copy(alpha = 0.22f) else ZernoColors.Chip)
                    .tap { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Label(
                    name,
                    color = if (isSelected) ZernoColors.Accent else ZernoColors.OnPreview,
                    size = 13.sp,
                    weight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}
