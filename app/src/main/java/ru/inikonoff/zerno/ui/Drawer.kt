package ru.inikonoff.zerno.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.Detail
import ru.inikonoff.zerno.adapter.Hdr
import ru.inikonoff.zerno.adapter.Mode
import ru.inikonoff.zerno.adapter.Ratio
import ru.inikonoff.zerno.adapter.Settings
import ru.inikonoff.zerno.adapter.Timer
import ru.inikonoff.zerno.adapter.UiState

/** The handle that opens and closes the drawer: a shallow chevron that points up when the
 *  drawer is closed, and flips over when it's open.
 */
@Composable
fun DrawerHandle(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.zerno_more)
    // 1 = pointing up, -1 = pointing down; passing through 0 (flat) gives the flip
    val bend by animateFloatAsState(if (open) -1f else 1f, label = "chevron")
    Box(
        modifier.width(96.dp).height(22.dp).semantics { contentDescription = description }.tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 30.dp, height = 8.dp)) {
            val mid = size.height / 2f
            val rise = (size.height / 2f - 1.dp.toPx()) * bend
            val path = Path().apply {
                moveTo(0f, mid + rise)
                lineTo(size.width / 2f, mid - rise)
                lineTo(size.width, mid + rise)
            }
            drawPath(path, Color(0xE6FFFFFF), style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private enum class DrawerItem { DETAIL, HDR, FRAMES, RATIO, TIMER }

/** The drawer above the mode carousel: the switches that aren't needed all the time. */
@Composable
fun Drawer(state: UiState, adapter: CameraAdapter, modifier: Modifier = Modifier) {
    var chooser by remember { mutableStateOf<DrawerItem?>(null) }
    val showHdr = state.mode == Mode.PHOTO && state.caps.hdrs.size > 1
    val showRatio = state.caps.ratios.isNotEmpty()
    val showFrames = state.mode == Mode.NIGHT
    val open = chooser.takeIf {
        when (it) {
            DrawerItem.HDR -> showHdr
            DrawerItem.FRAMES -> showFrames
            DrawerItem.RATIO -> showRatio
            else -> true
        }
    }

    Box(modifier.fillMaxWidth().height(84.dp), contentAlignment = Alignment.Center) {
        when (open) {
            null -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                // colour noise removal (the camera's own noise reduction, "Detail", is in the settings)
                DrawerButton(stringResource(R.string.zerno_chroma), state.settings.chromaDenoise != 0, { chooser = DrawerItem.DETAIL }) {
                    ZIcon(ZernoIcon.DETAIL, it, Modifier.size(22.dp).upright(state.uiRotation))
                }
                if (showHdr) {
                    DrawerButton(stringResource(R.string.zerno_hdr), state.hdr != Hdr.OFF, { chooser = DrawerItem.HDR }) {
                        if (state.hdr == Hdr.DRO) Label("DRO", Modifier.upright(state.uiRotation), color = it, size = 11.sp, weight = FontWeight.Bold)
                        else ZIcon(ZernoIcon.HDR, it, Modifier.size(22.dp).upright(state.uiRotation))
                    }
                }
                if (showFrames) {
                    val frames = state.settings.nightFrames
                    DrawerButton(stringResource(R.string.zerno_frames), frames != 0, { chooser = DrawerItem.FRAMES }) {
                        Label(if (frames == 0) stringResource(R.string.zerno_auto_short) else "$frames", Modifier.upright(state.uiRotation), color = it, size = 14.sp, weight = FontWeight.Bold)
                    }
                }
                if (showRatio) {
                    DrawerButton(stringResource(R.string.zerno_ratio), false, { chooser = DrawerItem.RATIO }) {
                        Label(state.ratio?.label ?: "–", Modifier.upright(state.uiRotation), color = it, size = 12.sp, weight = FontWeight.Bold)
                    }
                }
                DrawerButton(stringResource(R.string.zerno_timer), state.timer != Timer.OFF, { chooser = DrawerItem.TIMER }) {
                    if (state.timer == Timer.OFF) ZIcon(ZernoIcon.TIMER, it, Modifier.size(22.dp).upright(state.uiRotation))
                    else Label("${state.timer.seconds}", Modifier.upright(state.uiRotation), color = it, size = 14.sp, weight = FontWeight.Bold)
                }
                DrawerButton(stringResource(R.string.zerno_grid), state.grid, { adapter.setGrid(!state.grid) }) {
                    ZIcon(ZernoIcon.GRID, it, Modifier.size(22.dp).upright(state.uiRotation))
                }
            }
            DrawerItem.DETAIL -> ChoiceRow(
                title = stringResource(R.string.zerno_chroma),
                options = listOf(
                    0 to stringResource(R.string.zerno_chroma_off),
                    1 to stringResource(R.string.zerno_chroma_light),
                    2 to stringResource(R.string.zerno_chroma_medium),
                    3 to stringResource(R.string.zerno_chroma_strong),
                ),
                selected = state.settings.chromaDenoise,
                onClose = { chooser = null },
            ) { adapter.setChromaDenoise(it); chooser = null }
            DrawerItem.HDR -> ChoiceRow(
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
            DrawerItem.FRAMES -> ChoiceRow(
                title = stringResource(R.string.zerno_frames),
                options = Settings.NIGHT_FRAMES.map { it to if (it == 0) stringResource(R.string.zerno_frames_auto) else "$it" },
                selected = state.settings.nightFrames,
                onClose = { chooser = null },
            ) { adapter.setNightFrames(it); chooser = null }
            DrawerItem.RATIO -> ChoiceRow(
                title = stringResource(R.string.zerno_ratio),
                options = state.caps.ratios.map<Ratio, Pair<Ratio?, String>> { it to it.label },
                selected = state.ratio,
                onClose = { chooser = null },
            ) { if (it != null) adapter.setRatio(it); chooser = null }
            DrawerItem.TIMER -> ChoiceRow(
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

@Composable
private fun DrawerButton(name: String, active: Boolean, onClick: () -> Unit, icon: @Composable (tint: Color) -> Unit) {
    Column(
        Modifier.width(64.dp).tap(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0x40FFFFFF)), contentAlignment = Alignment.Center) {
            icon(if (active) ZernoColors.Accent else ZernoColors.OnPreview)
        }
        Spacer(Modifier.height(5.dp))
        Label(name, color = ZernoColors.Dim, size = 11.sp)
    }
}
