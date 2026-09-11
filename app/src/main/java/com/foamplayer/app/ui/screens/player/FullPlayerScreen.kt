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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.PreviousAction
import com.foamplayer.app.ui.components.SeekBarProgressState
import com.foamplayer.app.ui.components.rememberSeekBarProgressState
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FullPlayerScreen(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionState: SeekableTransitionState<Boolean>,
    backdropBottomExtension: Dp = 0.dp,
    isAnimateSeekBarEnabled: Boolean = false
) {
    val state by viewModel.playbackState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val isImmersiveEnabled = settings.immersiveModeEnabled
    val track = state.currentTrack ?: return

    var subViewMode by remember { mutableStateOf(SubViewMode.LYRICS) }

    val upTransitionState = remember { SeekableTransitionState(false) }
    val upTransition = rememberTransition(upTransitionState, label = "UpTransition")
    
    val rawSubViewProgress = upTransitionState.progress()
    val subViewBlurProgress = if (upTransitionState.targetState) {
        (rawSubViewProgress * PlayerDimens.subViewBlurRushFactor).coerceIn(0f, 1f)
    } else {
        rawSubViewProgress * rawSubViewProgress
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val scope = rememberCoroutineScope()

    val dragThresholdPx = screenHeightPx.coerceAtLeast(1f)
    val upDragThresholdPx = with(density) { PlayerDimens.subViewSwipeThreshold.toPx() }.coerceAtLeast(1f)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val gestureController = remember(playerTransitionState, dragThresholdPx, upDragThresholdPx) {
        PlayerGestureController(
            upTransitionState = upTransitionState,
            playerTransitionState = playerTransitionState,
            scope = scope,
            dragThresholdPx = dragThresholdPx,
            upDragThresholdPx = upDragThresholdPx,
            onDismiss = { currentOnDismiss.value() }
        )
    }

    BackHandler(enabled = upTransitionState.currentState || upTransitionState.targetState) {
        gestureController.collapseToMainPlayer()
    }

    val playerDrag = Modifier.draggable(
        state = rememberDraggableState { delta -> gestureController.onDrag(delta) },
        orientation = Orientation.Vertical,
        onDragStopped = { v -> gestureController.onDragStopped(v) }
    )

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

    val placeholderColor = if (playerTransitionState.currentState != playerTransitionState.targetState ||
            upTransitionState.currentState != upTransitionState.targetState) Color.Transparent else FoamTheme.colors.surfaceVariant

    val pagerState = rememberPagerState(
        initialPage = state.queueIndex.coerceAtLeast(0),
        pageCount = { state.queue.size }
    )

    val seekBarProgress = rememberSeekBarProgressState(
        positionMsFlow = viewModel.positionMs,
        liveProgress = { livePositionMs ->
            val progress = if (state.durationMs > 0) livePositionMs.toFloat() / state.durationMs else 0f

            val activeFraction = if (isAnimateSeekBarEnabled) {
                val scrollPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
                val distanceFromCurrent = abs(scrollPosition - state.queueIndex.coerceAtLeast(0))
                (1f - distanceFromCurrent).coerceIn(0f, 1f)
            } else {
                1f
            }

            progress * activeFraction
        }
    )

    val queueIndex = state.queueIndex
    val queueSize = state.queue.size

    fun isMainPagerVisible() = !upTransitionState.currentState

    val context = LocalContext.current

    LaunchedEffect(pagerState, state.queue) {
        val queue = state.queue
        snapshotFlow { pagerState.currentPage }
            .collect { currentPage ->
                val indices = listOf(currentPage - 1, currentPage, currentPage + 1)
                val pathsToKeep = indices.mapNotNull { i -> queue.getOrNull(i)?.albumArtPath }.toSet()
                pathsToKeep.forEach { path ->
                    AlbumArtPaletteCache.ensure(scope = this, context = context, path = path)
                }
            }
    }

    LaunchedEffect(pagerState, state.queue) {
        val queue = state.queue
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
            val fromPath = state.queue.getOrNull(pagerState.currentPage)?.albumArtPath
            val toPath = state.queue.getOrNull(queueIndex)?.albumArtPath
            if (fromPath != toPath) {
                programmaticBackdropFromPath = fromPath
                programmaticBackdropToPath = toPath
                programmaticBackdropCrossfade.snapTo(0f)
                programmaticBackdropCrossfade.animateTo(1f, tween(TRANSITION_DURATION_MS))
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
        { EaseInOutCubic.transform(playerTransitionState.progress().coerceIn(0f, 1f)) }
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
            val indexA by remember(pagerState, state.queue.size) {
                derivedStateOf {
                    val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    absoluteOffset.toInt().coerceIn(0, (state.queue.size - 1).coerceAtLeast(0))
                }
            }

            val indexB by remember(pagerState, state.queue.size) {
                derivedStateOf {
                    val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    (absoluteOffset.toInt() + 1).coerceAtMost((state.queue.size - 1).coerceAtLeast(0))
                }
            }

            val isProgrammaticCrossfadeActive = programmaticBackdropToPath != null &&
                    programmaticBackdropCrossfade.value < 1f

            AlbumArtBackdrop(
                frontArtPath = if (isProgrammaticCrossfadeActive) programmaticBackdropFromPath else state.queue.getOrNull(indexA)?.albumArtPath,
                backArtPath = if (isProgrammaticCrossfadeActive) {
                    programmaticBackdropToPath
                } else if (playerTransitionState.currentState != playerTransitionState.targetState) {
                    null
                } else {
                    state.queue.getOrNull(indexB)?.albumArtPath
                },
                crossfadeFraction = {
                    if (isProgrammaticCrossfadeActive) {
                        programmaticBackdropCrossfade.value
                    } else {
                        val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        (absoluteOffset - absoluteOffset.toInt()).coerceIn(0f, 1f)
                    }
                },
                immersive = isImmersiveEnabled,
                subViewProgress = subViewBlurProgress,
                dynamicBackgroundEnabled = settings.dynamicBackgroundEnabled,
                backgroundBlur = settings.backgroundBlur.dp,
                modifier = Modifier
                    .extendBottom(backdropBottomExtension)
                    .graphicsLayer { alpha = uiAlpha() },
            )

            Box(
                modifier = Modifier
                    .extendBottom(backdropBottomExtension)
                    .fillMaxSize()
                    .graphicsLayer { alpha = PlayerDimens.scrimMaxAlpha * subViewBlurProgress * uiAlpha() }
                    .background(Color.Black)
            )

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
                            state = state,
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
                            isAnimateSeekBarEnabled = isAnimateSeekBarEnabled
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
                            headerVisibilityScope = this@AnimatedVisibility,
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
                                horizontal = PlayerDimens.screenPadding,
                                vertical = PlayerDimens.screenPadding
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
                            isPlaying = state.isPlaying,
                            progress = subControlsProgress,
                            onClick = { if (state.isPlaying) viewModel.pause() else viewModel.play() }
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

            if (rawSubViewProgress > 0f) {
                PlaybackProgressSlider(
                    durationMs = state.durationMs,
                    seekBarProgress = seekBarProgress,
                    isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                    onSeek = { positionMs -> viewModel.seekTo(positionMs) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            horizontal = PlayerDimens.screenPadding,
                            vertical = PlayerDimens.seekBarRowVerticalPadding
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
