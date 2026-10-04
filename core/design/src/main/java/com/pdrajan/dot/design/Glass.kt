package com.pdrajan.dot.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Shared by the content (its pixels) and the floating controls drawn over it (blurred). */
@Composable
fun rememberGlass(): HazeState = rememberHazeState()

/** Marks the content that floating glass controls blur. Never put it on an ancestor of the glass itself. */
fun Modifier.glassSource(state: HazeState): Modifier = hazeSource(state)

/**
 * Frosted glass: whatever scrolls behind shows through, blurred and lightly tinted, so floating
 * controls stay readable without a solid band. Full-resolution blur with a fine grain, like frosted
 * glass rather than a smear. Android 12+ blurs; older phones get a soft scrim.
 */
fun Modifier.glass(
    state: HazeState,
    tint: Color,
    shape: Shape = RectangleShape,
    tintAlpha: Float = 0.55f,
    blur: Dp = 28.dp,
    /** Fades the glass out towards an edge (bars over a picture), so it has no hard line. */
    mask: Brush? = null,
): Modifier = clip(shape).hazeBlur(
    input = HazeInput.Sources(state),
    style = HazeBlurStyle {
        blurRadius(blur)
        noiseFactor(0.06f)
        colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = tintAlpha))))
        fallbackColorEffect(HazeColorEffect.tint(tint.copy(alpha = 0.9f)))
        if (mask != null) mask(mask)
    },
    performanceMode = HazePerformanceMode.Quality,
)
