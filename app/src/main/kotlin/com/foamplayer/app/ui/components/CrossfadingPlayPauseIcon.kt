package com.foamplayer.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.foamplayer.app.ui.theme.FoamTheme

/** Duration of the play/pause glyph crossfade shared by every transport button. */
internal const val PLAY_PAUSE_CROSSFADE_MS = 250

/**
 * The stacked play/pause glyph pair shared by every transport button (full player,
 * subview header, docked mini player, bottom controls cluster). The two icons ride
 * one animated fraction in opposite directions, so they crossfade AND scale in
 * lockstep: the icon leaving shrinks to 20% scale while fading out, the one arriving
 * grows from 20% while fading in.
 *
 * [loadingFraction] (0f..1f) fades the whole pair out while a loading circle takes
 * its place -- same scale+fade convention. It is a deferred read, evaluated inside
 * the graphicsLayer blocks below, so per-frame updates redraw without recomposing.
 *
 * Deliberately a leaf: keeping the glyph pair its own composable confines any
 * per-frame recomposition it causes to itself instead of dragging the surrounding
 * transport row along with it.
 */
@Composable
internal fun CrossfadingPlayPauseIcon(
    isPlaying: Boolean,
    loadingFraction: () -> Float,
    tint: Color,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    // 0f = play icon showing, 1f = pause icon showing. Both icons ride this one value
    // in opposite directions, so they always crossfade in lockstep.
    val pauseFraction by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = PLAY_PAUSE_CROSSFADE_MS),
        label = "playPauseCrossfade"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        // Play icon: 100% -> 20% scale and 100% -> 0% opacity when switching to pause.
        Icon(
            imageVector = FoamTheme.icons.current.play,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    val transport = 1f - loadingFraction()
                    alpha = (1f - pauseFraction) * transport
                    val iconScale =
                        ((0.2f + 0.8f * (1f - pauseFraction)) * (1f - 0.8f * loadingFraction()))
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        // Pause icon: the exact reverse (20% -> 100% scale, 0% -> 100% opacity).
        Icon(
            imageVector = FoamTheme.icons.current.pause,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    val transport = 1f - loadingFraction()
                    alpha = pauseFraction * transport
                    val iconScale =
                        ((0.2f + 0.8f * pauseFraction) * (1f - 0.8f * loadingFraction()))
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
    }
}
