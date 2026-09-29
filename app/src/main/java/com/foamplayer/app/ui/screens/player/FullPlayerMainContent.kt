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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.PreviousAction
import com.foamplayer.app.ui.modals.LocalTrackFoamModalState
import com.foamplayer.app.ui.components.MarqueeText
import com.foamplayer.app.ui.components.SeekBarProgressState
import com.foamplayer.app.ui.components.TrackScoreHeart
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.components.onSeekBarReleased
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.TimeUtils
import com.foamplayer.app.ui.utils.pressShrink
import com.foamplayer.app.ui.utils.rememberPausedArtScale
import kotlin.math.abs

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
    // Deferred isPlaying: read only by the play/pause button leaf and the paused-art
    // scale, so a playback flip never re-runs this subtree.
    isPlaying: () -> Boolean,
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
            isPlaying = isPlaying,
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
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
            marqueeMode = marqueeMode,
            flightGeometry = flightGeometry
        )

        Spacer(modifier = Modifier.weight(1f))

        PlaybackControls(
            track = track,
            isPlaying = isPlaying,
            durationMs = durationMs,
            outerVisibilityScope = outerVisibilityScope,
            seekBarProgress = seekBarProgress,
            isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
            onSeek = { positionMs -> viewModel.seekTo(positionMs) },
            onPrevious = {
                when (
                    seekBarProgress.onPreviousPressed(
                        animated = isAnimateSeekBarEnabled,
                        positionMs = viewModel.positionMs.value
                    )
                ) {
                    PreviousAction.RESTART_CURRENT -> viewModel.seekTo(0L)
                    PreviousAction.SKIP_TO_PREVIOUS -> viewModel.previous()
                }
            },
            onPlayPause = { if (isPlaying()) viewModel.pause() else viewModel.play() },
            onNext = {
                seekBarProgress.onNextPressed(animated = isAnimateSeekBarEnabled)
                viewModel.next()
            }
        )

        Spacer(modifier = Modifier.height(PlayerDimens.controlsBottomSpacer))
    }
}

