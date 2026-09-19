package com.foamplayer.app.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.ui.navigation.NavBarDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.rememberPausedArtScale
import kotlin.math.abs
import androidx.core.net.toUri
import kotlinx.coroutines.flow.StateFlow

// SeekBarProgressState / rememberSeekBarProgressState (SeekBarProgressState.kt) live
// in this same package, so no import is needed for them here.

/**
 * Represents a distant jump in progress. While the pager slides one page, from its
 * current position to [viaPage], [MiniPlayer] renders the *target* track's content on
 * [viaPage] instead of whatever track actually lives there. That turns any distant
 * jump into a single, real one-page pager slide that lands exactly on the right track
 * -- after which the pager is silently remapped onto the real index (content is
 * identical, so nothing pops).
 */
private data class PendingJump(val viaPage: Int, val targetIndex: Int)

/**
 * Format fallback shown when a track has no album art. Deliberately a leaf: it is the
 * only thing here that reads the per-frame [FoamTheme.accentColor] directly, so accent
 * animation frames invalidates just this text, not the pager page around it.
 */
@Composable
private fun FormatFallbackLabel(format: String, modifier: Modifier = Modifier) {
    BasicText(
        text = format.take(3),
        style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor),
        modifier = modifier
    )
}

/**
 * Play/pause button. Deliberately a leaf: the accent background changes every frame
 * while the accent color animates, and keeping the read here means only this button
 * recomposes per frame instead of the whole [MiniPlayer] body.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
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
            .clickable(onClick = onPlayPauseClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) FoamTheme.icons.current.pause else FoamTheme.icons.current.play,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = FoamTheme.colors.background,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Isolated on purpose, mirroring PlaybackProgressSlider in FullPlayerScreen.kt:
 * seekBarProgress.progress tracks pagerState.currentPageOffsetFraction under the
 * hood, which changes on essentially every frame of a horizontal swipe. Reading it
 * directly inside MiniPlayer's own body (as this used to do) forced the whole
 * function -- album art, title/artist text, and the HorizontalPager itself -- to
 * recompose on every drag pixel. Reading it here instead confines that
 * recomposition to just the slider, whose track fill is drawn from the value
 * lambda without recomposition at all.
 *
 * The seek itself is committed only on release: onValueChange just parks the
 * dragged position in local state, so dragging no longer issues a real media seek
 * (and a PlaybackState emission) per pixel.
 *
 * Accent colors are read here rather than in MiniPlayer's body on purpose: the
 * app-wide accent animates per frame while swiping between tracks, and reading it
 * in the body recomposed the whole mini player (art pager, texts, play button) on
 * every accent frame.
 */
