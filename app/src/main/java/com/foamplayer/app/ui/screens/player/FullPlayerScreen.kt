package com.foamplayer.app.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseInOutCubic
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
import com.foamplayer.app.ui.components.PreviousAction
import com.foamplayer.app.ui.components.SeekBarProgressState
import com.foamplayer.app.ui.components.rememberSeekBarProgressState
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import kotlin.math.abs

/**
 * Live geometry feeding the dismiss swipe's 1:1 finger-to-artwork mapping: the nav
 * root reads [heroArtCenterY] (root coordinates) and pairs it with the flight target
 * (mini player art or the origin collection's centered row) to size the swipe.
 */
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

    // Narrow, equality-gated projections of the playback state -- the same idea as the
    // ViewModel's narrow projections for the composition roots, kept local. derivedStateOf
    // only notifies its readers when the projected value actually changes, so unrelated
    // PlaybackState emissions (play/pause flips, audio-output changes) no longer recompose
    // this whole screen: a play/pause press now only invalidates the transport leaves that
    // read it. isPlaying deliberately stays raw -- it is consumed exclusively through the
    // deferred provider below, inside leaf scopes (transport buttons, paused-art scale).
    val currentTrack by remember { derivedStateOf { state.currentTrack } }
    val queue by remember { derivedStateOf { state.queue } }
    val queueIndex by remember { derivedStateOf { state.queueIndex } }
    val queueEntryIds by remember { derivedStateOf { state.queueEntryIds } }
    val durationMs by remember { derivedStateOf { state.durationMs } }
    val isPlayingProvider = { state.isPlaying }
    val track = currentTrack ?: return
    val queueSize = queue.size

    var subViewMode by remember { mutableStateOf(SubViewMode.LYRICS) }

    val upTransitionState = remember { SeekableTransitionState(false) }
    val upTransition = rememberTransition(upTransitionState, label = "UpTransition")
    
    // Boolean gate only: reading the raw transition fraction here used to re-run this
    // whole screen body on every frame of the subview drag/settle. derivedStateOf
    // invalidates just once, when the fraction crosses the 0f threshold.
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
    // feature setting is on. Keyed on the track so a pager skip cancels any
    // in-flight fetch and resolves the new track's canvas instead; null keeps
    // the video layer out of composition entirely (zero overhead when off).
    var animatedCoverUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(track.id, isAnimatedCoverEnabled, allowMobileDataForCanvas) {
        // Cut any previous track's video at once: the backdrop's static-art
        // crossfade takes over while the new canvas resolves, then fades in.
        animatedCoverUrl = null
        if (isAnimatedCoverEnabled) {
            animatedCoverUrl = viewModel
                .resolveAnimatedCover(track, allowMobileDataForCanvas)
                ?.preferredAnimationUrl
        }
    }
    // Composition-phase read: the SideEffect below runs outside snapshot
    // observation, so this local is what re-runs it when the url resolves.
    val currentAnimatedCoverUrl = animatedCoverUrl
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

    LaunchedEffect(pagerState, queue) {
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
        isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
        seekBarProgress = seekBarProgress
    )

    val latestQueueIndex = rememberUpdatedState(queueIndex)
    val latestQueueSize = rememberUpdatedState(queueSize)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { settledPage ->
                if (isMainPagerVisible() &&
                    settledPage in 0 until latestQueueSize.value &&
                    settledPage != latestQueueIndex.value
                ) {
                    seekBarProgress.onTrackChangeApplied()
                    viewModel.skipToIndex(settledPage)
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
                backdropState.canvasUrl = currentAnimatedCoverUrl
                backdropState.canvasPlaying = isCanvasPlaying
                backdropState.canvasSwipeFraction = {
                    // Distance of the pager from the playing track's page,
                    // direction-agnostic: the canvas fades out while the page
                    // drags either way and returns when the drag settles back.
                    val anchor = queueIndex.coerceAtLeast(0)
                    val offset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    abs(offset - anchor).coerceIn(0f, 1f)
                }
                backdropState.crossfadeFraction = {
                    if (programmaticBackdropToPath != null && programmaticBackdropCrossfade.value < 1f) {
                        programmaticBackdropCrossfade.value
                    } else {
                        // Anchor the fraction to backdropIndexA -- the exact index that
                        // produced the frontArtPath written by THIS SideEffect run -- rather
                        // than to absoluteOffset.toInt(). The lambda is read during the host's
                        // draw phase, which can observe a pager offset one tick fresher than
                        // the composition that produced the paths (the settle tick of an
                        // animateScrollToPage routinely lands after that frame's composition).
                        // With toInt(), such a tick wraps the fraction against stale paths:
                        // going next, offset N+1 against paths (N, N+1) wraps 1f -> 0f and
                        // flashes the previous track at the end of the animation; going
                        // previous, offset N-ε against resting paths (N, N+1) jumps to ~1f and
                        // flashes the next track at the start. Anchored to backdropIndexA the
                        // stale pairing renders coherently instead: offset N+1 -> fraction 1f
                        // (back layer = the new track, fully covering the front), offset N-ε ->
                        // fraction 0f (front layer = the current track). Whenever the paths are
                        // fresh, backdropIndexA == floor(absoluteOffset), so this is identical
                        // to the toInt() math.
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
                            isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                            scoreOverrides = scoreOverrides,
                            onSetScore = viewModel::setTrackScore,
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
                                        animated = isAnimateSeekBarEnabled,
                                        positionMs = viewModel.positionMs.value
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
                                seekBarProgress.onNextPressed(animated = isAnimateSeekBarEnabled)
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
}

@Composable
private fun TrackChangeCommitEffect(
    pagerState: PagerState,
    currentQueueIndex: Int,
    currentQueueSize: Int,
    isAnimateSeekBarEnabled: Boolean,
    seekBarProgress: SeekBarProgressState
) {
    val latestQueueIndex by rememberUpdatedState(currentQueueIndex)
    val latestQueueSize by rememberUpdatedState(currentQueueSize)
    val latestIsAnimateSeekBarEnabled by rememberUpdatedState(isAnimateSeekBarEnabled)

    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(pagerState) {
        snapshotFlow { isDragged to pagerState.targetPage }
            .collect { (dragging, targetPage) ->
                if (!dragging &&
                    targetPage in 0 until latestQueueSize &&
                    targetPage != latestQueueIndex
                ) {
                    seekBarProgress.onTrackChangeCommitted(animated = latestIsAnimateSeekBarEnabled)
                }
            }
    }
}
