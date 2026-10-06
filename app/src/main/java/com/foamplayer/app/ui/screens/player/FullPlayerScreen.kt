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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.foamplayer.app.ui.components.PreviousAction
import com.foamplayer.app.ui.components.SeekBarProgressState
import com.foamplayer.app.ui.components.rememberSeekBarProgressState
import com.foamplayer.app.ui.modals.PlayerAudioInfoModal
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.WaveformCache
import com.foamplayer.app.ui.utils.WaveformState
import com.foamplayer.app.ui.utils.rememberWaveform
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FullPlayerScreen(
    viewModel: PlayerViewModel,
    onDismiss: (velocityPxPerSec: Float) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionState: SeekableTransitionState<Boolean>,
    backdropState: FullPlayerBackdropState,
    isAnimateSeekBarEnabled: Boolean = false,
    // Title/artist box gestures: tap opens the album view, long-press opens the
    // artist view (both staged through the nav root, closing the player).
    onOpenAlbum: (TrackEntity) -> Unit = {},
    onOpenArtist: (TrackEntity) -> Unit = {},
    // Dismiss-to-origin-collection coordination (see PlayerGestureController): the
    // nav root preloads the row flight on finger-down (compose the page invisibly,
    // scroll the row into view -- no pairing), TAGS the row on the first drag delta
    // (activation) and is told when the gesture settles back without committing.
    onDismissGestureStart: (suspend () -> Unit)? = null,
    onDismissGestureActivate: (suspend () -> Unit)? = null,
    onDismissGestureCancelled: (() -> Unit)? = null,
    // Finger-to-artwork travel distance for the dismiss swipe (1:1 tracking); falls
    // back to the screen height when unknown.
    dismissSwipeDistancePx: (() -> Float)? = null,
    // Receives the hero artwork's center so the nav root can compute the swipe
    // distance above.
    flightGeometry: PlayerFlightGeometryState? = null
) {
    val state by viewModel.playbackState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val scoreOverrides by viewModel.scoreOverrides.collectAsState()
    val isImmersiveEnabled = settings.immersiveModeEnabled
    val isAnimatedCoverEnabled = isImmersiveEnabled && settings.animatedCoverEnabled
    val allowMobileDataForCanvas = settings.animatedCoverAllowMobileData
    val marqueeMode = settings.marqueeMode
    // Seek bar rendering (full player only; the mini player keeps the classic bar).
    val seekBarStyle = settings.seekBarStyle

    // Waveform bars always settle track changes with an animation (they recede on
    // the swipe commit instead of snapping -- see SeekBarProgressState); the classic
    // bar only does so when the "animate seek bar" setting is on. Continuous
    // swipe-follow of the bars stays tied to the setting alone (see liveProgress).
    val settleSeekBarAnimated = isAnimateSeekBarEnabled || seekBarStyle == SeekBarStyle.WAVEFORM

    // ── Waveform envelope morph (waveform seek bar only) ─────────────────────────
    // Track changes morph the bars between the two tracks' amplitude envelopes
    // instead of hard-swapping them. The swap's pinned pages live in
    // [SeekBarProgressState] (shared with the mask settle's hold/settle lifecycle);
    // this screen resolves the pinned envelopes through the process-wide cache and
    // hands the blend driver to the waveform. While the finger is down the blend is
    // the hero pager's own swipe fraction (progressive; raw "animate seek bar" setting
    // on), and at RELEASE a tween takes the blend over -- committing swipes tween on
    // up to 1, spring-backs tween back down to 0 -- so the morph always terminates
    // with an animation instead of snapping or coasting on the pager's fling.
    val isWaveformSeek = seekBarStyle == SeekBarStyle.WAVEFORM
    val isProgressiveEnvelope = isWaveformSeek && isAnimateSeekBarEnabled

    // Narrow, equality-gated projections of the playback state -- the same idea as the
    // ViewModel's narrow projections for the composition roots (FoamApp,
    // FoamNavHost). Collecting these instead of the whole state keeps emissions like
    // play/pause flips, audio-output changes) no longer recompose
    // this whole screen: a play/pause press now only invalidates the transport leaves that
    // read it. isPlaying deliberately stays raw -- it is consumed exclusively through the
    // deferred provider below, inside leaf scopes (transport buttons, paused-art scale).
    val currentTrack by remember { derivedStateOf { state.currentTrack } }
    val queue by remember { derivedStateOf { state.queue } }
    val queueIndex by remember { derivedStateOf { state.queueIndex } }
    val queueEntryIds by remember { derivedStateOf { state.queueEntryIds } }
    val durationMs by remember { derivedStateOf { state.durationMs } }
    val audioOutputInfo by remember { derivedStateOf { state.audioOutputInfo } }
    val isPlayingProvider = { state.isPlaying }
    // Combined playback-active bit for the paused-art shrink: isPlaying is false
    // through the buffering gap between prepare() and STATE_READY even though
    // playback was just requested, and shrinking across that gap made every track
    // tap scale the art down for the load duration and back up when ready. A
    // buffering read must not be a paused read -- see rememberPausedArtScale.
    // The mini player applies the identical formula, so both ends of the
    // album-art shared-element pair always render the same scale mid-flight.
    val isPlaybackActiveProvider = { state.isPlaying || state.isPlaybackRequested }
    val track = currentTrack ?: return
    val queueSize = queue.size

    var subViewMode by remember { mutableStateOf(SubViewMode.LYRICS) }
    var showAudioInfoModal by remember { mutableStateOf(false) }

    // Chip label between the lyrics/queue selector icons: live audio format +
    // bitrate from the engine's output info, falling back to the track's tags.
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

    val pagerState = rememberPagerState(
        initialPage = queueIndex.coerceAtLeast(0),
        pageCount = { queue.size }
    )

    val idlePage by remember {
        derivedStateOf { if (pagerState.isScrollInProgress) -1 else pagerState.currentPage }
    }
    val coverCarry = remember { AnimatedCoverCarry() }
    val animatedCover: AnimatedCoverOwner? = if (!isAnimatedCoverEnabled) {
        null
    } else {
        val resolved = resolvedCover
        if (resolved != null &&
            resolved.entryId == currentEntryId &&
            idlePage == queueIndex.coerceAtLeast(0)
        ) {
            resolved
        } else {
            coverCarry.owner?.takeIf { carried ->
                idlePage == -1 || queueEntryIds.indexOf(carried.entryId) == idlePage
            }
        }
    }
    val animatedCoverIndex = animatedCover
        ?.let { queueEntryIds.indexOf(it.entryId) }
        ?: -1

    val seekBarProgress = rememberSeekBarProgressState(
        positionMsFlow = viewModel.positionMs,
        liveProgress = { livePositionMs ->
            val progress = if (durationMs > 0) livePositionMs.toFloat() / durationMs else 0f

            val activeFraction = if (isAnimateSeekBarEnabled) {
                val scrollPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
                val distanceFromCurrent = abs(scrollPosition - queueIndex.coerceAtLeast(0))
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
    // hero pager's own fraction from the anchor page toward the pinned target, so the
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
                        val absolute = pagerState.currentPage + pagerState.currentPageOffsetFraction
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
    LaunchedEffect(pagerState, queue, settings.automaticColorEnabled) {
        if (!settings.automaticColorEnabled) return@LaunchedEffect
        snapshotFlow {
            AlbumArtPaletteCache.interpolatedColor(
                currentPage = pagerState.currentPage,
                currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                pageCount = queue.size
            ) { page -> queue.getOrNull(page)?.albumArtPath }
        }.collect { interpolatedColor ->
            viewModel.setDynamicAccentColor(interpolatedColor)
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
        seekBarProgress = seekBarProgress
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
    LaunchedEffect(queueIndex) {
        seekBarProgress.onPlayingIndexChanged(queueIndex, previousQueueIndex)
        previousQueueIndex = queueIndex
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { settledPage ->
                if (isMainPagerVisible() &&
                    settledPage in 0 until latestQueueSize.value &&
                    settledPage != latestQueueIndex.value
                ) {
                    seekBarProgress.onTrackChangeApplied()
                    // Keep the swipe direction: a swipe back must not land on a
                    // track that failed to load (it walks further back instead).
                    viewModel.skipToIndex(
                        settledPage,
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

    val programmaticBackdropCrossfade = remember { Animatable(1f) }
    var programmaticBackdropFromPath by remember { mutableStateOf<String?>(null) }
    var programmaticBackdropToPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(queueIndex) {
        if (!isMainPagerVisible() &&
            queueIndex in 0 until queueSize &&
            pagerState.currentPage != queueIndex
        ) {
            val fromPath = queue.getOrNull(pagerState.currentPage)?.albumArtPath
            val toPath = queue.getOrNull(queueIndex)?.albumArtPath
            if (fromPath != toPath) {
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
                    // in a cancelled coroutine's finally block without suspension.
                    programmaticBackdropFromPath = null
                    programmaticBackdropToPath = null
                }
            }
        }
    }

    LaunchedEffect(queueIndex) {
        if (queueIndex in 0 until queueSize &&
            pagerState.currentPage != queueIndex &&
            !pagerState.isScrollInProgress
        ) {
            if (isMainPagerVisible()) {
                pagerState.animateScrollToPage(
                    page = queueIndex,
                    animationSpec = tween(TRANSITION_DURATION_MS)
                )
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
            val indexA by remember(pagerState, queueSize) {
                derivedStateOf {
                    val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    absoluteOffset.toInt().coerceIn(0, (queueSize - 1).coerceAtLeast(0))
                }
            }

            val indexB by remember(pagerState, queueSize) {
                derivedStateOf {
                    val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    (absoluteOffset.toInt() + 1).coerceAtMost((queueSize - 1).coerceAtLeast(0))
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

            // The blurred backdrop itself is hosted at the very root of the hierarchy (see
            // FoamNavigation and FullPlayerBackdropState): the same fillMaxSize box as the
            // app-wide backdrop, so both crop the artwork identically and the open/close
            // crossfade between them is seamless. Only the pager-driven state is produced
            // here. SideEffect runs after apply and before the frame's draw, so the host
            // never draws a stale frame.
            SideEffect {
                backdropState.frontArtPath =
                    if (isProgrammaticCrossfadeActive) {
                        programmaticBackdropFromPath
                    } else {
                        queue.getOrNull(backdropIndexA)?.albumArtPath
                    }
                backdropState.backArtPath =
                    if (isProgrammaticCrossfadeActive) {
                        programmaticBackdropToPath
                    } else if (playerTransitionState.currentState != playerTransitionState.targetState) {
                        null
                    } else {
                        queue.getOrNull(backdropIndexB)?.albumArtPath
                    }
                backdropState.immersive = isImmersiveEnabled
                backdropState.canvasUrl = animatedCover?.url
                backdropState.canvasPlaying = isCanvasPlaying
                backdropState.canvasAnchor = when {
                    animatedCover == null || isProgrammaticCrossfadeActive -> CanvasAnchor.NONE
                    animatedCoverIndex == backdropIndexA -> CanvasAnchor.FRONT
                    animatedCoverIndex == backdropIndexB -> CanvasAnchor.BACK
                    else -> CanvasAnchor.NONE
                }
                coverCarry.owner = animatedCover
                backdropState.crossfadeFraction = {
                    if (programmaticBackdropToPath != null && programmaticBackdropCrossfade.value < 1f) {
                        programmaticBackdropCrossfade.value
                    } else {
                        val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        (absoluteOffset - backdropIndexA).coerceIn(0f, 1f)
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
                            morphFromWaveform = morphFromWaveform,
                            morphTargetWaveform = morphTargetWaveform,
                            envelopeMorphProgress = envelopeMorphProgress,
                            marqueeMode = marqueeMode,
                            scoreOverrides = scoreOverrides,
                            onSetScore = viewModel::setTrackScore,
                            onOpenAlbum = onOpenAlbum,
                            onOpenArtist = onOpenArtist,
                            flightGeometry = flightGeometry
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
                                translationY = -PlayerDimens.selectorRowRise.toPx() * subControlsProgress()
                            }
                    }

                    SubViewSelectorRow(
                        subViewMode = subViewMode,
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
                        audioInfoText = audioInfoText,
                        onAudioInfoClick = { showAudioInfoModal = true },
                        modifier = selectorRowModifier
                    )

                    val transportClusterModifier = remember(subControlsProgress) {
                        Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationY = -PlayerDimens.playButtonRise.toPx() * subControlsProgress()
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
                                    PreviousAction.SKIP_TO_PREVIOUS -> viewModel.previous()
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
                    isAnimateSeekBarEnabled = settleSeekBarAnimated,
                    onSeek = { positionMs -> viewModel.seekTo(positionMs) },
                    seekBarStyle = seekBarStyle,
                    track = track,
                    morphFromWaveform = morphFromWaveform,
                    morphTargetWaveform = morphTargetWaveform,
                    trackChangeProgress = envelopeMorphProgress,
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
                            translationY = PlayerDimens.seekBarRise.toPx() * (1f - subControlsProgress())
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
}

@Composable
private fun TrackChangeCommitEffect(
    pagerState: PagerState,
    currentQueueIndex: Int,
    currentQueueSize: Int,
    isAnimateSeekBarEnabled: Boolean,
    isWaveformSeek: Boolean,
    envelopeMorphProgress: () -> Float,
    seekBarProgress: SeekBarProgressState
) {
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
                    targetPage != latestQueueIndex
                ) {
                    // Release terminating on a NEW track: the envelope swap tweens on
                    // up to blend 1 from wherever the pager fraction left it (a
                    // progressive swipe) or from 0 (a non-progressive bar), in the same
                    // motion as the mask's settle tween. fromPageIndex pins the
                    // trigger-time playing page so the engine's later flip cannot swap
                    // the from-envelope underneath the tween.
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
