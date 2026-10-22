package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.ui.navigation.NavBarDimens
import com.foamplayer.app.ui.screens.player.PlayerDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.rememberPausedArtScale
import kotlin.math.abs
import androidx.core.net.toUri
import com.foamplayer.app.audio.engine.RepeatMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * A distant jump in progress: while the pager slides one page to [viaPage],
 * the mini player renders the TARGET track's content there instead of
 * whatever track actually lives on it. That turns any distant jump into a
 * single, real one-page slide that lands exactly on the right track, after
 * which the pager is silently remapped onto the real index (identical
 * content, so nothing pops).
 *
 * The slide is ALWAYS one page forward (never backward), wherever the target
 * really lives. When the queue was replaced under the pager (a list pick), the
 * departing page ([departurePage]) keeps rendering the track that was playing
 * ([departureTrack]) so it slides out instead of popping into something else.
 */
private data class PendingJump(
    val viaPage: Int,
    val targetIndex: Int,
    val departurePage: Int = -1,
    val departureTrack: TrackEntity? = null
)

/** The track [page] must render while [jump] is in flight, or null for its real one. */
private fun overrideTrackFor(jump: PendingJump?, page: Int, queue: List<TrackEntity>): TrackEntity? = when {
    jump == null -> null
    page == jump.viaPage -> queue.getOrNull(jump.targetIndex)
    jump.departureTrack != null && page == jump.departurePage -> jump.departureTrack
    else -> null
}

/** What the pager's current page showed on the last settled composition. */
private class PageShadow {
    var page = -1
    var entryId: Long? = null
    var track: TrackEntity? = null
    /** The departure of a slide that is still running, carried over if a newer change retargets it. */
    var inFlight: DepartureOverride? = null
}

/** The old playing track that must stay on its page while the queue under it was replaced. */
private class DepartureOverride(val page: Int, val track: TrackEntity)

/** Title + artist/album marquee state of one queue entry. */
private class MiniEntryMarqueeStates {
    val title = MarqueeTextState()
    val subtitle = MarqueeTextState()
}

/**
 * Play/pause button. Deliberately a leaf: the accent background changes every
 * frame while the accent color animates, and keeping the read here means only
 * this button recomposes per frame instead of the whole [MiniPlayer] body.
 */
@Composable
private fun SharedTransitionScope.MiniPlayerPlayPauseButton(
    trackId: Long,
    isPlaying: Boolean,
    animatedVisibilityScope: AnimatedVisibilityScope,
    useSharedElements: Boolean,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .then(
                if (useSharedElements) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "play_button_$trackId"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                } else Modifier
            )
            .clip(RoundedCornerShape(18.dp))
            .background(FoamTheme.accentColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPlayPauseClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) FoamTheme.icons.current.pause else FoamTheme.icons.current.play,
            contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
            tint = FoamTheme.colors.background,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Isolated on purpose, mirroring PlaybackProgressSlider in FullPlayerScreen.kt:
 * seekBarProgress.progress tracks the pager's offset fraction, which changes
 * on essentially every frame of a horizontal swipe. Reading it here confines
 * recomposition to the slider alone (its track fill is drawn from the value
 * lambda without recomposition) instead of the whole mini player body.
 *
 * The seek itself commits only on release: onValueChange parks the dragged
 * position in local state, so dragging no longer issues a real seek per pixel.
 */
@Composable
private fun MiniPlayerProgressSlider(
    seekBarProgress: SeekBarProgressState,
    isAnimateSeekBarEnabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val activeColor = FoamTheme.accentColor

    FoamSlider(
        value = { dragValue ?: seekBarProgress.progress },
        onValueChange = { new ->
            // A fresh manual drag always wins over any settle tween in flight.
            seekBarProgress.onDragging()
            dragValue = new
        },
        trackHeight = 4.dp,
        activeColor = activeColor,
        inactiveColor = FoamTheme.colors.surface,
        growFactor = 1f,
        modifier = modifier.onSeekBarReleased {
            val released = dragValue
            dragValue = null
            if (released != null) {
                currentOnSeek(released)
                seekBarProgress.onSeekDragFinished(
                    animated = isAnimateSeekBarEnabled,
                    releasedAt = released
                )
            }
        }
    )
}

