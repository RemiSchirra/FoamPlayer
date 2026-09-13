package com.foamplayer.app.ui.utils

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * Ping-pong marquee: scrolls the content to its end, pauses, scrolls back, pauses,
 * forever. `rememberScrollState` and the drive [LaunchedEffect] live in composition
 * (this is a @Composable modifier builder, not `composed {}`), so recompositions of
 * the caller reuse the same state instead of tearing down and re-creating a
 * ComposedModifier node on every pass.
 */
@Composable
fun Modifier.pingPongMarquee(
    speedPxPerSecond: Float = 75f,
    pauseDuration: Long = 1500L
): Modifier {
    val scrollState = rememberScrollState()

    LaunchedEffect(scrollState.maxValue, speedPxPerSecond, pauseDuration) {
        if (scrollState.maxValue > 0) {
            val scrollDurationMillis = ((scrollState.maxValue / speedPxPerSecond) * 1000).toInt()
            delay(pauseDuration)
            while (true) {
                scrollState.animateScrollTo(
                    value = scrollState.maxValue,
                    animationSpec = tween(durationMillis = scrollDurationMillis, easing = EaseInOut)
                )
                delay(pauseDuration)
                scrollState.animateScrollTo(
                    value = 0,
                    animationSpec = tween(durationMillis = scrollDurationMillis, easing = EaseInOut)
                )
                delay(pauseDuration)
            }
        }
    }
    return this.horizontalScroll(scrollState, enabled = false)
}
