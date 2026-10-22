package com.foamplayer.app.audio.engine

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

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
    private val projector: PositionProjector = PositionProjector()
) {

    suspend fun run() {
        while (true) {
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
