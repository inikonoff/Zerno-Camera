package ru.inikonoff.zerno.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.CameraAdapter
import ru.inikonoff.zerno.adapter.GreyCardState

/** The grey-card white balance measurement: a target in the middle of the preview, and a
 *  button to measure what is in it.
 *  @param middle the vertical centre of the preview, in pixels (or -1 if not known).
 */
@Composable
fun GreyCardOverlay(card: GreyCardState, adapter: CameraAdapter, middle: Int) {
    BackHandler { adapter.cancelGreyCard() }
    val side = CameraAdapter.GREY_CARD_DP.dp
    Box(Modifier.fillMaxSize()) {
        Column(
            (if (middle >= 0) Modifier.align(Alignment.TopCenter).offset { IntOffset(0, middle - (side / 2).roundToPx() - 44.dp.roundToPx()) }
            else Modifier.align(Alignment.Center)).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Label(
                stringResource(if (card.measuring) R.string.zerno_grey_measuring else R.string.zerno_grey_aim),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 6.dp),
                size = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.size(side)) {
                val l = size.width * 0.28f
                val w = 2.5.dp.toPx()
                val c = if (card.measuring) ZernoColors.Accent else Color.White
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(c, Offset(x, y), Offset(x + dx * l, y), w, StrokeCap.Round)
                    drawLine(c, Offset(x, y), Offset(x, y + dy * l), w, StrokeCap.Round)
                }
                corner(0f, 0f, 1f, 1f); corner(size.width, 0f, -1f, 1f)
                corner(0f, size.height, 1f, -1f); corner(size.width, size.height, -1f, -1f)
            }
            Spacer(Modifier.height(14.dp))
            card.message?.let {
                Label(
                    stringResource(it), color = ZernoColors.Accent, size = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            if (!card.measuring) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(stringResource(R.string.zerno_cancel), primary = false, onClick = adapter::cancelGreyCard)
                    Button(stringResource(R.string.zerno_grey_measure), primary = true, onClick = adapter::measureGreyCard)
                }
            }
        }
    }
}

@Composable
private fun Button(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) ZernoColors.Accent else Color(0x99000000))
            .tap(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Label(text, color = if (primary) Color.Black else Color.White, size = 14.sp, weight = FontWeight.SemiBold)
    }
}
