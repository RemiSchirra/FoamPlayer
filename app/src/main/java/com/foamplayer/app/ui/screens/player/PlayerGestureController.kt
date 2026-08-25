package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the two nested seekable transitions used by the full player:
 *  - [playerTransitionState]: mini player <-> full player, owned by the caller/nav graph.
 *  - [upTransitionState]: full player <-> mini header/subview, owned here.
 *
 * A single swipe gesture can affect either transition depending on where the drag starts
 * and how far it has already progressed, so all the drag arithmetic lives in one place.
 *
 * Every seekTo() issued during a drag and the animateTo() issued on release run through
 * [runExclusive] on the same coroutine job. Launching a fresh job per drag delta let a
 * stale, still-pending seekTo from an earlier delta execute *after* animateTo() had
 * already started interpolating, snapping the value back mid-animation — the release
 * "jump then complete" glitch. Cancelling any pending job before starting the next one
 * guarantees only the latest call ever runs.
 *
 * Separately: a drag is mid-seek toward *some* targetState the whole time (e.g. seekTo(false, 0.3)
 * while at rest on true). animateTo() is only safe to call to *continue* toward that same
 * targetState — calling it with the opposite state (the "released before threshold, snap back"
 * case) makes SeekableTransitionState resolve the in-flight seek before it can animate, which is
 * its own, unrelated "jump then complete" glitch. [settleBackToRest] avoids that by manually
 * walking the fraction back to 0 against the *existing* targetState via seekTo(), so currentState
 * never has to move at all.
 */
