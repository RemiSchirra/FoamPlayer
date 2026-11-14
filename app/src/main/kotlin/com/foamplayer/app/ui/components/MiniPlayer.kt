package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.EaseOutQuint
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.ui.navigation.NavBarDimens
import com.foamplayer.app.ui.screens.player.PlayerDimens
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurface
import com.foamplayer.app.ui.theme.foamSurfaceVariant
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.pressShrink
import com.foamplayer.app.ui.utils.rememberPausedArtScale
import kotlin.math.abs
import androidx.core.net.toUri
import com.foamplayer.app.audio.engine.RepeatMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

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
 * Play/pause button, a bare glyph like the full player's main control (no
 * filled circle behind it). Deliberately a leaf: the loading crossfade and
 * play/pause flips recompose only this button instead of the whole
 * [MiniPlayer] body.
 */
@Composable
private fun SharedTransitionScope.MiniPlayerPlayPauseButton(
    trackId: Long,
    isPlaying: Boolean,
    isLoading: Boolean,
    animatedVisibilityScope: AnimatedVisibilityScope,
    useSharedElements: Boolean,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Same scale+fade convention as the full player's play/pause crossfade.
    val loadingFraction by animateFloatAsState(
        targetValue = if (isLoading) 1f else 0f,
        animationSpec = tween(durationMillis = FoamMotion.Duration.STANDARD),
        label = "miniPlayerLoadingCrossfade"
    )
    val description = stringResource(
        when {
            isLoading -> R.string.loading
            isPlaying -> R.string.pause
            else -> R.string.play
        }
    )

    val playInteractions = remember { MutableInteractionSource() }

    // The glyph is the shared element, laid out at its own size and scaled to the
    // interpolated bounds (ScaleToBounds) so it morphs between 28dp and the full
    // player's glyph without a size pop.
    val glyphSharedState = if (useSharedElements) {
        rememberSharedContentState(key = "play_button_$trackId")
    } else null

    Box(
        modifier = modifier
            .pressShrink(playInteractions)
            .size(36.dp)
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = playInteractions,
                indication = null,
                enabled = !isLoading,
                onClick = onPlayPauseClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Same glyph size as the subview's mini play button
        // (PlayerDimens.miniPlayIconSize) — visibly larger than the old 20dp
        // without filling the 36dp touch target edge-to-edge.
        CrossfadingPlayPauseIcon(
            isPlaying = isPlaying,
            loadingFraction = { loadingFraction },
            tint = FoamTheme.colors.textPrimary,
            iconSize = 28.dp,
            modifier = if (glyphSharedState != null) {
                Modifier.sharedBounds(
                    glyphSharedState,
                    animatedVisibilityScope = animatedVisibilityScope,
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.Center),
                    enter = fadeIn(),
                    exit = fadeOut()
                )
            } else Modifier
        )
        if (isLoading || loadingFraction > 0f) {
            CircularProgressIndicator(
                color = FoamTheme.colors.textPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        alpha = loadingFraction
                        val circleScale = 0.2f + 0.8f * loadingFraction
                        scaleX = circleScale
                        scaleY = circleScale
                    }
            )
        }
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
        // Tint-aware one-shot read: the track must match the tinted card it
        // sits on. The slider scope already reads the accent (activeColor
        // above), so this adds no recomposition beyond what already happens.
        inactiveColor = FoamTheme.tintedSurface,
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

/** Gap between the expanded container and the top of the flying artwork. */
private val ExpandedTopInset = 32.dp

/**
 * Card background of the mini player. At [expansion] 0 it is the card; at 1 it
 * spans the window sideways and down to the bottom edge. Its top follows the
 * flying artwork ([artTop], root pixels, NaN when unknown) with a gap that grows
 * from [topInset] to [ExpandedTopInset].
 *
 * The layer is window-sized and shaped by a clip outline computed in the draw
 * phase, so it tracks the artwork in the same frame and never recomposes. It
 * applies [opacity] itself: a fade on an ancestor would clip it to the card.
 */
