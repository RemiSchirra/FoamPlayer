package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.data.settings.SeekBarStyle
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.CrossfadingPlayPauseIcon
import com.foamplayer.app.ui.components.PLAY_PAUSE_CROSSFADE_MS
import com.foamplayer.app.ui.components.NoAlbumArtPlaceholder
import com.foamplayer.app.ui.components.PreviousAction
import com.foamplayer.app.ui.components.SeekBarProgressState
import com.foamplayer.app.ui.components.TrackScoreHeart
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.components.WaveformSeekBar
import com.foamplayer.app.ui.components.onSeekBarReleased
import com.foamplayer.app.ui.modals.LocalTrackFoamModalState
import com.foamplayer.app.ui.components.MarqueeText
import com.foamplayer.app.ui.components.MarqueeTextState
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.TimeUtils
import com.foamplayer.app.ui.utils.WaveformState
import com.foamplayer.app.ui.utils.pressShrink
import com.foamplayer.app.ui.utils.rememberPausedArtScale
import com.foamplayer.app.ui.utils.rememberWaveform
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Layer 1 content: handle, hi-res badge, the track hero pager, playback controls, subview shortcuts. */
@Composable
internal fun SharedTransitionScope.MainPlayerContent(
    track: TrackEntity,
    // Narrow projections of PlaybackState (see FullPlayerScreen): passing only what this
    // subtree renders instead of the whole state object lets play/pause flips, duration
    // ticks and audio-output changes skip the entire pager/controls tree.
    queue: List<TrackEntity>,
    queueIndex: Int,
    queueEntryIds: List<Long>,
    isPlaying: () -> Boolean,
    isLoading: () -> Boolean,
    isPlaybackActive: () -> Boolean,
    durationMs: Long,
    pagerState: PagerState,
    density: Density,
    screenWidthDp: Dp,
    placeholderColor: Color,
    immersive: Boolean,
    vinyl: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope,
    viewModel: PlayerViewModel,
    playerTransitionState: SeekableTransitionState<Boolean>,
    upTransitionState: SeekableTransitionState<Boolean>,
    seekBarProgress: SeekBarProgressState,
    isAnimateSeekBarEnabled: Boolean,
    seekBarStyle: SeekBarStyle,
    audioInfoText: String?,
    onAudioInfoClick: () -> Unit,
    // The waveform envelope morph (waveform seek bar only, both null otherwise): the
    // track the bars are anchored on when it is NOT the playing one (pinned at trigger
    // time, carried across re-aims), and the track being changed to -- both resolved by
    // FullPlayerScreen through the process-wide WaveformCache, with the 0f..1f blend
    // driver (the pager's swipe fraction while the finger is down, the swap tween's
    // blend otherwise) as a draw-phase read.
    morphFromWaveform: WaveformState?,
    morphTargetWaveform: WaveformState?,
    envelopeMorphProgress: () -> Float,
    marqueeMode: MarqueeMode,
    scoreOverrides: Map<Long, Int>,
    onSetScore: (Long, Int) -> Unit,
    onOpenAlbum: (TrackEntity) -> Unit,
    onOpenArtist: (TrackEntity) -> Unit,
    flightGeometry: PlayerFlightGeometryState? = null,
    /** Reports a transport-button skip (true = previous) so a repeat-all wrap animates in the right direction. */
    onSkipIntent: (backward: Boolean) -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = PlayerDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Shared drag preview of the seek bar: mirrored by [PlaybackProgressSlider] while
        // the finger is down, read by [TonearmOverlay] so the arm follows a seek drag in
        // realtime instead of only on release.
        val seekDragPreview = remember { mutableStateOf<Float?>(null) }

        TrackHeroPager(
            track = track,
            queue = queue,
            queueIndex = queueIndex,
            queueEntryIds = queueEntryIds,
            isPlaying = isPlaying,
            isPlaybackActive = isPlaybackActive,
            pagerState = pagerState,
            density = density,
            screenWidthDp = screenWidthDp,
            placeholderColor = placeholderColor,
            immersive = immersive,
            vinyl = vinyl,
            outerVisibilityScope = outerVisibilityScope,
            headerVisibilityScope = headerVisibilityScope,
            playerTransitionState = playerTransitionState,
            upTransitionState = upTransitionState,
            seekBarProgress = seekBarProgress,
            seekDragPreview = seekDragPreview,
            scoreOverrides = scoreOverrides,
            onSetScore = onSetScore,
            viewModel = viewModel,
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            marqueeMode = marqueeMode,
            flightGeometry = flightGeometry
        )

        Spacer(modifier = Modifier.weight(1f))

        // The neighbors a Next/Previous press swaps the waveform's envelope toward;
        // null when the style isn't waveform or the queue edge is reached.
        val isWaveformSeek = seekBarStyle == SeekBarStyle.WAVEFORM

        PlaybackControls(
            track = track,
            isPlaying = isPlaying,
            isLoading = isLoading,
            durationMs = durationMs,
            outerVisibilityScope = outerVisibilityScope,
            seekBarProgress = seekBarProgress,
            dragValueState = seekDragPreview,
            isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
            seekBarStyle = seekBarStyle,
            audioInfoText = audioInfoText,
            onAudioInfoClick = onAudioInfoClick,
            morphFromWaveform = morphFromWaveform,
            morphTargetWaveform = morphTargetWaveform,
            envelopeMorphProgress = envelopeMorphProgress,
            onSeek = { positionMs -> viewModel.seekTo(positionMs) },
            onPrevious = {
                when (
                    seekBarProgress.onPreviousPressed(
                        animated = isAnimateSeekBarEnabled,
                        positionMs = viewModel.positionMs.value,
                        morphTargetPage = if (isWaveformSeek) (queueIndex - 1).takeIf { it >= 0 } else null,
                        fromBlend = envelopeMorphProgress(),
                        fromPageIndex = queueIndex
                    )
                ) {
                    PreviousAction.RESTART_CURRENT -> viewModel.seekTo(0L)
                    PreviousAction.SKIP_TO_PREVIOUS -> {
                        onSkipIntent(true)
                        viewModel.previous()
                    }
                }
            },
            onPlayPause = { if (isPlaying()) viewModel.pause() else viewModel.play() },
            onNext = {
                seekBarProgress.onNextPressed(
                    animated = isAnimateSeekBarEnabled,
                    morphTargetPage = if (isWaveformSeek) (queueIndex + 1).takeIf { it < queue.size } else null,
                    fromBlend = envelopeMorphProgress(),
                    fromPageIndex = queueIndex
                )
                onSkipIntent(false)
                viewModel.next()
            }
        )

        Spacer(modifier = Modifier.height(PlayerDimens.controlsBottomSpacer))
    }
}

