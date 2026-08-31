package com.foamplayer.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Duration of the seek bar's "settle" tween (drag release, track change, restart). */
const val SEEK_BAR_SETTLE_TWEEN_MS = 150

/** Default position, in ms, past which pressing Previous restarts the current track. */
const val SEEK_BAR_RESTART_THRESHOLD_MS = 5_000L

/** What the Previous button should actually do, decided by [SeekBarProgressState.onPreviousPressed]. */
enum class PreviousAction {
    /** Restart the current track from 0 -- do not change song. */
    RESTART_CURRENT,

    /** Go to the actual previous track in the queue. */
    SKIP_TO_PREVIOUS
}

/**
 * Drives the visual (0f..1f) progress shown by a seek bar (FullPlayerScreen's
 * PlaybackProgressSlider, MiniPlayer's FoamSlider). Both screens compute the same
 * "live" progress -- position/duration, scaled down while their album art pager is
 * mid-swipe so the bar recedes as you swipe toward the next/previous track -- and
 * that live-follow behavior is untouched here; it's exactly what you already had and
 * still happens on every frame regardless of mode.
 *
 * What this class adds is the "settle" behavior once a swipe/press *commits* a track
 * change (or a manual seek drag ends). Both modes react to the same moment -- a pager
 * swipe *committing* to a new track, e.g. `PagerState.targetPage` diverging from the
 * actual playing index, which happens right as you release, well before the pager's
 * own fling-to-settle animation has visually finished:
 *
 *  - **Animated**: [onTrackChangeCommitted] with `animated = true` explicitly tweens
 *    the bar the rest of the way down to 0 -- it doesn't rely on `liveProgress`'s
 *    passive pager-offset scaling to coast there on its own, which can look
 *    inconsistent since it's tied to however long the pager's own fling happens to
 *    take. Next/Previous tween the same way.
 *  - **Non-animated**: [onTrackChangeCommitted] with `animated = false` snaps straight
 *    to 0. `liveProgress` doesn't scale with the pager's offset in this mode (no
 *    live-follow), so it would otherwise keep reporting the *old* track's real
 *    position for as long as the drag/fling is still in progress. Next/Previous snap
 *    instantly too -- *except* Previous restarting the current track (position past
 *    [SEEK_BAR_RESTART_THRESHOLD_MS]), which is always tweened even in non-animated
 *    mode so a restart visually reads as "restart", not as a snap to a different track.
 *
 * In both modes, [onTrackChangeCommitted] *holds* the bar at 0 rather than releasing
 * it once the tween/snap finishes -- call [onTrackChangeApplied] once the pager has
 * actually landed and the real track change has taken effect to release that hold.
 * Without the hold, if the pager's own settle animation is still running once our
 * tween/snap finishes, control would hand back to `liveProgress` while it's still
 * mid-flight (still influenced by the in-progress pager offset, or in non-animated
 * mode still reporting the old track's stale position), and the bar would visibly
 * jump away from 0 before settling back down again.
 *
 * Usage:
 *  - Pass `liveProgress` in [rememberSeekBarProgressState]: the same 0f..1f
 *    computation you already had.
 *  - Render `seekBarProgress.progress` instead of the old locally-computed value.
 *  - Call [onDragging] from the slider's onValueChange (before forwarding the seek),
 *    [onSeekDragFinished] from the [onSeekBarReleased] modifier (FoamSlider has no
 *    onValueChangeFinished hook, so release is detected separately -- see below),
 *    [onNextPressed] / [onPreviousPressed] from the Next/Previous button click
 *    handlers, [onTrackChangeCommitted] as soon as a pager swipe commits to a track
 *    change, and [onTrackChangeApplied] once that settle has actually landed and the
 *    real track change has taken effect.
 */
