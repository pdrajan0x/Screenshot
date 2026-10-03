package com.pdrajan.dot.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

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
