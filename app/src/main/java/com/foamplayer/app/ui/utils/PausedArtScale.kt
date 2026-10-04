package com.foamplayer.app.ui.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.foamplayer.app.ui.screens.player.PausedArtScale
import com.foamplayer.app.ui.screens.player.TRANSITION_DURATION_MS

/**
 * Tweened shrink applied to the album art while playback is user-paused. Must
 * be applied to EVERY end of the album-art shared-element pairs (full hero,
 * mini player, subview header): during a shared-element flight the overlay
 * renders the entering side's layer at the interpolated bounds, so if any end
 * renders unscaled the art pops back to full-bleed the instant the gesture
 * starts.
 *
 * "User-paused" is NOT "not audibly playing": a buffering gap between prepare()
 * and STATE_READY also reports isPlaying=false, and shrinking across that gap
 * made every track tap scale the art down for the load duration and back up
 * the instant playback started. Callers therefore pass the combined
 * playback-active bit (isPlaying || isPlaybackRequested) from PlaybackState.
 */
@Composable
internal fun rememberPausedArtScale(isPlaybackActive: Boolean): State<Float> =
    rememberPausedArtScale(
        isPlaybackActiveProvider = remember(isPlaybackActive) { { isPlaybackActive } }
    )

/**
 * Deferred-read variant: the combined playback-active bit is read inside a
 * [derivedStateOf], so a flip only re-runs this animation's own scope -- the
 * caller (hero pager, mini player header) recomposes neither on the flip nor
 * on any frame of the resulting scale animation; the returned state is
 * consumed inside the caller's graphicsLayer blocks.
 */
@Composable
internal fun rememberPausedArtScale(isPlaybackActiveProvider: () -> Boolean): State<Float> {
    val target by remember(isPlaybackActiveProvider) {
        derivedStateOf { if (isPlaybackActiveProvider()) 1f else PausedArtScale }
    }
    return animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = TRANSITION_DURATION_MS),
        label = "pausedArtScale"
    )
}
