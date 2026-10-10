package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.data.settings.SeekBarStyle
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.NoAlbumArtPlaceholder
import com.foamplayer.app.ui.components.PreviousAction
import com.foamplayer.app.ui.components.SeekBarProgressState
import com.foamplayer.app.ui.components.TrackScoreHeart
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.components.WaveformSeekBar
import com.foamplayer.app.ui.components.onSeekBarReleased
import com.foamplayer.app.ui.modals.LocalTrackFoamModalState
import com.foamplayer.app.ui.components.MarqueeText
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.TimeUtils
import com.foamplayer.app.ui.utils.WaveformState
import com.foamplayer.app.ui.utils.pressShrink
import com.foamplayer.app.ui.utils.rememberPausedArtScale
import com.foamplayer.app.ui.utils.rememberWaveform
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Layer 1 content: handle, hi-res badge, the track hero pager, playback controls, subview shortcuts. */
@OptIn(ExperimentalSharedTransitionApi::class)
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
    isPlaybackActive: () -> Boolean,
    durationMs: Long,
    pagerState: PagerState,
    density: Density,
    screenWidthDp: Dp,
    placeholderColor: Color,
    immersive: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope,
    viewModel: PlayerViewModel,
    playerTransitionState: SeekableTransitionState<Boolean>,
    upTransitionState: SeekableTransitionState<Boolean>,
    seekBarProgress: SeekBarProgressState,
    isAnimateSeekBarEnabled: Boolean,
    seekBarStyle: SeekBarStyle,
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
    flightGeometry: PlayerFlightGeometryState? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = PlayerDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        TrackHeroPager(
            track = track,
            queue = queue,
            queueIndex = queueIndex,
            queueEntryIds = queueEntryIds,
            isPlaybackActive = isPlaybackActive,
            pagerState = pagerState,
            density = density,
            screenWidthDp = screenWidthDp,
            placeholderColor = placeholderColor,
            immersive = immersive,
            outerVisibilityScope = outerVisibilityScope,
            headerVisibilityScope = headerVisibilityScope,
            playerTransitionState = playerTransitionState,
            upTransitionState = upTransitionState,
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
            durationMs = durationMs,
            outerVisibilityScope = outerVisibilityScope,
            seekBarProgress = seekBarProgress,
            isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
            seekBarStyle = seekBarStyle,
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
                    PreviousAction.SKIP_TO_PREVIOUS -> viewModel.previous()
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
                viewModel.next()
            }
        )

        Spacer(modifier = Modifier.height(PlayerDimens.controlsBottomSpacer))
    }
}

