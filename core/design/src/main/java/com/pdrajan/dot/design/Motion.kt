package com.pdrajan.dot.design

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** Material 3 "emphasized" curves: quick to start, long gentle settle (and the reverse to leave). */
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** The screen-to-screen shared transition (set around the navigation host). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The current screen's enter/exit animation (set by each navigation destination). */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
private val ImageBounds = BoundsTransform { _, _ -> tween(380, easing = Emphasized) }

/**
 * The same picture on two screens (a grid thumbnail and the viewer): when one opens the other it
 * grows out of the thumbnail and shrinks back into it, instead of the screens just cross-fading.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedImage(key: Any, enabled: Boolean = true): Modifier {
    val shared = LocalSharedTransitionScope.current
    val screen = LocalNavAnimatedScope.current
    if (!enabled || shared == null || screen == null) return this
    return with(shared) {
        this@sharedImage.sharedElement(
            shared.rememberSharedContentState(key),
            animatedVisibilityScope = screen,
            boundsTransform = ImageBounds,
        )
    }
}