/** Title + artist/album marquee state of one queue entry. */
private class EntryMarqueeStates {
    val title = MarqueeTextState()
    val subtitle = MarqueeTextState()
}

/**
 * Renders the album art + title/artist for the current queue, and drives the transition
 * between them and the mini player / subview header when swiping.
 */
@Composable
internal fun SharedTransitionScope.TrackHeroPager(
    track: TrackEntity,
    queue: List<TrackEntity>,
    queueIndex: Int,
    queueEntryIds: List<Long>,
    isPlaying: () -> Boolean,
    isPlaybackActive: () -> Boolean,
    pagerState: PagerState,
    density: Density,
    screenWidthDp: Dp,
    placeholderColor: Color,
    immersive: Boolean,
    vinyl: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope,
    playerTransitionState: SeekableTransitionState<Boolean>,
    upTransitionState: SeekableTransitionState<Boolean>,
    seekBarProgress: SeekBarProgressState,
    // Shared drag preview of the seek bar: set by [PlaybackProgressSlider] while the
    // finger is down (null at rest), read by [TonearmOverlay] so the arm follows a
    // seek drag in realtime.
    seekDragPreview: MutableState<Float?>,
    scoreOverrides: Map<Long, Int>,
    onSetScore: (Long, Int) -> Unit,
    viewModel: PlayerViewModel,
    onOpenAlbum: (TrackEntity) -> Unit,
    onOpenArtist: (TrackEntity) -> Unit,
    marqueeMode: MarqueeMode,
    flightGeometry: PlayerFlightGeometryState? = null
) {
    val anchorPage = queueIndex.coerceAtLeast(0)


    // Marquee scroll state per queue ENTRY, shared by the pager page and the hero overlay
    // showing that entry: the page drives it (it is composed for as long as the entry is
    // on screen, swiping or not), the hero only renders it. When a swipe commits and the
    // hero re-anchors on the incoming entry, it therefore continues from the exact scroll
    // offset the page had -- and the outgoing hero hands over to its page the same way.
    val marqueeStates = remember { mutableMapOf<Long, EntryMarqueeStates>() }
    val entryKey = { page: Int -> queueEntryIds.getOrNull(page) ?: -(page + 1).toLong() }
    val statesFor = { key: Long -> marqueeStates.getOrPut(key) { EntryMarqueeStates() } }
    LaunchedEffect(queueEntryIds) { marqueeStates.keys.retainAll(queueEntryIds.toSet()) }

    // A swipe starting rewinds every marquee except the page being left: the neighbors
    // are composed (prefetched) before the finger moves, so their pause timer may already
    // be running or they may be mid-scroll. After this, an incoming title only starts
    // scrolling if the swipe is held long enough for its own pause to elapse.
    val currentEntryKey by rememberUpdatedState(entryKey)

    // Entry-aware: a pure renumbering (repeat mode toggled, a row removed before the playing one)
    // changes anchorPage in composition one frame before the pager's own position follows at measure.
    // Comparing raw page numbers there reads "pager far from anchor", drops the hero for that frame
    // and flashes the art + title. The pager's last-measured page is resolved to its entry key, so
    // both sides always describe the same entry.
    val showHero by remember(anchorPage, immersive, pagerState) {
        derivedStateOf {
            if (immersive) {
                true
            } else {
                val pageKey = pagerState.layoutInfo.visiblePagesInfo
                    .firstOrNull { it.index == pagerState.currentPage }?.key
                val offset = if (pageKey == currentEntryKey(anchorPage)) {
                    pagerState.currentPageOffsetFraction
                } else {
                    pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                }
                abs(offset) < 1f
            }
        }
    }
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

    val miniImageSize = 44.dp
    val fullImageSize = screenWidthDp - (PlayerDimens.horizontalPagePadding * 2)

    // Pages (and the hero overlay) span the same width as the seek bar / controls
    // (screenPadding), so the title + score + more row lines up with the seek bar. The
    // album art alone is inset by this extra amount to keep its original size
    // (horizontalPagePadding on each side).
    val artInset = (PlayerDimens.horizontalPagePadding - PlayerDimens.screenPadding).coerceAtLeast(0.dp)

    val pausedArtScale by rememberPausedArtScale(isPlaybackActive)

    val miniPlayerRounding = FoamTheme.shapes.miniPlayerRounding
    val miniRadius = (miniImageSize / 2) * miniPlayerRounding
    val fullRadius = (fullImageSize / 2) * FoamTheme.shapes.fullPlayerRounding

    // Paused-shrink departure/arrival: the endpoints of the art's shared-element pairs
    // (mini player, subview header) rest UNSCALED -- only this hero shrinks on
    // play/pause. During a flight the overlay renders the entering endpoint's layer,
    // so that layer must depart at THIS hero's current paused scale and relax to the
    // endpoint's own rest 1f along the blend fraction. The hero side mirrors it:
    // easing from the endpoint's 1f rest at flight start to its paused rest at settle,
    // along the same fraction its drop shadow rides -- otherwise a paused hero pops
    // 3% against the unscaled endpoint (mini/header) on the open's first frame, or
    // snaps from 0.97 to 1f the instant a close flight hands the overlay over.
    val heroRestFraction = remember(playerTransitionState, upTransitionState) {
        {
            val rawOuter = playerTransitionState.progress().coerceIn(0f, 1f)
            val outerFraction = if (playerTransitionState.targetState) {
                EaseOutCubic.transform(rawOuter)
            } else {
                EaseInCubic.transform(rawOuter)
            }
            val rawHeader = upTransitionState.progress().coerceIn(0f, 1f)
            val headerFraction = if (upTransitionState.targetState) {
                EaseOutCubic.transform(rawHeader)
            } else {
                EaseInCubic.transform(rawHeader)
            }
            (outerFraction * (1f - headerFraction)).coerceIn(0f, 1f)
        }
    }

    // Vinyl-mode flight fade: 1 while the player rests open, easing to 0 during the
    // open/close flight toward the mini player and while a subview slides up. It is
    // the exact mirror of the square shared-element art (which uses this sum directly
    // and is visible at rest mini and during flights), so the record and the square
    // art crossfade instead of stacking.
    val vinylFlightFade = remember(playerTransitionState, upTransitionState) {
        {
            val openFade = 1f - EaseOutCubic.transform(
                (playerTransitionState.progress() / immersiveArtFadeSpan).coerceIn(0f, 1f)
            )
            val subViewFade = 1f - EaseOutCubic.transform(
                ((1f - upTransitionState.progress()) / immersiveArtFadeSpan).coerceIn(0f, 1f)
            )
            1f - (openFade + subViewFade).coerceIn(0f, 1f)
        }
    }

    val prefetchContext = LocalContext.current
    LaunchedEffect(pagerState, queue) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            for (p in (page - 2)..(page + 2)) {
                val path = queue.getOrNull(p)?.albumArtPath ?: continue
                prefetchContext.imageLoader.enqueue(
                    ImageRequest.Builder(prefetchContext)
                        .data(EmbeddedAlbumArt(path.toUri()))
                        .size(AlbumArtRequestSize)
                        .build()
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = PlayerDimens.screenPadding),
            // Pages are wider by 2 * artInset now; shave it off the spacing so the gap
            // between neighbouring album arts (and their peek) stays what it was.
            pageSpacing = (PlayerDimens.pageSpacing - artInset * 2).coerceAtLeast(0.dp),
            beyondViewportPageCount = 0,
            // Keyed by queue ENTRY id, not track id: the same track can legitimately
            // appear twice in the queue ("play next" on the playing track), and
            // duplicate keys crash the pager.
            key = { page -> entryKey(page) }
        ) { page ->
            val pageTrack = queue.getOrNull(page) ?: return@HorizontalPager
            val isAnchorPage = page == anchorPage
            val pageMarquee = statesFor(entryKey(page))

            fun anchorPageAlpha(): Float =
                if (!isAnchorPage) {
                    1f
                } else {
                    val offset = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                    abs(offset).coerceIn(0f, 1f)
                }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (immersive) {
                    Spacer(modifier = Modifier.padding(horizontal = artInset).fillMaxWidth().aspectRatio(1f))
                } else if (vinyl) {
                    // Vinyl mode keeps the normal pager mechanics (each page is its own
                    // record, so swipes slide/fade like the square art); the anchor page
                    // renders a spinning record instead. Its layer alpha is the inverse
                    // of the hero's immersive fade: visible at rest, fading out as the
                    // player closes toward the mini player or as a subview slides up, so
                    // the square shared-element art can take over during flights. The
                    // player-level tonearm overlay is drawn above the pager; the record
                    // slides beneath it during swipes. Unlike classic mode the record
                    // never scales on pause/play.
                    Box(
                        modifier = Modifier
                            .padding(horizontal = artInset)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .graphicsLayer { alpha = vinylFlightFade() },
                        contentAlignment = Alignment.Center
                    ) {
                        VinylDisc(
                            albumArtPath = pageTrack.albumArtPath,
                            isPlaying = isPlaying,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = artInset)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .graphicsLayer {
                                alpha = anchorPageAlpha()
                                scaleX = pausedArtScale
                                scaleY = pausedArtScale
                                shape = RoundedCornerShape(with(density) { fullRadius.toPx() })
                                clip = true
                                shadowElevation = if (!immersive && !isAnchorPage) {
                                    with(density) { PlayerDimens.fullPlayerArtElevation.toPx() }
                                } else {
                                    0f
                                }
                            }
                            .background(placeholderColor),
                        contentAlignment = Alignment.Center
                    ) {
                        if (pageTrack.albumArtPath != null) {
                            val context = LocalContext.current
                            val pageArtRequest = remember(pageTrack.albumArtPath) {
                                ImageRequest.Builder(context)
                                    .data(EmbeddedAlbumArt(pageTrack.albumArtPath.toUri()))
                                    .size(AlbumArtRequestSize)
                                    .crossfade(false)
                                    .build()
                            }
                            AsyncImage(
                                model = pageArtRequest,
                                contentDescription = stringResource(R.string.album_art),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            // No art at all: the image-like music-note placeholder, filling
                            // this page's art box the same way the mini player's slot and the
                            // subview header do, so shared-element flights land on the exact
                            // same look on every surface.
                            NoAlbumArtPlaceholder()
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = anchorPageAlpha() }
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            // While the hero overlay shows this (anchor) page's text, hide the
                            // page's copy: both render the same shared marquee state, so
                            // drawing both would only double the glyphs.
                            .graphicsLayer { alpha = if (isAnchorPage && showHero) 0f else 1f }
                            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = FoamTheme.colors.textPrimary),
                                onClick = { onOpenAlbum(pageTrack) },
                                onLongClick = { onOpenArtist(pageTrack) }
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        MarqueeText(
                            text = pageTrack.title,
                            style = FoamTheme.typography.titleLarge,
                            mode = marqueeMode,
                            state = pageMarquee.title
                        )
                        MarqueeText(
                            text = stringResource(R.string.artist_album_separator, pageTrack.artist, pageTrack.album),
                            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                            mode = marqueeMode,
                            state = pageMarquee.subtitle
                        )
                    }
                    TrackScoreHeart(
                        score = scoreOverrides[pageTrack.id] ?: pageTrack.score,
                        onSetScore = { value -> onSetScore(pageTrack.id, value) }
                    )
                    TrackMoreButton(track = pageTrack, refresh = viewModel::freshTrack)
                }
            }
        }

        if (showHero) {
            val heroMarquee = statesFor(entryKey(anchorPage))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PlayerDimens.screenPadding)
                    .graphicsLayer {
                        val offset = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                        val pageWidthPx = pagerState.layoutInfo.pageSize.toFloat()
                        val pageSpacingPx = pagerState.layoutInfo.pageSpacing.toFloat()
                        translationX = -offset * (pageWidthPx + pageSpacingPx)
                    }
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = artInset)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .onGloballyPositioned { coordinates ->
                            // Root-space center of the shared-element hero art: one
                            // endpoint of the dismiss swipe's 1:1 distance mapping.
                            flightGeometry?.heroArtCenterY =
                                coordinates.positionInRoot().y + coordinates.size.height / 2f
                        }
                        .sharedElement(
                            rememberSharedContentState(key = "album_art_${track.id}"),
                            animatedVisibilityScope = outerVisibilityScope
                        )
                        .sharedElement(
                            rememberSharedContentState(key = "album_art_header_${track.id}"),
                            animatedVisibilityScope = headerVisibilityScope
                        )
                        .graphicsLayer {
                            val rawOuter = playerTransitionState.progress().coerceIn(0f, 1f)
                            val outerFraction = if (playerTransitionState.targetState) {
                                EaseOutCubic.transform(rawOuter)
                            } else {
                                EaseInCubic.transform(rawOuter)
                            }
                            val rawHeader = upTransitionState.progress().coerceIn(0f, 1f)
                            val headerFraction = if (upTransitionState.targetState) {
                                EaseOutCubic.transform(rawHeader)
                            } else {
                                EaseInCubic.transform(rawHeader)
                            }

                            val restRadius = with(density) {
                                (size.minDimension / 2f * miniPlayerRounding).toDp()
                            }
                            shape = RoundedCornerShape(
                                with(density) {
                                    min(
                                        if (outerFraction < 1f) {
                                            lerp(
                                                start = restRadius,
                                                stop = fullRadius,
                                                fraction = outerFraction
                                            )
                                        } else {
                                            fullRadius
                                        },
                                        lerp(
                                            start = fullRadius,
                                            stop = miniRadius,
                                            fraction = headerFraction
                                        )
                                    ).toPx()
                                }
                            )
                            clip = true
                            // The paused shrink eases in/out with the flight: at rest the
                            // hero shows its own paused look (heroRestFraction 1), and while
                            // flying it tracks the unscaled endpoint the overlay departs
                            // from (fraction 0), so both ends of the pair render the same
                            // scale the moment the gesture starts. See
                            // rememberPausedArtScale for the endpoint-side mirror.
                            scaleX = pausedArtScale + (1f - pausedArtScale) * (1f - heroRestFraction())
                            scaleY = pausedArtScale + (1f - pausedArtScale) * (1f - heroRestFraction())
                            shadowElevation = if (!immersive && !vinyl) {
                                with(density) {
                                    PlayerDimens.fullPlayerArtElevation.toPx()
                                } * outerFraction * (1f - headerFraction)
                            } else {
                                0f
                            }
                            alpha = if (immersive || vinyl) {
                                val openFade = 1f - EaseOutCubic.transform(
                                    (playerTransitionState.progress() / immersiveArtFadeSpan)
                                        .coerceIn(0f, 1f)
                                )
                                val subViewFade = 1f - EaseOutCubic.transform(
                                    ((1f - upTransitionState.progress()) / immersiveArtFadeSpan)
                                        .coerceIn(0f, 1f)
                                )
                                (openFade + subViewFade).coerceIn(0f, 1f)
                            } else {
                                1f
                            }
                        }
                        .background(placeholderColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (track.albumArtPath != null) {
                        val context = LocalContext.current
                        val albumArtRequest = remember(track.albumArtPath) {
                            ImageRequest.Builder(context)
                                .data(EmbeddedAlbumArt(track.albumArtPath.toUri()))
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
                        // No art at all: the image-like music-note placeholder, exactly
                        // like the mini player's slot and this page's underlying art box.
                        NoAlbumArtPlaceholder()
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = FoamTheme.colors.textPrimary),
                                onClick = { onOpenAlbum(track) },
                                onLongClick = { onOpenArtist(track) }
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        MarqueeText(
                            text = track.title,
                            style = FoamTheme.typography.titleLarge,
                            mode = marqueeMode,
                            state = heroMarquee.title,
                            drivesState = false,
                            modifier = Modifier
                                .sharedBounds(
                                    rememberSharedContentState(key = "track_title_${track.id}"),
                                    animatedVisibilityScope = outerVisibilityScope
                                )
                                .sharedBounds(
                                    rememberSharedContentState(key = "track_title_header_${track.id}"),
                                    animatedVisibilityScope = headerVisibilityScope
                                )
                        )
                        MarqueeText(
                            text = stringResource(R.string.artist_album_separator, track.artist, track.album),
                            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                            mode = marqueeMode,
                            state = heroMarquee.subtitle,
                            drivesState = false,
                            modifier = Modifier
                                .sharedBounds(
                                    rememberSharedContentState(key = "track_artist_${track.id}"),
                                    animatedVisibilityScope = outerVisibilityScope
                                )
                                .sharedBounds(
                                    rememberSharedContentState(key = "track_artist_header_${track.id}"),
                                    animatedVisibilityScope = headerVisibilityScope
                                )
                        )
                    }
                    key(track.id) {
                        TrackScoreHeart(
                            score = scoreOverrides[track.id] ?: track.score,
                            onSetScore = { value -> onSetScore(track.id, value) }
                        )
                    }
                    TrackMoreButton(track = track, refresh = viewModel::freshTrack)
                }
            }
        }
        if (vinyl) {
            var isPagerSwiping by remember { mutableStateOf(false) }
            LaunchedEffect(pagerState) {
                pagerState.interactionSource.interactions.collect { interaction ->
                    if (interaction is DragInteraction.Start) isPagerSwiping = true
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.isScrollInProgress }
                    .collect { scrolling -> if (!scrolling) isPagerSwiping = false }
            }
            TonearmOverlay(
                progress = { seekDragPreview.value ?: seekBarProgress.progress },
                isDragging = { seekDragPreview.value != null },
                isSwiping = { isPagerSwiping },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = PlayerDimens.screenPadding + artInset)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer { alpha = vinylFlightFade() }
            )
        }
    }
}

