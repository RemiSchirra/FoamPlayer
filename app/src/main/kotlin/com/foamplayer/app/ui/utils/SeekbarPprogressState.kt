package com.foamplayer.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Duration of the seek bar's "settle" tween (drag release, track change, restart). */
const val SEEK_BAR_SETTLE_TWEEN_MS = 150

/**
 * How long a committed waveform envelope swap keeps holding for the playing track to
 * actually flip before giving up and falling back to the real playing track (a
 * committed skip the engine could not honor at all -- e.g. the queue shrank under
 * it). The flip normally lands within a couple of frames of the pager settle.
 */
private const val ENVELOPE_SAFETY_NET_MS = 1_000L

/**
 * Blend above which a re-aimed morph re-anchors on the OLD target's envelope instead
 * of carrying its from-envelope over: past halfway the bars are visually "arrived" at
 * the old target, so restarting from it makes the hand-off jump-free (a carried blend
 * would jump by blend * (oldTarget - newTarget)). Below it the from-envelope still
 * dominates the shape, so carrying the blend is the smaller jump.
 */
private const val ENVELOPE_REANCHOR_BLEND_THRESHOLD = 0.5f

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
 * mid-flight (still pager-offset-scaled, or in non-animated mode still reporting the
 * old track's stale position), and the bar would visibly jump away from 0 before
 * settling back down again.
 *
 * ## Waveform envelope morph
 *
 * The waveform seek bar's track-change envelope swap (WaveformSeekBar's
 * `targetWaveform`/`trackChangeProgress`) is coordinated here: this class owns the
 * swap's pinned pages so every driver renders the same morph --
 *
 *  - [envelopeTargetPage]: the queue page the bars are swapping TOWARD (null at
 *    rest). The host resolves that page's envelope through the process-wide
 *    WaveformCache and hands it to the waveform as the morph target.
 *  - [envelopeFromPage]: the queue page the bars are anchored on, when it is NOT the
 *    playing track (pinned at trigger time, carried across re-aims); null means
 *    "the playing track's own envelope".
 *  - [isEnvelopeTweenActive] / [envelopeTweenBlend]: a tween's blend -- used whenever
 *    a tween owns the swap (release, transport buttons, flip catch-ups).
 *
 * The blend DRIVER while the finger is down is the host's (FullPlayerScreen derives it
 * from the hero pager's fraction toward [envelopeTargetPage], so the bars follow the
 * swipe 1:1 -- that is the "progressive" behavior of the raw "animate seek bar" setting). At RELEASE the blend is handed over to a tween, the same takeover the
 * mask's settle performs: committing swipes tween on up to 1 from wherever the
 * fraction left the bars, spring-backs tween back down to 0
 * ([settleSwipeEnvelopeBack]) -- neither terminates instantly, and neither stays
 * hostage to however long the pager's own fling happens to take. Programmatic pager
 * syncs (no finger ever down) sweep the fraction formula directly, morphing like a
 * fast swipe.
 *
 * Lifecycle: a commit/button press tweens to blend 1 and HOLDS there -- the pager's
 * settle and the engine's track update trail it, and releasing early would pop the
 * bars back to the old envelope. [onPlayingIndexChanged] fires when the playing index
 * actually moved: onto the pinned target, the tween finishes and then clears (once
 * the flip has landed both morph ends render the new track's envelope, so the
 * hand-back is pixel-continuous at any blend); back onto the anchor page, the blend
 * unwinds down to 0; anywhere else (a fast chained swipe whose fling sailed past the
 * committed page), the morph re-aims at where the track actually landed -- anchored
 * on the old target when the bars had visually arrived there, carrying the blend
 * otherwise -- so chained changes stay animated instead of snapping. A safety net
 * stops holding if the flip never comes at all.
 *
 * Usage:
 *  - Pass `liveProgress` in [rememberSeekBarProgressState]: the same 0f..1f
 *    computation you already had.
 *  - Render `seekBarProgress.progress` instead of the old locally-computed value.
 *  - Anything that must NOT follow the bar's visual animation (the full player's
 *    elapsed-time label) reads [SeekBarProgressState.positionMs] instead -- the raw
 *    engine position, untouched by the settle tween and the pager-offset scaling.
 *  - Call [onDragging] from the slider's onValueChange (before forwarding the seek),
 *    [onSeekDragFinished] from the [onSeekBarReleased] modifier (FoamSlider has no
 *    onValueChangeFinished hook, so release is detected separately -- see below),
 *    [onNextPressed] / [onPreviousPressed] from the Next/Previous button click
 *    handlers, [onTrackChangeCommitted] as soon as a pager swipe commits to a track
 *    change, and [onTrackChangeApplied] once that settle has actually landed and the
 *    real track change has taken effect. In waveform mode, also pass the change's
 *    target page (`morphTargetPage`) and the playing page at trigger time
 *    (`fromPageIndex`) on the commit/button calls, drive the progressive latch from
 *    the pager (see FullPlayerScreen), and route every playing index change through
 *    [onPlayingIndexChanged].
 */
