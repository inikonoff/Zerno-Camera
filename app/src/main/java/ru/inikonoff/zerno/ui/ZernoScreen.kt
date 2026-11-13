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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.Mode

/** The whole camera screen, drawn over the engine's live preview. Anything not covered by a
 *  control lets touches through to the preview (tap to focus and meter, pinch to zoom).
 */
@Composable
fun ZernoScreen(adapter: CameraAdapter) {
    val state by adapter.state.collectAsStateWithLifecycle()
    if (state.settingsOpen) return

    Box(Modifier.fillMaxSize()) {
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

        // bottom controls
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color(0x73000000), 1f to Color(0x99000000)))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            if (state.mode == Mode.PRO) {
                ProPanel(state, adapter)
            }
            if (state.caps.hasZoom && state.caps.zoomMax > state.caps.zoomMin) {
                ZoomControl(
                    zoom = state.zoom.coerceIn(state.caps.zoomMin, state.caps.zoomMax),
                    min = state.caps.zoomMin,
                    max = state.caps.zoomMax,
                    uiRotation = state.uiRotation,
                    onZoom = adapter::setZoom,
                )
            }
            ModeCarousel(state.caps.modes, state.mode, adapter::setMode)
            Spacer(Modifier.height(6.dp))
            ShutterRow(state, adapter)
            Spacer(Modifier.height(22.dp))
        }
    }
}
