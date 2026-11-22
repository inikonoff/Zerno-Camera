package ru.inikonoff.zerno.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.Mode

/** The panel currently open above the mode carousel, if any. */
private sealed interface Panel {
    data class Pro(val param: ProParam) : Panel
    data object Exposure : Panel
    data object Focus : Panel
    data object Drawer : Panel
}

/** The whole camera screen, drawn over the engine's live preview. Anything not covered by a
 *  control lets touches through to the preview (tap to focus and meter, pinch to zoom).
 */
@Composable
fun ZernoScreen(adapter: CameraAdapter) {
    val state by adapter.state.collectAsStateWithLifecycle()
    if (state.settingsOpen) return
    var panel by remember { mutableStateOf<Panel?>(null) }
    var controlsTop by remember { mutableIntStateOf(0) }
    var sheetOpen by remember { mutableStateOf(false) }
    var macroParam by remember { mutableStateOf<MacroParam?>(null) }

    Box(Modifier.fillMaxSize()) {
        // taps and pinches on the preview: handled here rather than left to the camera view
        // underneath, so that they work whatever is drawn over the preview
        var rootOffset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { rootOffset = it.positionInWindow() }
                .pointerInput(Unit) {
                    detectTapGestures { position -> adapter.tapPreview(rootOffset.x + position.x, rootOffset.y + position.y) }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) adapter.pinchZoom(zoom) }
                }
        )
        // top bar
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0x80000000), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        ) {
            TopBar(state, adapter, Modifier.fillMaxWidth().height(CameraAdapter.TOP_BAR_DP.dp))
        }

        // "hold still" while a burst is being captured, in the middle of the preview
        val middle = if (state.previewBottom > state.previewTop) (state.previewTop + state.previewBottom) / 2 else -1
        AnimatedVisibility(
            visible = state.holdStill,
            enter = fadeIn(), exit = fadeOut(),
            modifier = if (middle >= 0) Modifier.align(Alignment.TopCenter).offset { IntOffset(0, middle - 18.dp.roundToPx()) }
            else Modifier.align(Alignment.Center),
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Label(stringResource(R.string.zerno_hold_still), size = 14.sp)
            }
        }

        // the first time a focus point is chosen by touch: how to get back to the centre
        AnimatedVisibility(
            visible = state.focusHint && state.greyCard == null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).offset {
                IntOffset(0, (if (state.previewTop > 0) state.previewTop else 0) + 14.dp.roundToPx())
            },
        ) {
            Label(
                stringResource(R.string.zerno_focus_hint),
                modifier = Modifier.upright(state.uiRotation).clip(RoundedCornerShape(50)).background(Color(0x80000000))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                size = 13.sp,
            )
        }

        // self-timer countdown, in the middle of the preview, upright however the phone is held
        if (state.onTimer && state.timerSeconds > 0) {
            Label(
                state.timerSeconds.toString(),
                modifier = (if (middle >= 0) Modifier.align(Alignment.TopCenter).offset { IntOffset(0, middle - 60.dp.roundToPx()) }
                else Modifier.align(Alignment.Center)).upright(state.uiRotation),
                size = 96.sp,
                weight = FontWeight.Light,
            )
        }

        // bottom controls
        val effectivePanel = panel.takeIf {
            (it !is Panel.Pro || state.mode == Mode.PRO) && (it != Panel.Focus || state.mode != Mode.MACRO)
        }
        // in Macro the preview follows the end of the series being set
        LaunchedEffect(macroParam, state.mode) {
            if (state.mode == Mode.MACRO) adapter.macroPreview(far = macroParam == MacroParam.FAR)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned { controlsTop = it.positionInRoot().y.roundToInt() }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DrawerHandle(open = effectivePanel == Panel.Drawer, onClick = { panel = if (effectivePanel == Panel.Drawer) null else Panel.Drawer })
            // the panels open upwards, over the bottom of the preview
            Column(Modifier.fillMaxWidth().background(Color(0x66000000))) {
                if (state.mode == Mode.PRO) {
                    ProPanel(
                        state, adapter,
                        selected = (effectivePanel as? Panel.Pro)?.param,
                        onSelect = { panel = it?.let(Panel::Pro) },
                    )
                }
                if (state.mode == Mode.MACRO) {
                    MacroPanel(state, adapter, selected = macroParam, onSelect = { macroParam = it })
                }
                when (effectivePanel) {
                    Panel.Exposure -> ExposurePanel(state, adapter)
                    Panel.Focus -> FocusPanel(state, adapter)
                    Panel.Drawer -> Drawer(state, adapter)
                    else -> {}
                }
            }
            ModeCarousel(state.caps.modes, state.mode, adapter::setMode)
            Spacer(Modifier.height(4.dp))
            ShutterRow(
                state, adapter,
                exposureOpen = effectivePanel == Panel.Exposure || effectivePanel == Panel.Pro(ProParam.EV),
                focusOpen = effectivePanel == Panel.Focus,
                onExposure = {
                    // in Pro, exposure compensation lives in the Pro row
                    val target = if (state.mode == Mode.PRO) Panel.Pro(ProParam.EV) else Panel.Exposure
                    panel = if (effectivePanel == target) null else target
                },
                onExposureReset = { adapter.setEv(0) },
                onFocus = { panel = if (effectivePanel == Panel.Focus) null else Panel.Focus },
            )
            Spacer(Modifier.height(14.dp))
        }

        // zoom sits on the preview, just above its bottom edge (or above the controls, if
        // those reach higher)
        if (state.caps.hasZoom && state.caps.zoomMax > state.caps.zoomMin && controlsTop > 0) {
            val anchor = if (state.previewBottom > 0) minOf(state.previewBottom, controlsTop) else controlsTop
            ZoomControl(
                zoom = state.zoom.coerceIn(state.caps.zoomMin, state.caps.zoomMax),
                min = state.caps.zoomMin,
                max = state.caps.zoomMax,
                uiRotation = state.uiRotation,
                onZoom = adapter::setZoom,
                modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, anchor - 62.dp.roundToPx()) },
            )
        }

        // a QR code in view
        val qr = state.qr
        if (qr != null && !sheetOpen) {
            val top = if (state.previewTop > 0) state.previewTop else 0
            QrChip(
                qr,
                onClick = { sheetOpen = true; adapter.setQrPinned(true) },
                modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, top + 14.dp.roundToPx()) }
                    .then(if (state.previewTop > 0) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(top = CameraAdapter.TOP_BAR_DP.dp)),
            )
        }
        if (qr != null && sheetOpen) {
            QrSheet(qr) { sheetOpen = false; adapter.setQrPinned(false) }
        }

        state.greyCard?.let { GreyCardOverlay(it, adapter, middle) }

        if (state.zernoSettingsOpen) SettingsScreen(state, adapter)

        state.diagnostics?.let {
            ReportSheet(stringResource(R.string.zerno_diagnostics), stringResource(R.string.zerno_diagnostics_text), it) { adapter.showDiagnostics(false) }
        }

        state.crashReport?.let { CrashSheet(it, onClose = adapter::dismissCrashReport) }
    }
}
