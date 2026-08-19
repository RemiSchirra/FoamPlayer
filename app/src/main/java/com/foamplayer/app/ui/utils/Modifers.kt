package com.foamplayer.app.ui.utils

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import kotlinx.coroutines.delay


fun Modifier.pingPongMarquee(
    speedPxPerSecond: Float = 75f,
    pauseDuration: Long = 1500L
): Modifier = composed {
    val scrollState = rememberScrollState()

    LaunchedEffect(scrollState.maxValue) {
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
    this.horizontalScroll(scrollState, enabled = false)
}