@Composable
private fun MiniPlayerContainer(
    radius: Dp,
    topInset: Dp,
    expansion: () -> Float,
    opacity: () -> Float,
    artTop: () -> Float,
    modifier: Modifier = Modifier
) {
    var card by remember { mutableStateOf(IntRect.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val radiusPx = with(density) { radius.toPx() }
    val insetPx = with(density) { topInset.toPx() }
    val expandedInsetPx = with(density) { ExpandedTopInset.toPx() }
    val elevationPx = with(density) { 8.dp.toPx() }

    Box(
        modifier = modifier
            .onGloballyPositioned {
                card = IntRect(it.positionInRoot().round(), it.size)
                rootSize = it.findRootCoordinates().size
            }
            .layout { measurable, constraints ->
                val size = if (rootSize == IntSize.Zero) {
                    IntSize(constraints.maxWidth, constraints.maxHeight)
                } else rootSize
                val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val t = expansion()
                val ready = rootSize != IntSize.Zero
                val left = card.left.toFloat()
                val top = card.top.toFloat()
                val art = artTop()
                val flyingTop = when {
                    t == 0f -> top
                    art.isNaN() -> lerp(top, 0f, t)
                    else -> minOf(top, art - lerp(insetPx, expandedInsetPx, t))
                }
                val rect = RoundRect(
                    left = lerp(left, 0f, t),
                    top = flyingTop,
                    right = lerp(card.right.toFloat(), rootSize.width.toFloat(), t),
                    bottom = lerp(card.bottom.toFloat(), rootSize.height.toFloat(), t),
                    cornerRadius = CornerRadius(lerp(radiusPx, 0f, t))
                )
                translationX = if (ready) -left else 0f
                translationY = if (ready) -top else 0f
                alpha = opacity()
                shadowElevation = elevationPx * (1f - t)
                clip = true
                shape = object : Shape {
                    override fun createOutline(
                        size: Size,
                        layoutDirection: LayoutDirection,
                        density: Density
                    ): Outline = Outline.Rounded(
                        if (ready) rect else RoundRect(0f, 0f, size.width, size.height, rect.topLeftCornerRadius)
                    )
                }
            }
            .foamSurfaceVariant()
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
    /** Expand the card to the window and fade while the player is open; the host then must not fade this composable itself. */
    expandContainer: Boolean = false,
    fallbackPlaybackState: PlaybackState? = null,
    /** Whether the app accent derives from album art; see the pager color effect below. */
    isAutomaticColorEnabled: Boolean = true,
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
        // True from the moment a finger grabs the pager until the pager has really stopped
        // (drag AND the fling that follows the release). Engine-driven syncs must not touch
        // the pager while this is set.
        var userScrolling by mutableStateOf(false)
    }
    val pagerSyncState = remember { PagerSyncState() }
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()

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

    val fallbackAccent = FoamTheme.colors.textPrimary
    // Gated like FullPlayerScreen's equivalent effect: with automatic color off the
    // app-wide accent ignores these updates entirely (the manual pick wins in
    // rememberAccentColor), so firing one per drag frame would only recompose the
    // accent scope -- and FoamTheme behind it -- for nothing during a swipe.
    LaunchedEffect(pagerState, isAutomaticColorEnabled, fallbackAccent) {
        if (!isAutomaticColorEnabled) return@LaunchedEffect
        snapshotFlow {
            val jump = pendingJump
            val dep = latestDeparture
            val currentQueue = latestQueue
            val artPathFor: (Int) -> String? = { page ->
                // Same page -> track mapping as the pager content below, so the
                // accent never shows the replacement queue's track on the page
                // that is still displaying the departing one.
                (
                        overrideTrackFor(jump, page, currentQueue)
                            ?: dep?.takeIf { jump == null && page == it.page }?.track
                            ?: currentQueue.getOrNull(page)
                        )?.albumArtPath
            }
            val page = pagerState.currentPage
            val fraction = pagerState.currentPageOffsetFraction
            val resolved = AlbumArtPaletteCache.interpolatedColor(
                currentPage = page,
                currentPageOffsetFraction = fraction,
                pageCount = currentQueue.size,
                fallback = fallbackAccent
            ) { artPathFor(it) }
            val path = artPathFor(page)
            when {
                fraction == 0f &&
                        (path == null ||
                                (AlbumArtPaletteCache.colors[path] == null &&
                                        AlbumArtPaletteCache.isSettled(path))
                                ) -> fallbackAccent
                else -> resolved
            }
        }.collect { interpolatedColor ->
            if (interpolatedColor != null) onColorChange(interpolatedColor)
        }
    }

    // Keep the app-wide backdrop in lockstep with the pager, including while
    // the finger is still dragging between two tracks. The page -> art mapping
    // mirrors the pager content (jump override, then the departing track still
    // pinned on its page, then the real queue): when a list pick replaces the
    // queue, the page we are on keeps showing the OLD track's art until the
    // slide runs, instead of cutting to whatever the new queue has at that page.
    // Discrete cuts that still slip through are crossfaded by the backdrop
    // itself (see BackdropCrossfadeDriver in FoamNavBackdrops.kt).
    LaunchedEffect(pagerState) {
        snapshotFlow {
            val jump = pendingJump
            val dep = latestDeparture
            val currentQueue = latestQueue
            val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val frontIndex = absoluteOffset.toInt().coerceIn(0, (currentQueue.size - 1).coerceAtLeast(0))
            val backIndex = (frontIndex + 1).coerceAtMost((currentQueue.size - 1).coerceAtLeast(0))
            val fraction = (absoluteOffset - frontIndex).coerceIn(0f, 1f)
            val pathForPage: (Int) -> String? = { page ->
                (
                        overrideTrackFor(jump, page, currentQueue)
                            ?: dep?.takeIf { jump == null && page == it.page }?.track
                            ?: currentQueue.getOrNull(page)
                        )?.albumArtPath
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

        // The user's finger (or the fling it just launched) owns the pager right now.
        // The engine reports the track of a previous swipe with some delay; if that report
        // lands while a second swipe is under way, syncing here would animate the pager back
        // to the first swipe's page and cancel the second swipe. So wait until the pager has
        // really stopped. The swipe's own settle (see the settledPage effect) then asks the
        // engine for wherever the user actually ended up, which makes this sync pointless;
        // it only still runs when the gesture ended on the page it started from.
        if (isDragged || pagerSyncState.userScrolling) {
            pendingJump = null
            pageShadow.inFlight = null
            departureSpent = true
            pagerSyncState.isSyncing = false
            val settledBefore = pagerState.settledPage
            snapshotFlow {
                !pagerState.isScrollInProgress && pagerState.settledPage == pagerState.currentPage
            }.first { it }
            if (pagerState.settledPage != settledBefore) return@LaunchedEffect
        }

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
    LaunchedEffect(pagerState) {
        snapshotFlow { isDragged to pagerState.isScrollInProgress }
            .collect { (dragged, scrolling) ->
                if (dragged) pagerSyncState.userScrolling = true
                else if (!scrolling) pagerSyncState.userScrolling = false
            }
    }
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

    val hiddenByPlayer = { state: EnterExitState -> expandContainer && state != EnterExitState.Visible }
    val containerExpansion = animatedVisibilityScope.transition.animateFloat(
        transitionSpec = { FoamMotion.Spec.sharedElementFlight },
        label = "miniContainerExpansion"
    ) { state -> if (hiddenByPlayer(state)) 1f else 0f }
    val visibilityAlphaState = animatedVisibilityScope.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                FoamMotion.Spec.sharedElementFlight
            } else {
                tween(
                    FoamMotion.Duration.FLIGHT,
                    easing = EaseOutQuint
                )
            }
        },
        label = "miniVisibilityAlpha"
    ) { state -> if (hiddenByPlayer(state)) 0f else 1f }
    val visibilityAlpha = { visibilityAlphaState.value }
    var flyingArtTop by remember { mutableFloatStateOf(Float.NaN) }

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
        ) {
            if (showContainer) {
                MiniPlayerContainer(
                    radius = containerRadius,
                    topInset = containerPadding,
                    artTop = { flyingArtTop },
                    expansion = { containerExpansion.value },
                    opacity = visibilityAlpha,
                    modifier = Modifier.matchParentSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = visibilityAlpha() }
                    .clip(RoundedCornerShape(containerRadius))
                    .padding(containerPadding)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f),
                            pageSpacing = NavBarDimens.MiniPlayerPageSpacing,
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
                            // Hoisted so the art box below can be re-keyed on the tag state: a
                            // tag (re-)added to an already-composed page must get a fresh
                            // composition identity, or its remembered entry never registers with
                            // the coordinator and the open/close flight cannot pair.
                            val artSharedState = if (useSharedElements && isSettledCurrentTrackPage) {
                                rememberSharedContentState(key = "album_art_${track.id}")
                            } else null
                            val titleSharedState = if (useSharedElements && isSettledCurrentTrackPage) {
                                rememberSharedContentState(key = "track_title_${track.id}")
                            } else null
                            val artistSharedState = if (useSharedElements && isSettledCurrentTrackPage) {
                                rememberSharedContentState(key = "track_artist_${track.id}")
                            } else null
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
                                // A tag added to an already-composed page via the gate flip never
                                // registers its entry with the coordinator (the composed modifier's
                                // remembered entry keeps its stale slot), so the open/close flight
                                // can never pair and the artwork cross-fades in place. Re-keying
                                // the art box on the tag state forces a fresh composition identity,
                                // so the entry always (re-)registers.
                                key(artSharedState) {
                                    Box(
                                        modifier = Modifier
                                            .size(miniImageSize)
                                            .then(
                                                if (artSharedState != null) {
                                                    Modifier
                                                        .sharedElement(
                                                            artSharedState,
                                                            animatedVisibilityScope = animatedVisibilityScope
                                                        )
                                                        .graphicsLayer { alpha = sharedArtFlightAlpha() }
                                                } else Modifier
                                            )
                                            .then(
                                                if (artSharedState != null) {
                                                    Modifier.onGloballyPositioned {
                                                        flyingArtTop = it.positionInRoot().y
                                                    }
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
                                            .foamSurface(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (pageTrack.albumArtPath != null) {
                                            val albumArtRequest = remember(pageTrack.albumArtPath) {
                                                ImageRequest.Builder(context)
                                                    .data(EmbeddedAlbumArt(pageTrack.albumArtPath.toUri()))
                                                    .size(AlbumArtRequestSize)
                                                    // Shares the player flight's cache slot: this
                                                    // request warms the bitmap the full-player hero
                                                    // draws synchronously on the flight's first frame.
                                                    .placeholderMemoryCacheKey(
                                                        albumArtMemoryCacheKey(pageTrack.albumArtPath)
                                                    )
                                                    .memoryCacheKey(
                                                        albumArtMemoryCacheKey(pageTrack.albumArtPath)
                                                    )
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
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Same late-tag problem as the art box above: the title and the
                                // artist/album bounds are only tagged once the page is the settled
                                // current one (or the gate flips on). A tag added to an already-composed
                                // page never registers with the coordinator, so the first close (or the
                                // first close after swiping to a new song) cannot pair the text with the
                                // full player's and it cross-fades -> flicker. Re-keying on the tag
                                // states gives the column a fresh identity whenever they appear.
                                // Marquee state is hoisted per entry, so scrolling carries over.
                                key(titleSharedState, artistSharedState) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        MarqueeText(
                                            text = pageTrack.title,
                                            style = FoamTheme.typography.titleMedium,
                                            mode = marqueeMode,
                                            state = pageMarquee.title,
                                            modifier = if (titleSharedState != null) {
                                                Modifier.sharedBounds(
                                                    titleSharedState,
                                                    animatedVisibilityScope = animatedVisibilityScope
                                                )
                                            } else Modifier
                                        )
                                        MarqueeText(
                                            text = stringResource(R.string.artist_album_separator, pageTrack.artist, pageTrack.album),
                                            style = FoamTheme.typography.caption,
                                            mode = marqueeMode,
                                            state = pageMarquee.subtitle,
                                            modifier = if (artistSharedState != null) {
                                                Modifier.sharedBounds(
                                                    artistSharedState,
                                                    animatedVisibilityScope = animatedVisibilityScope
                                                )
                                            } else Modifier
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        with(sharedTransitionScope) {
                            MiniPlayerPlayPauseButton(
                                trackId = track.id,
                                isPlaying = playbackState.isPlaying,
                                isLoading = playbackState.isLoading,
                                animatedVisibilityScope = animatedVisibilityScope,
                                useSharedElements = useSharedElements,
                                onPlayPauseClick = onPlayPauseClick,
                                modifier = Modifier.padding(end = 4.dp)
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
}