package com.pdrajan.dot.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
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
 * controls stay readable without a solid band. Android 12+ blurs; older phones get a soft scrim.
 */
fun Modifier.glass(
    state: HazeState,
    tint: Color,
    shape: Shape = RectangleShape,
    tintAlpha: Float = 0.55f,
    blur: Dp = 24.dp,
): Modifier = clip(shape).hazeBlur(
    input = HazeInput.Sources(state),
    style = HazeBlurStyle {
        blurRadius(blur)
        colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = tintAlpha))))
        fallbackColorEffect(HazeColorEffect.tint(tint.copy(alpha = 0.9f)))
    },
)