/**
 * The three-dots affordance to the right of the track score heart: opens the
 * track actions modal (TrackFoamModal) for [track] via the root-provided
 * modal state. Same 40dp touch target / 26dp icon sizing as the score heart.
 */
@Composable
internal fun TrackMoreButton(
    track: TrackEntity,
    // Re-reads the track from the DB right before the modal opens: the queue entity is a
    // snapshot, so its play count / last-listen date are stale once the track has played.
    refresh: suspend (TrackEntity) -> TrackEntity = { it }
) {
    val trackModalState = LocalTrackFoamModalState.current
    val scope = rememberCoroutineScope()
    val moreInteractions = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(40.dp)
            .pressShrink(moreInteractions)
            .clickable(
                interactionSource = moreInteractions,
                indication = null
            ) { scope.launch { trackModalState?.show(refresh(track)) } },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = stringResource(R.string.more_options),
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
internal fun SharedTransitionScope.PlaybackControls(
    track: TrackEntity,
    // Deferred: forwarded to PlayPauseButton, which reads it in its own scope.
    isPlaying: () -> Boolean,
    isLoading: () -> Boolean,
    durationMs: Long,
    outerVisibilityScope: AnimatedVisibilityScope,
    seekBarProgress: SeekBarProgressState,
    dragValueState: MutableState<Float?>,
    isAnimateSeekBarEnabled: Boolean,
    seekBarStyle: SeekBarStyle,
    audioInfoText: String?,
    onAudioInfoClick: () -> Unit,
    morphFromWaveform: WaveformState?,
    morphTargetWaveform: WaveformState?,
    envelopeMorphProgress: () -> Float,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PlayerDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PlaybackProgressSlider(
            durationMs = durationMs,
            seekBarProgress = seekBarProgress,
            isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
            seekBarStyle = seekBarStyle,
            track = track,
            morphFromWaveform = morphFromWaveform,
            morphTargetWaveform = morphTargetWaveform,
            trackChangeProgress = envelopeMorphProgress,
            onSeek = onSeek,
            audioInfoText = audioInfoText,
            onAudioInfoClick = onAudioInfoClick,
            dragValueState = dragValueState
        )
        Spacer(modifier = Modifier.height(PlayerDimens.controlsSpacing))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val previousInteractions = remember { MutableInteractionSource() }
            Icon(
                imageVector = FoamTheme.icons.current.previous,
                contentDescription = stringResource(R.string.previous),
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier
                    .size(PlayerDimens.transportIconSize)
                    .pressShrink(previousInteractions)
                    .clickable(
                        interactionSource = previousInteractions,
                        indication = null,
                        onClick = onPrevious
                    )
            )
            PlayPauseButton(
                track = track,
                isPlaying = isPlaying,
                isLoading = isLoading,
                outerVisibilityScope = outerVisibilityScope,
                onClick = onPlayPause
            )
            val nextInteractions = remember { MutableInteractionSource() }
            Icon(
                imageVector = FoamTheme.icons.current.next,
                contentDescription = stringResource(R.string.next),
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier
                    .size(PlayerDimens.transportIconSize)
                    .pressShrink(nextInteractions)
                    .clickable(
                        interactionSource = nextInteractions,
                        indication = null,
                        onClick = onNext
                    )
            )
        }
    }
}

