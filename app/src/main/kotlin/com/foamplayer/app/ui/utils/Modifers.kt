package com.foamplayer.app.ui.utils

import androidx.compose.animation.core.Animatable
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
import kotlinx.coroutines.launch

private const val PressShrinkTargetScale = 0.8f

/**
 * Swallows tap gestures without reacting to them, so taps that miss the
 * interactive elements of an overlay surface cannot fall through to the content
 * scrolling behind it. Place it under the interactive content: whatever gets
 * the press first keeps working, and this only catches presses nothing else
 * claimed.
 */
fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/**
 * Press feedback for control buttons: springs down to [pressedScale] on press
 * and back to 1f on release/cancel with a slightly elastic return. Every
 * interaction launches its own animation and a newer event cancels the
 * in-flight one (a single [Animatable] preserves velocity across the
 * retarget), so rapid taps replace each other instead of queueing -- the
 * scale always settles at 1f shortly after the last release, no matter how
 * many presses piled up. On release the compress is finished before the
 * return starts, so a tap that ends within a single frame still plays the
 * full down-up pop instead of nothing. Pair with the same [interactionSource]
 * on `clickable(interactionSource = ..., indication = null)` so no ripple
 * draws on top of the scale. The animated value is only read inside the
 * graphicsLayer block, so press frames are draw-only.
 */
@Composable
fun Modifier.pressShrink(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = PressShrinkTargetScale,
): Modifier {
    val scale = remember { Animatable(1f) }

    LaunchedEffect(interactionSource) {
        // Child jobs of this effect's own scope: cancelled when the effect
        // leaves composition, and each new launch cancels the previous
        // in-flight animation via the Animatable's mutation mutex.
        val scope = this
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> scope.launch {
                    scale.animateTo(pressedScale, FoamMotion.Springs.PRESS_DOWN)
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> scope.launch {
                    // Fast taps deliver Press + Release before one frame can render
                    // the pressed state, so finish the compress here first: the
                    // down-up pop always plays in full, whatever the tap speed.
                    scale.animateTo(pressedScale, FoamMotion.Springs.PRESS_DOWN)
                    scale.animateTo(1f, FoamMotion.Springs.PRESS_RETURN)
                }
            }
        }
    }

    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
