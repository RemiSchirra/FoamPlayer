package com.foamplayer.app.ui.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Scale a control shrinks to while pressed. */
private const val PressShrinkTargetScale = 0.9f
private const val PressShrinkInMs = 110
private const val PressShrinkOutMs = 200

/**
 * Press feedback for control buttons: tweens down to [PressShrinkTargetScale] when the
 * pointer goes down and back to 1f on release/cancel. A quick tap plays the full
 * shrink-then-return cycle (release is handled after the shrink tween completes), a
 * hold stays shrunk until the finger lifts. Pass the same [interactionSource] to the
 * paired `clickable(interactionSource = ..., indication = null)` so the ripple overlay
 * doesn't draw on top of the scale. The animated value is only read inside the
 * graphicsLayer block, so press frames redraw without recomposing the button.
 */
@Composable
fun Modifier.pressShrink(interactionSource: MutableInteractionSource): Modifier {
    val scale = remember { Animatable(1f) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> scale.animateTo(
                    targetValue = PressShrinkTargetScale,
                    animationSpec = tween(durationMillis = PressShrinkInMs, easing = EaseOutCubic)
                )
                is PressInteraction.Release, is PressInteraction.Cancel -> scale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = PressShrinkOutMs, easing = EaseOutCubic)
                )
            }
        }
    }

    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
