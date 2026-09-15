package com.foamplayer.app.ui.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.foamplayer.app.ui.screens.player.PausedArtScale
import com.foamplayer.app.ui.screens.player.TRANSITION_DURATION_MS

/**
 * Tweened shrink applied to the album art while playback is paused. Must be applied
 * to EVERY end of the album-art shared-element pairs (full hero, mini player, subview
 * header): during a shared-element flight the overlay renders the entering side's
 * layer at the interpolated bounds, so if any end renders unscaled the art pops back
 * to full-bleed the instant the gesture starts.
 */
@Composable
internal fun rememberPausedArtScale(isPlaying: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (isPlaying) 1f else PausedArtScale,
        animationSpec = tween(durationMillis = TRANSITION_DURATION_MS),
        label = "pausedArtScale"
    )
