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

private const val PressShrinkTargetScale = 0.9f
private const val PressShrinkInMs = 110
private const val PressShrinkOutMs = 200

/**
 * Press feedback for control buttons: tweens down to the target scale on
 * press and back to 1f on release/cancel. Pair with the same
 * [interactionSource] on `clickable(interactionSource = ..., indication = null)`
 * so no ripple draws on top of the scale. The animated value is only read
 * inside the graphicsLayer block, so press frames are draw-only.
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
