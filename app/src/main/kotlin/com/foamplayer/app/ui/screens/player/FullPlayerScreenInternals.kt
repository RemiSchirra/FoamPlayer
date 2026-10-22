package com.foamplayer.app.ui.screens.player

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import com.foamplayer.app.ui.components.SeekBarProgressState

/**
 * Small supporting types and effects of the full player that do not need the screen's state.
 * Split out of `FullPlayerScreen.kt` so the screen body stays readable.
 */

/** A resolved animated canvas and the queue entry it belongs to. */
internal data class AnimatedCoverOwner(val entryId: Long, val url: String)

/** Plain holder (not snapshot state): the canvas shown last, kept while its page is still settled. */
internal class AnimatedCoverCarry {
    var owner: AnimatedCoverOwner? = null
}

/** Live geometry feeding the dismiss swipe's 1:1 finger-to-artwork mapping. */
class PlayerFlightGeometryState {
    var heroArtCenterY: Float by mutableFloatStateOf(Float.NaN)
}

/**
 * Lets this node's content measure and draw [extra] taller than the space it is given while
 * reporting its original size to the parent, so siblings and surrounding layout are untouched.
 * Used to run the lyrics/queue content (and its bottom fade) down behind the lowered control row.
 */
internal fun Modifier.extendBottom(extra: Dp): Modifier = layout { measurable, constraints ->
    val extraPx = extra.roundToPx()
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(Constraints.fixed(width, height + extraPx))
    layout(width, height) { placeable.place(0, 0) }
}

/**
 * Arms the seek bar's swap tween when a finger-driven pager gesture commits to another page.
 *
 * A ghost page twinning the playing track is a repeat-all wrap landing, not a track change.
 */
@Composable
internal fun TrackChangeCommitEffect(
    pagerState: PagerState,
    currentQueueIndex: Int,
    currentQueueSize: Int,
    isAnimateSeekBarEnabled: Boolean,
    isWaveformSeek: Boolean,
    envelopeMorphProgress: () -> Float,
    seekBarProgress: SeekBarProgressState,
    isWrapLanding: (Int) -> Boolean
) {
    val latestIsWrapLanding by rememberUpdatedState(isWrapLanding)
    val latestQueueIndex by rememberUpdatedState(currentQueueIndex)
    val latestQueueSize by rememberUpdatedState(currentQueueSize)
    val latestIsAnimateSeekBarEnabled by rememberUpdatedState(isAnimateSeekBarEnabled)
    val latestIsWaveformSeek by rememberUpdatedState(isWaveformSeek)
    val latestEnvelopeMorphProgress by rememberUpdatedState(envelopeMorphProgress)

    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(pagerState) {
        snapshotFlow { isDragged to pagerState.targetPage }
            .collect { (dragging, targetPage) ->
                if (!dragging &&
                    targetPage in 0 until latestQueueSize &&
                    targetPage != latestQueueIndex &&
                    !latestIsWrapLanding(targetPage)
                ) {
                    seekBarProgress.onTrackChangeCommitted(
                        animated = latestIsAnimateSeekBarEnabled,
                        morphTargetPage = if (latestIsWaveformSeek) targetPage else null,
                        fromBlend = latestEnvelopeMorphProgress(),
                        fromPageIndex = latestQueueIndex
                    )
                }
            }
    }
}

/** Window within which a transport-button skip disambiguates the direction of a repeat-all wrap. */
internal const val SKIP_INTENT_WINDOW_MS = 1_500L
