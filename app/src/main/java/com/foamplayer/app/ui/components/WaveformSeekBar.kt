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

/** Duration of the grow / release tween while the bar is being pressed or dragged. */
private const val GROW_TWEEN_MS = 200

/** Duration of the glide between the shape on screen and a newly available envelope. */
private const val ENVELOPE_SETTLE_MS = 350

/**
 * How long the bars keep holding the last shape while NO envelope is available (the
 * track is still being extracted). Extraction normally lands well within this; past it
 * (a file that failed to decode and will never produce one) the bars glide to flat
 * instead of showing a previous song's waveform forever.
 */
private const val PENDING_HOLD_TIMEOUT_MS = 3_000L

/**
 * What the bars currently draw, kept across frames (plain mutable fields: only the
 * draw phase and a SideEffect touch them, both on the main thread).
 *
 *  - [shown]: the shaped (0f..1f) value per bucket that was drawn last frame.
 *  - [offset]: the leftover difference between what was on screen and what the
 *    sources say, decayed to 0 by the settle animation. It is what turns a sudden
 *    change of source (envelope finishing extraction, track change) into a glide.
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
 * The full player's waveform seek bar: a row of vertical bars whose heights
 * trace the audio's amplitude envelope, colored by a horizontal mask like a
 * classic seek bar — bars left of [value] render [activeColor], bars right of
 * it [inactiveColor], with the mask edge cutting through (not jumping over)
 * whichever bar it crosses.
 *
 * Geometry mirrors [FoamSlider]: bar thickness [barWidth] and gap [barSpacing]
 * are fixed, the bar count follows the available width, and bars are drawn
 * centered vertically so the shape reads as a mirrored waveform.
 *
 * Track changes morph between envelopes instead of hard-swapping them:
 *  - with [trackChangeProgress] attached to the hero pager's swipe fraction, the
 *    two envelopes crossfade continuously while the user drags between tracks;
 *  - with it driven by the seek bar's settle tween (drag release / Next /
 *    Previous commit), the swap tweens in one static motion instead.
 * The adjacent tracks' envelopes are pre-extracted by the caller ([WaveformCache]
 * keeps them hot), so the morph has both shapes ready the moment a swipe starts.
 *
 * Envelopes that are NOT available yet (null state: the track is still being
 * extracted) never snap the bars to flat:
 *  - an unavailable morph side simply contributes nothing, so the bars keep the
 *    other side's shape;
 *  - when neither side is available, the bars HOLD whatever they last showed (flat
 *    on the very first open, the previous song's shape when chaining swipes);
 *  - whenever the set of sources changes (an envelope lands, the track changes) the
 *    difference between what was on screen and what the sources now say is carried
 *    as an offset that glides to 0 over [ENVELOPE_SETTLE_MS], so the bars animate
 *    to the new shape. Continuous drivers (the pager fraction) are NOT smoothed:
 *    they keep following the finger 1:1.
 * A cached envelope present from the first frame is drawn directly, without any
 * entrance animation.
 *
 * Extraction itself is streamed ([WaveformState.filled] advances while the audio
 * decodes), and each bucket is scaled against the song's own loudness reference
 * ([WaveformState.scale]) with a soft gamma, so a loud master keeps dynamic
 * shape instead of pinning every bar at maximum height.
 *
 * While the user drags the bar itself, [growFactor] scales every bar taller —
 * the same grow affordance FoamSlider gives its track — animated by
 * [animateDpAsState] so it releases smoothly.
 *
 * Like FoamSlider's lambda overload, [value] is read inside the draw phase, so
 * playback ticks and drag updates only invalidate this bar's drawing — nothing
 * in the player tree recomposes while the song advances.
 */
