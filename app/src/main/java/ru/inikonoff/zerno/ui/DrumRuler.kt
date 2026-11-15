package ru.inikonoff.zerno.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A horizontal "drum" ruler: the value under the fixed centre mark is the selected one. Scroll
 *  it (it snaps to values), or tap a value to bring it to the centre.
 */
@Composable
fun DrumRuler(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Draw tick marks instead of labels (like the zoom dial). An empty label is a tick; any
     *  other label (e.g. "A" for automatic) is drawn as text. Ticks are packed closer.
     */
    ticks: Boolean = false,
    /** In ticks mode: how many items before the first tick are not ticks (e.g. the "A"). */
    tickOffset: Int = 0,
    /** In ticks mode: icons shown above the first and the last tick. */
    startIcon: ZernoIcon? = null,
    endIcon: ZernoIcon? = null,
) {
    val itemWidth = if (ticks) 7.dp else 60.dp
    BoxWithConstraints(modifier.fillMaxWidth().height(if (ticks) 64.dp else 52.dp)) {
        val itemPx = with(LocalDensity.current) { itemWidth.toPx() }
        val sidePadding = (maxWidth - itemWidth) / 2
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0)))
        val scope = rememberCoroutineScope()
        val currentOnSelect by rememberUpdatedState(onSelect)
        val currentSelected by rememberUpdatedState(selectedIndex)
        val count by rememberUpdatedState(labels.size)

        val centred by remember {
            derivedStateOf {
                val index = listState.firstVisibleItemIndex + if (listState.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0
                index.coerceIn(0, (count - 1).coerceAtLeast(0))
            }
        }

        // the user turned the drum
        LaunchedEffect(listState) {
            snapshotFlow { centred to listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { (index, scrolling) ->
                    if (scrolling && index != currentSelected) currentOnSelect(index)
                }
        }
        // the value changed from outside (e.g. taken over from the auto exposure)
        LaunchedEffect(selectedIndex, labels.size) {
            if (!listState.isScrollInProgress && centred != selectedIndex && selectedIndex in labels.indices) {
                listState.scrollToItem(selectedIndex)
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = sidePadding),
            flingBehavior = rememberSnapFlingBehavior(listState),
        ) {
            items(labels.size) { i ->
                val isCentre = i == centred
                Box(
                    Modifier.width(itemWidth).fillMaxHeight().tap {
                        currentOnSelect(i)
                        scope.launch { listState.animateScrollToItem(i) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (ticks && labels[i].isEmpty()) {
                        // every fifth tick is longer, every tenth longer still; the first and the last
                        // ones (the ends of the scale) are always long
                        val n = i - tickOffset
                        val length = when {
                            n % 10 == 0 || i == labels.lastIndex -> 22.dp
                            n % 5 == 0 -> 16.dp
                            else -> 10.dp
                        }
                        val icon = when (i) {
                            tickOffset -> startIcon
                            labels.lastIndex -> endIcon
                            else -> null
                        }
                        if (icon != null) {
                            ZIcon(
                                icon, if (isCentre) ZernoColors.Accent else ZernoColors.OnPreview,
                                Modifier.align(Alignment.TopCenter).padding(top = 2.dp).requiredSize(20.dp), // wider than its narrow cell
                            )
                        }
                        Canvas(Modifier.fillMaxSize()) {
                            val x = size.width / 2f
                            val bottom = size.height - 14.dp.toPx()
                            drawLine(
                                if (isCentre) ZernoColors.Accent else ZernoColors.OnPreview.copy(alpha = 0.75f),
                                Offset(x, bottom), Offset(x, bottom - length.toPx()),
                                strokeWidth = (if (isCentre) 1.8.dp else 1.dp).toPx(), cap = StrokeCap.Round,
                            )
                        }
                    } else {
                        Label(
                            labels[i],
                            modifier = Modifier.padding(bottom = 10.dp),
                            color = if (isCentre) ZernoColors.Accent else ZernoColors.Dim,
                            size = if (isCentre) 15.sp else 13.sp,
                            weight = if (isCentre) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
        // the fixed centre mark
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width / 2f
            drawLine(
                ZernoColors.Accent, Offset(x, size.height - 12.dp.toPx()), Offset(x, size.height - 2.dp.toPx()),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}
