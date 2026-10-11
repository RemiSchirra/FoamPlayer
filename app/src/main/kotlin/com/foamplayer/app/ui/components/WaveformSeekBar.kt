package com.foamplayer.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.WaveformState
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.pow

/** Duration of the grow / release tween while the bar is pressed or dragged. */
private const val GROW_TWEEN_MS = 200

/** Duration of the glide between the shape on screen and a newly available envelope. */
private const val ENVELOPE_SETTLE_MS = 350

/**
 * How long the bars hold the last shape while no envelope is available (the
 * track is still being extracted). Past it (a file that will never produce
 * one) the bars glide to flat.
 */
private const val PENDING_HOLD_TIMEOUT_MS = 3_000L

/**
 * What the bars currently draw, kept across frames (plain mutable fields: only
 * the draw phase and a SideEffect touch them, both on the main thread).
 *
 *  - [shown]: the shaped (0f..1f) value per bucket drawn last frame.
 *  - [offset]: the leftover difference between what was on screen and what the
 *    sources say, decayed to 0 by the settle animation.
 *  - [needsCapture]: set when the sources changed identity; the next draw turns
 *    "what was on screen minus the new source" into [offset].
 */
private class EnvelopeDisplay {
    var shown = FloatArray(0)
    var offset = FloatArray(0)
    var lastSignature: EnvelopeSignature? = null
    var needsCapture = false
}

/** Identity of the envelope sources feeding the bars (states compare by identity). */
private data class EnvelopeSignature(
    val from: WaveformState?,
    val to: WaveformState?,
    val holdExpired: Boolean
)

/**
 * The full player's waveform seek bar: vertical bars tracing the audio's
 * amplitude envelope, colored by a horizontal mask — bars left of [value]
 * render [activeColor], bars right of it [inactiveColor], with the mask edge
 * cutting through whichever bar it crosses.
 *
 * Track changes morph between envelopes instead of hard-swapping them: with
 * [trackChangeProgress] attached to the hero pager's swipe fraction the two
 * envelopes crossfade while dragging; with it tween-driven the swap plays in
 * one motion. Unavailable envelopes (still extracting) never snap the bars
 * flat: an unavailable morph side contributes nothing, and when neither side
 * is available the bars HOLD the last shape — the difference to the sources is
 * carried as an offset that glides to 0 over [ENVELOPE_SETTLE_MS] whenever the
 * sources change. Continuous drivers are not smoothed.
 *
 * While the user drags the bar itself, [growFactor] scales every bar taller,
 * animated so it releases smoothly.
 *
 * Like FoamSlider's lambda overload, [value] is read inside the draw phase:
 * playback ticks and drags only invalidate drawing, nothing recomposes.
 */
