package com.foamplayer.app.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
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
import androidx.compose.ui.unit.Dp
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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.settings.SeekBarStyle
import com.foamplayer.app.ui.components.LoopingPagerModelCache
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
    /** Opens the song-radio sheet for [TrackEntity] (the current track). */
    onOpenRadio: (TrackEntity) -> Unit = {},
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
    val isLoadingProvider = { state.isLoading }
    val isPlaybackActiveProvider = { state.isPlaying || state.isPlaybackRequested }
    val track = currentTrack ?: return
    val queueSize = queue.size

    var subViewMode by remember { mutableStateOf(SubViewMode.LYRICS) }
    var showAudioInfoModal by remember { mutableStateOf(false) }
    var showPlayOnModal by remember { mutableStateOf(false) }

    val playOnDevices by viewModel.playOnDevices.collectAsState()
    val selectedPlayOnDeviceId by viewModel.selectedPlayOnDeviceId.collectAsState()
    val castScanning by viewModel.castScanning.collectAsState()

    // Chromecast discovery is opt-in: it only runs while the sheet is open.
    DisposableEffect(showPlayOnModal) {
        if (showPlayOnModal) viewModel.startPlayOnDiscovery()
        onDispose {
            if (showPlayOnModal) viewModel.stopPlayOnDiscovery()
        }
    }

    val audioInfoText = run {
        val format = audioOutputInfo?.sourceFormat?.takeIf { it.isNotBlank() } ?: track.format
        val bitrateKbps = audioOutputInfo?.sourceBitrateKbps?.takeIf { it > 0 } ?: track.bitrate
        if (bitrateKbps > 0) "$format · $bitrateKbps kbps" else format
    }

    val upTransitionState = remember { SeekableTransitionState(false) }
    val upTransition = rememberTransition(upTransitionState, label = "UpTransition")

    val showSubViewSlider by remember(upTransitionState) {
        derivedStateOf { upTransitionState.progress() > 0f }
    }
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

    val backHandlerEnabled by remember {
        derivedStateOf { upTransitionState.currentState || upTransitionState.targetState }
    }
    BackHandler(enabled = backHandlerEnabled) {
        gestureController.collapseToMainPlayer()
    }

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
    val isCanvasPlaying by remember {
        derivedStateOf {
            (playerTransitionState.currentState || playerTransitionState.targetState) &&
                    !(upTransitionState.currentState && upTransitionState.targetState)
        }
    }

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

    val pagerState = key(state.repeatMode == RepeatMode.ALL) {
        rememberPagerState(
            initialPage = queueIndex.coerceAtLeast(0),
            pageCount = { queue.size }
        )
    }
    // Long-lived lambdas (remember { } / rememberSeekBarProgressState) must not capture a replaced pager.
    val latestPagerState by rememberUpdatedState(pagerState)

    var pagerAnchoredQueue by remember { mutableStateOf(queue) }
    var pagerAnchoredEntryIds by remember { mutableStateOf(queueEntryIds) }
    var pagerSyncEntryId by remember { mutableStateOf(currentEntryId) }
    var pagerSyncQueueIndex by remember { mutableStateOf(queueIndex) }
    var backdropRenumberHold by remember { mutableStateOf(false) }
    val pagerRenumberingPending = currentEntryId == pagerSyncEntryId &&
            queueIndex != pagerSyncQueueIndex
    // Art resolves against the pager's numbering until a pending renumbering snap lands.
    SideEffect {
        if (pagerRenumberingPending &&
            queueIndex in 0 until queueSize &&
            pagerState.currentPage != queueIndex &&
            !pagerState.isScrollInProgress
        ) {
            pagerState.requestScrollToPage(queueIndex)
        }
        if (!pagerRenumberingPending || pagerState.currentPage == queueIndex) {
            pagerAnchoredQueue = queue
            pagerAnchoredEntryIds = queueEntryIds
        }
        pagerSyncEntryId = currentEntryId
        pagerSyncQueueIndex = queueIndex
        backdropRenumberHold = pagerRenumberingPending && pagerState.currentPage != queueIndex
    }

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

    val latestQueueIndex = rememberUpdatedState(queueIndex)
    val latestQueueSize = rememberUpdatedState(queueSize)
    val latestQueue = rememberUpdatedState(queue)
    val latestHeroModel = rememberUpdatedState(heroModel)
    val lastBackwardSkipMs = remember { longArrayOf(0L) }
    val markSkipIntent: (Boolean) -> Unit = { backward ->
        lastBackwardSkipMs[0] = if (backward) System.currentTimeMillis() else 0L
    }

    var backdropHopHold by remember { mutableStateOf(false) }
    suspend fun hopOffGhost() {
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

    LaunchedEffect(pagerState, isWaveformSeek, queue) {
        if (!isWaveformSeek) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }
            .collect { currentPage ->
                val pages = intArrayOf(
                    currentPage, currentPage + 1, currentPage - 1, currentPage + 2, currentPage - 2
                )
                val seen = HashSet<String>()
                for (page in pages) {
                    val t = queue.getOrNull(page) ?: continue
                    if (seen.add(t.path)) WaveformCache.ensure(context, t.path, t.duration)
                }
            }
    }

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

    PlayerPagerSyncEffects(
        pagerState = pagerState,
        viewModel = viewModel,
        isProgressiveEnvelope = isProgressiveEnvelope,
        queueIndex = queueIndex,
        currentEntryId = currentEntryId,
        latestQueueIndex = latestQueueIndex,
        latestQueueSize = latestQueueSize,
        latestHeroModel = latestHeroModel,
        seekBarProgress = seekBarProgress,
        envelopeMorphProgress = envelopeMorphProgress,
        lastBackwardSkipMs = lastBackwardSkipMs,
        markSkipIntent = markSkipIntent,
        backdropRenumberHold = { backdropRenumberHold },
        isMainPagerVisible = { isMainPagerVisible() },
        hopOffGhost = { hopOffGhost() }
    )

    val programmaticBackdropCrossfade = remember { Animatable(1f) }
    var programmaticBackdropFromPath by remember { mutableStateOf<String?>(null) }
    var programmaticBackdropToPath by remember { mutableStateOf<String?>(null) }
    var backdropFadeGeneration by remember { mutableIntStateOf(0) }

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
                if (generation == backdropFadeGeneration) {
                    programmaticBackdropFromPath = null
                    programmaticBackdropToPath = null
                }
            }
        }
    }

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

            // Composition-phase reads: re-run the SideEffect when the pager indices change.
            val backdropIndexA = indexA
            val backdropIndexB = indexB

            val isProgrammaticCrossfadeActive by remember {
                derivedStateOf {
                    programmaticBackdropToPath != null && programmaticBackdropCrossfade.value < 1f
                }
            }
            // Composition-phase read: re-run the SideEffect when the crossfade starts or ends.
            val programmaticCrossfadeActive = isProgrammaticCrossfadeActive

            SideEffect {
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
                            isLoading = isLoadingProvider,
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
                            .extendBottom(PlayerDimens.subViewContentBottomExtension)
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

                PlayerBottomControls(
                    viewModel = viewModel,
                    subViewMode = subViewMode,
                    onSelectSubView = { mode ->
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
                    repeatMode = state.repeatMode,
                    isShuffle = state.isShuffle,
                    onPlayOnClick = { showPlayOnModal = true },
                    uiAlpha = uiAlpha,
                    subControlsProgress = subControlsProgress,
                    navbarExpandHeight = navbarExpandHeight,
                    isPlayingProvider = isPlayingProvider,
                    isLoadingProvider = isLoadingProvider,
                    seekBarProgress = seekBarProgress,
                    settleSeekBarAnimated = settleSeekBarAnimated,
                    isWaveformSeek = isWaveformSeek,
                    queueIndex = queueIndex,
                    queueSize = queueSize,
                    envelopeMorphProgress = envelopeMorphProgress,
                    markSkipIntent = markSkipIntent
                )
            }

            if (showSubViewSlider) {
                PlaybackProgressSlider(
                    durationMs = durationMs,
                    seekBarProgress = seekBarProgress,
                    isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                    onSeek = { positionMs -> viewModel.seekTo(positionMs) },
                    seekBarStyle = SeekBarStyle.NORMAL,
                    showTimeLabels = false,
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
                            translationY = PlayerDimens.seekBarRise.toPx() * (1f - subControlsProgress()) +
                                    PlayerDimens.seekBarNavbarDrop.toPx() -
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
        scanning = castScanning,
        onDeviceSelected = { device ->
            showPlayOnModal = false
            if (device.id != (selectedPlayOnDeviceId ?: PlayOnDevice.ThisDeviceId)) {
                viewModel.routePlayOn(device.id)
            }
        }
    )
}

