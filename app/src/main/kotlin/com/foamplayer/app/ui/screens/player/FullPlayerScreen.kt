package com.foamplayer.app.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.settings.SeekBarStyle
import com.foamplayer.app.ui.components.LoopingPagerModelCache
import com.foamplayer.app.ui.components.PreviousAction
import com.foamplayer.app.ui.components.SeekBarProgressState
import com.foamplayer.app.ui.components.rememberSeekBarProgressState
import com.foamplayer.app.ui.modals.PlayerAudioInfoModal
import com.foamplayer.app.ui.modals.PlayOnDevice
import com.foamplayer.app.ui.modals.PlayOnModal
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.WaveformCache
import com.foamplayer.app.ui.utils.WaveformState
import com.foamplayer.app.ui.utils.rememberWaveform
import com.foamplayer.app.audio.engine.RepeatMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Live geometry feeding the dismiss swipe's 1:1 finger-to-artwork mapping: the nav
 * root reads [heroArtCenterY] (root coordinates) and pairs it with the flight target
 * (mini player art or the origin collection's centered row) to size the swipe.
 */
/** A resolved animated canvas and the queue entry it belongs to. */
private data class AnimatedCoverOwner(val entryId: Long, val url: String)

/** Plain holder (not snapshot state): the canvas shown last, kept while its page is still settled. */
private class AnimatedCoverCarry {
    var owner: AnimatedCoverOwner? = null
}

class PlayerFlightGeometryState {
    var heroArtCenterY: Float by mutableFloatStateOf(Float.NaN)
}

@Composable
fun FullPlayerScreen(
    viewModel: PlayerViewModel,
    onDismiss: (velocityPxPerSec: Float) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionState: SeekableTransitionState<Boolean>,
    backdropState: FullPlayerBackdropState,
    isAnimateSeekBarEnabled: Boolean = false,
    onOpenAlbum: (TrackEntity) -> Unit = {},
    onOpenArtist: (TrackEntity) -> Unit = {},
    onDismissGestureStart: (suspend () -> Unit)? = null,
    onDismissGestureActivate: (suspend () -> Unit)? = null,
    onDismissGestureCancelled: (() -> Unit)? = null,
    dismissSwipeDistancePx: (() -> Float)? = null,
    flightGeometry: PlayerFlightGeometryState? = null,
    /** Current extra-row growth of the navbar below, in px (0 = collapsed). Draw-phase read. */
    navbarExpandHeight: () -> Float = { 0f }
) {
    val state by viewModel.playbackState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val scoreOverrides by viewModel.scoreOverrides.collectAsState()
    val isImmersiveEnabled = settings.immersiveModeEnabled
    val isAnimatedCoverEnabled = isImmersiveEnabled && settings.animatedCoverEnabled
    val allowMobileDataForCanvas = settings.animatedCoverAllowMobileData
    val marqueeMode = settings.marqueeMode
    val seekBarStyle = settings.seekBarStyle

    val settleSeekBarAnimated = isAnimateSeekBarEnabled || seekBarStyle == SeekBarStyle.WAVEFORM

    val isWaveformSeek = seekBarStyle == SeekBarStyle.WAVEFORM
    val isProgressiveEnvelope = isWaveformSeek && isAnimateSeekBarEnabled

    val currentTrack by remember { derivedStateOf { state.currentTrack } }
    // The hero pager shows [heroModel]: the plain queue, except in repeat-all where it is a
    // closed loop over the PRIMARY queue (auxiliary entries hidden) framed by two ghost pages
    // on each side (copies of the loop's neighbours, so a ghost has the same neighbours as its
    // twin and the silent hop is invisible). Next at the end and previous at the start are
    // therefore ordinary one-page moves; the pager snaps off the ghost onto its twin. `queue`, `queueIndex` and
    // `queueEntryIds` below are the pager's (display) numbering; engine calls go through
    // [LoopingPagerModel.realIndexOf].
    val heroModelCache = remember { LoopingPagerModelCache() }
    val heroModel by remember {
        derivedStateOf {
            heroModelCache.resolve(
                queue = state.queue,
                auxiliaryFlags = state.auxiliaryFlags,
                entryIds = state.queueEntryIds,
                queueIndex = state.queueIndex,
                repeatAll = state.repeatMode == RepeatMode.ALL
            )
        }
    }
    val queue by remember { derivedStateOf { heroModel.queue } }
    val queueIndex by remember { derivedStateOf { heroModel.index } }
    val queueEntryIds by remember { derivedStateOf { heroModel.entryIds } }
    val durationMs by remember { derivedStateOf { state.durationMs } }
    val audioOutputInfo by remember { derivedStateOf { state.audioOutputInfo } }
    val isPlayingProvider = { state.isPlaying }
    val isPlaybackActiveProvider = { state.isPlaying || state.isPlaybackRequested }
    val track = currentTrack ?: return
    val queueSize = queue.size

    var subViewMode by remember { mutableStateOf(SubViewMode.LYRICS) }
    var showAudioInfoModal by remember { mutableStateOf(false) }
    var showPlayOnModal by remember { mutableStateOf(false) }

    val playOnDevices by viewModel.playOnDevices.collectAsState()
    val selectedPlayOnDeviceId by viewModel.selectedPlayOnDeviceId.collectAsState()

    val audioInfoText = run {
        val format = audioOutputInfo?.sourceFormat?.takeIf { it.isNotBlank() } ?: track.format
        val bitrateKbps = audioOutputInfo?.sourceBitrateKbps?.takeIf { it > 0 } ?: track.bitrate
        if (bitrateKbps > 0) "$format · $bitrateKbps kbps" else format
    }

    val upTransitionState = remember { SeekableTransitionState(false) }
    val upTransition = rememberTransition(upTransitionState, label = "UpTransition")

    // Boolean gate only: reading the raw transition fraction here used to re-run this
    // whole screen body on every frame of the subview drag/settle. derivedStateOf
    // only notifies its readers when the projected value actually changes, so unrelated
    // PlaybackState emissions (play/pause flips, audio-output changes) no longer recompose
    // this whole screen: a play/pause press now only invalidates the transport leaves that
    // read it. isPlaying deliberately stays raw -- it is consumed exclusively through the
    // deferred provider below, inside leaf scopes (transport buttons, paused-art scale).
    val showSubViewSlider by remember(upTransitionState) {
        derivedStateOf { upTransitionState.progress() > 0f }
    }
    // Deferred form for the root-hosted backdrop: the lambda only reads snapshot state
    // (transition fraction + target), so the root evaluates it during its draw phase and
    // never needs to recompose while the subview is dragged.
    val subViewBlurProgressProvider = remember(upTransitionState) {
        {
            val raw = upTransitionState.progress().coerceIn(0f, 1f)
            if (upTransitionState.targetState) {
                (raw * PlayerDimens.subViewBlurRushFactor).coerceIn(0f, 1f)
            } else {
                raw * raw
            }
        }
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val scope = rememberCoroutineScope()

    val dragThresholdPx = screenHeightPx.coerceAtLeast(1f)
    val upDragThresholdPx = with(density) { PlayerDimens.subViewSwipeThreshold.toPx() }.coerceAtLeast(1f)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val currentOnDismissGestureStart = rememberUpdatedState(onDismissGestureStart)
    val currentOnDismissGestureActivate = rememberUpdatedState(onDismissGestureActivate)
    val currentOnDismissGestureCancelled = rememberUpdatedState(onDismissGestureCancelled)
    val currentDismissSwipeDistance = rememberUpdatedState(dismissSwipeDistancePx)
    val gestureController = remember(playerTransitionState, dragThresholdPx, upDragThresholdPx) {
        PlayerGestureController(
            upTransitionState = upTransitionState,
            playerTransitionState = playerTransitionState,
            scope = scope,
            dismissSwipeDistancePx = {
                currentDismissSwipeDistance.value?.invoke() ?: dragThresholdPx
            },
            upDragThresholdPx = upDragThresholdPx,
            onDismiss = { velocity -> currentOnDismiss.value(velocity) },
            onDismissGestureStart = { currentOnDismissGestureStart.value?.invoke() },
            onDismissGestureActivate = { currentOnDismissGestureActivate.value?.invoke() },
            onDismissGestureCancelled = { currentOnDismissGestureCancelled.value?.invoke() }
        )
    }

    // derivedStateOf gate: currentState/targetState churn at every transition boundary
    // (and SeekableTransitionState may touch them while seeking); only the derived
    // boolean flipping may recompose the screen body.
    val backHandlerEnabled by remember {
        derivedStateOf { upTransitionState.currentState || upTransitionState.targetState }
    }
    BackHandler(enabled = backHandlerEnabled) {
        gestureController.collapseToMainPlayer()
    }

    // Animated canvas resolution: only for the immersive player, only while the
    // feature setting is on. Keyed on the queue entry so a pager skip cancels any
    // in-flight fetch and resolves the new track's canvas instead. The result is stored
    // WITH the queue entry it belongs to and is never cleared when the track changes:
    // which canvas is actually shown (and for how long the previous one is kept) is
    // decided further down, once the pager exists.
    val currentEntryId = queueEntryIds.getOrNull(queueIndex)
    var resolvedCover by remember { mutableStateOf<AnimatedCoverOwner?>(null) }
    LaunchedEffect(track.id, currentEntryId, isAnimatedCoverEnabled, allowMobileDataForCanvas) {
        if (!isAnimatedCoverEnabled || currentEntryId == null) {
            resolvedCover = null
            return@LaunchedEffect
        }
        val url = viewModel
            .resolveAnimatedCover(track, allowMobileDataForCanvas)
            ?.preferredAnimationUrl
        resolvedCover = url?.let { AnimatedCoverOwner(currentEntryId, it) }
    }
    // Playback gate: pauses only at the rest endpoints -- player fully closed
    // to the mini player, or subview fully open. Transitions (subview drag,
    // player close flight) keep playing; the surrounding layers fade the video
    // out visually instead.
    val isCanvasPlaying by remember {
        derivedStateOf {
            (playerTransitionState.currentState || playerTransitionState.targetState) &&
                    !(upTransitionState.currentState && upTransitionState.targetState)
        }
    }

    // Remembered so the Modifier instance stays stable across body recompositions:
    // handing MainPlayerContent/SubViewContent a fresh modifier on every pass would
    // defeat their skipping. The pointerInput observer preloads the dismiss-to-row
    // flight only once the gesture reveals itself as a DOWNWARD drag (direction check
    // at the touch-slop crossing): a pager swipe or an upward swipe must never pay
    // the preload's branch-composition cost. It only observes and never consumes.
    val playerDrag = remember(gestureController) {
        Modifier
            .pointerInput(gestureController) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var preloadStarted = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.none { it.pressed }) break
                        if (!preloadStarted) {
                            val change = event.changes.firstOrNull() ?: continue
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (maxOf(abs(dx), abs(dy)) > viewConfiguration.touchSlop) {
                                preloadStarted = true
                                if (dy > 0f && dy >= abs(dx)) {
                                    gestureController.onDismissPressStarted()
                                }
                            }
                        }
                    }
                    gestureController.onDismissPressEnded()
                }
            }
            .draggable(
                state = DraggableState { delta -> gestureController.onDrag(delta) },
                orientation = Orientation.Vertical,
                onDragStopped = { v -> gestureController.onDragStopped(v) }
            )
    }

    val nestedScrollConnection = remember(gestureController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero

                val upState = gestureController.upTransitionState
                val isMidDrag = upState.currentState != upState.targetState

                if (isMidDrag && available.y != 0f) {
                    gestureController.onDrag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero

                if (available.y > 0f) {
                    gestureController.onDrag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val upState = gestureController.upTransitionState
                val isMidDrag = upState.currentState != upState.targetState

                if (isMidDrag) {
                    gestureController.onDragStopped(available.y)
                    return Velocity(0f, available.y)
                }
                return Velocity.Zero
            }
        }
    }

    val isTransitioning by remember {
        derivedStateOf {
            playerTransitionState.currentState != playerTransitionState.targetState ||
                    upTransitionState.currentState != upTransitionState.targetState
        }
    }
    val placeholderColor = if (isTransitioning) Color.Transparent else FoamTheme.colors.surfaceVariant

    // A fresh pager per repeat-all on/off: toggling it renumbers the queue (ghost pages are added or
    // removed in front of the playing page). Re-anchoring the same PagerState only lands at the next
    // measure, leaving one frame where composition and pager disagree on the numbering (the flicker).
    // A new state starts ON the new numbering's playing page, so every reader is coherent at once.
    val pagerState = key(state.repeatMode == RepeatMode.ALL) {
        rememberPagerState(
            initialPage = queueIndex.coerceAtLeast(0),
            pageCount = { queue.size }
        )
    }
    // Long-lived lambdas (remember { } / rememberSeekBarProgressState) must not capture a replaced pager.
    val latestPagerState by rememberUpdatedState(pagerState)

    // Page-number anchoring for the hero pager across queue renumberings. Deleting
    // an entry BEFORE the playing one (repeat-all's full-queue view) shifts every
    // later entry down a slot while the track under the playhead is unchanged. The
    // snap only lands at this frame's MEASURE, but the backdrop SideEffect below
    // publishes art in the APPLY phase -- using composition-phase page numbers.
    // Resolving that pre-snap publish against the live (already-shifted) queue
    // shows the shifted neighbor for one frame -- the flash. So the backdrop (and
    // canvas gates) resolve through [pagerAnchoredQueue]/[pagerAnchoredEntryIds],
    // the snapshot matching the numbering the pager still displays, until the
    // snap lands; the pager's own page content follows the live queue (its
    // key-based scroll position remaps in the same measure that lands the snap).
    var pagerAnchoredQueue by remember { mutableStateOf(queue) }
    var pagerAnchoredEntryIds by remember { mutableStateOf(queueEntryIds) }
    var pagerSyncEntryId by remember { mutableStateOf(currentEntryId) }
    var pagerSyncQueueIndex by remember { mutableStateOf(queueIndex) }
    // Held for the one frame where the queue is renumbered (repeat mode toggled, ...) but the pager has
    // not yet landed on the new page: the backdrop fraction would otherwise be computed from the new
    // page against the old art pair and flash the neighbour's art.
    var backdropRenumberHold by remember { mutableStateOf(false) }
    // One-frame signal, computed in composition where the shift is observed: the
    // playing entry kept its id while its index moved. The pager is snapped
    // silently (a real track change animates via the follow effect further down).
    val pagerRenumberingPending = currentEntryId == pagerSyncEntryId &&
            queueIndex != pagerSyncQueueIndex
    SideEffect {
        if (pagerRenumberingPending &&
            queueIndex in 0 until queueSize &&
            pagerState.currentPage != queueIndex &&
            !pagerState.isScrollInProgress
        ) {
            pagerState.requestScrollToPage(queueIndex)
        }
        // Adopt the live queue unless this frame is a pre-snap renumbering: until
        // the snap lands, art must resolve the way the pager still displays it.
        if (!pagerRenumberingPending || pagerState.currentPage == queueIndex) {
            pagerAnchoredQueue = queue
            pagerAnchoredEntryIds = queueEntryIds
        }
        pagerSyncEntryId = currentEntryId
        pagerSyncQueueIndex = queueIndex
        backdropRenumberHold = pagerRenumberingPending && pagerState.currentPage != queueIndex
    }

    // The playing entry's page as the pager currently displays it: the live index
    // once the anchor has adopted the live queue, the pre-shift position during
    // a pending renumbering frame.
    val anchoredPlayingPage = if (pagerAnchoredQueue === queue) {
        queueIndex.coerceAtLeast(0)
    } else {
        pagerAnchoredEntryIds.indexOf(currentEntryId).coerceAtLeast(0)
    }

    val idlePage by remember {
        derivedStateOf { if (latestPagerState.isScrollInProgress) -1 else latestPagerState.currentPage }
    }
    val coverCarry = remember { AnimatedCoverCarry() }
    val animatedCover: AnimatedCoverOwner? = if (!isAnimatedCoverEnabled) {
        null
    } else {
        val resolved = resolvedCover
        if (resolved != null &&
            resolved.entryId == currentEntryId &&
            idlePage == anchoredPlayingPage
        ) {
            resolved
        } else {
            coverCarry.owner?.takeIf { carried ->
                idlePage == -1 || pagerAnchoredEntryIds.indexOf(carried.entryId) == idlePage
            }
        }
    }
    val animatedCoverIndex = animatedCover
        ?.let { pagerAnchoredEntryIds.indexOf(it.entryId) }
        ?: -1

    val seekBarProgress = rememberSeekBarProgressState(
        positionMsFlow = viewModel.positionMs,
        liveProgress = { livePositionMs ->
            val progress = if (durationMs > 0) livePositionMs.toFloat() / durationMs else 0f

            val activeFraction = if (isAnimateSeekBarEnabled) {
                val scrollPosition = latestPagerState.currentPage + latestPagerState.currentPageOffsetFraction
                val distanceFromCurrent = heroModel.distanceToPlayingPage(scrollPosition)
                (1f - distanceFromCurrent).coerceIn(0f, 1f)
            } else {
                1f
            }

            progress * activeFraction
        }
    )

    // Live proxies for the envelope's collectors and draw-phase provider below (the
    // queue/track can change mid-gesture; a stale closure must not pin a stale page).
    val latestQueueIndex = rememberUpdatedState(queueIndex)
    val latestQueueSize = rememberUpdatedState(queueSize)
    val latestQueue = rememberUpdatedState(queue)
    val latestHeroModel = rememberUpdatedState(heroModel)
    // Time of the last transport-button "previous" skip; disambiguates the wrap direction of a
    // two-track loop (see LoopingPagerModel.wrapTransitPage). 0 = the last button skip was a "next".
    val lastBackwardSkipMs = remember { longArrayOf(0L) }
    val markSkipIntent: (Boolean) -> Unit = { backward ->
        lastBackwardSkipMs[0] = if (backward) System.currentTimeMillis() else 0L
    }

    // While the pager hops off a repeat-all ghost page onto its twin, the backdrop holds still.
    // The hop changes the pager's page in one measure pass, but the backdrop's art pair is only
    // republished by the next composition; drawing that frame against the stale pair makes the
    // crossfade fraction jump to 1 on a backward hop (page 1 -> n + 1) and flash the wrong art.
    // Ghost and twin show the same track, so holding the fraction at 0 is exact.
    var backdropHopHold by remember { mutableStateOf(false) }
    suspend fun hopOffGhost() {
        // Mid-renumbering the pager still reports its OLD page number, which can land in the new
        // ghost zone: hopping "off" it would scroll to the wrong twin and fight the pending snap.
        if (backdropRenumberHold) return
        val model = latestHeroModel.value
        val page = pagerState.currentPage
        if (!model.isGhostPage(page)) return
        backdropHopHold = true
        try {
            pagerState.scrollToPage(model.ghostTwinPage(page))
            // Let the composition that republishes the art pair land before releasing the hold.
            withFrameNanos { }
            withFrameNanos { }
        } finally {
            backdropHopHold = false
        }
    }

    // The two envelopes the waveform renders while a swap is in flight: where the bars
    // are anchored (null = the playing track's own envelope, pinned at trigger time)
    // and where the swap is heading. Resolving here kicks off any missing extraction in
    // the process-wide cache; the states' own snapshot fields carry the progressive
    // fill from there.
    val envelopeTargetPage = seekBarProgress.envelopeTargetPage
    val envelopeFromPage = seekBarProgress.envelopeFromPage
    val morphFromWaveform: WaveformState? = if (!isWaveformSeek) {
        null
    } else {
        val fromTrack = envelopeFromPage?.let { page -> queue.getOrNull(page) } ?: track
        rememberWaveform(fromTrack.path, fromTrack.duration)
    }
    val morphTargetWaveform: WaveformState? = if (!isWaveformSeek) {
        null
    } else {
        envelopeTargetPage
            ?.let { page -> queue.getOrNull(page) }
            ?.let { rememberWaveform(it.path, it.duration) }
    }

    // Blend driver handed to the waveform (draw-phase read: each frame of the swap
    // invalidates just the bars' drawing). A swap tween owns the blend whenever one is
    // running (release, transport buttons, flip catch-ups); otherwise the blend is the
    // pager's own fraction from the anchor page toward the pinned target, so the
    // bars follow the finger 1:1 (programmatic pager syncs sweep the same formula,
    // morphing like a fast swipe). Once the playing index has reached the pinned target
    // the swap is over as far as rendering goes: the plain current-envelope rendering at
    // blend 0 is pixel-identical to the held swap.
    val latestQueueIndexForEnvelope = latestQueueIndex
    val envelopeMorphProgress = remember(seekBarProgress) {
        {
            val targetPage = seekBarProgress.envelopeTargetPage
            when {
                targetPage == null -> 0f
                seekBarProgress.isEnvelopeTweenActive -> seekBarProgress.envelopeTweenBlend
                else -> {
                    val playingPage = latestQueueIndexForEnvelope.value
                    val originPage = seekBarProgress.envelopeFromPage ?: playingPage
                    if (originPage == targetPage) {
                        0f
                    } else {
                        val absolute = latestPagerState.currentPage + latestPagerState.currentPageOffsetFraction
                        abs((absolute - originPage) / (targetPage - originPage)).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }

    fun isMainPagerVisible() = !upTransitionState.currentState

    val context = LocalContext.current

    LaunchedEffect(pagerState, queue) {
        snapshotFlow { pagerState.currentPage }
            .collect { currentPage ->
                val indices = listOf(currentPage - 1, currentPage, currentPage + 1)
                val pathsToKeep = indices.mapNotNull { i -> queue.getOrNull(i)?.albumArtPath }.toSet()
                pathsToKeep.forEach { path ->
                    AlbumArtPaletteCache.ensure(scope = this, context = context, path = path)
                }
            }
    }

    // Waveform neighbor prefetch (waveform seek bar only): keeps the tracks adjacent
    // to the pager's window extracted in the process-wide cache, so a track-change
    // morph has both envelopes ready the moment a swipe starts or a transport button
    // is pressed -- the same window the palette prefetch above keeps hot.
    LaunchedEffect(pagerState, isWaveformSeek) {
        if (!isWaveformSeek) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }
            .collect { currentPage ->
                listOf(currentPage - 1, currentPage, currentPage + 1)
                    .mapNotNull { page -> latestQueue.value.getOrNull(page) }
                    .forEach { WaveformCache.ensure(context, it.path, it.duration) }
            }
    }

    // The interpolated color drives the app-wide accent. When the user picked a
    // manual accent (automaticColorEnabled = false) the value is discarded by
    // rememberAccentColor, so the whole per-frame pipeline (snapshotFlow read,
    // StateFlow write, FoamApp + theme recomposition) runs for nothing -- skip it.
    // Resolves pages through [pagerAnchoredQueue], read INSIDE the flow, instead of the
    // live `queue` captured by the effect key. When a row before the playing one is
    // removed, the SideEffect above snaps the pager to the shifted page and adopts the
    // shifted queue in the same apply pass. A closure over the live queue (or over the
    // previous one until the key-restart lands) paired the new page number with the
    // wrong queue for one emission -- the neighbor's color pushed to the accent for a
    // frame. Page and queue snapshot now always change together, so each emission is
    // coherent, and no restart is needed when the queue changes.
    LaunchedEffect(pagerState, settings.automaticColorEnabled) {
        if (!settings.automaticColorEnabled) return@LaunchedEffect
        snapshotFlow {
            // New page number against the old queue for a frame would push a wrong accent.
            if (backdropRenumberHold) return@snapshotFlow null
            val anchoredQueue = pagerAnchoredQueue
            AlbumArtPaletteCache.interpolatedColor(
                currentPage = pagerState.currentPage,
                currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                pageCount = anchoredQueue.size
            ) { page -> anchoredQueue.getOrNull(page)?.albumArtPath }
        }.collect { interpolatedColor ->
            // An artless page (or one whose palette extraction is still in flight)
            // yields no color. Keeping the last accent instead of dropping it avoids
            // flashing the static default mid-swipe and snapping back the next
            // frame a colored page re-enters the interpolation. The player's own
            // DisposableEffect below still clears the dynamic accent on dispose,
            // so dismissal re-resolves through the static path as before.
            if (interpolatedColor != null) {
                viewModel.setDynamicAccentColor(interpolatedColor)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.setDynamicAccentColor(null)
        }
    }

    TrackChangeCommitEffect(
        pagerState = pagerState,
        currentQueueIndex = queueIndex,
        currentQueueSize = queueSize,
        isAnimateSeekBarEnabled = settleSeekBarAnimated,
        isWaveformSeek = isWaveformSeek,
        envelopeMorphProgress = envelopeMorphProgress,
        seekBarProgress = seekBarProgress,
        isWrapLanding = { page ->
            val loop = latestHeroModel.value
            loop.isGhostPage(page) && loop.ghostTwinPage(page) == latestQueueIndex.value
        }
    )

    // Progressive envelope latch (raw "animate seek bar" setting only): while the
    // finger is DOWN a real hero-pager GESTURE carries the blend from the moment it
    // resolves a direction -- the pinned neighbor follows the drag direction (the
    // pager's predicted target first, the offset-derived neighbor otherwise), so the
    // bars start morphing with the finger instead of waiting for the commit. Crossing
    // back through the playing page re-anchors on the playing track's envelope; a
    // chained swipe that rests on the previously pinned page carries that envelope
    // forward instead -- both keep the morph continuous at the re-pin. Programmatic
    // pager syncs never drag, so they never pin anything. At RELEASE the blend is
    // handed to a tween: a commit takes it on up to 1 (see onTrackChangeCommitted),
    // a spring-back takes it back down to 0 via settleSwipeEnvelopeBack -- the pager's
    // own fling never decides the morph's ending, and nothing snaps.
    if (isProgressiveEnvelope) {
        val isPagerDragged by pagerState.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(pagerState) {
            snapshotFlow {
                Triple(isPagerDragged, pagerState.targetPage, pagerState.currentPageOffsetFraction)
            }.collect { (dragging, targetPage, offsetFraction) ->
                val playingPage = latestQueueIndex.value
                if (dragging) {
                    val dragNeighbor = when {
                        offsetFraction > 0f -> pagerState.currentPage + 1
                        offsetFraction < 0f -> pagerState.currentPage - 1
                        else -> null
                    }
                    val pinned =
                        if (targetPage != playingPage && targetPage in 0 until latestQueueSize.value) {
                            targetPage
                        } else {
                            dragNeighbor
                        }
                    if (pinned != null && pinned != playingPage && pinned in 0 until latestQueueSize.value) {
                        // Where the shown envelope should anchor at the re-pin: crossing
                        // back through the playing page anchors on it (the blend reads 0
                        // there), while a swipe resting on/near the previous pinned
                        // target carries that envelope forward.
                        val absolute = pagerState.currentPage + offsetFraction
                        val fromPage =
                            if (abs(absolute - pinned) <= abs(absolute - playingPage)) {
                                seekBarProgress.envelopeTargetPage
                            } else {
                                null
                            }
                        seekBarProgress.onSwipeEnvelopeTarget(pinned, fromPage)
                    }
                } else if (targetPage == playingPage) {
                    // Released short of a commit: hand the currently-rendered blend to
                    // an unwind tween instead of clearing the pin outright -- clearing
                    // here would collapse the blend formula to 0 on the spot and snap
                    // the bars to the playing track's envelope.
                    seekBarProgress.settleSwipeEnvelopeBack(envelopeMorphProgress())
                }
            }
        }
    }

    // Every playing-index change settles (or starts) the envelope swap: a swap whose
    // pinned target is where the track LANDED resolves (an in-flight tween finishes
    // first and then clears), one that lands back on its anchor unwinds, one that
    // lands past the committed page re-aims (see onPlayingIndexChanged), and an
    // unprompted one-page move (natural advance, queue pick) gets a catch-up morph
    // from the previously playing track's envelope.
    var previousQueueIndex by remember { mutableStateOf(queueIndex) }
    var previousEnvelopeEntryId by remember { mutableStateOf(currentEntryId) }
    LaunchedEffect(queueIndex, currentEntryId) {
        // A pure renumbering (playing entry unchanged -- e.g. a row before it was
        // deleted) must not start a catch-up morph: the "previous page" it would
        // anchor on holds a different track after the shift.
        if (currentEntryId != previousEnvelopeEntryId) {
            // Repeat-all wrap (last <-> first): the engine index jumps across the whole loop, but the
            // swap was pinned on the ghost page the pager slides onto. Report the landing there, or the
            // swap never resolves (wrong blend, envelope pops to the new track).
            val landedPage = latestHeroModel.value.wrapTransitPage(
                fromPage = previousQueueIndex,
                toPage = queueIndex,
                backward = System.currentTimeMillis() - lastBackwardSkipMs[0] < SKIP_INTENT_WINDOW_MS
            ) ?: queueIndex
            seekBarProgress.onPlayingIndexChanged(landedPage, previousQueueIndex)
        }
        previousQueueIndex = queueIndex
        previousEnvelopeEntryId = currentEntryId
    }

    LaunchedEffect(pagerState) {
        // Real queue index (and time) of a skip already requested for a swipe that ended on a
        // repeat-all ghost page, so the later settle on its twin page doesn't request it twice.
        var wrapRequestedReal = -1
        var wrapRequestedAtMs = 0L
        snapshotFlow { pagerState.settledPage }
            .collect { settledPage ->
                // A settled page read mid-renumbering is a stale number, not a user landing.
                if (backdropRenumberHold) return@collect
                val model = latestHeroModel.value
                if (model.isGhostPage(settledPage)) {
                    // Ghost pages are visual only: commit the twin track to the engine but do
                    // NOT move the pager here (settle can be reported at finger release, and a
                    // jump would cut the fling short). The idle-hop effect below moves the pager
                    // onto the twin once it has really stopped. A twin the engine already
                    // plays is a programmatic wrap that landed, which needs no skip.
                    if (isMainPagerVisible() &&
                        model.ghostTwinPage(settledPage) != latestQueueIndex.value
                    ) {
                        wrapRequestedReal = model.realIndexOf(settledPage)
                        wrapRequestedAtMs = System.currentTimeMillis()
                        seekBarProgress.onTrackChangeApplied()
                        markSkipIntent(!model.isTrailingGhost(settledPage))
                        viewModel.skipToIndex(
                            wrapRequestedReal,
                            forward = model.isTrailingGhost(settledPage)
                        )
                    }
                    return@collect
                }
                val alreadyRequested = wrapRequestedReal >= 0 &&
                        System.currentTimeMillis() - wrapRequestedAtMs < 2_000L &&
                        model.realIndexOf(settledPage) == wrapRequestedReal
                wrapRequestedReal = -1
                if (alreadyRequested) return@collect

                if (isMainPagerVisible() &&
                    settledPage in 0 until latestQueueSize.value &&
                    settledPage != latestQueueIndex.value
                ) {
                    seekBarProgress.onTrackChangeApplied()
                    // Keep the swipe direction: a swipe back must not land on a
                    // track that failed to load (it walks further back instead).
                    viewModel.skipToIndex(
                        model.realIndexOf(settledPage),
                        forward = settledPage > latestQueueIndex.value
                    )
                } else if (settledPage == latestQueueIndex.value) {
                    // The pager settled back onto the playing page: a committed change
                    // never landed (a re-grabbed fling pulled back). A finger-driven
                    // swap has already been unwound by the release tween above; a swap
                    // tween morphs the bars back down to the playing track's envelope.
                    seekBarProgress.revertTrackChangeEnvelope()
                }
            }
    }

    // Idle hop: a pager that has really stopped on a repeat-all ghost page is moved silently
    // onto its identical twin, whoever got it there (a swipe, a transport-button wrap, an
    // automatic advance). Waiting for idle keeps the fling's animation intact.
    LaunchedEffect(pagerState) {
        snapshotFlow {
            val model = latestHeroModel.value
            if (!backdropRenumberHold && !pagerState.isScrollInProgress &&
                model.isGhostPage(pagerState.currentPage)
            ) {
                pagerState.currentPage
            } else {
                -1
            }
        }.collect { ghostPage ->
            if (ghostPage >= 0) hopOffGhost()
        }
    }

    val programmaticBackdropCrossfade = remember { Animatable(1f) }
    var programmaticBackdropFromPath by remember { mutableStateOf<String?>(null) }
    var programmaticBackdropToPath by remember { mutableStateOf<String?>(null) }
    // Generation of the programmatic-crossfade run that owns fromPath/toPath. A
    // restarted effect (rapid next/previous presses) cancels the previous run, and the
    // cancelled run's finally can execute AFTER the new run has already published its
    // own paths -- without this guard that stale finally wipes them and silently kills
    // the new fade (the lambda falls back to the pager fraction and the pair snaps
    // with no crossfade).
    var backdropFadeGeneration by remember { mutableIntStateOf(0) }

    // Backdrop follows playback, but only while the main pager is hidden (subview
    // open): there the pager sweep can't communicate the change, so the backdrop
    // crossfades programmatically. A pure renumbering never reaches the fade: the
    // anchored-queue snapshot keeps the pager frame art-correct, and this gate
    // skips the shift frame where page != queueIndex with the track unchanged.
    LaunchedEffect(queueIndex, currentEntryId) {
        if (isMainPagerVisible() ||
            queueIndex !in 0 until queueSize ||
            pagerState.currentPage == queueIndex ||
            pagerRenumberingPending
        ) {
            return@LaunchedEffect
        }
        val fromPath = pagerAnchoredQueue.getOrNull(pagerState.currentPage)?.albumArtPath
        val toPath = queue.getOrNull(queueIndex)?.albumArtPath
        if (fromPath != toPath) {
            val generation = ++backdropFadeGeneration
            programmaticBackdropFromPath = fromPath
            programmaticBackdropToPath = toPath
            programmaticBackdropCrossfade.snapTo(0f)
            try {
                programmaticBackdropCrossfade.animateTo(1f, tween(TRANSITION_DURATION_MS))
            } finally {
                // A cancelled animation (queueIndex changing again mid-crossfade, or the
                // player leaving composition) must not leave the crossfade "active" with
                // fromPath frozen into frontArtPath; clearing the programmatic paths hands
                // control straight back to the pager-driven state. Plain state writes run
                // in a cancelled coroutine's finally block without suspension -- but only
                // when THIS run still owns the paths (see backdropFadeGeneration).
                if (generation == backdropFadeGeneration) {
                    programmaticBackdropFromPath = null
                    programmaticBackdropToPath = null
                }
            }
        }
    }

    // Re-anchors the pager onto the playing page with the sweep animation. The
    // silent, immediate case (a pure renumbering) is handled by the SideEffect
    // snap above; the shift frame itself is skipped so a renumbering never
    // animates a sweep through the shifted neighbors' art.
    LaunchedEffect(queueIndex, currentEntryId) {
        if (queueIndex in 0 until queueSize &&
            pagerState.currentPage != queueIndex &&
            !pagerState.isScrollInProgress &&
            !pagerRenumberingPending
        ) {
            val loop = latestHeroModel.value
            if (loop.isGhostPage(pagerState.currentPage) &&
                loop.ghostTwinPage(pagerState.currentPage) == queueIndex
            ) {
                // Parked on the ghost of the playing track: the idle hop lands on its twin.
                return@LaunchedEffect
            }
            if (isMainPagerVisible()) {
                // Repeat-all wrap (last -> first or first -> last): the engine already sits on
                // the far end of the loop, but visually it is a normal one-page move onto the
                // ghost page, followed by a silent hop onto its twin.
                val transitPage = latestHeroModel.value.wrapTransitPage(
                    fromPage = pagerState.currentPage,
                    toPage = queueIndex,
                    backward = System.currentTimeMillis() - lastBackwardSkipMs[0] < SKIP_INTENT_WINDOW_MS
                )
                if (transitPage != null) {
                    try {
                        pagerState.animateScrollToPage(
                            page = transitPage,
                            animationSpec = tween(TRANSITION_DURATION_MS)
                        )
                    } finally {
                        // Also when cancelled by a rapid second skip: never leave the pager parked on a ghost.
                        withContext(NonCancellable) { hopOffGhost() }
                    }
                } else {
                    pagerState.animateScrollToPage(
                        page = queueIndex,
                        animationSpec = tween(TRANSITION_DURATION_MS)
                    )
                }
            } else {
                pagerState.scrollToPage(queueIndex)
            }
        }
    }

    val uiAlpha = remember(playerTransitionState) {
        {
            val raw = playerTransitionState.progress().coerceIn(0f, 1f)
            if (playerTransitionState.targetState) {
                EaseOutCubic.transform(raw)
            } else {
                EaseInCubic.transform(raw)
            }
        }
    }

    val subControlsProgress = remember(upTransitionState) {
        {
            val raw = upTransitionState.progress().coerceIn(0f, 1f)
            if (upTransitionState.targetState) EaseOutCubic.transform(raw) else EaseInCubic.transform(raw)
        }
    }

    with(sharedTransitionScope) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            val indexA by remember(pagerState, pagerAnchoredQueue) {
                derivedStateOf {
                    val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    absoluteOffset.toInt().coerceIn(0, (pagerAnchoredQueue.size - 1).coerceAtLeast(0))
                }
            }

            val indexB by remember(pagerState, pagerAnchoredQueue) {
                derivedStateOf {
                    val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    (absoluteOffset.toInt() + 1).coerceAtMost((pagerAnchoredQueue.size - 1).coerceAtLeast(0))
                }
            }

            // Composition-phase reads of the derived indices: they are otherwise only consumed
            // inside the SideEffect below, and SideEffects re-run on recomposition. Nothing in
            // this scope reads pager state during composition, so a pager settle changes
            // indexA/indexB without recomposing, the SideEffect never re-runs on the settle
            // frame, and crossfadeFraction (a draw-phase read) wraps 1f -> 0f against the stale
            // paths -- snapping the backdrop back to the previous track's art at the end of
            // every swipe. When no skipToIndex roundtrip follows (e.g. swiping back to the
            // playing track's page), the stale image persists instead of correcting itself.
            val backdropIndexA = indexA
            val backdropIndexB = indexB

            // derivedStateOf gate: programmaticBackdropCrossfade.value advances every frame
            // of the crossfade animation, and reading it raw here recomposed this whole
            // screen per frame for the full crossfade duration. The boolean only flips at
            // the crossfade's start and end.
            val isProgrammaticCrossfadeActive by remember {
                derivedStateOf {
                    programmaticBackdropToPath != null && programmaticBackdropCrossfade.value < 1f
                }
            }
            // Composition-phase read of that gate. The SideEffect below runs in the apply
            // phase, where state reads are UNTRACKED -- without this read, the paths written
            // inside the crossfade's LaunchedEffect invalidated nothing, so the publication
            // never switched onto the programmatic pair when the fade started and never
            // handed back to the settled pager pair when it completed. From the subview this
            // mattered twice over: next/previous SNAP the hidden pager once (no sweep to
            // re-trigger the SideEffect mid-fade like a visible pager's swipe does), so once
            // the fade finished and cleared its paths, nothing ever re-ran the SideEffect --
            // the backdrop stayed published with front = the OLD track's art and the stored
            // fraction lambda falling back to the pager offset against the already-snapped
            // page (0 == the old art) until an unrelated recomposition, e.g. a play/pause
            // flip, corrected it. The boolean flips exactly twice per crossfade, so this
            // costs two recompositions of this scope per fade.
            val programmaticCrossfadeActive = isProgrammaticCrossfadeActive

            // The blurred backdrop itself is hosted at the very root of the hierarchy (see
            // FoamNavigation and FullPlayerBackdropState): the same fillMaxSize box as the
            // app-wide backdrop, so both crop the artwork identically and the open/close
            // crossfade between them is seamless. Only the pager-driven state is produced
            // here. SideEffect runs after apply and before the frame's draw, so the host
            // never draws a stale frame.
            SideEffect {
                // Resolve the pager indices NOW, against the same snapshot pair
                // (pagerState + pagerAnchoredQueue) the effect above just left coherent.
                // backdropIndexA/B were captured during composition, i.e. under the
                // pre-snap numbering; when a row before the playing one is removed, the
                // effect above snaps the pager and adopts the shifted queue in this same
                // apply pass, so pairing those stale indices with the new queue shows the
                // NEXT track's art for exactly one frame. The captured values above stay
                // only as the composition-phase read that re-triggers this effect.
                val lastAnchoredIndex = (pagerAnchoredQueue.size - 1).coerceAtLeast(0)
                val liveOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                val liveIndexA = liveOffset.toInt().coerceIn(0, lastAnchoredIndex)
                val liveIndexB = (liveOffset.toInt() + 1).coerceAtMost(lastAnchoredIndex)

                backdropState.frontArtPath =
                    if (programmaticCrossfadeActive) {
                        programmaticBackdropFromPath
                    } else {
                        pagerAnchoredQueue.getOrNull(liveIndexA)?.albumArtPath
                    }
                backdropState.backArtPath =
                    if (programmaticCrossfadeActive) {
                        programmaticBackdropToPath
                    } else if (playerTransitionState.currentState != playerTransitionState.targetState) {
                        null
                    } else {
                        pagerAnchoredQueue.getOrNull(liveIndexB)?.albumArtPath
                    }
                backdropState.immersive = isImmersiveEnabled
                backdropState.canvasUrl = animatedCover?.url
                backdropState.canvasPlaying = isCanvasPlaying
                val liveCoverIndex = animatedCover
                    ?.let { pagerAnchoredEntryIds.indexOf(it.entryId) }
                    ?: -1
                backdropState.canvasAnchor = when {
                    animatedCover == null || programmaticCrossfadeActive -> CanvasAnchor.NONE
                    liveCoverIndex == liveIndexA -> CanvasAnchor.FRONT
                    liveCoverIndex == liveIndexB -> CanvasAnchor.BACK
                    else -> CanvasAnchor.NONE
                }
                coverCarry.owner = animatedCover

                backdropState.crossfadeFraction =
                    if (programmaticCrossfadeActive) {
                        { programmaticBackdropCrossfade.value }
                    } else {
                        {
                            if (upTransitionState.currentState || backdropHopHold || backdropRenumberHold) {
                                0f
                            } else {
                                val absoluteOffset =
                                    pagerState.currentPage + pagerState.currentPageOffsetFraction
                                (absoluteOffset - liveIndexA).coerceIn(0f, 1f)
                            }
                        }
                    }
                backdropState.subViewBlurProgress = subViewBlurProgressProvider
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = uiAlpha() }
                    .statusBarsPadding()
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    upTransition.AnimatedVisibility(
                        visible = { !it },
                        enter = fadeIn(tween(TRANSITION_DURATION_MS)) +
                                slideInVertically(tween(TRANSITION_DURATION_MS)) { h -> -h / PlayerDimens.headerSlideDivisor },
                        exit = fadeOut(tween(TRANSITION_DURATION_MS)) +
                                slideOutVertically(tween(TRANSITION_DURATION_MS)) { h -> -h / PlayerDimens.headerSlideDivisor },
                        modifier = Modifier.fillMaxSize().then(playerDrag)
                    ) {
                        MainPlayerContent(
                            track = track,
                            queue = queue,
                            queueIndex = queueIndex,
                            queueEntryIds = queueEntryIds,
                            isPlaying = isPlayingProvider,
                            isPlaybackActive = isPlaybackActiveProvider,
                            durationMs = durationMs,
                            pagerState = pagerState,
                            density = density,
                            screenWidthDp = configuration.screenWidthDp.dp,
                            placeholderColor = placeholderColor,
                            immersive = isImmersiveEnabled,
                            outerVisibilityScope = animatedVisibilityScope,
                            headerVisibilityScope = this@AnimatedVisibility,
                            viewModel = viewModel,
                            playerTransitionState = playerTransitionState,
                            upTransitionState = upTransitionState,
                            seekBarProgress = seekBarProgress,
                            isAnimateSeekBarEnabled = settleSeekBarAnimated,
                            seekBarStyle = seekBarStyle,
                            audioInfoText = audioInfoText,
                            onAudioInfoClick = { showAudioInfoModal = true },
                            morphFromWaveform = morphFromWaveform,
                            morphTargetWaveform = morphTargetWaveform,
                            envelopeMorphProgress = envelopeMorphProgress,
                            marqueeMode = marqueeMode,
                            scoreOverrides = scoreOverrides,
                            onSetScore = viewModel::setTrackScore,
                            onOpenAlbum = onOpenAlbum,
                            onOpenArtist = onOpenArtist,
                            flightGeometry = flightGeometry,
                            onSkipIntent = markSkipIntent
                        )
                    }

                    upTransition.AnimatedVisibility(
                        visible = { it },
                        enter = fadeIn(tween(TRANSITION_DURATION_MS)) +
                                slideInVertically(tween(TRANSITION_DURATION_MS)) { h -> h / 6 },
                        exit = fadeOut(tween(TRANSITION_DURATION_MS)) +
                                slideOutVertically(tween(TRANSITION_DURATION_MS)) { h -> h / 6 },
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(nestedScrollConnection)
                    ) {
                        SubViewContent(
                            track = track,
                            subViewMode = subViewMode,
                            viewModel = viewModel,
                            isPlaying = isPlayingProvider,
                            isPlaybackActive = isPlaybackActiveProvider,
                            isImmersiveEnabled = isImmersiveEnabled,
                            marqueeMode = marqueeMode,
                            headerVisibilityScope = this@AnimatedVisibility,
                            headerArtAlpha = { if (isImmersiveEnabled) upTransitionState.progress() else 1f },
                            modifier = playerDrag,
                            onCollapse = { gestureController.collapseToMainPlayer() },
                            upTransitionState = upTransitionState
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    val latestNavbarExpandHeight = rememberUpdatedState(navbarExpandHeight)
                    val latestSubControlsProgress = rememberUpdatedState(subControlsProgress)

                    val selectorRowModifier = remember(subControlsProgress, uiAlpha) {
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = PlayerDimens.screenPadding,
                                end = PlayerDimens.screenPadding,
                                top = PlayerDimens.screenPadding * 2 - PlayerDimens.selectorRowBottomPadding,
                                bottom = PlayerDimens.selectorRowBottomPadding
                            )
                            .graphicsLayer {
                                alpha = uiAlpha()
                                val progress = latestSubControlsProgress.value()
                                translationY = PlayerDimens.selectorRowNavbarDrop.toPx() * (1f - progress) -
                                        PlayerDimens.selectorRowRise.toPx() * progress -
                                        latestNavbarExpandHeight.value()
                            }
                    }

                    SubViewSelectorRow(
                        subViewMode = subViewMode,
                        repeatMode = state.repeatMode,
                        progress = subControlsProgress,
                        onSelect = { mode ->
                            if (upTransitionState.targetState) {
                                if (subViewMode == mode) {
                                    gestureController.collapseToMainPlayer()
                                } else {
                                    subViewMode = mode
                                }
                            } else {
                                subViewMode = mode
                                gestureController.expandToSubView()
                            }
                        },
                        onRadioClick = { /* TODO: radio */ },
                        onPlayOnClick = { showPlayOnModal = true },
                        onRepeatClick = { viewModel.toggleRepeat() },
                        modifier = selectorRowModifier
                    )

                    val transportClusterModifier = remember(subControlsProgress) {
                        Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationY = -PlayerDimens.playButtonRise.toPx() *
                                        latestSubControlsProgress.value() -
                                        latestNavbarExpandHeight.value()
                            }
                    }

                    Row(
                        modifier = transportClusterModifier,
                        horizontalArrangement = Arrangement.spacedBy(PlayerDimens.transportSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniTransportButton(
                            icon = FoamTheme.icons.current.previous,
                            contentDescription = stringResource(R.string.previous),
                            progress = subControlsProgress,
                            onClick = {
                                when (
                                    seekBarProgress.onPreviousPressed(
                                        animated = settleSeekBarAnimated,
                                        positionMs = viewModel.positionMs.value,
                                        morphTargetPage = if (isWaveformSeek) {
                                            (queueIndex - 1).takeIf { it >= 0 }
                                        } else {
                                            null
                                        },
                                        fromBlend = envelopeMorphProgress(),
                                        fromPageIndex = queueIndex
                                    )
                                ) {
                                    PreviousAction.RESTART_CURRENT -> viewModel.seekTo(0L)
                                    PreviousAction.SKIP_TO_PREVIOUS -> {
                                        markSkipIntent(true)
                                        viewModel.previous()
                                    }
                                }
                            }
                        )

                        MiniPlayPauseButton(
                            isPlaying = isPlayingProvider,
                            progress = subControlsProgress,
                            onClick = { if (isPlayingProvider()) viewModel.pause() else viewModel.play() }
                        )

                        MiniTransportButton(
                            icon = FoamTheme.icons.current.next,
                            contentDescription = stringResource(R.string.next),
                            progress = subControlsProgress,
                            onClick = {
                                seekBarProgress.onNextPressed(
                                    animated = settleSeekBarAnimated,
                                    morphTargetPage = if (isWaveformSeek) {
                                        (queueIndex + 1).takeIf { it < queueSize }
                                    } else {
                                        null
                                    },
                                    fromBlend = envelopeMorphProgress(),
                                    fromPageIndex = queueIndex
                                )
                                markSkipIntent(false)
                                viewModel.next()
                            }
                        )
                    }
                }
            }

            if (showSubViewSlider) {
                PlaybackProgressSlider(
                    durationMs = durationMs,
                    seekBarProgress = seekBarProgress,
                    isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                    onSeek = { positionMs -> viewModel.seekTo(positionMs) },
                    seekBarStyle = SeekBarStyle.NORMAL,
                    labelSpacing = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            start = PlayerDimens.screenPadding,
                            end = PlayerDimens.screenPadding,
                            top = PlayerDimens.seekBarRowVerticalPadding,
                            bottom = PlayerDimens.seekBarRowBottomPadding
                        )
                        .graphicsLayer {
                            alpha = subControlsProgress()
                            translationY = PlayerDimens.seekBarRise.toPx() * (1f - subControlsProgress()) -
                                    navbarExpandHeight()
                        }
                )
            }
        }
    }

    PlayerAudioInfoModal(
        visible = showAudioInfoModal,
        onDismiss = { showAudioInfoModal = false },
        outputInfo = audioOutputInfo,
        track = track
    )

    PlayOnModal(
        visible = showPlayOnModal,
        onDismiss = { showPlayOnModal = false },
        devices = playOnDevices,
        selectedDeviceId = selectedPlayOnDeviceId ?: PlayOnDevice.ThisDeviceId,
        onDeviceSelected = { device ->
            showPlayOnModal = false
            if (device.id != (selectedPlayOnDeviceId ?: PlayOnDevice.ThisDeviceId)) {
                viewModel.routePlayOn(device.id)
            }
        }
    )
}

@Composable
private fun TrackChangeCommitEffect(
    pagerState: PagerState,
    currentQueueIndex: Int,
    currentQueueSize: Int,
    isAnimateSeekBarEnabled: Boolean,
    isWaveformSeek: Boolean,
    envelopeMorphProgress: () -> Float,
    seekBarProgress: SeekBarProgressState,
    /** True for a ghost page twinning the playing track: the pager is landing a wrap, not starting a change. */
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
private const val SKIP_INTENT_WINDOW_MS = 1_500L