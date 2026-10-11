package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.ui.navigation.NavBarDimens
import com.foamplayer.app.ui.screens.player.PlayerDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.rememberPausedArtScale
import kotlin.math.abs
import androidx.core.net.toUri
import kotlinx.coroutines.flow.StateFlow

/**
 * A distant jump in progress: while the pager slides one page to [viaPage],
 * the mini player renders the TARGET track's content there instead of
 * whatever track actually lives on it. That turns any distant jump into a
 * single, real one-page slide that lands exactly on the right track, after
 * which the pager is silently remapped onto the real index (identical
 * content, so nothing pops).
 */
private data class PendingJump(val viaPage: Int, val targetIndex: Int)

/**
 * Play/pause button. Deliberately a leaf: the accent background changes every
 * frame while the accent color animates, and keeping the read here means only
 * this button recomposes per frame instead of the whole [MiniPlayer] body.
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

@OptIn(ExperimentalSharedTransitionApi::class)
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
    modifier: Modifier = Modifier
) {
    val playbackState by playbackStateFlow.collectAsState()
    val track = playbackState.currentTrack ?: return
    val queue = playbackState.queue
    val queueEntryIds = playbackState.queueEntryIds
    val currentIndex = playbackState.queueIndex

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceAtLeast(0),
        pageCount = { queue.size }
    )

    var pendingJump by remember { mutableStateOf<PendingJump?>(null) }

    // Token-guarded sync state: a newer sync must be able to cancel an older
    // one mid-flight without the cancelled one clobbering shared state.
    class PagerSyncState {
        var isSyncing = false
        var activeToken = 0
    }
    val pagerSyncState = remember { PagerSyncState() }

    // rememberSeekBarProgressState captures `liveProgress` once, so this lambda
    // must read the live values through rememberUpdatedState proxies instead
    // of closing over plain parameters, which would freeze on first-composition
    // values.
    val latestPlaybackState by rememberUpdatedState(playbackState)
    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestSkipTrackChangeAnimation by rememberUpdatedState(skipTrackChangeAnimation)
    val latestBackgroundTransition by rememberUpdatedState(onBackgroundTransition)
    val latestIsAnimateSeekBarEnabled by rememberUpdatedState(isAnimateSeekBarEnabled)
    val latestQueue by rememberUpdatedState(queue)

    // Same live computation FoamSlider consumes below — read lazily per access,
    // so it stays live while dragging/swiping and is only overridden by a
    // settle tween after a release/transport-button track change.
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

    // Ensure colors for nearby tracks (and any in-flight jump target) are
    // available for interpolation. Only asks the shared cache for missing
    // paths — never decodes on its own.
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

    // Drives the dynamic accent color transition while the pager is mid-drag.
    // Keyed on pagerState only: pendingJump is snapshot state read inside the
    // snapshotFlow lambda, and the queue is reached through a
    // rememberUpdatedState proxy — keying on the List made every queue
    // mutation tear down and resubscribe these collectors.
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
            // An artless page (or one still being extracted) yields no color.
            // Keeping the last accent avoids flashing the static default
            // mid-swipe and snapping back the next frame a colored page
            // re-enters the interpolation.
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

    // Reset the accent color when the player is dismissed.
    DisposableEffect(Unit) {
        onDispose {
            onColorChange(null)
        }
    }

    // Sync the pager when the track changes; reads the live queue through the
    // proxy, since the queue can change while this effect is running.
    LaunchedEffect(currentIndex) {
        val currentQueue = latestQueue
        pendingJump = null

        if (currentIndex in currentQueue.indices &&
            pagerState.currentPage != currentIndex &&
            !latestSkipTrackChangeAnimation
        ) {
            val myToken = ++pagerSyncState.activeToken
            pagerSyncState.isSyncing = true
            try {
                val distance = currentIndex - pagerState.currentPage
                if (abs(distance) <= 1) {
                    pagerState.animateScrollToPage(currentIndex)
                } else {
                    // Distant jump: slide one page but render the via page as
                    // the target track (see [PendingJump]).
                    val direction = if (distance > 0) 1 else -1
                    val viaPage = (pagerState.currentPage + direction).coerceIn(0, currentQueue.size - 1)
                    try {
                        pendingJump = PendingJump(viaPage, currentIndex)
                        pagerState.animateScrollToPage(viaPage)
                        pagerState.scrollToPage(currentIndex)
                    } finally {
                        // Runs even if a newer tap cancels us mid-flight, unless
                        // a newer run already took over.
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
        if (!pagerSyncState.isSyncing &&
            pendingJump == null &&
            pagerState.settledPage != latestCurrentIndex &&
            pagerState.settledPage in latestQueue.indices
        ) {
            seekBarProgress.onTrackChangeApplied()
            // Keep the swipe direction: a swipe back must walk further back
            // past tracks that failed to load.
            onSkipToTrack(
                pagerState.settledPage,
                pagerState.settledPage > latestCurrentIndex
            )
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

    // Same driver as the corner radius above: 1 whenever this art is flying, 0
    // once it rests. When the full player closes, THIS art is the morph's
    // target — so the full player's drop shadow has to live here too, fading
    // to zero as the art lands in the shadowless mini slot.
    val animatedArtElevationFraction by animatedVisibilityScope.transition.animateFloat(label = "miniArtElevation") { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 0f else 1f
    }

    // The full player hero's current paused shrink. This slot rests at 1f (a
    // play/pause flip must not animate the mini art), but mid-flight this
    // layer renders the shared-element overlay, so it must depart at the
    // hero's current paused scale and relax to 1f as it lands — or a close
    // flight taken while paused pops the art 3% at hand-off.
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
                    // Elevation over the scrolling content, only when the card
                    // paints its own surface: in ATTACHED mode the nav bar's
                    // extension IS the card's surface and carries the elevation.
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
                        // Keyed by queue ENTRY id, not track id: the same track
                        // can legitimately appear twice in the queue ("play
                        // next" on the playing track), and duplicate keys crash
                        // the pager.
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

                        // Only the page genuinely settled as the current track
                        // may carry the shared-element continuity into the full
                        // player: during a jump override, `track` can
                        // transiently match either the page still on screen or
                        // the override page that hasn't landed — neither is a
                        // safe attach point. The page index must also match:
                        // the queue can hold the same track twice, and an id
                        // match alone would tag the duplicated neighbor page
                        // too — two visible shared elements with one key.
                        val isSettledCurrentTrackPage =
                            jump == null && page == currentIndex && pageTrack.id == track.id

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
                                    // The shared-element transition animates the
                                    // corner radius every frame; consuming it
                                    // inside graphicsLayer keeps that draw-only.
                                    .graphicsLayer {
                                        shape = RoundedCornerShape(animatedCornerRadius)
                                        clip = true
                                        // Play/pause shrink is the full player
                                        // hero's rest look ONLY; mid-flight this
                                        // layer renders the overlay and must
                                        // depart at the hero's CURRENT paused
                                        // scale, easing to this slot's unscaled
                                        // rest along the shadow's curve.
                                        val scale =
                                            1f + (heroPausedArtScale - 1f) * animatedArtElevationFraction
                                        scaleX = scale
                                        scaleY = scale
                                        // Only while this art is the morph's
                                        // renderer; gated off in immersive so the
                                        // fading sharedArtFlightAlpha never
                                        // coexists with a shadow a layer's own
                                        // alpha cannot fade.
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
                                    marqueeEnabled = isSettledCurrentTrackPage,
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
                    onSeek = onSeek,
                    modifier = Modifier.height(NavBarDimens.MiniPlayerSliderHeight)
                )
            }
        }
    }
}