@Composable
fun MiniPlayer(
    playbackStateFlow: StateFlow<PlaybackState>,
    positionMsFlow: StateFlow<Long>,
    onPlayPauseClick: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipToTrack: (index: Int, forward: Boolean) -> Unit,
    onColorChange: (Color?) -> Unit,
    onBackgroundTransition: (frontArtPath: String?, backArtPath: String?, fraction: Float) -> Unit,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    useSharedElements: Boolean = true,
    sharedArtFlightAlpha: () -> Float = { 1f },
    isImmersiveEnabled: Boolean = false,
    isAnimateSeekBarEnabled: Boolean = false,
    marqueeMode: MarqueeMode = MarqueeMode.PING_PONG,
    skipTrackChangeAnimation: Boolean = false,
    showContainer: Boolean = true,
    fallbackPlaybackState: PlaybackState? = null,
    modifier: Modifier = Modifier
) {
    val livePlaybackState by playbackStateFlow.collectAsState()
    val playbackState = livePlaybackState.takeIf { it.currentTrack != null }
        ?: fallbackPlaybackState
        ?: return
    val track = playbackState.currentTrack ?: return
    // In repeat-all the pager shows the primary queue as a closed loop (see LoopingPagerModel):
    // `queue`, `queueEntryIds` and `currentIndex` are the pager's page numbering, and engine
    // calls go through `realIndexOf`.
    val loopModelCache = remember { LoopingPagerModelCache() }
    val loopModel = loopModelCache.resolve(
        queue = playbackState.queue,
        auxiliaryFlags = playbackState.auxiliaryFlags,
        entryIds = playbackState.queueEntryIds,
        queueIndex = playbackState.queueIndex,
        repeatAll = playbackState.repeatMode == RepeatMode.ALL
    )
    val latestLoopModel by rememberUpdatedState(loopModel)
    val queue = loopModel.queue
    val queueEntryIds = loopModel.entryIds
    val currentIndex = loopModel.index
    val currentEntryId = queueEntryIds.getOrNull(currentIndex)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceAtLeast(0),
        pageCount = { queue.size }
    )

    var pendingJump by remember { mutableStateOf<PendingJump?>(null) }

    val pageShadow = remember { PageShadow() }
    val departureCandidate = remember(currentEntryId) {
        val s = pageShadow
        s.inFlight ?: run {
            val oldEntry = s.entryId
            val oldTrack = s.track
            if (oldEntry != null && oldTrack != null && currentEntryId != null &&
                oldEntry != currentEntryId &&
                s.page in queue.indices &&
                s.page == pagerState.currentPage &&
                queue.getOrNull(currentIndex)?.id != oldTrack.id &&
                oldEntry !in queueEntryIds
            ) DepartureOverride(s.page, oldTrack) else null
        }
    }
    var departureSpent by remember(currentEntryId) { mutableStateOf(false) }
    val departure = if (departureSpent) null else departureCandidate
    val latestDeparture by rememberUpdatedState(departure)
    SideEffect {
        if (departure == null && pendingJump == null) {
            val page = pagerState.currentPage
            pageShadow.page = page
            pageShadow.entryId = queueEntryIds.getOrNull(page)
            pageShadow.track = queue.getOrNull(page)
        }
    }

    // Token-guarded sync state: a newer sync must be able to cancel an older
    // one mid-flight without the cancelled one clobbering shared state.
    class PagerSyncState {
        var isSyncing = false
        var activeToken = 0
        // A skip already requested for a swipe that ended on a loop ghost page (real index + time).
        var wrapRequestedReal = -1
        var wrapRequestedAtMs = 0L
    }
    val pagerSyncState = remember { PagerSyncState() }

    // Marquee text state per track
    val marqueeStates = remember { mutableMapOf<Long, MiniEntryMarqueeStates>() }
    val entryKey = { page: Int -> queueEntryIds.getOrNull(page) ?: -(page + 1).toLong() }
    val statesFor = { key: Long -> marqueeStates.getOrPut(key) { MiniEntryMarqueeStates() } }
    LaunchedEffect(queueEntryIds) { marqueeStates.keys.retainAll(queueEntryIds.toSet()) }

    // Restart the marquee effect on pager state update
    val currentEntryKey by rememberUpdatedState(entryKey)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                val leavingKey = currentEntryKey(pagerState.currentPage)
                marqueeStates.forEach { (key, states) ->
                    if (key != leavingKey) {
                        states.title.restart()
                        states.subtitle.restart()
                    }
                }
            }
    }

    val latestPlaybackState by rememberUpdatedState(playbackState)
    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestSkipTrackChangeAnimation by rememberUpdatedState(skipTrackChangeAnimation)
    val latestBackgroundTransition by rememberUpdatedState(onBackgroundTransition)
    val latestIsAnimateSeekBarEnabled by rememberUpdatedState(isAnimateSeekBarEnabled)
    val latestQueue by rememberUpdatedState(queue)

    val seekBarProgress = rememberSeekBarProgressState(
        positionMsFlow = positionMsFlow,
        liveProgress = { livePositionMs ->
            val progress = if (latestPlaybackState.durationMs > 0) {
                livePositionMs.toFloat() / latestPlaybackState.durationMs
            } else 0f

            val activeFraction = if (isAnimateSeekBarEnabled) {
                val scrollPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
                val distanceFromCurrent = latestLoopModel.distanceToPlayingPage(scrollPosition)
                (1f - distanceFromCurrent).coerceIn(0f, 1f)
            } else {
                1f
            }

            progress * activeFraction
        }
    )

    LaunchedEffect(pagerState.currentPage, queue, pendingJump) {
        val indices = listOf(
            pagerState.currentPage - 1,
            pagerState.currentPage,
            pagerState.currentPage + 1
        )

        val pathsToKeep = (indices.mapNotNull { i ->
            queue.getOrNull(i)?.albumArtPath
        } + listOfNotNull(
            pendingJump?.let { queue.getOrNull(it.targetIndex)?.albumArtPath },
            pendingJump?.departureTrack?.albumArtPath
        )).toSet()

        pathsToKeep.forEach { path ->
            AlbumArtPaletteCache.ensure(scope = this, context = context, path = path)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow {
            val jump = pendingJump
            val currentQueue = latestQueue
            AlbumArtPaletteCache.interpolatedColor(
                currentPage = pagerState.currentPage,
                currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                pageCount = currentQueue.size
            ) { page ->
                (overrideTrackFor(jump, page, currentQueue) ?: currentQueue.getOrNull(page))?.albumArtPath
            }
        }.collect { interpolatedColor ->
            if (interpolatedColor != null) onColorChange(interpolatedColor)
        }
    }

    // Keep the app-wide backdrop in lockstep with the pager, including while
    // the finger is still dragging between two tracks.
    LaunchedEffect(pagerState) {
        snapshotFlow {
            val jump = pendingJump
            val currentQueue = latestQueue
            val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val frontIndex = absoluteOffset.toInt().coerceIn(0, (currentQueue.size - 1).coerceAtLeast(0))
            val backIndex = (frontIndex + 1).coerceAtMost((currentQueue.size - 1).coerceAtLeast(0))
            val fraction = (absoluteOffset - frontIndex).coerceIn(0f, 1f)
            val pathForPage: (Int) -> String? = { page ->
                (overrideTrackFor(jump, page, currentQueue) ?: currentQueue.getOrNull(page))?.albumArtPath
            }
            Triple(pathForPage(frontIndex), pathForPage(backIndex), fraction)
        }.collect { (frontArtPath, backArtPath, fraction) ->
            latestBackgroundTransition(frontArtPath, backArtPath, fraction)
        }
    }

    // Reset the accent color when the player is dismissed.
    DisposableEffect(Unit) {
        onDispose {
            onColorChange(null)
        }
    }

    // Sync the pager when the track changes; reads the live queue through the
    // proxy, since the queue can change while this effect is running.
    val latestEntryId by rememberUpdatedState(currentEntryId)
    var lastSyncedEntryId by remember { mutableStateOf<Long?>(null) }
    // Keyed on the entry too: a list pick can swap the playing entry while the index stays put
    // (e.g. tapping the first row), which must still slide instead of popping.
    LaunchedEffect(currentIndex, currentEntryId) {
        // Taking the token first turns a superseded run's `finally` into a no-op.
        val myToken = ++pagerSyncState.activeToken
        val currentQueue = latestQueue
        val inFlight = pendingJump          // slide of a superseded run still on screen, if any
        val dep = latestDeparture           // old track pinned on its page by a queue replacement

        fun clearJumpState() {
            pendingJump = null
            pageShadow.inFlight = null
            departureSpent = true
            pagerSyncState.isSyncing = false
        }

        // Same playing entry under a new page number (repeat mode toggled, a row removed before it):
        // snap, never sweep across the renumbered queue.
        val pureRenumbering = latestEntryId != null && latestEntryId == lastSyncedEntryId
        lastSyncedEntryId = latestEntryId

        // Parked on (or flung onto) the ghost of the playing track: the idle hop below lands on
        // its twin. Treating the ghost as a real position would sweep back across the whole loop.
        val loopModel = latestLoopModel
        val parkedOnGhostOfPlaying = loopModel.isGhostPage(pagerState.currentPage) &&
                loopModel.ghostTwinPage(pagerState.currentPage) == currentIndex

        if (pureRenumbering && !parkedOnGhostOfPlaying && currentIndex in currentQueue.indices) {
            if (pagerState.currentPage != currentIndex) pagerState.scrollToPage(currentIndex)
            clearJumpState()
            return@LaunchedEffect
        }

        val needsSync = currentIndex in currentQueue.indices &&
                !parkedOnGhostOfPlaying &&
                (pagerState.currentPage != currentIndex || dep != null)
        if (!needsSync) {
            clearJumpState()
            return@LaunchedEffect
        }

        // Player is opening over the mini player: no animation, but the pager must still land on
        // the playing page (otherwise it keeps showing whatever sits on its old page number).
        if (latestSkipTrackChangeAnimation) {
            if (pagerState.currentPage != currentIndex) pagerState.scrollToPage(currentIndex)
            clearJumpState()
            return@LaunchedEffect
        }

        pagerSyncState.isSyncing = true
        try {
            val from = pagerState.currentPage
            val plain = dep == null && inFlight == null
            // Repeat-all wrap across the loop's seam: one real page slide onto the ghost
            // page, then a silent hop onto its identical twin.
            val wrapPage = if (plain) {
                latestLoopModel.wrapTransitPage(
                    fromPage = from,
                    toPage = currentIndex,
                    backward = false
                )
            } else null
            val distance = currentIndex - from
            if (wrapPage != null) {
                try {
                    pagerState.animateScrollToPage(wrapPage)
                } finally {
                    withContext(NonCancellable) {
                        val model = latestLoopModel
                        val page = pagerState.currentPage
                        if (model.isGhostPage(page)) {
                            pagerState.scrollToPage(model.ghostTwinPage(page))
                        }
                    }
                }
            } else if (plain && abs(distance) <= 1) {
                // Prev/next neighbour: the natural one-page slide.
                pagerState.animateScrollToPage(currentIndex)
            } else {
                // Any other jump (list pick, queue pick, retarget mid-slide): exactly ONE page
                // forward, rendering the target on the via page (see [PendingJump]) — never a
                // sweep across the tracks in between, never backward.
                val viaPage = when {
                    inFlight != null -> inFlight.viaPage
                    from + 1 in currentQueue.indices -> from + 1
                    from - 1 in currentQueue.indices -> from - 1 // nothing ahead: last page
                    else -> -1
                }
                if (viaPage < 0) {
                    pagerState.scrollToPage(currentIndex)
                } else {
                    pageShadow.inFlight = dep
                    pendingJump = PendingJump(viaPage, currentIndex, dep?.page ?: -1, dep?.track)
                    pagerState.animateScrollToPage(viaPage)
                    // Landed on the target's content; remap silently onto its real page.
                    pagerState.scrollToPage(currentIndex)
                }
            }
        } finally {
            // Runs even if a newer change cancels us mid-flight, unless that run already took over.
            if (pagerSyncState.activeToken == myToken) clearJumpState()
        }
    }

    // Commit the track change only once the finger actually releases — well
    // before the pager's fling-to-settle visually finishes. Gating on
    // `isDragged` going false (rather than on `targetPage` directly) matters:
    // PagerState.targetPage already reflects its predicted destination while
    // the finger is still down, so collecting it directly fired the commit
    // mid-drag instead of on release.
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pagerState) {
        snapshotFlow { isDragged }
            .collect { dragging ->
                if (!dragging &&
                    pendingJump == null &&
                    pagerState.targetPage != latestCurrentIndex &&
                    pagerState.targetPage in latestQueue.indices
                ) {
                    seekBarProgress.onTrackChangeCommitted(animated = latestIsAnimateSeekBarEnabled)
                }
            }
    }

    LaunchedEffect(pagerState.settledPage) {
        val settled = pagerState.settledPage
        val model = latestLoopModel
        if (pagerSyncState.isSyncing || pendingJump != null || settled !in latestQueue.indices) {
            return@LaunchedEffect
        }
        val real = model.realIndexOf(settled)

        if (model.isGhostPage(settled)) {
            // Loop ghost page: commit its twin track but don't move the pager here (settle can be
            // reported at finger release; the idle hop below moves it once it has really
            // stopped). A twin the engine already plays is a programmatic wrap that landed.
            if (model.ghostTwinPage(settled) != latestCurrentIndex) {
                pagerSyncState.wrapRequestedReal = real
                pagerSyncState.wrapRequestedAtMs = System.currentTimeMillis()
                seekBarProgress.onTrackChangeApplied()
                onSkipToTrack(real, model.isTrailingGhost(settled))
            }
            return@LaunchedEffect
        }

        val alreadyRequested = pagerSyncState.wrapRequestedReal >= 0 &&
                System.currentTimeMillis() - pagerSyncState.wrapRequestedAtMs < 2_000L &&
                real == pagerSyncState.wrapRequestedReal
        pagerSyncState.wrapRequestedReal = -1
        if (alreadyRequested) return@LaunchedEffect

        if (settled != latestCurrentIndex) {
            seekBarProgress.onTrackChangeApplied()
            // Keep the swipe direction: a swipe back must walk further back
            // past tracks that failed to load.
            onSkipToTrack(real, settled > latestCurrentIndex)
        }
    }

    // Idle hop: a pager that has really stopped on a loop ghost page moves silently onto its
    // identical twin, whoever got it there (swipe or automatic wrap).
    LaunchedEffect(pagerState) {
        snapshotFlow {
            val model = latestLoopModel
            if (!pagerState.isScrollInProgress && model.isGhostPage(pagerState.currentPage)) {
                pagerState.currentPage
            } else {
                -1
            }
        }.collect { ghostPage ->
            if (ghostPage >= 0) {
                pagerState.scrollToPage(latestLoopModel.ghostTwinPage(ghostPage))
            }
        }
    }

    val configuration = LocalConfiguration.current
    val miniImageSize = NavBarDimens.MiniPlayerArtSize
    val fullImageSize = configuration.screenWidthDp.dp - 48.dp

    val miniRoundingPercent = FoamTheme.shapes.miniPlayerRounding
    val fullRoundingPercent = FoamTheme.shapes.fullPlayerRounding

    val miniImageRadius = (miniImageSize / 2) * miniRoundingPercent
    val fullImageRadius = (fullImageSize / 2) * fullRoundingPercent

    val animatedCornerRadius by animatedVisibilityScope.transition.animateDp(label = "miniCornerRadius") { enterExitState ->
        if (enterExitState == EnterExitState.Visible) {
            miniImageRadius
        } else {
            fullImageRadius
        }
    }

    val animatedArtElevationFraction by animatedVisibilityScope.transition.animateFloat(label = "miniArtElevation") { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 0f else 1f
    }

    val heroPausedArtScale by rememberPausedArtScale(
        playbackState.isPlaying || playbackState.isPlaybackRequested
    )

    val containerPadding = NavBarDimens.MiniPlayerContainerPadding
    val containerRadius = miniImageRadius + containerPadding

    with(sharedTransitionScope) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = NavBarDimens.MiniPlayerCardVerticalPadding)
                .then(
                    if (showContainer) {
                        Modifier.shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(containerRadius)
                        )
                    } else Modifier
                )
                .clip(RoundedCornerShape(containerRadius))
                .then(
                    if (showContainer) {
                        Modifier.background(FoamTheme.colors.surfaceVariant)
                    } else Modifier
                )
                .padding(containerPadding)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                        beyondViewportPageCount = 1,
                        key = { index -> queueEntryIds.getOrNull(index) ?: -(index + 1).toLong() }
                    ) { page ->
                        val jump = pendingJump
                        val dep = departure
                        val pageTrack = (
                                overrideTrackFor(jump, page, queue)
                                    ?: dep?.takeIf { jump == null && page == it.page }?.track
                                    ?: queue.getOrNull(page)
                                ) ?: return@HorizontalPager

                        val isSettledCurrentTrackPage =
                            jump == null && dep == null && page == currentIndex && pageTrack.id == track.id
                        val pageMarquee = statesFor(entryKey(page))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onClick() }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(miniImageSize)
                                    .then(
                                        if (useSharedElements && isSettledCurrentTrackPage) {
                                            Modifier
                                                .sharedElement(
                                                    rememberSharedContentState(key = "album_art_${track.id}"),
                                                    animatedVisibilityScope = animatedVisibilityScope
                                                )
                                                .graphicsLayer { alpha = sharedArtFlightAlpha() }
                                        } else Modifier
                                    )
                                    .graphicsLayer {
                                        val artTransition = animatedVisibilityScope.transition
                                        shape = RoundedCornerShape(
                                            if (artTransition.currentState == artTransition.targetState) {
                                                miniImageRadius
                                            } else {
                                                animatedCornerRadius
                                            }
                                        )
                                        clip = true
                                        val scale =
                                            1f + (heroPausedArtScale - 1f) * animatedArtElevationFraction
                                        scaleX = scale
                                        scaleY = scale
                                        shadowElevation = if (!isImmersiveEnabled && useSharedElements && isSettledCurrentTrackPage) {
                                            PlayerDimens.fullPlayerArtElevation.toPx() * animatedArtElevationFraction
                                        } else {
                                            0f
                                        }
                                    }
                                    .background(FoamTheme.colors.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                if (pageTrack.albumArtPath != null) {
                                    val albumArtRequest = remember(pageTrack.albumArtPath) {
                                        ImageRequest.Builder(context)
                                            .data(EmbeddedAlbumArt(pageTrack.albumArtPath.toUri()))
                                            .size(AlbumArtRequestSize)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = albumArtRequest,
                                        contentDescription = stringResource(R.string.album_art),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    // Fills this slot like real artwork would and
                                    // scales with the shared-element flight.
                                    NoAlbumArtPlaceholder()
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                MarqueeText(
                                    text = pageTrack.title,
                                    style = FoamTheme.typography.titleMedium,
                                    mode = marqueeMode,
                                    state = pageMarquee.title,
                                    modifier = if (useSharedElements && isSettledCurrentTrackPage) {
                                        Modifier.sharedBounds(
                                            rememberSharedContentState(key = "track_title_${track.id}"),
                                            animatedVisibilityScope = animatedVisibilityScope
                                        )
                                    } else Modifier
                                )
                                MarqueeText(
                                    text = stringResource(R.string.artist_album_separator, pageTrack.artist, pageTrack.album),
                                    style = FoamTheme.typography.caption,
                                    mode = marqueeMode,
                                    state = pageMarquee.subtitle,
                                    modifier = if (useSharedElements && isSettledCurrentTrackPage) {
                                        Modifier.sharedBounds(
                                            rememberSharedContentState(key = "track_artist_${track.id}"),
                                            animatedVisibilityScope = animatedVisibilityScope
                                        )
                                    } else Modifier
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    with(sharedTransitionScope) {
                        MiniPlayerPlayPauseButton(
                            trackId = track.id,
                            isPlaying = playbackState.isPlaying,
                            animatedVisibilityScope = animatedVisibilityScope,
                            useSharedElements = useSharedElements,
                            onPlayPauseClick = onPlayPauseClick
                        )
                    }
                }

                Spacer(modifier = Modifier.height(NavBarDimens.MiniPlayerContentSpacing))

                MiniPlayerProgressSlider(
                    seekBarProgress = seekBarProgress,
                    isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                    onSeek = onSeek,
                    modifier = Modifier.height(NavBarDimens.MiniPlayerSliderHeight)
                )
            }
        }
    }
}