@Stable
class SeekBarProgressState internal constructor(
    private val scope: CoroutineScope,
    private val liveProgress: () -> Float
) {
    private val overrideAnimatable = Animatable(0f)
    private var overrideJob: Job? = null
    private var overrideActive by mutableStateOf(false)

    /** What the slider should actually render this frame. */
    val progress: Float
        get() = if (overrideActive) overrideAnimatable.value else liveProgress()

    /**
     * Call this at the top of the slider's onValueChange, before forwarding the new
     * value to the actual seek -- cancels any settle tween in flight so a fresh manual
     * drag always takes over immediately, even mid-animation.
     */
    fun onDragging() {
        overrideJob?.cancel()
        overrideActive = false
    }

    /** Call once the seek bar's thumb is released (see [onSeekBarReleased]). */
    fun onSeekDragFinished(animated: Boolean, releasedAt: Float) {
        if (!animated) return
        settle(from = releasedAt, target = null, animated = true)
    }

    /**
     * Call as soon as a pager swipe *commits* to a new track -- e.g. as soon as
     * `PagerState.targetPage` diverges from the actually-playing index -- which is
     * right as you release, well before the pager's own fling-to-settle animation has
     * visually finished.
     *
     * `animated` picks a tween or an instant snap down to 0, but either way the bar
     * *holds* at 0 afterwards rather than releasing -- see the class doc for why a
     * plain tween/snap isn't enough here. Call [onTrackChangeApplied] once the pager
     * has actually landed to release the hold.
     *
     * If a previous commit is still holding when a new one comes in (e.g. rapid
     * swiping through several tracks before the last one's [onTrackChangeApplied]
     * arrived), don't re-snap to the *live* progress -- that would yank the bar back
     * to wherever `liveProgress` is now (a different, newer track) before tweening it
     * back down again, a visible double-jump. Just carry on animating from wherever
     * the bar currently sits (mid-tween or already-settled-at-0, either way) down to
     * 0 -- in animated mode that's always a tween, never a hard snap.
     */
    fun onTrackChangeCommitted(animated: Boolean) {
        val alreadyLocked = overrideActive
        overrideJob?.cancel()
        overrideJob = scope.launch {
            if (animated) {
                if (!alreadyLocked) {
                    overrideAnimatable.snapTo(progress)
                    overrideActive = true
                }
                // No `overrideActive = false` at the end -- we hold at 0 rather than
                // handing back to `liveProgress`, which may well still be mid-flight
                // (still pager-offset-scaled, or in non-animated mode still stale)
                // until [onTrackChangeApplied] says the real change has landed.
                overrideAnimatable.animateTo(0f, tween((progress * SEEK_BAR_SETTLE_TWEEN_MS).toInt()))
            } else {
                overrideAnimatable.snapTo(0f)
                overrideActive = true
            }
        }
    }

    /**
     * Call once the pager has actually landed and the real track change (new
     * position/duration/queue index) has taken effect. Releases the hold from
     * [onTrackChangeCommitted] -- safe now, since `liveProgress` will already read as
     * (or essentially as) 0 for the new track.
     */
    fun onTrackChangeApplied() {
        overrideJob?.cancel()
        overrideActive = false
    }

    /** Call from the Next button's click handler. */
    fun onNextPressed(animated: Boolean) {
        settle(from = null, target = 0f, animated = animated)
    }

    /**
     * Call from the Previous button's click handler with the track's current
     * position. Returns which action the caller should actually perform (restart the
     * current track vs. skip to the real previous one) -- this class only owns the
     * bar's visuals, not playback, so the caller is responsible for acting on it.
     */
    fun onPreviousPressed(
        animated: Boolean,
        positionMs: Long,
        restartThresholdMs: Long = SEEK_BAR_RESTART_THRESHOLD_MS
    ): PreviousAction {
        val restart = positionMs > restartThresholdMs
        // A restart always tweens -- even in non-animated mode -- so it reads as
        // "current track restarting" rather than an instant jump to a new track.
        settle(from = null, target = 0f, animated = animated || restart)
        return if (restart) PreviousAction.RESTART_CURRENT else PreviousAction.SKIP_TO_PREVIOUS
    }

    private fun settle(from: Float?, target: Float?, animated: Boolean) {
        // If a settle is already animating when a new one comes in (Next/Previous
        // mashed quickly), don't stack another tween on top of it -- cut straight to
        // the resting value instead of restarting a fresh 320ms tween each time.
        val alreadySettling = overrideActive
        overrideJob?.cancel()
        if (!animated || alreadySettling) {
            overrideActive = false
            return
        }
        overrideJob = scope.launch {
            overrideAnimatable.snapTo(from ?: progress)
            overrideActive = true
            val destination = target ?: liveProgress()
            // Animatable.value is itself snapshot-state-backed, so every frame this
            // advances, readers of `progress` (via overrideAnimatable.value above)
            // recompose on their own -- no per-frame callback needed here.
            overrideAnimatable.animateTo(destination, tween((progress * SEEK_BAR_SETTLE_TWEEN_MS).toInt()))
            overrideActive = false
        }
    }
}

/**
 * Creates and remembers a [SeekBarProgressState]. [liveProgress] is read lazily every
 * time [SeekBarProgressState.progress] is accessed, so capture whatever state reads
 * (position, duration, pager offset) you need inside the lambda -- it doesn't need to
 * be `remember`-stable itself.
 */
@Composable
fun rememberSeekBarProgressState(liveProgress: () -> Float): SeekBarProgressState {
    val scope = rememberCoroutineScope()
    return remember { SeekBarProgressState(scope, liveProgress) }
}

/**
 * FoamSlider has no onValueChangeFinished -- it drives everything from its own
 * internal `pointerInput { awaitFirstDown(); ...; drag(...) }`, which consumes the
 * down event and every drag change itself. This modifier is meant to be chained onto
 * FoamSlider's `modifier` parameter (i.e. it becomes part of the *same* node, ahead of
 * FoamSlider's own pointerInput in the chain) and watches the pointer stream at
 * [PointerEventPass.Initial] -- the pass that runs top-down *before* any descendant
 * gets to consume anything -- so it sees every down/up regardless of what FoamSlider
 * itself consumes, without stealing or altering the gesture FoamSlider is handling.
 *
 * Usage: `FoamSlider(..., modifier = Modifier.onSeekBarReleased { seekBarProgress.onSeekDragFinished(...) })`.
 */
@Composable
fun Modifier.onSeekBarReleased(onReleased: () -> Unit): Modifier {
    val latestOnReleased by rememberUpdatedState(onReleased)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            latestOnReleased()
        }
    }
}