/**
 * Renders the album art + title/artist for the current queue, and drives the transition
 * between them and the mini player / subview header when swiping.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.TrackHeroPager(
    track: TrackEntity,
    queue: List<TrackEntity>,
    queueIndex: Int,
    queueEntryIds: List<Long>,
    isPlaybackActive: () -> Boolean,
    pagerState: PagerState,
    density: Density,
    screenWidthDp: Dp,
    placeholderColor: Color,
    immersive: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope,
    playerTransitionState: SeekableTransitionState<Boolean>,
    upTransitionState: SeekableTransitionState<Boolean>,
    scoreOverrides: Map<Long, Int>,
    onSetScore: (Long, Int) -> Unit,
    viewModel: PlayerViewModel,
    onOpenAlbum: (TrackEntity) -> Unit,
    onOpenArtist: (TrackEntity) -> Unit,
    marqueeMode: MarqueeMode,
    flightGeometry: PlayerFlightGeometryState? = null
) {
    val anchorPage = queueIndex.coerceAtLeast(0)

    val showHero by remember(anchorPage, immersive) {
        derivedStateOf {
            if (immersive) {
                true
            } else {
                val offset = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                abs(offset) < 1f
            }
        }
    }

    val miniImageSize = 44.dp
    val fullImageSize = screenWidthDp - (PlayerDimens.horizontalPagePadding * 2)

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

    Box(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = PlayerDimens.horizontalPagePadding),
            pageSpacing = PlayerDimens.pageSpacing,
            beyondViewportPageCount = 0,
            // Keyed by queue ENTRY id, not track id: the same track can legitimately
            // appear twice in the queue ("play next" on the playing track), and
            // duplicate keys crash the pager.
            key = { page -> queueEntryIds.getOrNull(page) ?: -(page + 1).toLong() }
        ) { page ->
            val pageTrack = queue.getOrNull(page) ?: return@HorizontalPager
            val isAnchorPage = page == anchorPage

            fun anchorPageAlpha(): Float =
                if (!isAnchorPage) {
                    1f
                } else {
                    val offset = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                    abs(offset).coerceIn(0f, 1f)
                }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (immersive) {
                    Spacer(modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                } else {
                    Box(
                        modifier = Modifier
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
                            marqueeEnabled = false
                        )
                        MarqueeText(
                            text = stringResource(R.string.artist_album_separator, pageTrack.artist, pageTrack.album),
                            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                            mode = marqueeMode,
                            marqueeEnabled = false
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    TrackScoreHeart(
                        score = scoreOverrides[pageTrack.id] ?: pageTrack.score,
                        onSetScore = { value -> onSetScore(pageTrack.id, value) }
                    )
                    TrackMoreButton(track = pageTrack, refresh = viewModel::freshTrack)
                }
            }
        }

        if (showHero) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PlayerDimens.horizontalPagePadding)
                    .graphicsLayer {
                        val offset = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                        val pageWidthPx = pagerState.layoutInfo.pageSize.toFloat()
                        val pageSpacingPx = pagerState.layoutInfo.pageSpacing.toFloat()
                        translationX = -offset * (pageWidthPx + pageSpacingPx)
                    }
            ) {
                Box(
                    modifier = Modifier
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
                            shadowElevation = if (!immersive) {
                                with(density) {
                                    PlayerDimens.fullPlayerArtElevation.toPx()
                                } * outerFraction * (1f - headerFraction)
                            } else {
                                0f
                            }
                            alpha = if (immersive) {
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
                    Spacer(modifier = Modifier.width(12.dp))
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.PlaybackControls(
    track: TrackEntity,
    // Deferred: forwarded to PlayPauseButton, which reads it in its own scope.
    isPlaying: () -> Boolean,
    durationMs: Long,
    outerVisibilityScope: AnimatedVisibilityScope,
    seekBarProgress: SeekBarProgressState,
    isAnimateSeekBarEnabled: Boolean,
    seekBarStyle: SeekBarStyle,
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
            onSeek = onSeek
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
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.PlayPauseButton(
    track: TrackEntity,
    isPlaying: () -> Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Read in this leaf's scope: a play/pause flip recomposes only this button -- the
    // deferred provider keeps the controls row and the pager above it uninvalidated.
    val isPlaying = isPlaying()
    val playInteractions = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(PlayerDimens.playButtonSize)
            .sharedBounds(
                rememberSharedContentState(key = "play_button_${track.id}"),
                animatedVisibilityScope = outerVisibilityScope
            )
            .pressShrink(playInteractions)
            .clickable(
                interactionSource = playInteractions,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) FoamTheme.icons.current.pause else FoamTheme.icons.current.play,
            contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(PlayerDimens.playButtonIconSize)
        )
    }
}

/**
 * Progress bar + elapsed/remaining labels. [seekBarStyle] picks the rendering:
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
    labelSpacing: Dp = PlayerDimens.seekBarLabelSpacing
) {
    Column(modifier = modifier) {
        var dragValue by remember { mutableStateOf<Float?>(null) }
        val currentOnSeek by rememberUpdatedState(onSeek)

        val onSliderValueChange: (Float) -> Unit = { new ->
            // A fresh manual drag always wins over any settle tween in flight.
            seekBarProgress.onDragging()
            dragValue = new
        }
        val sliderModifier = Modifier.onSeekBarReleased {
            val released = dragValue
            dragValue = null
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
        Spacer(modifier = Modifier.height(labelSpacing))
        val labelStyle = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ElapsedTimeLabel(
                durationMs = durationMs,
                dragValue = { dragValue },
                positionMs = { seekBarProgress.positionMs },
                style = labelStyle
            )
            BasicText(text = TimeUtils.formatDurationCompact(durationMs), style = labelStyle)
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