@Stable
class SeekBarProgressState internal constructor(
    private val scope: CoroutineScope,
    private val positionMsFlow: StateFlow<Long>,
    private val liveProgressState: State<(Long) -> Float>
) {
    private val overrideAnimatable = Animatable(0f)
    private var overrideJob: Job? = null
    private var overrideActive by mutableStateOf(false)

    // Waveform envelope swap (see the class doc). morphActive/morphDrivenByPager are
    // snapshot state because the host reads them in its draw-phase blend provider.
    private var morphAnimatable = Animatable(0f)
    private var morphJob: Job? = null
    private var morphActive by mutableStateOf(false)
    private var morphDrivenByPager by mutableStateOf(false)

    // Set once the host confirms the committed change has resolved (the playing track
    // moved onto the pinned index); an in-flight swap tween finishes first and then
    // clears, so the hand-back lands on a completed blend instead of popping
    // mid-tween. Flip-driven morphs (see onPlayingIndexChanged) set it immediately:
    // their flip already happened, so they are cosmetic catch-ups that self-clear.
    // Plain var: every reader/writer is main-thread.
    private var envelopeSettleRequested = false

    private var envelopeSafetyNetJob: Job? = null

    /** Queue page the envelope swap is heading to; null at rest. Snapshot-backed read. */
    var envelopeTargetPage: Int? by mutableStateOf(null)
        private set

    /** Queue page the bars are anchored on when it is not the playing track; null otherwise. */
    var envelopeFromPage: Int? by mutableStateOf(null)
        private set

    /** True while a tween owns the swap (a finger-driven blend is the host's pager fraction). */
    val isEnvelopeTweenActive: Boolean
        get() = morphActive && !morphDrivenByPager

    /**
     * The swap tween's 0f..1f blend (holds at 1 until the playing track flips).
     * Draw-phase read only: every frame invalidates just the waveform's drawing.
     */
    val envelopeTweenBlend: Float
        get() = morphAnimatable.value

    private val currentPositionState = mutableStateOf(positionMsFlow.value)

    init {
        scope.launch {
            positionMsFlow.collect {
                currentPositionState.value = it
            }
        }
    }

    private fun getLiveProgress(): Float = liveProgressState.value(currentPositionState.value)

    /** What the slider should actually render this frame. */
    val progress: Float
        get() = if (overrideActive) overrideAnimatable.value else getLiveProgress()

    /**
     * The engine position snapshot the live progress is computed from. Consumers that
     * must NOT follow the bar's visual animation -- the settle tween override, or
     * animated mode's pager-offset scaling -- read this instead of [progress]: the
     * full player's elapsed-time label, so the timer keeps ticking the actual
     * playback position while a track-change swipe recedes the bar and only jumps
     * when the track actually flips.
     */
    val positionMs: Long
        get() = currentPositionState.value

    /**
     * Call this at the top of the slider's onValueChange, before forwarding the new
     * value to the actual seek -- cancels any settle tween in flight so a fresh manual
     * drag always takes over immediately, even mid-animation. Also abandons any
     * in-flight envelope swap: the bars fall back to the current track's envelope (a
     * committed change that still lands hard-swaps there, exactly like the pre-morph
     * behavior).
     */
    fun onDragging() {
        overrideJob?.cancel()
        overrideActive = false
        stopTrackChangeMorph()
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
     *
     * `morphTargetPage` (the page the change is heading to) pins the waveform's
     * envelope swap target and takes the blend over as a tween from [fromBlend] --
     * whatever is currently rendered (the pager's fraction at release for a
     * progressive swipe, 0 for a button press) -- toward 1, in the same motion as the
     * mask's settle tween, holding until [onPlayingIndexChanged] reports the flip.
     * [fromPageIndex] (the playing page at trigger time) anchors the from-envelope so
     * the engine's later track flip cannot swap it underneath the tween. Releasing
     * therefore TERMINATES the morph with an animation instead of snapping the bars
     * or leaving them coasting on the pager's fling.
     */
    fun onTrackChangeCommitted(
        animated: Boolean,
        morphTargetPage: Int? = null,
        fromBlend: Float = 0f,
        fromPageIndex: Int? = null
    ) {
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
        if (morphTargetPage != null) {
            aimTrackChangeMorph(
                targetPage = morphTargetPage,
                fromBlend = fromBlend,
                fallbackFromPage = fromPageIndex
            )
        } else {
            stopTrackChangeMorph()
        }
    }

    /**
     * Call once the pager has actually landed and the real track change (new
     * position/duration/queue index) has taken effect. Releases the hold from
     * [onTrackChangeCommitted] -- safe now, since `liveProgress` will already read as
     * (or essentially as) 0 for the new track.
     *
     * Deliberately does NOT release the envelope swap: the queue/track update trails
     * this call (the skip runs right after it), and releasing now would pop the bars
     * back to the old envelope for that gap. [onPlayingIndexChanged] settles the swap
     * when the flip actually lands; a safety net stops holding if it never comes.
     */
    fun onTrackChangeApplied() {
        overrideJob?.cancel()
        overrideActive = false
        scheduleEnvelopeSafetyNet()
    }

    /**
     * Call from the Next button's click handler. The envelope swap tweens from the
     * currently-rendered blend ([fromBlend]) toward [morphTargetPage]'s envelope,
     * anchored on [fromPageIndex] (the playing page at press time) so the engine's
     * later flip cannot swap the from-envelope underneath it. See
     * [onTrackChangeCommitted] for the rest of the morph parameters.
     */
    fun onNextPressed(
        animated: Boolean,
        morphTargetPage: Int? = null,
        fromBlend: Float = 0f,
        fromPageIndex: Int? = null
    ) {
        settle(from = null, target = 0f, animated = animated)
        if (morphTargetPage != null) {
            aimTrackChangeMorph(morphTargetPage, fromBlend, fromPageIndex)
        } else {
            stopTrackChangeMorph()
        }
    }

    /**
     * Call from the Previous button's click handler with the track's current
     * position. Returns which action the caller should actually perform (restart the
     * current track vs. skip to the real previous one) -- this class only owns the
     * bar's visuals, not playback, so the caller is responsible for acting on it.
     *
     * A restart doesn't change tracks, so [morphTargetPage] only drives the envelope
     * swap on a real skip (a restart clears any leftover swap). See
     * [onNextPressed] for the morph parameters.
     */
    fun onPreviousPressed(
        animated: Boolean,
        positionMs: Long,
        morphTargetPage: Int? = null,
        fromBlend: Float = 0f,
        fromPageIndex: Int? = null,
        restartThresholdMs: Long = SEEK_BAR_RESTART_THRESHOLD_MS
    ): PreviousAction {
        val restart = positionMs > restartThresholdMs
        // A restart always tweens -- even in non-animated mode -- so it reads as
        // "current track restarting" rather than an instant jump to a new track.
        settle(from = null, target = 0f, animated = animated || restart)
        if (!restart && morphTargetPage != null) {
            aimTrackChangeMorph(morphTargetPage, fromBlend, fromPageIndex)
        } else {
            stopTrackChangeMorph()
        }
        return if (restart) PreviousAction.RESTART_CURRENT else PreviousAction.SKIP_TO_PREVIOUS
    }

    /**
     * Progressive latch (raw "animate seek bar" setting): pins the envelope the bars
     * are morphing toward while a hero-pager GESTURE is carrying them away, deriving
     * nothing itself -- the host computes the blend from the pager's fraction between
     * [envelopeFromPage] (null = the playing track) and [targetPage].
     *
     * The host calls this as the gesture resolves a direction, and again if it
     * reverses through the playing page or chains past the previously pinned page;
     * [fromPage] carries the previous pinned envelope forward when the bars are
     * currently resting near it (keeping the morph continuous at the re-pin), or null
     * to re-anchor on the playing track's envelope (when crossing back through it).
     * A swap tween still RUNNING is never hijacked -- it owns the bars until it
     * finishes; a tween that already finished (holding at 1) is: the grab re-anchors
     * on the envelope the bars are resting on, so the fraction takes over
     * pixel-continuously from there.
     */
    fun onSwipeEnvelopeTarget(targetPage: Int, fromPage: Int?) {
        if (morphActive && !morphDrivenByPager && morphJob?.isActive == true) return
        if (morphActive && envelopeTargetPage == targetPage) return
        pinSwipeMorph(targetPage, fromPage)
    }

    /**
     * Terminates a finger-driven envelope swap with an animation: the host calls it
     * when a hero-pager gesture has ended WITHOUT committing (the pager is settling
     * back onto the playing page), handing over the currently-rendered blend
     * ([fromBlend] -- the pager fraction at release). The bars tween back down to the
     * playing track's envelope instead of snapping there, and the pinned pages stay
     * until the unwind completes so the "from" envelope keeps rendering throughout.
     * A swap tween in flight is never hijacked -- it is already animating somewhere.
     */
    fun settleSwipeEnvelopeBack(fromBlend: Float) {
        if (!morphActive || !morphDrivenByPager) return
        unwindTrackChangeMorph(fromBlend)
    }

    /**
     * Called by the host on every playing-index change (the flip of a committed
     * change, a natural end-of-track advance, an external skip while the player is
     * open):
     *
     *  - a swap in flight whose pinned target is where the playing track LANDED
     *    resolves: a still-animating tween finishes first and then clears (both morph
     *    ends already render the new track's envelope, so the hand-back is
     *    pixel-continuous at any blend).
     *  - a swap in flight that lands back on its anchored page unwinds to blend 0 --
     *    the bars animate from wherever they were back onto that page's envelope.
     *  - a swap in flight landing anywhere else (a fast chained swipe whose fling
     *    sailed past the committed page) re-aims at the landed page: anchored on the
     *    old target's envelope when the bars had visually arrived there (blend past
     *    [ENVELOPE_REANCHOR_BLEND_THRESHOLD] -- a zero-jump hand-off), carrying the
     *    current blend otherwise.
     *  - no swap in flight and the index moved by one page: the change was unprompted
     *    as far as the bar is concerned (natural advance, queue-screen pick while
     *    open) -- give it a catch-up morph from the previously playing track's
     *    envelope to the new one. Far jumps are left alone: their "from" envelope
     *    would have to be resolved by a page index a queue replacement may have
     *    already invalidated.
     *
     * The flip-driven branches (all but the resolve) start morphs whose flip already
     * happened, so they self-clear at tween end instead of holding.
     */
    fun onPlayingIndexChanged(newIndex: Int, previousIndex: Int?) {
        if (morphActive) {
            val targetPage = envelopeTargetPage
            val fromPage = envelopeFromPage
            when {
                targetPage == newIndex -> {
                    envelopeSettleRequested = true
                    envelopeSafetyNetJob?.cancel()
                    envelopeSafetyNetJob = null
                    if (morphJob?.isActive != true) stopTrackChangeMorph()
                }
                fromPage != null && newIndex == fromPage -> {
                    // Landed back where the bars are anchored: unwind what's left of
                    // the swap instead of re-aiming past it.
                    unwindTrackChangeMorph(morphAnimatable.value)
                }
                targetPage != null -> {
                    val blend = morphAnimatable.value
                    if (blend > ENVELOPE_REANCHOR_BLEND_THRESHOLD) {
                        startTrackChangeMorph(newIndex, fromBlend = 0f, fromPage = targetPage)
                    } else {
                        startTrackChangeMorph(newIndex, fromBlend = blend, fromPage = fromPage)
                    }
                    envelopeSettleRequested = true
                }
                else -> stopTrackChangeMorph()
            }
            return
        }
        if (previousIndex != null && abs(previousIndex - newIndex) == 1) {
            startTrackChangeMorph(newIndex, fromBlend = 0f, fromPage = previousIndex)
            envelopeSettleRequested = true
        }
    }

    /**
     * Called by the host when the pager settled back onto the playing page without a
     * change having landed (a re-grabbed fling pulled back). A finger-driven swap has
     * already been unwound by [settleSwipeEnvelopeBack]; a swap tween animates the
     * bars back down to the playing track's envelope and clears.
     */
    fun revertTrackChangeEnvelope() {
        if (!morphActive || morphDrivenByPager) return
        unwindTrackChangeMorph(morphAnimatable.value)
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
            val destination = target ?: getLiveProgress()
            // Animatable.value is itself snapshot-state-backed, so every frame this
            // advances, readers of `progress` (via overrideAnimatable.value above)
            // recompose on their own -- no per-frame callback needed here.
            overrideAnimatable.animateTo(destination, tween((progress * SEEK_BAR_SETTLE_TWEEN_MS).toInt()))
            overrideActive = false
        }
    }

    /**
     * Aims the envelope swap at [targetPage], keeping the rendered shape continuous
     * across the re-aim: when a swap is already in flight toward a DIFFERENT page and
     * its blend has passed [ENVELOPE_REANCHOR_BLEND_THRESHOLD], the bars are visually
     * "arrived" at the old target's envelope -- re-anchor on it (zero-jump hand-off)
     * and tween to the new target. Otherwise carry the current blend over with the
     * current anchor ([fallbackFromPage] pins the trigger-time playing page when no
     * anchor exists yet, so the engine's later flip cannot swap the from-envelope
     * underneath the tween).
     */
    private fun aimTrackChangeMorph(targetPage: Int, fromBlend: Float, fallbackFromPage: Int?) {
        val blend = fromBlend.coerceIn(0f, 1f)
        if (morphActive && envelopeTargetPage != targetPage && blend > ENVELOPE_REANCHOR_BLEND_THRESHOLD) {
            startTrackChangeMorph(targetPage, fromBlend = 0f, fromPage = envelopeTargetPage)
        } else {
            startTrackChangeMorph(
                targetPage = targetPage,
                fromBlend = blend,
                fromPage = envelopeFromPage ?: fallbackFromPage
            )
        }
    }

    /**
     * Starts (or restarts) the swap tween toward [targetPage], starting from
     * [fromBlend] -- 0 for a fresh swap, or the currently-rendered blend when taking
     * over a finger-driven swap or re-aiming mid-flight, so the hand-off doesn't pop.
     * [fromPage] anchors the "from" envelope when it is not the playing track. The
     * tween ends by HOLDING at 1 rather than releasing -- unless
     * [envelopeSettleRequested] was set (flip-driven morphs), in which case it
     * self-clears -- and a safety net stops the hold if the flip never lands.
     */
    private fun startTrackChangeMorph(
        targetPage: Int,
        fromBlend: Float,
        fromPage: Int? = null
    ) {
        envelopeSettleRequested = false
        envelopeTargetPage = targetPage
        envelopeFromPage = fromPage
        morphDrivenByPager = false
        morphJob?.cancel()
        // A fresh Animatable seeded with fromBlend: the very first read after this
        // call already returns the takeover blend -- no stale-value frame.
        morphAnimatable = Animatable(fromBlend.coerceIn(0f, 1f))
        morphActive = true
        morphJob = scope.launch {
            morphAnimatable.animateTo(1f, tween(SEEK_BAR_SETTLE_TWEEN_MS))
            if (envelopeSettleRequested) stopTrackChangeMorph()
        }
        scheduleEnvelopeSafetyNet()
    }

    /**
     * Hands a swap over to an unwind tween toward 0 (see [settleSwipeEnvelopeBack] /
     * [revertTrackChangeEnvelope]): the pinned pages stay until the tween completes --
     * clearing them early would make the host's blend formula collapse to 0
     * mid-unwind, snapping the bars back. From here on the tween owns the blend
     * (morphDrivenByPager false), so the host's pager fraction no longer fights it.
     */
    private fun unwindTrackChangeMorph(fromBlend: Float) {
        val from = fromBlend.coerceIn(0f, 1f)
        morphJob?.cancel()
        envelopeSafetyNetJob?.cancel()
        envelopeSafetyNetJob = null
        envelopeSettleRequested = false
        morphDrivenByPager = false
        morphAnimatable = Animatable(from)
        morphJob = scope.launch {
            morphAnimatable.animateTo(0f, tween((from * SEEK_BAR_SETTLE_TWEEN_MS).toInt()))
            stopTrackChangeMorph()
        }
    }

    /**
     * (Re-)pins a finger-driven swap's target without starting a tween: the blend is
     * the host's pager fraction, computed live between [fromPage] (null = the playing
     * track) and [targetPage].
     */
    private fun pinSwipeMorph(targetPage: Int, fromPage: Int?) {
        envelopeSafetyNetJob?.cancel()
        envelopeSafetyNetJob = null
        envelopeSettleRequested = false
        envelopeTargetPage = targetPage
        envelopeFromPage = fromPage
        morphJob?.cancel()
        morphJob = null
        morphActive = true
        morphDrivenByPager = true
    }

    /**
     * The flip normally lands within a couple of frames of the trigger; if the engine
     * never moves at all (a committed skip the queue can no longer honor), stop
     * holding the swap so the bars return to the actual playing track.
     */
    private fun scheduleEnvelopeSafetyNet() {
        envelopeSafetyNetJob?.cancel()
        envelopeSafetyNetJob = scope.launch {
            delay(ENVELOPE_SAFETY_NET_MS)
            if (morphActive && !envelopeSettleRequested) stopTrackChangeMorph()
        }
    }

    private fun stopTrackChangeMorph() {
        morphJob?.cancel()
        morphJob = null
        envelopeSafetyNetJob?.cancel()
        envelopeSafetyNetJob = null
        morphActive = false
        morphDrivenByPager = false
        envelopeTargetPage = null
        envelopeFromPage = null
        envelopeSettleRequested = false
    }
}

/**
 * Creates and remembers a [SeekBarProgressState]. [liveProgress] is read lazily every
 * time [SeekBarProgressState.progress] is accessed, so capture whatever state reads
 * (position, duration, pager offset) you need inside the lambda -- it doesn't need to
 * be `remember`-stable itself.
 */
@Composable
fun rememberSeekBarProgressState(
    positionMsFlow: StateFlow<Long>,
    liveProgress: (Long) -> Float
): SeekBarProgressState {
    val scope = rememberCoroutineScope()
    val latestLiveProgress = rememberUpdatedState(liveProgress)
    return remember(positionMsFlow) { SeekBarProgressState(scope, positionMsFlow, latestLiveProgress) }
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
