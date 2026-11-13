package ru.inikonoff.zerno.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.Scales
import ru.inikonoff.zerno.adapter.UiState

enum class ProParam(val title: String) { ISO("ISO"), S("S"), EV("EV"), WB("WB"), MF("MF") }

@Composable
private fun wbName(value: String): String = when (value) {
    "auto" -> stringResource(R.string.zerno_wb_auto)
    "daylight" -> stringResource(R.string.zerno_wb_daylight)
    "cloudy-daylight" -> stringResource(R.string.zerno_wb_cloudy)
    "incandescent" -> stringResource(R.string.zerno_wb_incandescent)
    "fluorescent" -> stringResource(R.string.zerno_wb_fluorescent)
    else -> value
}

/** The "Pro" panel: a row with the current manual parameters; tapping one opens a drum ruler
 *  for it underneath.
 */
@Composable
fun ProPanel(
    state: UiState,
    adapter: CameraAdapter,
    selected: ProParam?,
    onSelect: (ProParam?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val caps = state.caps
    val pro = state.pro
    val params = buildList {
        if (caps.manualExposure) { add(ProParam.ISO); add(ProParam.S) }
        if (caps.hasEv) add(ProParam.EV)
        if (caps.wbPresets.size > 1 || caps.wbKelvinValues.isNotEmpty()) add(ProParam.WB)
        if (caps.manualFocus) add(ProParam.MF)
    }
    val open = selected.takeIf { it in params }
    val auto = stringResource(R.string.zerno_auto)
    val autoShort = stringResource(R.string.zerno_auto_short)

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (param in params) {
                val value = when (param) {
                    ProParam.ISO -> if (pro.isoAuto) autoShort + (if (pro.liveIso > 0) " ${pro.liveIso}" else "") else pro.iso.toString()
                    ProParam.S -> if (pro.shutterAuto) autoShort + (if (pro.liveShutterNs > 0) " ${Scales.formatShutter(pro.liveShutterNs)}" else "") else Scales.formatShutter(pro.shutterNs)
                    ProParam.EV -> Scales.formatEv(pro.ev, caps.evStep)
                    ProParam.WB -> if (pro.wb == "manual") "${pro.kelvin}K" else wbName(pro.wb)
                    ProParam.MF -> if (pro.focusAuto) "AF" else Scales.formatFocus(pro.focusDistance)
                }
                val locked = param == ProParam.EV && pro.evLocked
                val isOpen = param == open
                Column(
                    Modifier
                        .widthIn(min = 56.dp)
                        .tap { onSelect(if (isOpen) null else param) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Label(param.title, color = if (isOpen) ZernoColors.Accent else ZernoColors.Dim, size = 11.sp, letterSpacing = 0.8.sp)
                    Label(
                        value,
                        color = when {
                            locked -> ZernoColors.Disabled
                            isOpen -> ZernoColors.Accent
                            else -> ZernoColors.OnPreview
                        },
                        size = 14.sp,
                        weight = FontWeight.SemiBold,
                    )
                }
            }
        }

        when (open) {
            null -> {}
            ProParam.ISO -> DrumRuler(
                labels = listOf(auto) + caps.isoValues.map { it.toString() },
                selectedIndex = if (pro.isoAuto) 0 else 1 + Scales.nearestLogIndex(caps.isoValues, pro.iso),
                onSelect = { adapter.setIso(if (it == 0) null else caps.isoValues[it - 1]) },
            )
            ProParam.S -> DrumRuler(
                labels = listOf(auto) + caps.shutterValues.map { Scales.formatShutter(it) },
                selectedIndex = if (pro.shutterAuto) 0 else 1 + Scales.nearestLogIndex(caps.shutterValues, pro.shutterNs),
                onSelect = { adapter.setShutter(if (it == 0) null else caps.shutterValues[it - 1]) },
            )
            ProParam.EV -> if (pro.evLocked) {
                Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                    Label(stringResource(R.string.zerno_ev_locked), color = ZernoColors.Dim, size = 12.sp)
                }
            } else {
                DrumRuler(
                    labels = (caps.evMin..caps.evMax).map { Scales.formatEv(it, caps.evStep) },
                    selectedIndex = (pro.ev - caps.evMin).coerceIn(0, caps.evMax - caps.evMin),
                    onSelect = { adapter.setEv(caps.evMin + it) },
                )
            }
            ProParam.WB -> {
                val presets = caps.wbPresets
                val kelvins = caps.wbKelvinValues
                DrumRuler(
                    labels = presets.map { wbName(it) } + kelvins.map { "${it}K" },
                    selectedIndex = if (pro.wb == "manual" && kelvins.isNotEmpty()) {
                        presets.size + Scales.nearestIndex(kelvins, pro.kelvin.toDouble()) { it.toDouble() }
                    } else presets.indexOf(pro.wb).coerceAtLeast(0),
                    onSelect = { if (it < presets.size) adapter.setWhiteBalance(presets[it]) else adapter.setKelvin(kelvins[it - presets.size]) },
                )
            }
            ProParam.MF -> DrumRuler(
                labels = listOf(auto) + (0..Scales.FOCUS_STEPS).map { Scales.formatFocus(Scales.focusDistanceAt(it, caps.minFocusDistance)) },
                selectedIndex = if (pro.focusAuto) 0 else 1 + Scales.focusIndexOf(pro.focusDistance, caps.minFocusDistance),
                onSelect = { adapter.setFocusDistance(if (it == 0) null else Scales.focusDistanceAt(it - 1, caps.minFocusDistance)) },
            )
        }
    }
}