@Composable
private fun MiniPlayerProgressSlider(
    seekBarProgress: SeekBarProgressState,
    isAnimateSeekBarEnabled: Boolean,
    isBitPerfectMode: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val activeColor = if (isBitPerfectMode) {
        FoamTheme.colors.bitPerfectActive
    } else {
        FoamTheme.accentColor
    }

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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    playbackStateFlow: StateFlow<PlaybackState>,
    positionMsFlow: StateFlow<Long>,
    onPlayPauseClick: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipToTrack: (Int) -> Unit,
    onColorChange: (Color?) -> Unit,
    onBackgroundTransition: (frontArtPath: String?, backArtPath: String?, fraction: Float) -> Unit,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    useSharedElements: Boolean = true,
    sharedArtFlightAlpha: () -> Float = { 1f },
    isAnimateSeekBarEnabled: Boolean = false,
    marqueeMode: MarqueeMode = MarqueeMode.PING_PONG,
    skipTrackChangeAnimation: Boolean = false,
    showContainer: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Collected here instead of being passed as a parameter: this keeps playback
    // emissions (play/pause, queue mutations, track changes) from re-running the
    // whole FoamNavHost body just to forward state into this one composable.
    val playbackState by playbackStateFlow.collectAsState()
    val track = playbackState.currentTrack ?: return
    val queue = playbackState.queue
    val queueEntryIds = playbackState.queueEntryIds
    val currentIndex = playbackState.queueIndex
    val pausedArtScale by rememberPausedArtScale(playbackState.isPlaying)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Pager state to handle the progressive swipe animation
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceAtLeast(0),
        pageCount = { queue.size }
    )

    var pendingJump by remember { mutableStateOf<PendingJump?>(null) }

    // See the LaunchedEffect(currentIndex) doc below for why this needs to be a
    // token-guarded holder rather than a plain boolean.
    class PagerSyncState {
        var isSyncing = false
        var activeToken = 0
    }
    val pagerSyncState = remember { PagerSyncState() }

    // rememberSeekBarProgressState only captures `liveProgress` once (remember has no
    // keys), so this lambda must not close over `playbackState`/`currentIndex`
    // directly -- those are plain parameters, and a direct capture would freeze on
    // whatever values existed the first time this composable ran. FullPlayerScreen
    // avoids this because its `state` is a `by collectAsState()` delegate, so reading
    // it always re-reads the live value even from a stale closure; rememberUpdatedState
    // gives us that same "always current" proxy here.
    val latestPlaybackState by rememberUpdatedState(playbackState)
    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestSkipTrackChangeAnimation by rememberUpdatedState(skipTrackChangeAnimation)
    val latestBackgroundTransition by rememberUpdatedState(onBackgroundTransition)
    val latestIsAnimateSeekBarEnabled by rememberUpdatedState(isAnimateSeekBarEnabled)
    val latestQueue by rememberUpdatedState(queue)

    // Same live computation the FoamSlider below already had -- read lazily every
    // time `.progress` is accessed, so it stays live while dragging/swiping and is
    // only overridden by a settle tween after a release/track change.
    val seekBarProgress = rememberSeekBarProgressState(
        positionMsFlow = positionMsFlow,
        liveProgress = { livePositionMs ->
            val progress = if (latestPlaybackState.durationMs > 0) {
                livePositionMs.toFloat() / latestPlaybackState.durationMs
            } else 0f

            val activeFraction = if (isAnimateSeekBarEnabled) {
                val scrollPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
                val distanceFromCurrent = abs(scrollPosition - latestCurrentIndex.coerceAtLeast(0))
                (1f - distanceFromCurrent).coerceIn(0f, 1f)
            } else {
                1f
            }

            progress * activeFraction
        }
    )

    // Ensure colors for nearby tracks (and any in-flight jump target) are available
    // for smooth interpolation. This only asks the shared cache to fetch anything
    // missing -- it never decodes on its own, so it doesn't duplicate work the
    // full player (or a previous pass here) already kicked off for the same paths.
    LaunchedEffect(pagerState.currentPage, queue, pendingJump) {
        val indices = listOf(
            pagerState.currentPage - 1,
            pagerState.currentPage,
            pagerState.currentPage + 1
        )

        val pathsToKeep = (indices.mapNotNull { i ->
            queue.getOrNull(i)?.albumArtPath
        } + listOfNotNull(pendingJump?.let { queue.getOrNull(it.targetIndex)?.albumArtPath })).toSet()

        pathsToKeep.forEach { path ->
            AlbumArtPaletteCache.ensure(scope = this, context = context, path = path)
        }
    }

    // Intercept pager progress to drive the dynamic accent color transition. Fires
    // every frame the pager is mid-drag, by design. See
    // AlbumArtPaletteCache.interpolatedColor for the shared calculation (this used to
    // be a local copy of the same index/lerp math that also lived in FullPlayerScreen).
    //
    // Keyed on pagerState only: pendingJump is snapshot state read inside the
    // snapshotFlow lambda (so the flow re-emits when it changes on its own), and the
    // queue is reached through a rememberUpdatedState proxy -- keying on the List made
    // every queue mutation tear down and resubscribe these collectors.
    LaunchedEffect(pagerState) {
        snapshotFlow {
            val jump = pendingJump
            val currentQueue = latestQueue
            AlbumArtPaletteCache.interpolatedColor(
                currentPage = pagerState.currentPage,
                currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                pageCount = currentQueue.size
            ) { page ->
                if (jump != null && page == jump.viaPage) {
                    currentQueue.getOrNull(jump.targetIndex)?.albumArtPath
                } else {
                    currentQueue.getOrNull(page)?.albumArtPath
                }
            }
        }.collect { interpolatedColor ->
            onColorChange(interpolatedColor)
        }
    }

    // Keep the app-wide backdrop in lockstep with the mini-player pager, including
    // while the user's finger is still dragging between two tracks.
    LaunchedEffect(pagerState) {
        snapshotFlow {
            val jump = pendingJump
            val currentQueue = latestQueue
            val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val frontIndex = absoluteOffset.toInt().coerceIn(0, (currentQueue.size - 1).coerceAtLeast(0))
            val backIndex = (frontIndex + 1).coerceAtMost((currentQueue.size - 1).coerceAtLeast(0))
            val fraction = (absoluteOffset - frontIndex).coerceIn(0f, 1f)
            val pathForPage: (Int) -> String? = { page ->
                if (jump != null && page == jump.viaPage) {
                    currentQueue.getOrNull(jump.targetIndex)?.albumArtPath
                } else {
                    currentQueue.getOrNull(page)?.albumArtPath
                }
            }
            Triple(pathForPage(frontIndex), pathForPage(backIndex), fraction)
        }.collect { (frontArtPath, backArtPath, fraction) ->
            latestBackgroundTransition(frontArtPath, backArtPath, fraction)
        }
    }

    // Reset accent color when the player is dismissed
    DisposableEffect(Unit) {
        onDispose {
            onColorChange(null)
        }
    }

    // Sync pager when track changes. Reads the live queue through the
    // rememberUpdatedState proxy: the queue can change (aux refill, play-next) while
    // this effect is running, and animating against a stale snapshot could target an
    // out-of-range page.
    LaunchedEffect(currentIndex) {
        val currentQueue = latestQueue
        pendingJump = null

        // Doesn't animate is the full player is auto opening
        if (currentIndex in currentQueue.indices &&
            pagerState.currentPage != currentIndex &&
            !latestSkipTrackChangeAnimation
        ) {
            val myToken = ++pagerSyncState.activeToken
            pagerSyncState.isSyncing = true
            try {
                val distance = currentIndex - pagerState.currentPage
                if (abs(distance) <= 1) {
                    // Slide 1 item
                    pagerState.animateScrollToPage(currentIndex)
                } else {
                    // Distant slide : slide 1 item but renders the via as the target track
                    val direction = if (distance > 0) 1 else -1
                    val viaPage = (pagerState.currentPage + direction).coerceIn(0, currentQueue.size - 1)
                    try {
                        pendingJump = PendingJump(viaPage, currentIndex)
                        pagerState.animateScrollToPage(viaPage)
                        pagerState.scrollToPage(currentIndex)
                    } finally {
                        // Runs even if a newer tap cancels us mid-flight, so we
                        // never hand off a stale override to the next jump --
                        // unless a newer run already took over (see token doc above).
                        if (pagerSyncState.activeToken == myToken) {
                            pendingJump = null
                        }
                    }
                }
            } finally {
                if (pagerSyncState.activeToken == myToken) {
                    pagerSyncState.isSyncing = false
                }
            }
        }
    }

    // Lock the bar to 0 only once the finger actually releases -- tweened in animated
    // mode, snapped instantly otherwise -- well before the pager's own fling-to-settle
    // animation visually finishes. Does nothing if the swipe springs back to the same
    // track (targetPage == currentIndex), or while a button-triggered distant
    // [pendingJump] is in flight (that's a programmatic scroll, not a finger swipe).
    //
    // Gating on `isDragged` going from true to false (rather than on `targetPage`
    // directly) matters: PagerState.targetPage already reflects its predicted
    // destination page *while the finger is still down* -- Compose updates it live
    // from drag distance/velocity, well before release -- so collecting it directly
    // fired the settle tween/snap mid-drag instead of on release.
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
        if (!pagerSyncState.isSyncing &&
            pendingJump == null &&
            pagerState.settledPage != latestCurrentIndex &&
            pagerState.settledPage in latestQueue.indices
        ) {
            seekBarProgress.onTrackChangeApplied()
            onSkipToTrack(pagerState.settledPage)
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

    val containerPadding = NavBarDimens.MiniPlayerContainerPadding
    val containerRadius = miniImageRadius + containerPadding

    with(sharedTransitionScope) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = NavBarDimens.MiniPlayerCardVerticalPadding)
                .then(
                    // Elevation over the scrolling content. Only when the card paints
                    // its own surface: in ATTACHED mode the nav bar's extension IS the
                    // card's surface and carries the unit's elevation instead.
                    if (showContainer) {
                        Modifier.shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(containerRadius)
                        )
                    } else Modifier
                )
                .clip(RoundedCornerShape(containerRadius))
                .then(
                    // In ATTACHED mode the nav bar's pill extends up behind the card and
                    // provides the surface; painting the card's own would double the edges.
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
                        // Keyed by queue ENTRY id, not track id: the same track can
                        // legitimately appear twice in the queue ("play next" on the
                        // playing track), and duplicate keys crash the pager.
                        key = { index -> queueEntryIds.getOrNull(index) ?: -(index + 1).toLong() }
                    ) { page ->
                        val jump = pendingJump
                        val pageTrack = (
                                if (jump != null && page == jump.viaPage) {
                                    queue.getOrNull(jump.targetIndex)
                                } else {
                                    queue.getOrNull(page)
                                }
                                ) ?: return@HorizontalPager

                        // Only the page that's genuinely settled as the current
                        // track may carry the shared-element continuity into the
                        // full player. While a jump override is active, `track`
                        // (updated instantly) can transiently match either the
                        // page we're still visually on, or the override page
                        // that hasn't landed yet -- neither is a safe attach
                        // point, since the shared-element overlay renders at
                        // wherever that page actually lays out, which may be
                        // outside the mini player's clipped bounds. Skipping
                        // the tag entirely during a jump avoids that; it
                        // reattaches cleanly on the next frame after landing.
                        // The page index must also match: the queue can hold the
                        // same track more than once ("play next" on the playing
                        // track), and an id match alone would tag the duplicated
                        // neighbor page too -- two visible shared elements with
                        // one key, which derails the close transition.
                        val isSettledCurrentTrackPage =
                            jump == null && page == currentIndex && pageTrack.id == track.id

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onClick() }
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
                                    // The shared-element transition animates this corner radius
                                    // every frame; consuming it inside graphicsLayer keeps that
                                    // animation draw-only (a .clip() here recomposed the whole
                                    // mini player per transition frame and allocated a fresh
                                    // shape each time).
                    .graphicsLayer {
                        shape = RoundedCornerShape(animatedCornerRadius)
                        clip = true
                        scaleX = pausedArtScale
                        scaleY = pausedArtScale
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
                                        contentDescription = "Album Art",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    FormatFallbackLabel(format = pageTrack.format)
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                MarqueeText(
                                    text = pageTrack.title,
                                    style = FoamTheme.typography.titleMedium,
                                    mode = marqueeMode,
                                    marqueeEnabled = isSettledCurrentTrackPage,
                                    modifier = if (useSharedElements && isSettledCurrentTrackPage) {
                                        Modifier.sharedBounds(
                                            rememberSharedContentState(key = "track_title_${track.id}"),
                                            animatedVisibilityScope = animatedVisibilityScope
                                        )
                                    } else Modifier
                                )
                                MarqueeText(
                                    text = "${pageTrack.artist} • ${pageTrack.format}",
                                    style = FoamTheme.typography.caption,
                                    mode = marqueeMode,
                                    marqueeEnabled = isSettledCurrentTrackPage,
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
                    isBitPerfectMode = playbackState.audioOutputInfo?.isBitPerfectMode == true,
                    onSeek = onSeek,
                    modifier = Modifier.height(NavBarDimens.MiniPlayerSliderHeight)
                )
            }
        }
    }
}