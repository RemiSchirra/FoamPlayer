package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val SETTLE_DURATION_MS = 250

/**
 * Cancels an in-flight seek without ever calling animateTo() on the opposite state. Keeps
 * the current targetState fixed and eases [SeekableTransitionState.fraction] from wherever
 * it is back down to 0, so currentState is never touched and there's nothing for
 * SeekableTransitionState to "resolve" before animating -- see [PlayerGestureController]'s
 * class doc for the underlying glitch this avoids.
 *
 * Shared (rather than private to one controller) because both halves of the mini <-> full
 * player swipe -- [PlayerGestureController]'s swipe-down-to-dismiss and
 * [MiniPlayerSwipeController]'s swipe-up-to-open -- spring back the same way when a drag
 * doesn't cross its commit threshold.
 *
 * Easing fraction to 0 alone isn't enough to fully settle things: targetState is left
 * pointing at restingTarget even though fraction says we're visually back at currentState.
 * Transition treats currentState != targetState as "still transitioning" regardless of
 * fraction, so it keeps *both* AnimatedVisibility branches composed -- the extra, stale
 * branch is what shows up as a duplicate album art image on the next real transition. The
 * closing snapTo(restState) collapses targetState back onto currentState (a visual no-op,
 * since fraction is already 0) so the Transition actually settles.
 */
