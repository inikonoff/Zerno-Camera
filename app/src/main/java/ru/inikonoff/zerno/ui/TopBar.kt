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
import androidx.compose.ui.draw.alpha
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

/** The top bar: flash, horizon level and settings. Everything else lives in the drawer above
 *  the mode carousel (see [Drawer]).
 */
@Composable
fun TopBar(state: UiState, adapter: CameraAdapter, modifier: Modifier = Modifier) {
    var flashOpen by remember { mutableStateOf(false) }
    val hasFlash = state.caps.flashes.isNotEmpty()

    Box(modifier, contentAlignment = Alignment.Center) {
        if (flashOpen && hasFlash) {
            ChoiceRow(
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
                onClose = { flashOpen = false },
            ) { adapter.setFlash(it); flashOpen = false }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(flashIcon(state.flash), stringResource(R.string.zerno_flash), state.flash != Flash.OFF, state.uiRotation, visible = hasFlash) { flashOpen = true }
                BarIcon(ZernoIcon.LEVEL, stringResource(R.string.zerno_level), state.level, state.uiRotation) { adapter.setLevel(!state.level) }
                BarIcon(ZernoIcon.SETTINGS, stringResource(R.string.zerno_settings), false, state.uiRotation) { adapter.openSettings() }
            }
        }
    }
}

internal fun flashIcon(flash: String): ZernoIcon = when (flash) {
    Flash.AUTO, Flash.SCREEN_AUTO -> ZernoIcon.FLASH_AUTO
    Flash.ON, Flash.SCREEN_ON -> ZernoIcon.FLASH_ON
    Flash.TORCH, Flash.SCREEN_TORCH -> ZernoIcon.TORCH
    else -> ZernoIcon.FLASH_OFF
}

@Composable
private fun RowScope.BarIcon(icon: ZernoIcon, description: String, active: Boolean, uiRotation: Int, visible: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(48.dp)
            .alpha(if (visible) 1f else 0f)
            .semantics { contentDescription = description }
            .tap(enabled = visible, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZIcon(icon, if (active) ZernoColors.Accent else ZernoColors.OnPreview, Modifier.size(24.dp).upright(uiRotation))
    }
}

/** A title followed by the choices for one switch; tapping outside the choices closes it. */
@Composable
internal fun <T> ChoiceRow(
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
