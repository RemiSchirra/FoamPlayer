package com.foamplayer.app.ui.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.foamplayer.app.ui.theme.FoamMotion

private const val PressShrinkTargetScale = 0.9f

/**
 * Swallows tap gestures without reacting to them, so taps that miss the
 * interactive elements of an overlay surface cannot fall through to the content
 * scrolling behind it. Place it under the interactive content: whatever gets
 * the press first keeps working, and this only catches presses nothing else
 * claimed.
 */
fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

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
                    animationSpec = tween(durationMillis = FoamMotion.Duration.SNAP, easing = EaseOutCubic)
                )
                is PressInteraction.Release, is PressInteraction.Cancel -> scale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = FoamMotion.Duration.SWIFT, easing = EaseOutCubic)
                )
            }
        }
    }

    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
