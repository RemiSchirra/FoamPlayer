package com.foamplayer.app.audio.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure math behind the fade engine: equal-power ramp curves and the
 * transition-trigger arithmetic. No Android dependencies so it is unit-testable.
 */
object FadeMath {

    /**
     * Gain of the outgoing stream at crossfade progress [t] in [0, 1]:
     * an equal-power cosine curve, 1 -> 0.
     */
    fun fadeOutGain(t: Float): Float =
        cos(t.coerceIn(0f, 1f) * (PI.toFloat() / 2f))

    /**
     * Gain of the incoming stream at crossfade progress [t] in [0, 1]:
     * an equal-power sine curve, 0 -> 1.
     */
    fun fadeInGain(t: Float): Float =
        sin(t.coerceIn(0f, 1f) * (PI.toFloat() / 2f))

    /**
     * Combined loudness of an ideal equal-power crossfade at progress [t]:
     * sin^2 + cos^2 == 1 for every t, so overlapping two uncorrelated streams
     * never dips below either one's power (no audible volume hole).
     */
    fun equalPowerSum(t: Float): Float {
        val out = fadeOutGain(t)
        val inn = fadeInGain(t)
        return out * out + inn * inn
    }

    /**
     * Playback time that must remain before a track's natural end for the
     * crossfade to the next track to START: the user's normal fade, clamped
     * to half the track so short tracks fade over at most half of themselves.
     */
    fun crossfadeStartThresholdMs(normalFadeMs: Long, trackDurationMs: Long): Long =
        if (trackDurationMs <= 0L) 0L else normalFadeMs.coerceAtMost(trackDurationMs / 2)

    /**
     * Whether the auto-crossfade should fire right now, given the track's
     * remaining playback time. Requires an enabled fade, a known duration and
     * at least one frame of runway left (remaining > 0): once the track has
     * effectively ended, ExoPlayer's natural transition takes over.
     */
    fun shouldStartCrossfade(remainingMs: Long, normalFadeMs: Long, trackDurationMs: Long): Boolean {
        if (normalFadeMs <= 0L || trackDurationMs <= 0L) return false
        val threshold = crossfadeStartThresholdMs(normalFadeMs, trackDurationMs)
        return remainingMs in 1..threshold
    }

    /**
     * Actual fade length used for a transition: the user's setting clamped to
     * half the track, so a 40 s track fades for at most 20 s and a 4 s track
     * for at most 2 s.
     */
    fun clampedFadeDurationMs(normalFadeMs: Long, trackDurationMs: Long): Long =
        if (trackDurationMs <= 0L) 0L else normalFadeMs.coerceAtMost(trackDurationMs / 2)
}
