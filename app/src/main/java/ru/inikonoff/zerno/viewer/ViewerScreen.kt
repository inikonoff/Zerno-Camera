package ru.inikonoff.zerno.viewer

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.ui.ZIcon
import ru.inikonoff.zerno.ui.ZernoColors
import ru.inikonoff.zerno.ui.ZernoIcon
import ru.inikonoff.zerno.ui.tap
import kotlin.math.abs

private val Panel = Color(0xF21C1C1E)
private val White = TextStyle(color = Color.White, fontSize = 15.sp)
private val Dim = TextStyle(color = Color(0x99FFFFFF), fontSize = 13.sp, lineHeight = 18.sp)

/** The viewer: one photo at a time. Swipe sideways to move between photos, up or down to send
 *  one to the trash, pinch or double-tap to zoom; drag along the left edge for screen
 *  brightness. Tapping shows and hides the bars.
 */
@Composable
fun ViewerScreen(
    allItems: List<MediaItem>,
    loaded: Boolean,
    trashMode: Boolean,
    hasTrash: Boolean,
    repository: MediaRepository,
    canUndo: Boolean,
    brightness: Float,
    onBrightness: (Float) -> Unit,
    onBack: () -> Unit,
    onRemove: (MediaItem) -> Unit,
    onOpenTrash: () -> Unit,
    onRestore: (MediaItem) -> Unit,
    onDeleteForever: (MediaItem) -> Unit,
    onEmptyTrash: () -> Unit,
    onUndo: () -> Unit,
    onUndoExpired: () -> Unit,
) {
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    /** Series shown frame by frame (by the id of their first frame); the others are one photo. */
    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    val items = remember(allItems, expanded) {
        allItems.flatMap { if (it.series.isNotEmpty() && it.id in expanded) listOf(it.copy(series = emptyList())) + it.series else listOf(it) }
    }
    /** What a confirmation dialog is asking about, if one is showing. */
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val pagerState = rememberPagerState(pageCount = { items.size })
    val current = items.getOrNull(pagerState.currentPage)

    LaunchedEffect(canUndo) {
        if (canUndo) {
            delay(5000)
            onUndoExpired()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isEmpty()) {
            if (loaded) {
                BasicText(
                    stringResource(if (trashMode) R.string.zerno_viewer_trash_empty else R.string.zerno_viewer_empty),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    style = Dim.copy(fontSize = 16.sp, textAlign = TextAlign.Center),
                )
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { items.getOrNull(it)?.id ?: it },
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                pageSpacing = 16.dp,
            ) { page ->
                val item = items.getOrNull(page)
                if (item != null) {
                    PhotoPage(
                        item = item,
                        repository = repository,
                        isCurrent = page == pagerState.currentPage,
                        swipeToRemove = !trashMode,
                        onZoomed = { zoomed = it },
                        onTap = { if (info) info = false else chrome = !chrome },
                        onRemove = { if (hasTrash) onRemove(item) else confirm = Confirm.Delete(item) },
                    )
                }
            }
        }

        BrightnessStrip(brightness, onBrightness, Modifier.align(Alignment.CenterStart))

        // top bar: back, the position "3 / 22" exactly in the middle, and "i" - nothing else
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .height(48.dp),
            ) {
                BarButton(ZernoIcon.BACK, stringResource(R.string.zerno_viewer_back), onBack, Modifier.align(Alignment.CenterStart))
                if (current != null) {
                    BasicText(
                        "${pagerState.currentPage + 1} / ${items.size}",
                        modifier = Modifier.align(Alignment.Center),
                        style = White.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                    )
                    Row(Modifier.align(Alignment.CenterEnd)) {
                        if (!trashMode && hasTrash) {
                            BarButton(ZernoIcon.TRASH, stringResource(R.string.zerno_trash), onOpenTrash)
                        }
                        BarButton(
                            ZernoIcon.INFO, stringResource(R.string.zerno_viewer_info), if (info) ZernoColors.Accent else Color.White,
                        ) { info = !info }
                    }
                }
            }
        }

        // bottom: info panel, trash actions, undo
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (canUndo && !trashMode) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Panel).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(stringResource(R.string.zerno_viewer_trashed), style = White)
                    Spacer(Modifier.width(10.dp))
                    TextButton(stringResource(R.string.zerno_viewer_undo), primary = true, onClick = onUndo)
                }
            }
            if (info && current != null) {
                InfoPanel(current, repository)
            }
            if (current != null && current.series.isNotEmpty() && chrome) {
                TextButton(
                    stringResource(R.string.zerno_series_show, current.series.size + 1), primary = true,
                ) { expanded = expanded + current.id }
            }
            if (trashMode && current != null && chrome) {
                // three equal buttons across the width, so that they always fit
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(stringResource(R.string.zerno_trash_restore), primary = true, Modifier.weight(1f)) { onRestore(current) }
                    TextButton(stringResource(R.string.zerno_trash_delete_short), primary = false, Modifier.weight(1f)) { confirm = Confirm.Delete(current) }
                    TextButton(stringResource(R.string.zerno_trash_empty), primary = false, Modifier.weight(1f)) { confirm = Confirm.EmptyTrash }
                }
            }
        }

        when (val c = confirm) {
            null -> {}
            is Confirm.Delete -> ConfirmDialog(
                stringResource(R.string.zerno_confirm_delete), stringResource(R.string.zerno_trash_delete_short),
                onConfirm = { confirm = null; onDeleteForever(c.item) }, onDismiss = { confirm = null },
            )
            Confirm.EmptyTrash -> ConfirmDialog(
                stringResource(R.string.zerno_confirm_empty, items.size), stringResource(R.string.zerno_trash_empty),
                onConfirm = { confirm = null; onEmptyTrash() }, onDismiss = { confirm = null },
            )
        }
    }
}

