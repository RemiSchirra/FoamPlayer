package com.foamplayer.app.ui.navigation

import android.os.Bundle
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.foamplayer.app.ui.components.TrackCenterRequest
import com.foamplayer.app.ui.screens.player.DownSwipeCommitVelocityPxPerSec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the mini-player <-> full-player transition: tap opens/closes, drags
 * seek the shared [SeekableTransitionState], and a swipe-down from a collection
 * page can fly the artwork into that page's playing row.
 *
 * Superseding flights cancel prior ones (the generation counter makes late
 * completions drop their cleanup), and [trackListTransitionTrackId] tags the
 * row the artwork should pair with.
 */
@Stable
internal class PlayerTransitionController(
    val state: SeekableTransitionState<Boolean>,
    private val scope: CoroutineScope,
    initialOpenRequested: Boolean,
) {
    var isTransitionAnimated by mutableStateOf(!initialOpenRequested)
        private set

    /** Track whose list row is the shared-element target, if any. */
    var trackListTransitionTrackId by mutableStateOf<Long?>(null)
        private set

    /** Suppresses the mini player's own track-change animation during a flight. */
    var skipMiniPlayerTrackAnimation by mutableStateOf(false)

    /** Pending request to center a row before a dismiss flight. */
    var centerTrackRequest by mutableStateOf<TrackCenterRequest?>(null)
        private set

    private var dismissToRowSessionActive = false
    private var centerRequestSeq = 0

    private var transitionJob: Job? = null
    private var transitionGeneration = 0

    /**
     * Cold start straight into the player: restore animated transitions once the
     * first frames are composed (see [animate] for the warm-start equivalent).
     */
    suspend fun enableAnimationAfterColdStart() {
        withFrameNanos { }
        withFrameNanos { }
        isTransitionAnimated = true
    }

    /** Animates (or snaps) the player transition; superseding flights cancel prior ones. */
    fun animate(
        open: Boolean,
        sourceTrackId: Long? = null,
        precompose: Boolean = false,
        animated: Boolean = true,
        gestureVelocityPxPerSec: Float = Float.NaN
    ) {
        transitionJob?.cancel()
        isTransitionAnimated = animated

        if (open) {
            trackListTransitionTrackId = sourceTrackId
            dismissToRowSessionActive = false
            centerTrackRequest = null
        }
        val myGeneration = ++transitionGeneration
        transitionJob = scope.launch {
            try {
                if (animated) {
                    if (precompose) {
                        withFrameNanos { }
                        state.seekTo(fraction = 0f, targetState = open)
                        withFrameNanos { }
                    }
                    state.animateTo(open, animationSpec(open, gestureVelocityPxPerSec))
                } else {
                    state.snapTo(open)
                    if (transitionGeneration == myGeneration) {
                        isTransitionAnimated = true
                    }
                }
            } finally {
                if (transitionGeneration == myGeneration) {
                    if (open) {
                        trackListTransitionTrackId = null
                        skipMiniPlayerTrackAnimation = false
                    } else {
                        trackListTransitionTrackId = null
                        dismissToRowSessionActive = false
                        centerTrackRequest = null
                    }
                }
            }
        }
    }

    /**
     * PRESS PRELOAD: precomposes the returning page and scrolls the playing row
     * to the screen's middle before the finger moves. Deliberately does NOT tag
     * the row (a rest-state pairing would hijack the hero artwork).
     */
    suspend fun prepareDismissToRow(
        route: String?,
        arguments: Bundle?,
        activeTrackId: Long?,
        activeCollectionId: String?
    ) {
        val trackId = activeTrackId ?: return
        if (!isOriginCollectionPage(route, arguments, activeCollectionId)) return

        val request = TrackCenterRequest(trackId, ++centerRequestSeq)
        centerTrackRequest = request
        state.seekTo(fraction = 0f, targetState = false)

        withTimeoutOrNull(250) { request.scrolledIntoPlace.await() }
        withFrameNanos { }
        withFrameNanos { }
    }

    /**
     * DRAG ACTIVATION: tags the playing row's artwork so the shared element
     * pairs, letting a frame land before the drag moves so the flight's first
     * frame is correct.
     */
    suspend fun activateDismissToRow(
        route: String?,
        arguments: Bundle?,
        activeTrackId: Long?,
        activeCollectionId: String?
    ) {
        val trackId = activeTrackId ?: return
        if (!isOriginCollectionPage(route, arguments, activeCollectionId)) return

        val request = centerTrackRequest
        if (request == null || request.trackId != trackId) {
            prepareDismissToRow(route, arguments, activeTrackId, activeCollectionId)
        }

        dismissToRowSessionActive = true
        trackListTransitionTrackId = trackId
        withFrameNanos { }
        withFrameNanos { }
    }

    /** Swipe-down settled without committing: drop the row tag. */
    fun clearDismissToRowSession() {
        if (dismissToRowSessionActive) {
            dismissToRowSessionActive = false
            trackListTransitionTrackId = null
        }
        centerTrackRequest = null
    }

    /** Duration scaled to the transition's remaining distance; flicks get a floor. */
    private fun animationSpec(
        targetIsFull: Boolean,
        gestureVelocityPxPerSec: Float
    ): TweenSpec<Float> {
        val isOpenProgress = if (state.currentState == state.targetState) {
            if (state.currentState) 1f else 0f
        } else {
            if (state.targetState) state.fraction else 1f - state.fraction
        }
        val remainingFraction = (if (targetIsFull) 1f - isOpenProgress else isOpenProgress)
            .coerceIn(0f, 1f)
        val maxDuration = if (!targetIsFull && gestureVelocityPxPerSec.isNaN()) {
            AutoCloseFlightDurationMs
        } else {
            PlayerTransitionMaxDurationMs
        }
        val dynamicDuration = (maxDuration * remainingFraction).toInt().coerceAtLeast(1)
        val duration = if (!gestureVelocityPxPerSec.isNaN() &&
            gestureVelocityPxPerSec >= DownSwipeCommitVelocityPxPerSec
        ) {
            dynamicDuration.coerceAtLeast(MinGestureCloseFlightMs)
        } else {
            dynamicDuration
        }
        return tween(durationMillis = duration, easing = EaseInOut)
    }
}