internal suspend fun SeekableTransitionState<Boolean>.settleBackToRest(
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
    // Dismiss swipe mapping: the finger travels the same distance the artwork itself
    // travels (full player hero -> mini player art, or -> the origin collection's
    // row), so the morph tracks the finger 1:1. The provider reads live geometry
    // (hero art center, current flight target) and falls back to the screen height.
    private val dismissSwipeDistancePx: () -> Float,
    // Subview (header <-> Lyrics/Queue) open/close swipe. This only ever needs to
    // travel a small fraction of the screen -- reusing a screen-sized threshold here
    // made a small, natural upward flick barely register any progress, since the drag
    // would need to cover most of the screen height before crossing the commit
    // threshold. Sized independently by the caller to a fixed, device-agnostic
    // distance instead.
    private val upDragThresholdPx: Float,
    private val onDismiss: (velocityPxPerSec: Float) -> Unit,
    // Dismiss-to-origin-collection support: [onDismissGestureStart] is the PRESS
    // PRELOAD (finger down: compose the returning page invisibly and scroll the row
    // into view, WITHOUT pairing it -- a rest-state pairing would hijack the hero art
    // and break pager swipes). [onDismissGestureActivate] runs on the first delta of a
    // down-stroke: it tags the row, letting the shared element pair, and gates the
    // drag until the match is configured. [onDismissGestureCancelled] lets the host
    // drop the row tag when the gesture settles back without committing. Nulls keep
    // the plain mini-player dismissal.
    private val onDismissGestureStart: (suspend () -> Unit)? = null,
    private val onDismissGestureActivate: (suspend () -> Unit)? = null,
    private val onDismissGestureCancelled: (() -> Unit)? = null
) {
    private var activeGestureJob: Job? = null
    private var lockedDirection: GestureDirection? = null

    // The dismiss preparation runs OUTSIDE the per-delta [activeGestureJob]:
    // drag deltas keep arriving while it frames by, and each one would cancel it
    // mid-flight. Instead it gets its own job, and deltas simply hold the drag at
    // zero progress until it finishes (see the DOWN branch). The same job slot serves
    // the press preload first and the drag-start activation second.
    private var dismissSetupJob: Job? = null
    private var dismissSetupActive = false

    // One preparation attempt per down-stroke: every delta is gated while the setup
    // runs, so progress is still 0 right after it finishes -- without this flag the
    // next delta would start the setup all over again and the drag would never move.
    private var dismissSetupAttempted = false

    // Which phase the running [dismissSetupJob] is in: the press preload may still be
    // composing/scrolling when the first drag delta arrives, in which case the
    // activation CHAINS after it instead of starting a competing job.
    private enum class DismissSetupPhase { None, Preload, Activate }

    private var dismissSetupPhase = DismissSetupPhase.None

    // Set when a dismissal is actually committed; guards the press-end revert below
    // against snapping the freshly launched close flight back to rest.
    private var dismissFlightStarted = false

    // When the current down-stroke first advanced the flight; anchors the in-gesture
    // rate cap to ELAPSED STROKE TIME (see the DOWN branch).
    private var downStrokeStartTimeNanos = 0L

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

                val newProgress = (progressTowardUpTarget + (signedDelta * easingCompensation) / (upDragThresholdPx * UP_SWIPE_SENSITIVITY))
                    .coerceIn(0f, 1f)
                upTransitionState.seekTo(targetState = upTarget, fraction = newProgress)
            }
            GestureDirection.DOWN -> {
                // First delta of a fresh down-stroke from full rest: tag the row and
                // wait for the shared element to pair before anything moves. The press
                // preload already composed the page and scrolled the row into view;
                // this activation only attaches the pairing.
                if (downProgress == 0f &&
                    playerTransitionState.currentState &&
                    onDismissGestureActivate != null &&
                    !dismissSetupAttempted
                ) {
                    dismissSetupAttempted = true
                    beginDismissActivation()
                }
                // Hold at zero progress while the pairing is configured: seeking now
                // would match the shared element against an out-of-view (or absent)
                // row, popping the art mid-gesture when the scroll lands.
                if (dismissSetupActive) return@runExclusive

                // Progressive swipe down, 1:1 with the artwork's own travel distance.
                // Compensate the easing on reversing direction
                val easingCompensation = if (delta < 0) {
                    1f + (downProgress * 2f)
                } else {
                    1f
                }

                val swipeDistancePx = dismissSwipeDistancePx().coerceAtLeast(1f)
                val rawProgress = downProgress + (delta * easingCompensation) / swipeDistancePx
                val newProgress = if (rawProgress <= downProgress) {
                    // Reversing (cancelling) is never rate-limited.
                    rawProgress.coerceIn(0f, 1f)
                } else {
                    // Cap the flight's in-gesture progress by ELAPSED STROKE TIME: a
                    // fast flick must not complete the whole morph inside the gesture
                    // (which reads as an instant snap), and movement accumulated while
                    // deltas were gated must not land as a single uncapped jump.
                    // Beyond the cap the art lags the finger slightly and the release
                    // animates the rest over a proper duration.
                    val now = System.nanoTime()
                    if (downStrokeStartTimeNanos == 0L) downStrokeStartTimeNanos = now
                    val elapsedMs = (now - downStrokeStartTimeNanos) / 1_000_000f
                    val maxProgress = (elapsedMs / MIN_INTERACTIVE_FLIGHT_MS).coerceIn(0f, 1f)
                    (downProgress + (rawProgress - downProgress)
                        .coerceAtMost((maxProgress - downProgress).coerceAtLeast(0f)))
                        .coerceIn(0f, 1f)
                }
                playerTransitionState.seekTo(targetState = false, fraction = newProgress)
            }
            null -> Unit
        }
    }

    private fun beginDismissSetup() {
        val onStart = onDismissGestureStart ?: return
        if (dismissSetupJob?.isActive == true) return
        dismissSetupActive = true
        dismissSetupPhase = DismissSetupPhase.Preload
        dismissSetupJob = scope.launch {
            try {
                onStart()
            } finally {
                // Only this phase owns the gate release: an activation may be running
                // concurrently (its pairing defers to the scroll via the host's
                // centering gate), and its finally must be the one to lift the gate.
                if (dismissSetupPhase == DismissSetupPhase.Preload) {
                    dismissSetupActive = false
                    dismissSetupPhase = DismissSetupPhase.None
                }
            }
        }
    }

    private fun beginDismissActivation() {
        val onActivate = onDismissGestureActivate ?: return
        if (dismissSetupJob?.isActive == true && dismissSetupPhase == DismissSetupPhase.Activate) return
        dismissSetupActive = true
        dismissSetupPhase = DismissSetupPhase.Activate
        dismissSetupJob = scope.launch {
            try {
                // Runs CONCURRENTLY with a still-running press preload: the pairing
                // is ordered by the host's centering gate (the row only pairs once
                // the scroll has landed), so waiting here would just add latency.
                onActivate()
            } finally {
                dismissSetupActive = false
                dismissSetupPhase = DismissSetupPhase.None
            }
        }
    }

    /**
     * Finger down on the full player: preload the dismiss-to-row flight (compose the
     * returning page invisibly, scroll the playing row into view, pair the shared
     * element) while the finger is still stationary, so the drag responds from its
     * first pixel instead of after the preparation's frames. The preload's seek only
     * flips the transition's target at zero fraction -- a visual no-op -- and
     * [onDismissPressEnded] collapses it back if no dismissal materialized.
     */
    fun onDismissPressStarted() {
        if (onDismissGestureStart == null) return
        // Only at full rest: a running flight or an already-seeking state must not
        // be touched by a mere press.
        if (!(playerTransitionState.currentState && playerTransitionState.targetState)) return
        dismissFlightStarted = false
        downStrokeStartTimeNanos = 0L
        beginDismissSetup()
    }

    /**
     * Gesture over (pointers up or cancelled) without a drag stroke: undo the press
     * preload -- cancel any still-running setup and collapse the preload's
     * zero-fraction seek back to rest so no invisible branch state lingers. When a
     * drag stroke DID happen (direction locked), [onDragStopped] owns the outcome --
     * commit, settle or subview -- and runs right after this on the same pointer-up;
     * touching the state here would race it.
     */
    fun onDismissPressEnded() {
        if (lockedDirection != null) {
            // A drag stroke happened; onDragStopped handles commit/settle/cleanup.
            return
        }
        scope.launch {
            if (dismissSetupJob?.isActive == true) {
                dismissSetupJob?.cancel()
                dismissSetupJob = null
            }
            collapsePressPreload()
        }
    }

    /**
     * Collapses a press-preload seek that never turned into a dismissal: targetState
     * points at "closed" while fraction is still 0 -- a visual no-op to undo (it just
     * collapses targetState onto currentState so the resting transition can settle),
     * but leaving it dangling would keep both AnimatedVisibility branches composed,
     * including an invisible, still-hittable mini player.
     */
    private suspend fun collapsePressPreload() {
        val state = playerTransitionState
        if (!dismissFlightStarted &&
            state.currentState &&
            !state.targetState &&
            state.fraction <= 0f
        ) {
            state.snapTo(true)
            onDismissGestureCancelled?.invoke()
        }
    }

    fun onDragStopped(velocity: Float) = runExclusive {
        lockedDirection = null
        dismissSetupAttempted = false
        downStrokeStartTimeNanos = 0L

        val upMidSeek = upTransitionState.currentState != upTransitionState.targetState
        if (upMidSeek) {
            val upTarget = upTransitionState.targetState
            val progressTowardUpTarget = if (upTarget) upTransitionState.progress() else 1f - upTransitionState.progress()
            if (progressTowardUpTarget > UP_SWIPE_MIN_PROGRESS) {
                val signedVelocity = if (upTarget) velocity else -velocity

                // Prevent committing if you actively flick your finger back in the opposite direction
                val isReverseFlick = signedVelocity > 500f

                val commit = !isReverseFlick && (progressTowardUpTarget > UP_SWIPE_COMMIT_PROGRESS || signedVelocity < UP_SWIPE_COMMIT_VELOCITY)
                if (commit) {
                    upTransitionState.animateTo(upTarget)
                } else {
                    upTransitionState.settleBackToRest()
                }
            } else {
                upTransitionState.settleBackToRest()
            }
            // An up-stroke never turned into a dismissal: collapse the press
            // preload's dangling zero-fraction seek, if any (visual no-op).
            collapsePressPreload()
        } else {
            // Serialize behind the dismiss-to-row setup: whatever happens next -- the
            // close flight, or a snap back to rest -- must not run while the row target
            // is still being put in place.
            dismissSetupJob?.join()

            val downProgress = 1f - playerTransitionState.progress()

            // Prevent dismissing if you actively flick the player back up to cancel
            val isReverseFlick = velocity < -500f

            val commitToDismiss = !isReverseFlick && (downProgress > DOWN_SWIPE_COMMIT_PROGRESS || velocity > DownSwipeCommitVelocityPxPerSec)
            when {
                commitToDismiss -> {
                    // Guards onDismissPressEnded's revert: a committed dismissal may
                    // still be at fraction 0 on its first frame when the pointer-up
                    // reaches the press observer. The velocity rides along so the
                    // close flight can guarantee an animated duration for flicks.
                    dismissFlightStarted = true
                    onDismiss(velocity)
                }
                else -> {
                    playerTransitionState.settleBackToRest()
                    onDismissGestureCancelled?.invoke()
                }
            }
        }
    }

    fun collapseToMainPlayer() = runExclusive { upTransitionState.animateTo(false) }

    fun expandToSubView() = runExclusive { upTransitionState.animateTo(true) }

    companion object {
        private const val UP_SWIPE_SENSITIVITY = 0.8f
        private const val UP_SWIPE_MIN_PROGRESS = 0.01f
        private const val UP_SWIPE_COMMIT_PROGRESS = 0.25f
        private const val UP_SWIPE_COMMIT_VELOCITY = -500f
        private const val DOWN_SWIPE_COMMIT_PROGRESS = 0.15f

        /**
         * Fastest the drag alone may complete the dismiss flight, in ms. Finger
         * movement faster than this is rate-limited so a flick always leaves a
         * visible, animated remainder for the release to fly.
         */
        private const val MIN_INTERACTIVE_FLIGHT_MS = 200f
    }
}