private sealed interface Confirm {
    data class Delete(val item: MediaItem) : Confirm
    data object EmptyTrash : Confirm
}

/** One photo, with zoom and pan, and the swipe up/down that removes it. */
@Composable
private fun PhotoPage(
    item: MediaItem,
    repository: MediaRepository,
    isCurrent: Boolean,
    swipeToRemove: Boolean,
    onZoomed: (Boolean) -> Unit,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenSide = with(density) { maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx() }
    // a screen-sized version first; the full resolution only once zoomed in
    val fitted by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { repository.loadBitmap(item, screenSide) }
    }
    var full by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    val swipe = remember(item.id) { Animatable(0f) } // vertical drag towards removal
    var box by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val currentOnRemove by rememberUpdatedState(onRemove)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnZoomed by rememberUpdatedState(onZoomed)

    val bitmap = full ?: fitted

    // how the photo sits in the box at scale 1, and how far it may be zoomed
    val shown = bitmap?.let {
        val fit = minOf(box.width / it.width.toFloat(), box.height / it.height.toFloat())
        Offset(it.width * fit, it.height * fit)
    } ?: Offset.Zero
    val originalLongSide = maxOf(item.width, item.height).takeIf { it > 0 } ?: bitmap?.let { maxOf(it.width, it.height) } ?: 0
    // the scale at which one photo pixel is one screen pixel
    val actualSize = if (shown.x > 0f && originalLongSide > 0) (originalLongSide / maxOf(shown.x, shown.y)).coerceAtLeast(2f) else 2f
    val maxScale = actualSize * 2f

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = ((shown.x * s - box.width) / 2f).coerceAtLeast(0f)
        val maxY = ((shown.y * s - box.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
            full = null
            swipe.snapTo(0f)
        }
    }
    LaunchedEffect(scale > 1.01f, isCurrent) {
        if (isCurrent) currentOnZoomed(scale > 1.01f)
    }
    LaunchedEffect(scale > 1.4f, isCurrent, fitted) {
        val small = fitted
        if (isCurrent && scale > 1.4f && full == null && small != null && maxOf(small.width, small.height) < originalLongSide) {
            full = withContext(Dispatchers.IO) { repository.loadBitmap(item, Int.MAX_VALUE) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(item.id, swipeToRemove) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zooming = false
                    var removing = false
                    var totalX = 0f
                    var totalY = 0f
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // pinch: zoom about the point between the fingers
                            zooming = true
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, maxScale)
                            val q = event.calculateCentroid(useCurrent = true) - centre
                            offset = clamp(q - (q - offset) * (newScale / scale) + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && !zooming) {
                            val change = pressed[0]
                            val delta = change.positionChange()
                            if (scale > 1.01f) {
                                // zoomed in: move around the photo
                                offset = clamp(offset + delta, scale)
                                change.consume()
                            } else if (swipeToRemove) {
                                totalX += delta.x
                                totalY += delta.y
                                if (!removing && abs(totalY) > touchSlop && abs(totalY) > abs(totalX) * 1.5f) removing = true
                                if (removing) {
                                    scope.launch { swipe.snapTo(swipe.value + delta.y) }
                                    change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                    if (removing) {
                        val distance = swipe.value
                        scope.launch {
                            if (abs(distance) > size.height * 0.2f) {
                                swipe.animateTo(if (distance > 0) size.height.toFloat() else -size.height.toFloat(), tween(140))
                                currentOnRemove()
                                swipe.snapTo(0f) // in case the removal was cancelled and the photo comes back
                            } else {
                                swipe.animateTo(0f, tween(160))
                            }
                        }
                    }
                }
            }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { position ->
                        // glide to actual size (keeping the tapped point where it is), or back out
                        val startScale = scale
                        val startOffset = offset
                        val endScale = if (startScale > 1.01f) 1f else actualSize
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val endOffset = if (endScale == 1f) Offset.Zero else clamp((position - centre) * (1f - endScale), endScale)
                        scope.launch {
                            Animatable(0f).animateTo(1f, tween(260, easing = FastOutSlowInEasing)) {
                                scale = startScale + (endScale - startScale) * value
                                offset = startOffset + (endOffset - startOffset) * value
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y + swipe.value
                    alpha = 1f - (abs(swipe.value) / size.height.coerceAtLeast(1f) * 2f).coerceIn(0f, 0.75f)
                },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** The left edge of the screen: drag up and down to change the brightness of this screen. */
@Composable
private fun BrightnessStrip(brightness: Float, onBrightness: (Float) -> Unit, modifier: Modifier = Modifier) {
    var touched by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(brightness)
    val currentOnBrightness by rememberUpdatedState(onBrightness)
    LaunchedEffect(touched) {
        if (touched > 0) {
            visible = true
            delay(1200)
            visible = false
        }
    }
    Box(
        modifier
            .fillMaxHeight()
            .width(80.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { touched++ }) { change, dragAmount ->
                    change.consume()
                    touched++
                    // a little over half the height of the screen covers the whole range
                    currentOnBrightness((current - dragAmount / size.height * 1.7f).coerceIn(0.01f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 18.dp)) {
                ZIcon(ZernoIcon.EXPOSURE, Color.White, Modifier.size(18.dp))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(12.dp).height(160.dp).clip(RoundedCornerShape(50)).background(Color(0x66FFFFFF)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(brightness.coerceIn(0f, 1f)).background(ZernoColors.Accent))
                }
            }
        }
    }
}

@Composable
private fun InfoPanel(item: MediaItem, repository: MediaRepository) {
    val lines by produceState<List<InfoLine>>(emptyList(), item.id) {
        value = withContext(Dispatchers.IO) { repository.info(item) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .tap {} // taps on the panel don't toggle the bars
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        for (line in lines) {
            Row {
                BasicText(stringResource(line.label), modifier = Modifier.width(118.dp), style = Dim)
                BasicText(line.value, modifier = Modifier.weight(1f), style = White.copy(fontSize = 14.sp))
            }
        }
    }
}

@Composable
private fun BarButton(icon: ZernoIcon, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    BarButton(icon, description, Color.White, modifier, onClick)

@Composable
private fun BarButton(icon: ZernoIcon, description: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(48.dp).semantics { contentDescription = description }.tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZIcon(icon, tint, Modifier.size(24.dp))
    }
}

@Composable
private fun TextButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) ZernoColors.Accent else Color(0x33FFFFFF))
            .tap(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, maxLines = 1, style = TextStyle(color = if (primary) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun ConfirmDialog(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).tap(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(28.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1C1C1E)).tap {}.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BasicText(text, style = White.copy(fontSize = 16.sp, lineHeight = 22.sp))
            // two buttons of the same width
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(stringResource(R.string.zerno_cancel), primary = false, Modifier.weight(1f), onClick = onDismiss)
                TextButton(confirmLabel, primary = true, Modifier.weight(1f), onClick = onConfirm)
            }
        }
    }
}