/**
 * Renders the album art + title/artist for the current queue, and drives the transition
 * between them and the mini header when swiping up.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.TrackHeroPager(
    track: TrackEntity,
    queue: List<TrackEntity>,
    queueIndex: Int,
    queueEntryIds: List<Long>,
    // Deferred: consumed only via rememberPausedArtScale, whose animation reads it
    // inside its own scope, so play/pause flips don't recompose the pager pages.
    isPlaying: () -> Boolean,
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

    val pausedArtScale by rememberPausedArtScale(isPlaying)

    val miniRadius = (miniImageSize / 2) * FoamTheme.shapes.miniPlayerRounding
    val fullRadius = (fullImageSize / 2) * FoamTheme.shapes.fullPlayerRounding

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
                if (immersive || !isAnchorPage) {
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
                                // The pager page art casts the full player's drop shadow
                                // while it is the visually-responsible image -- i.e. whenever
                                // it is NOT the anchor page. The anchor's art is always
                                // either exactly covered by the hero overlay (which casts
                                // the shadow in its place, from identical bounds) or fully
                                // off-viewport, so it never needs one of its own. Gating on
                                // !isAnchorPage -- rather than tying the elevation to the
                                // page's content alpha -- is what keeps the shadow
                                // continuous across a swipe: the incoming image slides in
                                // with its shadow already behind it (its alpha is 1 there),
                                // and at the settle commit the hero reappears over it
                                // casting the identical shadow. Every layer that casts here
                                // runs at alpha 1, so nothing depends on how a layer's own
                                // alpha composites with its RenderNode shadow.
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
                            marqueeEnabled = immersive && isAnchorPage && pageTrack.id == track.id,
                            modifier = if (immersive && isAnchorPage && pageTrack.id == track.id) {
                                Modifier
                                    .sharedBounds(
                                        rememberSharedContentState(key = "track_title_${track.id}"),
                                        animatedVisibilityScope = outerVisibilityScope
                                    )
                                    .sharedBounds(
                                        rememberSharedContentState(key = "track_title_header_${track.id}"),
                                        animatedVisibilityScope = headerVisibilityScope
                                    )
                            } else {
                                Modifier
                            }
                        )
                        MarqueeText(
                            text = stringResource(R.string.artist_album_separator, pageTrack.artist, pageTrack.album),
                            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                            mode = marqueeMode,
                            marqueeEnabled = immersive && isAnchorPage && pageTrack.id == track.id,
                            modifier = if (immersive && isAnchorPage && pageTrack.id == track.id) {
                                Modifier
                                    .sharedBounds(
                                        rememberSharedContentState(key = "track_artist_${track.id}"),
                                        animatedVisibilityScope = outerVisibilityScope
                                    )
                                    .sharedBounds(
                                        rememberSharedContentState(key = "track_artist_header_${track.id}"),
                                        animatedVisibilityScope = headerVisibilityScope
                                    )
                            } else {
                                Modifier
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    TrackScoreHeart(
                        score = scoreOverrides[pageTrack.id] ?: pageTrack.score,
                        onSetScore = { value -> onSetScore(pageTrack.id, value) }
                    )
                    TrackMoreButton(track = pageTrack)
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
                            // Eased fractions of the two flights the hero takes: outer --
                            // the player open/close flight (1 = fully open); header -- the
                            // up-transition to the subview header (0 = main player). Both
                            // are read per-frame inside this draw scope, so transitions
                            // only redraw this layer instead of recomposing anything. The
                            // radius must chase the flight (it lives in the shared-element
                            // overlay's interpolated bounds each frame) -- the same reason
                            // the subview header's art radius is computed per frame.
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

                            shape = RoundedCornerShape(
                                with(density) {
                                    min(
                                        lerp(start = miniRadius, stop = fullRadius, fraction = outerFraction),
                                        lerp(start = fullRadius, stop = miniRadius, fraction = headerFraction)
                                    ).toPx()
                                }
                            )
                            clip = true
                            scaleX = pausedArtScale
                            scaleY = pausedArtScale
                            // The hero casts the full player's drop shadow while it rests
                            // over the anchor page -- the anchor page itself casts none
                            // (see the pager page art), so this is the shadow visible
                            // behind the resting image. It fades out over both flights
                            // because neither destination -- the mini player's art nor the
                            // subview header's art -- carries its own shadow; a constant
                            // one would pop off the frame the hero leaves composition.
                            // During pager swipes both fractions rest at (1, 0), so the
                            // shadow rides the hero at full strength and hands off to the
                            // incoming page's own shadow without an intensity jump. The
                            // hero's alpha is 1 in the classic player, so the shadow never
                            // depends on layer-alpha/shadow compositing semantics.
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
                    }
                }
                if (!immersive) {
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
                        TrackScoreHeart(
                            score = scoreOverrides[track.id] ?: track.score,
                            onSetScore = { value -> onSetScore(track.id, value) }
                        )
                        TrackMoreButton(track = track)
                    }
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
internal fun TrackMoreButton(track: TrackEntity) {
    val trackModalState = LocalTrackFoamModalState.current
    val moreInteractions = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(40.dp)
            .pressShrink(moreInteractions)
            .clickable(
                interactionSource = moreInteractions,
                indication = null
            ) { trackModalState?.show(track) },
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

@Composable
internal fun PlaybackProgressSlider(
    durationMs: Long,
    seekBarProgress: SeekBarProgressState,
    isAnimateSeekBarEnabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    labelSpacing: Dp = PlayerDimens.seekBarLabelSpacing
) {
    Column(modifier = modifier) {
        // Hold the dragged position locally and commit the seek only on release.
        // Forwarding onSeek on every change used to issue a real media seek and a
        // PlaybackState emission per drag pixel, recomposing every collector of
        // playbackState while the user was still holding the bar.
        var dragValue by remember { mutableStateOf<Float?>(null) }
        val currentOnSeek by rememberUpdatedState(onSeek)

        FoamSlider(
            value = { dragValue ?: seekBarProgress.progress },
            onValueChange = { new ->
                // A fresh manual drag always wins over any settle tween in flight.
                seekBarProgress.onDragging()
                dragValue = new
            },
            modifier = Modifier.onSeekBarReleased {
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
        )
        Spacer(modifier = Modifier.height(labelSpacing))
        val labelStyle = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ElapsedTimeLabel(
                durationMs = durationMs,
                progress = { dragValue ?: seekBarProgress.progress },
                style = labelStyle
            )
            BasicText(text = TimeUtils.formatDurationCompact(durationMs), style = labelStyle)
        }
    }
}

/**
 * Isolated so the per-frame position state is only observed here: [derivedStateOf]
 * re-formats the label only when the displayed second actually changes, so position
 * ticks neither recompose this label nor its siblings. [progress] is the same value
 * the bar renders (drag position while dragging, engine position otherwise), so the
 * label tracks a drag in realtime and only touches the audio engine's position again
 * once the seek commits.
 */
@Composable
private fun ElapsedTimeLabel(
    durationMs: Long,
    progress: () -> Float,
    style: androidx.compose.ui.text.TextStyle
) {
    val elapsedText by remember(durationMs) {
        derivedStateOf {
            TimeUtils.formatDurationCompact((progress() * durationMs).toLong())
        }
    }
    BasicText(text = elapsedText, style = style)
}
