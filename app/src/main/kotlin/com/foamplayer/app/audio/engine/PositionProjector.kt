package com.foamplayer.app.audio.engine

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns the player's coarse, quantized position samples into the smooth, monotonic playback
 * clock published as [AudioEngine.positionMs].
 *
 * ExoPlayer reports position in large steps, and the value may legitimately step backward around
 * fades, seeks and priming. Publishing the raw sample makes the seek bar jitter, so this projector
 * keeps a dead-reckoned anchor, slews it toward each fresh sample, holds the published value on
 * small backward corrections, and re-anchors on discontinuities.
 *
 * Pure and clock-injected: the caller passes [nowNanos], which makes the whole state machine
 * unit-testable without a player or a real clock.
 */
internal class PositionProjector(
    private val snapMs: Double = SNAP_MS,
    private val errorSlew: Double = ERROR_SLEW,
    private val settleTimeoutMs: Long = SETTLE_TIMEOUT_MS,
    private val monotonicHoldMaxMs: Long = MONOTONIC_HOLD_MAX_MS
) {
    private var lastPublishedMs = Long.MIN_VALUE
    private var lastSampleMs = -1L
    private var anchorMs = 0.0
    private var anchorNanos = 0L
    private var wasPriming = false
    private var settlingSinceNanos = 0L

    /** Position published on the previous tick, or `Long.MIN_VALUE` before the first tick. */
    val publishedMs: Long
        get() = lastPublishedMs

    /**
     * Consumes one player sample and returns the position to publish.
     *
     * @param sampleMs the raw `currentPosition` of the display player
     * @param audible whether the display player is playing or fading
     * @param loading whether the display player is buffering before its first frame
     * @param priming the incoming crossfade player has not rendered yet
     * @param nowNanos monotonic clock reading for this tick
     */
    fun project(
        sampleMs: Long,
        audible: Boolean,
        loading: Boolean,
        priming: Boolean,
        nowNanos: Long
    ): Long {
        // True on ticks where the position may legitimately step backward.
        var allowBackward = false

        // Settled pause: freeze the published clock. Publishing the raw sample here
        // could step it backward at the end of a pause fade-out.
        if (!audible && !loading && sampleMs == lastSampleMs) {
            val publishedMs = maxOf(sampleMs, lastPublishedMs)
            lastPublishedMs = publishedMs
            anchorMs = sampleMs.toDouble()
            anchorNanos = 0L
            lastSampleMs = sampleMs
            settlingSinceNanos = 0L
            return publishedMs
        }

        if (priming) {
            allowBackward = true
            anchorMs = sampleMs.toDouble()
            anchorNanos = 0L
            lastSampleMs = sampleMs
            wasPriming = true
        } else if (wasPriming) {
            // isPlaying only means READY + playWhenReady: the sink needs longer before
            // the clock really starts. Keep holding until it moves.
            wasPriming = false
            allowBackward = true
            settlingSinceNanos = nowNanos
            anchorMs = sampleMs.toDouble()
            anchorNanos = 0L
            lastSampleMs = sampleMs
        } else if (settlingSinceNanos != 0L) {
            if (sampleMs != lastSampleMs) {
                val jumpMs = abs(sampleMs - anchorMs)
                anchorMs = sampleMs.toDouble()
                lastSampleMs = sampleMs
                if (jumpMs > snapMs) {
                    // Another seek while settling: re-anchor and keep waiting.
                    allowBackward = true
                    anchorNanos = 0L
                    settlingSinceNanos = nowNanos
                } else {
                    anchorNanos = nowNanos
                    settlingSinceNanos = 0L
                }
            } else if ((nowNanos - settlingSinceNanos) / 1_000_000L > settleTimeoutMs) {
                anchorNanos = nowNanos
                settlingSinceNanos = 0L
            }
        } else if (sampleMs != lastSampleMs) {
            // Fresh sample: slew the anchor toward it, or snap on a discontinuity.
            val elapsedMs =
                if (anchorNanos == 0L) 0.0 else (nowNanos - anchorNanos) / 1_000_000.0
            val errorMs = sampleMs - (anchorMs + elapsedMs)
            val snapped = abs(errorMs) > snapMs
            if (snapped) allowBackward = true
            anchorMs =
                if (snapped) {
                    sampleMs.toDouble()
                } else {
                    anchorMs + elapsedMs + errorMs * errorSlew
                }
            if (snapped && audible) {
                // Seek while playing: hold at the target until the clock moves.
                anchorNanos = 0L
                settlingSinceNanos = nowNanos
            } else {
                // Dead-reckon only while audible, otherwise the position would run
                // ahead of a frozen clock and be pulled back later.
                anchorNanos = if (audible) nowNanos else 0L
            }
            lastSampleMs = sampleMs
        } else if (!audible) {
            // The sample moved while paused: external seek, the raw clock is the truth.
            allowBackward = true
            anchorMs = sampleMs.toDouble()
            anchorNanos = 0L
            lastSampleMs = sampleMs
        }
        // Audible with a frozen sample (quantization gap): keep dead-reckoning from the anchor.

        val rawProjectedMs =
            (if (anchorNanos == 0L) {
                anchorMs
            } else {
                anchorMs + (nowNanos - anchorNanos) / 1_000_000.0
            }).roundToLong().coerceAtLeast(0L)
        // Monotonic guard: hold the published value on small backward corrections.
        val deficitMs = lastPublishedMs - rawProjectedMs
        val projectedMs =
            if (!allowBackward && lastPublishedMs != Long.MIN_VALUE &&
                deficitMs in 1..monotonicHoldMaxMs
            ) {
                lastPublishedMs
            } else {
                rawProjectedMs
            }
        lastPublishedMs = projectedMs
        return projectedMs
    }

    companion object {
        /** Residual error beyond which a position sample is a discontinuity and re-anchors immediately. */
        const val SNAP_MS = 120.0

        /** Share of each sample's residual error absorbed by the dead-reckoning anchor. */
        const val ERROR_SLEW = 0.25

        /** Max wait for a stream clock to start moving before dead-reckoning resumes anyway. */
        const val SETTLE_TIMEOUT_MS = 300L

        /** Largest backward correction absorbed by holding the position instead of stepping back. */
        const val MONOTONIC_HOLD_MAX_MS = 60L
    }
}
