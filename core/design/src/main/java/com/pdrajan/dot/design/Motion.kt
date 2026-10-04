package com.pdrajan.dot.design

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

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
 *
 * Each side keeps its own layout and is only scaled while it flies (nothing is measured again
 * each frame, so it stays smooth even for the zoomable viewer), and the two pictures swap with
 * a quick fade, which hides the thumbnail's crop.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedImage(key: Any, enabled: Boolean = true): Modifier {
    val shared = LocalSharedTransitionScope.current
    val screen = LocalNavAnimatedScope.current
    if (!enabled || shared == null || screen == null) return this
    return with(shared) {
        this@sharedImage.sharedBounds(
            shared.rememberSharedContentState(key),
            animatedVisibilityScope = screen,
            // The arriving picture is opaque before the leaving one fades: no see-through dip.
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120, delayMillis = 120)),
            boundsTransform = ImageBounds,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.Center),
        )
    }
}

/**
 * True once the current screen has finished arriving (and while it isn't leaving). Heavy parts
 * of a screen can wait for it, so the opening animation has the phone to itself.
 */
@Composable
fun screenSettled(): Boolean {
    val transition = LocalNavAnimatedScope.current?.transition ?: return true
    return transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
}