/**
 * Isolated on purpose (same rationale as before the circle was dropped): keeping
 * this button its own composable confines any per-frame recomposition it causes
 * to itself instead of also dragging the Prev/Next icons and shared-element
 * bounds along with it.
 */
@Composable
internal fun SharedTransitionScope.PlayPauseButton(
    track: TrackEntity,
    isPlaying: () -> Boolean,
    isLoading: () -> Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Read in this leaf's scope: a play/pause flip recomposes only this button -- the
    // deferred provider keeps the controls row and the pager above it uninvalidated.
    val isPlaying = isPlaying()
    val isLoading = isLoading()
    val playInteractions = remember { MutableInteractionSource() }

    val isPressed by playInteractions.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) PLAY_BUTTON_PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "playButtonPressScale"
    )

    // The loading circle replaces the play/pause glyph with the same scale+fade
    // convention the two icons use: 0f = transport showing, 1f = circle showing.
    val loadingFraction by animateFloatAsState(
        targetValue = if (isLoading) 1f else 0f,
        animationSpec = tween(durationMillis = PLAY_PAUSE_CROSSFADE_MS),
        label = "playLoadingCrossfade"
    )

    val description = stringResource(
        when {
            isLoading -> R.string.loading
            isPlaying -> R.string.pause
            else -> R.string.play
        }
    )
    Box(
        modifier = modifier
            .size(PlayerDimens.playButtonSize)
            .sharedBounds(
                rememberSharedContentState(key = "play_button_${track.id}"),
                animatedVisibilityScope = outerVisibilityScope
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = playInteractions,
                indication = null,
                enabled = !isLoading,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Shared stacked play/pause glyphs: crossfade in lockstep (100% <-> 20% scale
        // while the alpha flips), faded out while the loading circle takes their place.
        CrossfadingPlayPauseIcon(
            isPlaying = isPlaying,
            loadingFraction = { loadingFraction },
            tint = FoamTheme.colors.textPrimary,
            iconSize = PlayerDimens.playButtonIconSize
        )
        if (isLoading || loadingFraction > 0f) {
            CircularProgressIndicator(
                color = FoamTheme.colors.textPrimary,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .size(PlayerDimens.playLoadingSize)
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

private const val PLAY_BUTTON_PRESSED_SCALE = 0.88f

/**
 * Progress bar + optional elapsed/remaining labels. [seekBarStyle] picks the rendering:
 * the classic [FoamSlider] track, or the full player's [WaveformSeekBar] whose
 * bars trace the audio's amplitude envelope (see [rememberWaveform] for the
 * data source). Both share the same drag-to-seek contract and release callback.
 *
 * In waveform mode the track change MORPHS the envelope instead of hard-swapping
 * it: [morphTargetWaveform] carries the track being changed to (resolved +
 * prefetched by FullPlayerScreen through the process-wide WaveformCache) and
 * [trackChangeProgress] drives the blend -- the hero pager's own swipe fraction
 * while the finger is down (the bars follow the swipe continuously), the swap
 * tween's blend otherwise (release, transport buttons, flip catch-ups). Both
 * drivers are draw-phase reads. [morphFromWaveform] anchors the bars on a track
 * other than the playing one when one is pinned at trigger time.
 */
@Composable
internal fun PlaybackProgressSlider(
    durationMs: Long,
    seekBarProgress: SeekBarProgressState,
    isAnimateSeekBarEnabled: Boolean,
    onSeek: (Long) -> Unit,
    seekBarStyle: SeekBarStyle = SeekBarStyle.NORMAL,
    track: TrackEntity? = null,
    morphFromWaveform: WaveformState? = null,
    morphTargetWaveform: WaveformState? = null,
    trackChangeProgress: () -> Float = { 0f },
    modifier: Modifier = Modifier,
    labelSpacing: Dp = PlayerDimens.seekBarLabelSpacing,
    showTimeLabels: Boolean = true,
    audioInfoText: String? = null,
    onAudioInfoClick: () -> Unit = {},
    // Shared drag preview of the seek bar (optional): mirrored on every drag tick so
    // the player-level tonearm can follow a manual seek in realtime (null at rest).
    dragValueState: MutableState<Float?>? = null
) {
    Column(modifier = modifier) {
        var dragValue by remember { mutableStateOf<Float?>(null) }
        val currentOnSeek by rememberUpdatedState(onSeek)

        val onSliderValueChange: (Float) -> Unit = { new ->
            // A fresh manual drag always wins over any settle tween in flight.
            seekBarProgress.onDragging()
            dragValue = new
            dragValueState?.value = new
        }
        val sliderModifier = Modifier.onSeekBarReleased {
            val released = dragValue
            dragValue = null
            dragValueState?.value = null
            if (released != null) {
                currentOnSeek((released * durationMs).toLong())
                seekBarProgress.onSeekDragFinished(
                    animated = isAnimateSeekBarEnabled,
                    releasedAt = released
                )
            }
        }

        if (seekBarStyle == SeekBarStyle.WAVEFORM) {
            // The waveform is fetched (and cached process-wide) only in waveform mode.
            // morphFromWaveform anchors the bars elsewhere while a swap is in flight;
            // at rest the bars trace the playing track's own envelope.
            val waveform = morphFromWaveform ?: track?.let { rememberWaveform(it.path, it.duration) }
            WaveformSeekBar(
                value = { dragValue ?: seekBarProgress.progress },
                onValueChange = onSliderValueChange,
                waveform = waveform,
                targetWaveform = morphTargetWaveform,
                trackChangeProgress = trackChangeProgress,
                modifier = sliderModifier,
                inactiveColor = FoamTheme.accentColor.copy(alpha = 0.4f)
            )
        } else {
            FoamSlider(
                value = { dragValue ?: seekBarProgress.progress },
                onValueChange = onSliderValueChange,
                modifier = sliderModifier,
                inactiveColor = FoamTheme.accentColor.copy(alpha = 0.4f)
            )
        }
        if (showTimeLabels) {
            Spacer(modifier = Modifier.height(labelSpacing))
            val labelStyle = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
            val infoInteractions = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ElapsedTimeLabel(
                    durationMs = durationMs,
                    dragValue = { dragValue },
                    positionMs = { seekBarProgress.positionMs },
                    style = labelStyle
                )
                if (audioInfoText != null) {
                    BasicText(
                        text = audioInfoText,
                        style = labelStyle.copy(color = FoamTheme.typography.body.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .pressShrink(infoInteractions)
                            .clickable(
                                interactionSource = infoInteractions,
                                indication = null,
                                onClick = onAudioInfoClick
                            )
                    )
                }
                BasicText(text = TimeUtils.formatDurationCompact(durationMs), style = labelStyle)
            }
        }
    }
}

/**
 * Isolated so the per-frame position state is only observed here: [derivedStateOf]
 * re-formats the label only when the displayed second actually changes, so position
 * ticks neither recompose this label nor its siblings. [dragValue] is the bar's own
 * drag position (null while not dragging), so a manual seek drag still previews the
 * target time in realtime.
 *
 * At rest the label reads the ENGINE position ([SeekBarProgressState.positionMs]),
 * NOT the value the bar renders: with the animated seek bar a track-change swipe
 * scales the bar's progress down with the pager offset and then tweens/holds it to
 * 0 -- a visual animation of the bar only. The timer keeps counting the real
 * playback position through that (and only jumps when the track actually flips),
 * so it is fed the raw engine position instead of the bar's progress.
 */
@Composable
private fun ElapsedTimeLabel(
    durationMs: Long,
    dragValue: () -> Float?,
    positionMs: () -> Long,
    style: androidx.compose.ui.text.TextStyle
) {
    val elapsedText by remember(durationMs) {
        derivedStateOf {
            val drag = dragValue()
            val ms = if (drag != null) (drag * durationMs).toLong() else positionMs()
            TimeUtils.formatDurationCompact(ms)
        }
    }
    BasicText(text = elapsedText, style = style)
}