package com.pdrajan.dotscreenshots.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdrajan.dot.design.DotMatrix
import com.pdrajan.dot.design.DotTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Where each day starts in the grid (its label's item index) and when its screenshots were taken. */
data class ScrubMark(val index: Int, val takenAt: Long)

private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

/**
 * Nothing Gallery-style fast scroller: a handle on the right edge appears while the grid scrolls;
 * drag it to fly through the library, with the month shown in dot-matrix type next to your thumb.
 */
@Composable
fun DateScrubber(
    gridState: LazyGridState,
    marks: List<ScrubMark>,
    modifier: Modifier = Modifier,
    /** Space kept free at the bottom (the floating search bar). */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    if (marks.size < 2) return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = gridState.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else { delay(1500); shown = false }
    }
    val total by remember { derivedStateOf { gridState.layoutInfo.totalItemsCount } }
    val scrollFraction by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val visible = info.visibleItemsInfo.size
            val max = (info.totalItemsCount - visible).coerceAtLeast(1)
            (gridState.firstVisibleItemIndex.toFloat() / max).coerceIn(0f, 1f)
        }
    }
    val fraction = if (dragging) dragFraction else scrollFraction
    val target = (fraction * (total - 1).coerceAtLeast(0)).roundToInt()
    val label = remember(target, marks) {
        val mark = marks.lastOrNull { it.index <= target } ?: marks.first()
        MONTH.format(Instant.ofEpochMilli(mark.takenAt).atZone(ZoneId.systemDefault())).uppercase()
    }
    LaunchedEffect(label) { if (dragging) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    BoxWithConstraints(modifier.fillMaxHeight().width(160.dp).padding(top = 72.dp, bottom = bottomInset)) {
        val density = LocalDensity.current
        val handleHeight = 48.dp
        val trackPx = with(density) { (maxHeight - handleHeight).toPx() }.coerceAtLeast(1f)
        val y = (fraction * trackPx).roundToInt()
        // The drag handler outlives recompositions: read the latest values through these.
        val currentTrack by rememberUpdatedState(trackPx)
        val currentTotal by rememberUpdatedState(total)
        val currentScroll by rememberUpdatedState(scrollFraction)
        AnimatedVisibility(shown || dragging, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopEnd)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset { IntOffset(0, y) },
            ) {
                if (dragging) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(label, fontFamily = DotMatrix, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // The handle: a dark pill with the red dot, Nothing-style.
                Box(
                    Modifier.size(width = 28.dp, height = handleHeight)
                        .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .pointerInput(marks) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true; dragFraction = currentScroll },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onVerticalDrag = { change, delta ->
                                    change.consume()
                                    dragFraction = (dragFraction + delta / currentTrack).coerceIn(0f, 1f)
                                    val index = (dragFraction * (currentTotal - 1).coerceAtLeast(0)).roundToInt()
                                    scope.launch { gridState.scrollToItem(index) }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (dragging) DotTheme.extra.accent else MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }
        }
    }
}