/**
 * Drives [playerTransitionState] for the mini player's swipe-up-to-open gesture -- the
 * mirror image of the swipe-down-to-dismiss [PlayerGestureController] handles once the full
 * player is on screen.
 *
 * This has to be a separate controller rather than another branch on
 * [PlayerGestureController]: that controller is only constructed inside [FullPlayerScreen],
 * which itself only enters composition once [playerTransitionState] starts moving toward
 * open. A swipe-up needs to react to the very first pixel of the drag, while the mini player
 * is still the only thing on screen, so it needs its own gesture handling that lives
 * alongside the mini player instead.
 *
 * Reuses [settleBackToRest] for the same "released before threshold" glitch described on
 * [PlayerGestureController], and the same [runExclusive]-per-job pattern for the same
 * "release lands after the animation already started" glitch.
 */
class MiniPlayerSwipeController(
    private val playerTransitionState: SeekableTransitionState<Boolean>,
    private val scope: CoroutineScope,
    private val dragThresholdPx: Float,
    private val onOpen: () -> Unit
) {
    private var activeGestureJob: Job? = null

    private fun runExclusive(block: suspend () -> Unit) {
        activeGestureJob?.cancel()
        activeGestureJob = scope.launch { block() }
    }

    fun onDrag(delta: Float) = runExclusive {
        val openProgress = playerTransitionState.progress()

        // Already fully open/opening (e.g. a tap-triggered animateTo is running) -- once the
        // full player itself is composed it takes over via PlayerGestureController, so there's
        // nothing left for this drag to do.
        if (playerTransitionState.currentState) return@runExclusive

        // At rest and swiping further down: nothing to open, ignore.
        if (openProgress == 0f && delta >= 0f) return@runExclusive

        val signedDelta = -delta // up = negative delta = increasing open progress

        // Compensate the easing on reversing direction
        val easingCompensation = if (signedDelta < 0) {
            1f + (openProgress * 2f)
        } else {
            1f
        }

        val newProgress = (openProgress + (signedDelta * easingCompensation) / (dragThresholdPx * OPEN_SWIPE_SENSITIVITY))
            .coerceIn(0f, 1f)
        playerTransitionState.seekTo(targetState = true, fraction = newProgress)
    }

    fun onDragStopped(velocity: Float) = runExclusive {
        val openProgress = playerTransitionState.progress()
        if (openProgress <= 0f || playerTransitionState.currentState) return@runExclusive

        // Prevent opening if you actively flick your finger back down to cancel
        val isReverseFlick = velocity > 500f

        val commit = !isReverseFlick && (openProgress > OPEN_SWIPE_COMMIT_PROGRESS || velocity < OPEN_SWIPE_COMMIT_VELOCITY)
        if (commit) {
            onOpen()
        } else {
            playerTransitionState.settleBackToRest()
        }
    }

    companion object {
        private const val OPEN_SWIPE_SENSITIVITY = 1.2f
        private const val OPEN_SWIPE_COMMIT_PROGRESS = 0.15f
        private const val OPEN_SWIPE_COMMIT_VELOCITY = -800f
    }
}