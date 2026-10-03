package com.pdrajan.dot.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Grid cell for a photo/screenshot. In selection mode the image shrinks and a check circle
 * appears, like Google Photos.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    model: Any?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    aspectRatio: Float = 1f,
    cornerRadius: Dp = 0.dp,
    sizePx: Int = 360,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    /** Already described by the AI: a tiny red dot in the corner. */
    processed: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val scale by animateFloatAsState(if (selected) 0.86f else 1f, label = "select-scale")
    val context = LocalContext.current
    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .background(if (selected) DotTheme.extra.accent.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(if (selected) 12.dp else cornerRadius))
                .background(DotTheme.extra.thumbnailPlaceholder),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(model).size(sizePx).crossfade(true).build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            overlay()
            if (processed && !selectionMode) ProcessedDot(Modifier.align(Alignment.BottomEnd))
        }
        if (selectionMode) {
            SelectionCheck(selected, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
    }
}

@Composable
fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    if (selected) {
        Box(
            modifier.size(24.dp).clip(CircleShape).background(DotTheme.extra.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    } else {
        Box(
            modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.25f))
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

/**
 * Pinch on a grid to change its column count (Google Photos gesture). Single-finger input is
 * left alone so scrolling and taps keep working.
 */
fun Modifier.pinchToChangeColumns(
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    min: Int = 2,
    max: Int = 6,
): Modifier = pointerInput(columns) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        var changed = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            if (pressed >= 2) {
                zoom *= event.calculateZoom()
                if (!changed && zoom > 1.25f && columns > min) {
                    onColumnsChange(columns - 1)
                    changed = true
                } else if (!changed && zoom < 0.8f && columns < max) {
                    onColumnsChange(columns + 1)
                    changed = true
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    }
}

/**
 * Long-press a thumbnail to start selecting, then drag across others to select the whole range
 * (Google Photos gesture); dragging near the top or bottom edge scrolls the grid. The long press is
 * watched before the thumbnails see the touch, so it works whether or not the grid is already
 * selecting, while taps and scrolling behave as usual. [idOf] maps a grid item key to an item id
 * (null for headers).
 */
fun Modifier.dragToSelect(
    state: LazyGridState,
    selection: () -> Set<Long>,
    onChange: (Set<Long>) -> Unit,
    orderedIds: () -> List<Long>,
    idOf: (Any) -> Long? = { it as? Long },
): Modifier = pointerInput(state) {
    val pointer = this
    coroutineScope {
        val scope = this
        pointer.awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            fun idAt(offset: Offset): Long? = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                offset.x.toInt() in info.offset.x until info.offset.x + info.size.width &&
                    offset.y.toInt() in info.offset.y until info.offset.y + info.size.height
            }?.let { idOf(it.key) }

            // A long press means the finger stays put; moving first is a scroll, lifting is a tap.
            val stillDown = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!c.pressed || e.changes.count { it.pressed } > 1) return@withTimeoutOrNull false
                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } ?: true
            if (!stillDown) return@awaitEachGesture
            val anchor = idAt(down.position) ?: return@awaitEachGesture

            val initial = selection()
            onChange(initial + anchor)
            var last = down.position
            fun extendTo(position: Offset) {
                val id = idAt(position) ?: return
                val ids = orderedIds()
                val a = ids.indexOf(anchor)
                val b = ids.indexOf(id)
                if (a < 0 || b < 0) return
                onChange(initial + ids.subList(minOf(a, b), maxOf(a, b) + 1))
            }
            var speed = 0f
            val autoScroll = scope.launch {
                while (isActive) {
                    if (speed != 0f) {
                        state.scrollBy(speed)
                        extendTo(last)
                    }
                    delay(16)
                }
            }
            try {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    // The drag is ours: the thumbnail doesn't get a click and the grid doesn't scroll.
                    e.changes.forEach { it.consume() }
                    if (!c.pressed) break
                    last = c.position
                    val edge = 72.dp.toPx()
                    speed = when {
                        last.y < edge -> -(edge - last.y) / 3f
                        last.y > size.height - edge -> (last.y - (size.height - edge)) / 3f
                        else -> 0f
                    }
                    extendTo(last)
                }
            } finally {
                autoScroll.cancel()
            }
        }
    }
}
