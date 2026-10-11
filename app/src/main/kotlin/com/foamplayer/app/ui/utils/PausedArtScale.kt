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
 * Tweened shrink applied to the full player's hero artwork while playback is
 * user-paused. This is the hero's rest look ONLY: other shared-element
 * endpoints rest at 1f and consume the value solely as their flight departure
 * scale, blending from the hero's paused scale back to 1f along the flight
 * fraction — otherwise a paused hero pops at hand-off. A buffering gap between
 * prepare() and STATE_READY also reports isPlaying=false, so callers pass the
 * combined playback-active bit (isPlaying || isPlaybackRequested).
 */
@Composable
internal fun rememberPausedArtScale(isPlaybackActive: Boolean): State<Float> =
    rememberPausedArtScale(
        isPlaybackActiveProvider = remember(isPlaybackActive) { { isPlaybackActive } }
    )

/**
 * Deferred-read variant: the playback-active bit is read inside a
 * [derivedStateOf], so a flip only re-runs this animation's own scope — the
 * caller recomposes neither on the flip nor on any frame of the animation.
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