class PlayerGestureController(
    val upTransitionState: SeekableTransitionState<Boolean>,
    private val playerTransitionState: SeekableTransitionState<Boolean>,
    private val scope: CoroutineScope,
    private val dragThresholdPx: Float,
    private val onDismiss: () -> Unit
) {
    private var activeGestureJob: Job? = null
    private var lockedDirection: GestureDirection? = null

    private enum class GestureDirection { UP, DOWN }

    private fun SeekableTransitionState<Boolean>.progress(): Float =
        if (currentState == targetState) {
            if (currentState) 1f else 0f
        } else {
            if (targetState) fraction else 1f - fraction
        }

    private fun runExclusive(block: suspend () -> Unit) {
        activeGestureJob?.cancel()
        activeGestureJob = scope.launch { block() }
    }

    /**
     * Cancels an in-flight seek without ever calling animateTo() on the opposite state.
     * Keeps the current targetState fixed and eases [SeekableTransitionState.fraction] from
     * wherever it is back down to 0, so currentState is never touched and there's nothing for
     * SeekableTransitionState to "resolve" before animating — see class doc.
     *
     * Easing fraction to 0 alone isn't enough to fully settle things: targetState is left
     * pointing at restingTarget even though fraction says we're visually back at currentState.
     * Transition treats currentState != targetState as "still transitioning" regardless of
     * fraction, so it keeps *both* AnimatedVisibility branches composed — the extra, stale
     * branch is what shows up as a duplicate album art image on the next real transition. The
     * closing snapTo(restState) collapses targetState back onto currentState (a visual no-op,
     * since fraction is already 0) so the Transition actually settles.
     */
    private suspend fun SeekableTransitionState<Boolean>.settleBackToRest(
        durationMillis: Int = SETTLE_DURATION_MS
    ) {
        val restingTarget = targetState
        val restState = currentState
        val startFraction = fraction
        if (startFraction <= 0f) {
            if (targetState != restState) snapTo(restState)
            return
        }

        try {
            val startFrameNanos = withFrameNanos { it }
            var t: Float
            do {
                val elapsedMs = (withFrameNanos { it } - startFrameNanos) / 1_000_000f
                t = (elapsedMs / durationMillis).coerceIn(0f, 1f)
                seekTo(targetState = restingTarget, fraction = startFraction * (1f - FastOutSlowInEasing.transform(t)))
            } while (t < 1f)
        } finally {
            withContext(NonCancellable) { snapTo(restState) }
        }
    }

    /**
     * A single drag stroke picks a direction on its first meaningful delta and is locked to it
     * until the finger lifts. Without this, dragging down (mini-player-bound) and reversing
     * upward past the point where downProgress returns to exactly 0 would satisfy the up-swipe
     * condition below and start driving upTransitionState too — both AnimatedVisibility layers
     * (main player and header/subview) end up animating at once, each with its own copy of the
     * album art, which is the duplicate-image glitch. Locking the direction for the stroke keeps
     * the two transitions mutually exclusive per gesture: reversing a down-drag can only ever
     * cancel the dismiss, never bleed into opening the subview, and vice versa.
     */
    fun onDrag(delta: Float) = runExclusive {
        val upProgress = upTransitionState.progress()
        // false = mini player (closed), true = full player (open)
        val downProgress = 1f - playerTransitionState.progress()

        val direction = lockedDirection ?: when {
            upProgress > 0f || (delta < 0 && downProgress == 0f) -> GestureDirection.UP
            downProgress > 0f || (delta > 0 && upProgress == 0f) -> GestureDirection.DOWN
            else -> null
        }?.also { lockedDirection = it }

        when (direction) {
            GestureDirection.UP -> {
                val upTarget = if (upTransitionState.currentState != upTransitionState.targetState) {
                    upTransitionState.targetState
                } else {
                    !upTransitionState.currentState
                }
                val progressTowardUpTarget = if (upTarget) upProgress else 1f - upProgress
                val signedDelta = if (upTarget) -delta else delta

                // Compensate the easing on reversing direction
                val easingCompensation = if (signedDelta < 0) {
                    1f + (progressTowardUpTarget * 2f)
                } else {
                    1f
                }

                val newProgress = (progressTowardUpTarget + (signedDelta * easingCompensation) / (dragThresholdPx * UP_SWIPE_SENSITIVITY))
                    .coerceIn(0f, 1f)
                upTransitionState.seekTo(targetState = upTarget, fraction = newProgress)
            }
            GestureDirection.DOWN -> {
                // Progressive swipe down: full player -> app mini player
                // Compensate the easing on reversing direction
                val easingCompensation = if (delta < 0) {
                    1f + (downProgress * 2f)
                } else {
                    1f
                }

                val newProgress = (downProgress + (delta * easingCompensation) / (dragThresholdPx * DOWN_SWIPE_SENSITIVITY))
                    .coerceIn(0f, 1f)
                playerTransitionState.seekTo(targetState = false, fraction = newProgress)
            }
            null -> Unit
        }
    }

    fun onDragStopped(velocity: Float) = runExclusive {
        lockedDirection = null

        val upMidSeek = upTransitionState.currentState != upTransitionState.targetState
        if (upMidSeek) {
            // Resolve against whichever target the drag was actually heading toward —
            // true (opening the subview) or false (closing it back to the header drag) —
            // not always "true". See onDrag for why that distinction matters.
            val upTarget = upTransitionState.targetState
            val progressTowardUpTarget = if (upTarget) upTransitionState.progress() else 1f - upTransitionState.progress()
            if (progressTowardUpTarget > UP_SWIPE_MIN_PROGRESS) {
                // A fast flick continuing in the same direction as upTarget commits;
                // velocity's sign convention flips depending on which way we're headed.
                val signedVelocity = if (upTarget) velocity else -velocity
                val commit = progressTowardUpTarget > UP_SWIPE_COMMIT_PROGRESS || signedVelocity < UP_SWIPE_COMMIT_VELOCITY
                if (commit) {
                    // Continuing toward the same targetState we've been seekTo-ing during the drag.
                    upTransitionState.animateTo(upTarget)
                } else {
                    // Reversing direction — settle the seek back to rest instead of animateTo(!upTarget).
                    upTransitionState.settleBackToRest()
                }
            } else {
                upTransitionState.settleBackToRest()
            }
        } else {
            val downProgress = 1f - playerTransitionState.progress()
            val commitToDismiss = downProgress > DOWN_SWIPE_COMMIT_PROGRESS || velocity > DOWN_SWIPE_COMMIT_VELOCITY
            when {
                commitToDismiss -> onDismiss()
                else -> playerTransitionState.settleBackToRest()
            }
        }
    }

    fun collapseToMainPlayer() = runExclusive { upTransitionState.animateTo(false) }

    fun expandToSubView() = runExclusive { upTransitionState.animateTo(true) }

    companion object {
        private const val UP_SWIPE_SENSITIVITY = 0.8f
        private const val UP_SWIPE_MIN_PROGRESS = 0.01f
        private const val UP_SWIPE_COMMIT_PROGRESS = 0.4f
        private const val UP_SWIPE_COMMIT_VELOCITY = -500f
        private const val DOWN_SWIPE_SENSITIVITY = 1.2f
        private const val DOWN_SWIPE_COMMIT_PROGRESS = 0.15f
        private const val DOWN_SWIPE_COMMIT_VELOCITY = 800f
        private const val SETTLE_DURATION_MS = 250
    }
}