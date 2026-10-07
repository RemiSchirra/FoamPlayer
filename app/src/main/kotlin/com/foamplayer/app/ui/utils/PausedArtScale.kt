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
 * Tweened shrink applied to the FULL PLAYER's hero artwork while playback is
 * user-paused. The shrink is the hero's own rest look ONLY: the mini player and
 * the subview header rest at scale 1f and must not animate on play/pause.
 *
 * The other endpoints of the album-art shared-element pairs still consume this
 * value, but solely as their flight DEPARTURE scale. During a shared-element
 * flight the overlay renders the entering side's layer at the interpolated
 * bounds, so an endpoint that rests unscaled must blend from the hero's current
 * paused scale at the hero end back to its own rest 1f as it lands, along its
 * flight fraction: `paused + (1f - paused) * flightFraction`. Skipping the blend
 * makes a paused hero (0.97) hand off to an unscaled endpoint and pop 3% the
 * instant the gesture starts (while playing every end rests at 1f and the blend
 * is a no-op). Consumers: TrackHeroPager's hero (the owner, which eases the
 * shrink in along the same settled fraction its drop shadow rides), MiniPlayer,
 * FullPlayerSubView's MiniPlayerHeader and playerFlightArtwork
 * (dismiss-to-row).
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