@Composable
fun WaveformSeekBar(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    waveform: WaveformState?,
    // The morph target (the track being swiped TO), or null when the bar is at
    // rest on the current track.
    targetWaveform: WaveformState? = null,
    // 0f = rest on [waveform], 1f = fully [targetWaveform]: the pager's swipe
    // fraction for animated seek bars, or 0f→1f tweened on commit otherwise.
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

    // The bar tracks its own press state, so the grow works even when the host does
    // not forward [isDragging]; the parameter still forces it on when set.
    var isPressed by remember { mutableStateOf(false) }

    // Bars grow taller while the user presses/drags the bar, like FoamSlider's
    // track, and tween back to normal on release.
    val animatedTrackHeight by animateDpAsState(
        targetValue = if (isDragging || isPressed) trackHeight * growFactor else trackHeight,
        animationSpec = tween(GROW_TWEEN_MS, easing = FastOutSlowInEasing),
        label = "waveformTrackHeight"
    )

    // --- Source-change smoothing -------------------------------------------------
    val display = remember { EnvelopeDisplay() }
    // 1f right after the sources change (full offset on screen) → 0f (offset gone).
    // Only read in the draw phase.
    val settle = remember { mutableFloatStateOf(0f) }

    // Nothing available: after a while stop holding the old shape (failed extraction).
    var holdExpired by remember { mutableStateOf(false) }
    LaunchedEffect(waveform, targetWaveform) {
        holdExpired = false
        if (waveform == null && targetWaveform == null) {
            delay(PENDING_HOLD_TIMEOUT_MS)
            holdExpired = true
        }
    }

    val signature = EnvelopeSignature(waveform, targetWaveform, holdExpired)

    // Runs after composition but before the frame is drawn: the very next draw sees
    // the capture request and a full offset, so no frame ever shows the new source
    // un-smoothed. (The first composition only records the signature.)
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
                // requiredHeight: the 32dp container would otherwise clamp the grown
                // height back down; the bars overflow it (centered) instead.
                .requiredHeight(animatedTrackHeight)
                .drawBehind {
                    val width = size.width
                    if (width <= 0f) return@drawBehind

                    val barWidthPx = barWidth.toPx()
                    val gapPx = barSpacing.toPx()
                    val period = barWidthPx + gapPx
                    // N bars + (N-1) gaps span at most the track's width.
                    val barCount = ((width + gapPx) / period).toInt().coerceAtLeast(1)

                    val minBarHeightPx = 2.dp.toPx()
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

                    // The bucket resolution both envelopes share (the cache's
                    // fixed extraction resolution). 0 while nothing has ever been
                    // available: the bars are flat.
                    val bucketCount = fromState?.bucketCount ?: toState?.bucketCount ?: 0

                    fun shapedBucket(state: WaveformState, filled: Int, scale: Float, bucket: Int): Float {
                        if (scale <= 0f || bucket >= filled) return 0f
                        return (state.peaks[bucket] / scale).coerceIn(0f, 1f).pow(0.65f)
                    }

                    // Consume the capture request exactly once per source change.
                    val capture = display.needsCapture
                    display.needsCapture = false

                    if (bucketCount > 0) {
                        if (display.shown.size != bucketCount) {
                            // First time buckets exist: what was on screen so far was
                            // flat, which is exactly what zeros mean.
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

                            // Envelope morph: lerp the two tracks' shaped peaks. A side
                            // that is not available contributes nothing — the other one
                            // is shown as-is instead of fading toward flat.
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
                                // Carry the gap between what was on screen and the new
                                // source; it glides away with [settle].
                                if (capture) offset[i] = shown[i] - source
                                shown[i] = (source + offset[i] * settleFactor).coerceIn(0f, 1f)
                            }
                        }
                    }

                    fun barHeight(bar: Int): Float {
                        if (bucketCount <= 0) return minBarHeightPx
                        // Max-pool the buckets under this bar so peaks survive the
                        // downsample to the on-screen bar count.
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

                    // Inactive envelope first, then the active color masked to the
                    // progress fraction: the mask edge cuts a partially-covered bar
                    // in two instead of switching its whole color at once.
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
                        // Full-height clip: the mask edge runs vertically, cutting the
                        // straddling bar's WIDTH, not its height.
                        clipRect(right = width * fraction) {
                            drawBars(activeColor)
                        }
                    }
                }
        )
    }
}