@Composable
fun WaveformSeekBar(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    waveform: WaveformState?,
    targetWaveform: WaveformState? = null,
    // 0f = rest on [waveform], 1f = fully [targetWaveform]: the pager's swipe
    // fraction for animated seek bars, or a 0f→1f tween on commit otherwise.
    trackChangeProgress: () -> Float = { 0f },
    activeColor: Color = FoamTheme.accentColor,
    inactiveColor: Color = FoamTheme.colors.surfaceVariant,
    barWidth: Dp = 3.dp,
    barSpacing: Dp = 3.dp,
    trackHeight: Dp = 28.dp,
    growFactor: Float = 1.5f,
    isDragging: Boolean = false
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    // The bar tracks its own press state so the grow works even when the host
    // does not forward [isDragging]; the parameter still forces it on.
    var isPressed by remember { mutableStateOf(false) }

    val animatedTrackHeight by animateDpAsState(
        targetValue = if (isDragging || isPressed) trackHeight * growFactor else trackHeight,
        animationSpec = tween(GROW_TWEEN_MS, easing = FastOutSlowInEasing),
        label = "waveformTrackHeight"
    )

    // --- Source-change smoothing ---
    val display = remember { EnvelopeDisplay() }
    // 1f right after the sources change (full offset on screen) -> 0f (offset gone).
    val settle = remember { mutableFloatStateOf(0f) }

    var holdExpired by remember { mutableStateOf(false) }
    LaunchedEffect(waveform, targetWaveform) {
        holdExpired = false
        if (waveform == null && targetWaveform == null) {
            delay(PENDING_HOLD_TIMEOUT_MS)
            holdExpired = true
        }
    }

    val signature = EnvelopeSignature(waveform, targetWaveform, holdExpired)

    // Runs after composition but before the frame is drawn: the next draw sees
    // the capture request and a full offset, so no frame shows the new source
    // un-smoothed.
    SideEffect {
        val previous = display.lastSignature
        display.lastSignature = signature
        if (previous != null && previous != signature) {
            display.needsCapture = true
            settle.floatValue = 1f
        }
    }
    LaunchedEffect(signature) {
        val from = settle.floatValue
        if (from > 0f) {
            animate(
                initialValue = from,
                targetValue = 0f,
                animationSpec = tween(ENVELOPE_SETTLE_MS, easing = FastOutSlowInEasing)
            ) { v, _ -> settle.floatValue = v }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    try {
                        val width = size.width
                        if (width > 0) {
                            currentOnValueChange((down.position.x / width).coerceIn(0f, 1f))
                        }
                        down.consume()
                        drag(down.id) { change ->
                            if (width > 0) {
                                currentOnValueChange((change.position.x / width).coerceIn(0f, 1f))
                            }
                            if (change.pressed) change.consume()
                        }
                    } finally {
                        // Also runs when the gesture is cancelled.
                        isPressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // requiredHeight: the 32dp container would clamp the grown height;
                // the bars overflow it (centered) instead.
                .requiredHeight(animatedTrackHeight)
                .drawBehind {
                    val width = size.width
                    if (width <= 0f) return@drawBehind

                    val barWidthPx = barWidth.toPx()
                    val gapPx = barSpacing.toPx()
                    val period = barWidthPx + gapPx
                    val barCount = ((width + gapPx) / period).toInt().coerceAtLeast(1)

                    // The minimum scales with the grow animation too, so
                    // near-silent bars grow along with the rest.
                    val growRatio = (size.height / trackHeight.toPx()).coerceAtLeast(1f)
                    val minBarHeightPx = barWidthPx * growRatio
                    val maxBarHeightPx = size.height
                    val centerY = size.height / 2f
                    val cornerRadius = CornerRadius(barWidthPx / 2f)

                    // Draw-phase reads: filled/scale changes and drag progress
                    // redraw the bars without recomposing anything up the tree.
                    val fromState = waveform
                    val toState = targetWaveform
                    val filledFrom = fromState?.filled?.intValue ?: 0
                    val filledTo = toState?.filled?.intValue ?: 0
                    val scaleFrom = fromState?.scale?.floatValue ?: 0f
                    val scaleTo = toState?.scale?.floatValue ?: 0f
                    val blend = trackChangeProgress().coerceIn(0f, 1f)
                    val settleFactor = settle.floatValue

                    // The bucket resolution both envelopes share; 0 while nothing
                    // has ever been available (bars are flat).
                    val bucketCount = fromState?.bucketCount ?: toState?.bucketCount ?: 0

                    fun shapedBucket(state: WaveformState, filled: Int, scale: Float, bucket: Int): Float {
                        if (scale <= 0f || bucket >= filled) return 0f
                        return (state.peaks[bucket] / scale).coerceIn(0f, 1f).pow(0.65f)
                    }

                    val capture = display.needsCapture
                    display.needsCapture = false

                    if (bucketCount > 0) {
                        if (display.shown.size != bucketCount) {
                            // First time buckets exist: what was on screen so far
                            // was flat, which is exactly what zeros mean.
                            display.shown = FloatArray(bucketCount)
                            display.offset = FloatArray(bucketCount)
                        }
                        val shown = display.shown
                        val offset = display.offset

                        for (i in 0 until bucketCount) {
                            val peakFrom = if (fromState != null) {
                                shapedBucket(fromState, filledFrom, scaleFrom, i)
                            } else 0f
                            val peakTo = if (toState != null) {
                                shapedBucket(toState, filledTo, scaleTo, i)
                            } else 0f

                            // Morph: lerp the two tracks' shaped peaks. An
                            // unavailable side contributes nothing instead of
                            // fading toward flat.
                            val source: Float? = when {
                                fromState != null && toState != null ->
                                    peakFrom + (peakTo - peakFrom) * blend
                                fromState != null -> peakFrom
                                toState != null -> peakTo
                                holdExpired -> 0f
                                else -> null // Nothing available yet: hold what is shown.
                            }

                            if (source == null) {
                                offset[i] = 0f
                            } else {
                                // Carry the gap to the new source; it glides away.
                                if (capture) offset[i] = shown[i] - source
                                shown[i] = (source + offset[i] * settleFactor).coerceIn(0f, 1f)
                            }
                        }
                    }

                    fun barHeight(bar: Int): Float {
                        if (bucketCount <= 0) return minBarHeightPx
                        // Max-pool the buckets under this bar so peaks survive
                        // the downsample to the on-screen bar count.
                        val start = bar * bucketCount / barCount
                        val end = ((bar + 1) * bucketCount / barCount)
                            .coerceAtLeast(start + 1)
                            .coerceAtMost(bucketCount)
                        var peak = 0f
                        for (i in start until end) {
                            peak = max(peak, display.shown[i])
                        }
                        return minBarHeightPx + (maxBarHeightPx - minBarHeightPx) * peak
                    }

                    fun drawBars(color: Color) {
                        for (bar in 0 until barCount) {
                            val h = barHeight(bar)
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(bar * period, centerY - h / 2f),
                                size = Size(barWidthPx, h),
                                cornerRadius = cornerRadius
                            )
                        }
                    }

                    drawBars(inactiveColor)
                    val fraction = value().coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        // Full-height clip: the mask edge cuts the straddling
                        // bar's WIDTH, not its height.
                        clipRect(right = width * fraction) {
                            drawBars(activeColor)
                        }
                    }
                }
        )
    }
}
