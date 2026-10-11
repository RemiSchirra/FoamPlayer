package com.foamplayer.app.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.data.settings.MarqueeMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Blank space the text travels through between two repeats in [MarqueeMode.INFINITE]. */
private val MarqueeLoopGap: Dp = 48.dp

/**
 * Hoisted marquee state: the current scroll offset plus the measured widths
 * the drive loop needs. Owning it outside [MarqueeText] lets TWO composables
 * render the same scrolling text (e.g. a pager page and the hero overlay
 * replacing it) without the second one restarting from offset 0: one instance
 * drives the state ([MarqueeText.drivesState] = true), the others render it.
 */
@Stable
class MarqueeTextState {
    internal var offsetPx by mutableFloatStateOf(0f)
    internal var viewportWidth by mutableIntStateOf(0)
    internal var contentWidth by mutableIntStateOf(0)
    internal var restartCount by mutableIntStateOf(0)

    /** Rewinds the text to its start and restarts the initial pause. */
    fun restart() {
        offsetPx = 0f
        restartCount++
    }
}

@Composable
fun rememberMarqueeTextState(): MarqueeTextState = remember { MarqueeTextState() }

private fun travelMillis(distancePx: Float, speedPxPerSecond: Float): Int =
    ((abs(distancePx) / speedPxPerSecond) * 1000f).toInt()

/**
 * Clips to its own bounds, measures the child with unbounded width and places
 * it shifted left by [offsetPx]. The offset is read in the placement block, so
 * a scroll tick only re-places — no recomposition or remeasure.
 */
private fun Modifier.marqueeViewport(offsetPx: (overflow: Int) -> Int): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = 0,
                maxWidth = Constraints.Infinity,
                minHeight = constraints.minHeight,
                maxHeight = constraints.maxHeight
            )
        )
        val width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, placeable.height) {
            placeable.placeRelative(-offsetPx(placeable.width - width), 0)
        }
    }

/**
 * Single-line text with the scrolling behavior selected by [mode].
 *
 *  [MarqueeMode.PING_PONG] scrolls to the end, pauses, scrolls back, pauses,
 *  forever. [MarqueeMode.INFINITE] slides left continuously; a second copy
 *  follows the first and the scroll resets by exactly one copy + gap for a
 *  seamless wrap.
 *
 * Neither effect runs while the text fits the available width — it renders as
 * a plain single line that [overflow]-ellipsizes (also when [marqueeEnabled]
 * is false).
 *
 * [state] can be hoisted so several instances show the same scroll position.
 * Exactly one of them should have [drivesState] = true; the rest only render
 * the offset. A driving instance resets the offset when it leaves composition.
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
    pauseDuration: Long = 1500L,
    state: MarqueeTextState = rememberMarqueeTextState(),
    drivesState: Boolean = true
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

    if (drivesState) {
        DisposableEffect(state) {
            onDispose { state.offsetPx = 0f }
        }
    }

    when (mode) {
        MarqueeMode.PING_PONG -> {
            val maxScroll = (state.contentWidth - state.viewportWidth).coerceAtLeast(0)

            LaunchedEffect(drivesState, maxScroll, speedPxPerSecond, pauseDuration, state.restartCount) {
                if (!drivesState) return@LaunchedEffect
                val generation = state.restartCount
                if (maxScroll <= 0) {
                    state.offsetPx = 0f
                    return@LaunchedEffect
                }
                val end = maxScroll.toFloat()
                // A restart (size change) resumes from wherever the text is.
                if (state.offsetPx > end) state.offsetPx = end
                delay(pauseDuration)
                while (true) {
                    animate(
                        initialValue = state.offsetPx,
                        targetValue = end,
                        animationSpec = tween(
                            durationMillis = travelMillis(end - state.offsetPx, speedPxPerSecond),
                            easing = EaseInOut
                        )
                    ) { value, _ ->
                        // A restart() bumps restartCount: a stale animation frame
                        // arriving before cancellation must not overwrite the rewind.
                        if (state.restartCount == generation) state.offsetPx = value
                    }
                    delay(pauseDuration)
                    animate(
                        initialValue = state.offsetPx,
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = travelMillis(state.offsetPx, speedPxPerSecond),
                            easing = EaseInOut
                        )
                    ) { value, _ ->
                        if (state.restartCount == generation) state.offsetPx = value
                    }
                    delay(pauseDuration)
                }
            }

            BasicText(
                text = text,
                style = style,
                maxLines = maxLines,
                overflow = overflow,
                modifier = modifier
                    .onSizeChanged { if (drivesState) state.viewportWidth = it.width }
                    .marqueeViewport { overflowPx ->
                        state.offsetPx.roundToInt().coerceIn(0, overflowPx.coerceAtLeast(0))
                    }
                    .onSizeChanged { if (drivesState) state.contentWidth = it.width }
            )
        }

        MarqueeMode.INFINITE -> {
            val gapPx = with(LocalDensity.current) { MarqueeLoopGap.roundToPx() }
            // One copy + gap is exactly the distance after which the second copy
            // sits where the first started, so snapping back to 0 is invisible.
            val loopDistance = state.contentWidth + gapPx
            val isOverflowing = state.contentWidth > state.viewportWidth

            LaunchedEffect(drivesState, isOverflowing, loopDistance, speedPxPerSecond, pauseDuration, state.restartCount) {
                if (!drivesState) return@LaunchedEffect
                val generation = state.restartCount
                if (!isOverflowing || loopDistance <= 0) {
                    state.offsetPx = 0f
                    return@LaunchedEffect
                }
                val end = loopDistance.toFloat()
                if (state.offsetPx > end) state.offsetPx = 0f
                delay(pauseDuration)
                while (true) {
                    animate(
                        initialValue = state.offsetPx,
                        targetValue = end,
                        animationSpec = tween(
                            durationMillis = travelMillis(end - state.offsetPx, speedPxPerSecond),
                            easing = LinearEasing
                        )
                    ) { value, _ ->
                        if (state.restartCount == generation) state.offsetPx = value
                    }
                    state.offsetPx = 0f
                }
            }

            Row(
                modifier = modifier
                    .onSizeChanged { if (drivesState) state.viewportWidth = it.width }
                    .marqueeViewport { _ -> if (isOverflowing) state.offsetPx.roundToInt() else 0 }
            ) {
                BasicText(
                    text = text,
                    style = style,
                    maxLines = maxLines,
                    overflow = overflow,
                    modifier = Modifier.onSizeChanged { if (drivesState) state.contentWidth = it.width }
                )
                // The trailing copy only exists while the text overflows, so
                // short titles never show a duplicate.
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
