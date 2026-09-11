package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.PreviousAction
import com.foamplayer.app.ui.components.SeekBarProgressState
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.components.onSeekBarReleased
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.pingPongMarquee
import kotlin.math.abs

/** Layer 1 content: handle, hi-res badge, the track hero pager, playback controls, subview shortcuts. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.MainPlayerContent(
    track: TrackEntity,
    state: PlaybackState,
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
    isAnimateSeekBarEnabled: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = PlayerDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(PlayerDimens.screenPadding))

        TrackHeroPager(
            track = track,
            queue = state.queue,
            queueIndex = state.queueIndex,
            queueEntryIds = state.queueEntryIds,
            pagerState = pagerState,
            density = density,
            screenWidthDp = screenWidthDp,
            placeholderColor = placeholderColor,
            immersive = immersive,
            outerVisibilityScope = outerVisibilityScope,
            headerVisibilityScope = headerVisibilityScope,
            playerTransitionState = playerTransitionState,
            upTransitionState = upTransitionState
        )

        Spacer(modifier = Modifier.weight(1f))

        PlaybackControls(
            track = track,
            isPlaying = state.isPlaying,
            durationMs = state.durationMs,
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
            onPlayPause = { if (state.isPlaying) viewModel.pause() else viewModel.play() },
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
    pagerState: PagerState,
    density: Density,
    screenWidthDp: Dp,
    placeholderColor: Color,
    immersive: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope,
    playerTransitionState: SeekableTransitionState<Boolean>,
    upTransitionState: SeekableTransitionState<Boolean>
) {
    val anchorPage = queueIndex.coerceAtLeast(0)

    val showHero by remember(anchorPage, immersive) {
        derivedStateOf {
            if (immersive) {
                false
            } else {
                val offset = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                abs(offset) < 1f
            }
        }
    }

    val miniImageSize = 44.dp
    val fullImageSize = screenWidthDp - (PlayerDimens.horizontalPagePadding * 2)

    val miniRadius = (miniImageSize / 2) * FoamTheme.shapes.miniPlayerRounding
    val fullRadius = (fullImageSize / 2) * FoamTheme.shapes.fullPlayerRounding

    fun animatedCornerRadiusPx(): Float = with(density) {
        val outerRadius = lerp(start = miniRadius, stop = fullRadius, fraction = playerTransitionState.progress())
        val headerRadius = lerp(start = fullRadius, stop = miniRadius, fraction = upTransitionState.progress())
        min(outerRadius, headerRadius).toPx()
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
                            .clip(RoundedCornerShape(fullRadius))
                            .background(placeholderColor)
                            .graphicsLayer { alpha = anchorPageAlpha() },
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
                Spacer(modifier = Modifier.height(24.dp))
                Column(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = anchorPageAlpha() }) {
                    BasicText(
                        text = pageTrack.title,
                        style = FoamTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                                .pingPongMarquee()
                        } else {
                            Modifier
                        }
                    )
                    BasicText(
                        text = stringResource(R.string.artist_album_separator, pageTrack.artist, pageTrack.album),
                        style = FoamTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                                .pingPongMarquee()
                        } else {
                            Modifier
                        }
                    )
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
                        .sharedElement(
                            rememberSharedContentState(key = "album_art_${track.id}"),
                            animatedVisibilityScope = outerVisibilityScope
                        )
                        .sharedElement(
                            rememberSharedContentState(key = "album_art_header_${track.id}"),
                            animatedVisibilityScope = headerVisibilityScope
                        )
                        .graphicsLayer {
                            shape = RoundedCornerShape(animatedCornerRadiusPx())
                            clip = true
                            alpha = 1f
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
                Spacer(modifier = Modifier.height(24.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    BasicText(
                        text = track.title,
                        style = FoamTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .sharedBounds(
                                rememberSharedContentState(key = "track_title_${track.id}"),
                                animatedVisibilityScope = outerVisibilityScope
                            )
                            .sharedBounds(
                                rememberSharedContentState(key = "track_title_header_${track.id}"),
                                animatedVisibilityScope = headerVisibilityScope
                            )
                            .pingPongMarquee()
                    )
                    BasicText(
                        text = stringResource(R.string.artist_album_separator, track.artist, track.album),
                        style = FoamTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .sharedBounds(
                                rememberSharedContentState(key = "track_artist_${track.id}"),
                                animatedVisibilityScope = outerVisibilityScope
                            )
                            .sharedBounds(
                                rememberSharedContentState(key = "track_artist_header_${track.id}"),
                                animatedVisibilityScope = headerVisibilityScope
                            )
                            .pingPongMarquee()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.PlaybackControls(
    track: TrackEntity,
    isPlaying: Boolean,
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
            Icon(
                imageVector = FoamTheme.icons.current.previous,
                contentDescription = stringResource(R.string.previous),
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(PlayerDimens.transportIconSize).clickable(onClick = onPrevious)
            )
            PlayPauseButton(
                track = track,
                isPlaying = isPlaying,
                outerVisibilityScope = outerVisibilityScope,
                onClick = onPlayPause
            )
            Icon(
                imageVector = FoamTheme.icons.current.next,
                contentDescription = stringResource(R.string.next),
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(32.dp).clickable(onClick = onNext)
            )
        }
    }
}

/**
 * Isolated on purpose: FoamTheme.accentColor changes every frame of a horizontal
 * swipe (see viewModel.setDynamicAccentColor in the LaunchedEffect above). Reading
 * it directly inside this Box, rather than inline in PlaybackControls' body, keeps
 * that per-frame recomposition confined to just the play button instead of also
 * dragging the Prev/Next icons and shared-element bounds along with it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.PlayPauseButton(
    track: TrackEntity,
    isPlaying: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(PlayerDimens.playButtonSize)
            .sharedBounds(
                rememberSharedContentState(key = "play_button_${track.id}"),
                animatedVisibilityScope = outerVisibilityScope
            )
            .clip(CircleShape)
            .background(FoamTheme.accentColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) FoamTheme.icons.current.pause else FoamTheme.icons.current.play,
            contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
            tint = FoamTheme.colors.background,
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
    modifier: Modifier = Modifier
) {
    FoamSlider(
        value = seekBarProgress.progress,
        onValueChange = { new ->
            seekBarProgress.onDragging()
            onSeek((new * durationMs).toLong())
        },
        modifier = modifier.onSeekBarReleased {
            seekBarProgress.onSeekDragFinished(
                animated = isAnimateSeekBarEnabled,
                releasedAt = seekBarProgress.progress
            )
        }
    )
}
