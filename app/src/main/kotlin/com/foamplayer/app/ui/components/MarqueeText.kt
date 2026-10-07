package com.foamplayer.app.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.data.settings.MarqueeMode
import kotlinx.coroutines.delay

/** Blank space the text travels through between two repeats in [MarqueeMode.INFINITE]. */
private val MarqueeLoopGap: Dp = 48.dp

/**
 * Single-line text with the scrolling behaviour selected by [mode].
 *
 *  [MarqueeMode.PING_PONG] scrolls the text to its end, pauses, scrolls back, pauses,
 *  forever. [MarqueeMode.INFINITE] slides the text left continuously; a second copy
 *  follows the first, and the scroll resets by exactly one copy + gap so the text
 *  re-enters from the right with a seamless wrap.
 *
 * Neither effect runs while the text fits the available width — it then renders as a
 * plain single line that [overflow]-ellipsizes (also the behaviour when [marqueeEnabled]
 * is false). `rememberScrollState` and the drive [LaunchedEffect] live in composition
 * (not `composed {}`), so caller recompositions reuse the same state instead of tearing
 * down and re-creating a ComposedModifier node on every pass.
 */
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    mode: MarqueeMode,
    modifier: Modifier = Modifier,
    marqueeEnabled: Boolean = true,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    speedPxPerSecond: Float = 75f,
    pauseDuration: Long = 1500L
) {
    if (!marqueeEnabled) {
        BasicText(
            text = text,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier
        )
        return
    }

    when (mode) {
        MarqueeMode.PING_PONG -> {
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
            BasicText(
                text = text,
                style = style,
                maxLines = maxLines,
                overflow = overflow,
                modifier = modifier.horizontalScroll(scrollState, enabled = false)
            )
        }

        MarqueeMode.INFINITE -> {
            val scrollState = rememberScrollState()
            val gapPx = with(LocalDensity.current) { MarqueeLoopGap.roundToPx() }
            var viewportWidth by remember { mutableIntStateOf(0) }
            var copyWidth by remember { mutableIntStateOf(0) }
            // One copy + gap is exactly the distance after which the second copy sits
            // where the first started, so snapping back to 0 is invisible.
            val loopDistance = copyWidth + gapPx
            val isOverflowing = copyWidth > viewportWidth

            LaunchedEffect(isOverflowing, loopDistance, speedPxPerSecond) {
                if (isOverflowing && loopDistance > 0) {
                    val scrollDurationMillis = ((loopDistance / speedPxPerSecond) * 1000).toInt()
                    delay(pauseDuration)
                    while (true) {
                        scrollState.animateScrollTo(
                            value = loopDistance,
                            animationSpec = tween(durationMillis = scrollDurationMillis, easing = LinearEasing)
                        )
                        scrollState.animateScrollTo(value = 0, animationSpec = snap())
                    }
                }
            }

            Row(
                modifier = modifier
                    .onSizeChanged { viewportWidth = it.width }
                    .horizontalScroll(scrollState, enabled = false)
            ) {
                BasicText(
                    text = text,
                    style = style,
                    maxLines = maxLines,
                    overflow = overflow,
                    modifier = Modifier.onSizeChanged { copyWidth = it.width }
                )
                // The trailing copy only exists while the text actually overflows, so
                // short titles never show a duplicate next to them.
                if (isOverflowing) {
                    Spacer(modifier = Modifier.width(MarqueeLoopGap))
                    BasicText(
                        text = text,
                        style = style,
                        maxLines = maxLines,
                        overflow = overflow
                    )
                }
            }
        }
    }
}
