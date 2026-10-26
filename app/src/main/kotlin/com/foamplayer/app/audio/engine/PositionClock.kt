package com.foamplayer.app.audio.engine

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * One reading of the Chromecast receiver's clock. [isPlaying] means the receiver's
 * playback clock is running, so the position may be dead-reckoned forward between
 * samples; [isBuffering] means it is priming the current item and must be held.
 */
internal data class CastClockSample(
    val positionMs: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean
)

/**
 * Drives the frame-rate playback clock: samples the display player, projects the position through
 * [PositionProjector], publishes it, and runs the per-tick hooks (listen accounting, auto
 * crossfade).
 *
 * Split out of [AudioEngine] so the loop's bookkeeping is separate from queue/routing ownership;
 * all player access goes through the injected providers, which keeps it free of engine state.
 */
internal class PositionClock(
    private val player: () -> ExoPlayer,
    private val fadeDisplayPlayer: () -> Player?,
    private val isFading: () -> Boolean,
    private val onPosition: (Long) -> Unit,
    private val onTick: (displayPlayer: Player, positionMs: Long) -> Unit,
    private val onPrimaryPlayingFrame: () -> Unit,
    private val awaitFrame: suspend () -> Unit,
    /**
     * Non-null while a Chromecast owns playback: the receiver's clock is sampled instead of the
     * (parked) local player's.
     */
    private val castSample: () -> CastClockSample? = { null },
    /** Per-poll hook while a Chromecast owns playback (listen accounting). */
    private val onCastTick: (positionMs: Long) -> Unit = {},
    private val projector: PositionProjector = PositionProjector(),
    /**
     * Receiver clocks are quantized and arrive a network hop late, so corrections have to be
     * absorbed instead of snapped onto: a normal sample changing by up to
     * [PositionProjector.CAST_SNAP_MS] is slewed, and backward corrections up to
     * [PositionProjector.CAST_MONOTONIC_HOLD_MS] are held. A real seek still re-anchors,
     * because it exceeds that band.
     */
    private val castProjector: PositionProjector = PositionProjector(
        snapMs = PositionProjector.CAST_SNAP_MS,
        errorSlew = PositionProjector.CAST_ERROR_SLEW,
        monotonicHoldMaxMs = PositionProjector.CAST_MONOTONIC_HOLD_MS
    )
) {

    suspend fun run() {
        var wasCasting = false
        while (true) {
            val cast = castSample()
            if (cast != null) {
                if (!wasCasting) castProjector.reset()
                wasCasting = true
                val castMs = castProjector.project(
                    sampleMs = cast.positionMs,
                    audible = cast.isPlaying,
                    loading = false,
                    priming = cast.isBuffering,
                    nowNanos = System.nanoTime()
                )
                onPosition(castMs)
                onCastTick(castMs)
                if (cast.isPlaying) awaitFrame() else delay(PAUSED_POLL_INTERVAL_MS.milliseconds)
                continue
            }
            wasCasting = false
            val displayPlayer = fadeDisplayPlayer() ?: player()
            val sampleMs = displayPlayer.currentPosition.coerceAtLeast(0L)
            val audible = displayPlayer.isPlaying || isFading()
            val loading = fadeDisplayPlayer() == null &&
                    displayPlayer.playWhenReady &&
                    displayPlayer.playbackState == Player.STATE_BUFFERING
            val priming = loading || (fadeDisplayPlayer() != null && !displayPlayer.isPlaying)

            val projectedMs = projector.project(
                sampleMs = sampleMs,
                audible = audible,
                loading = loading,
                priming = priming,
                nowNanos = System.nanoTime()
            )
            onPosition(projectedMs)
            onTick(displayPlayer, projectedMs)

            // Gated on the real primary: mid-fade the display player is a priming secondary.
            if (player().isPlaying) onPrimaryPlayingFrame()

            // Per frame while audible; awaitFrame() falls back to a slow poll in the background.
            if (audible) awaitFrame() else delay(PAUSED_POLL_INTERVAL_MS.milliseconds)
        }
    }

    companion object {
        /** Poll interval while paused; bounds the latency to notice a play or an external seek. */
        const val PAUSED_POLL_INTERVAL_MS = 50L
    }
}
