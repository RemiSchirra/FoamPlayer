package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    playbackState: PlaybackState,
    onPlayPauseClick: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipToTrack: (Int) -> Unit,
    onColorChange: (Color?) -> Unit,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    isAnimateSeekBarEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack ?: return
    val queue = playbackState.queue
    val currentIndex = playbackState.queueIndex

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Pager state to handle the progressive swipe animation
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceAtLeast(0),
        pageCount = { queue.size }
    )

    // Represents a distant jump in progress. While the pager slides one page,
    // from its current position to `viaPage`, we render the *target* track's
    // content on `viaPage` instead of whatever track actually lives there.
    // That turns any distant jump into a single, real one-page pager slide
    // that lands exactly on the right track -- after which we silently remap
    // the pager onto the real index (content is identical, so nothing pops).
    data class PendingJump(val viaPage: Int, val targetIndex: Int)
    var pendingJump by remember { mutableStateOf<PendingJump?>(null) }

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

    // Intercept pager progress to drive the dynamic accent color transition
    LaunchedEffect(pagerState, queue, pendingJump) {
        snapshotFlow {
            val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val indexA = absoluteOffset.toInt().coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            val indexB = (indexA + 1).coerceAtMost((queue.size - 1).coerceAtLeast(0))
            val fraction = (absoluteOffset - indexA).coerceIn(0f, 1f)

            val jump = pendingJump
            fun resolvedPath(page: Int) = if (jump != null && page == jump.viaPage) {
                queue.getOrNull(jump.targetIndex)?.albumArtPath
            } else {
                queue.getOrNull(page)?.albumArtPath
            }

            val pathA = resolvedPath(indexA)
            val pathB = resolvedPath(indexB)

            val cA = pathA?.let { AlbumArtPaletteCache.colors[it] }
            val cB = pathB?.let { AlbumArtPaletteCache.colors[it] }

            if (cA != null) {
                lerp(cA, cB ?: cA, fraction)
            } else null
        }.collect { interpolatedColor ->
            onColorChange(interpolatedColor)
        }
    }

    // Reset accent color when the player is dismissed
    DisposableEffect(Unit) {
        onDispose {
            onColorChange(null)
        }
    }

    // Sync pager when track changes elsewhere (e.g. tapped from a full queue list).
    LaunchedEffect(currentIndex) {
        // Clear any override left behind by a jump that got interrupted by
        // this new one -- otherwise a stale viaPage/targetIndex pair can
        // linger and desync the text/art from the real current track.
        pendingJump = null

        if (currentIndex in queue.indices && pagerState.currentPage != currentIndex) {
            val distance = currentIndex - pagerState.currentPage
            if (abs(distance) <= 1) {
                // Already adjacent: the pager's normal slide is exactly right.
                pagerState.animateScrollToPage(currentIndex)
            } else {
                // Distant jump: slide exactly one page -- rendering the real
                // target track on that page the whole way -- so it plays as
                // a single continuous slide straight to the new song. Once
                // landed, silently remap the pager onto the real index; the
                // content shown doesn't change, so nothing visibly pops.
                val direction = if (distance > 0) 1 else -1
                val viaPage = (pagerState.currentPage + direction).coerceIn(0, queue.size - 1)
                try {
                    pendingJump = PendingJump(viaPage, currentIndex)
                    pagerState.animateScrollToPage(viaPage)
                    pagerState.scrollToPage(currentIndex)
                } finally {
                    // Runs even if a newer tap cancels us mid-flight, so we
                    // never hand off a stale override to the next jump.
                    pendingJump = null
                }
            }
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        if (pendingJump == null &&
            pagerState.settledPage != currentIndex &&
            pagerState.settledPage in queue.indices
        ) {
            onSkipToTrack(pagerState.settledPage)
        }
    }

    val animatedCornerRadius by animatedVisibilityScope.transition.animateDp(label = "miniCornerRadius") { enterExitState ->
        if (enterExitState == EnterExitState.Visible) {
            FoamTheme.shapes.miniPlayerRounding
        } else {
            FoamTheme.shapes.fullPlayerRounding
        }
    }

    with(sharedTransitionScope) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FoamTheme.colors.surfaceVariant)
                .padding(8.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                        beyondViewportPageCount = 1,
                        key = { index -> queue.getOrNull(index)?.id ?: index }
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
                        val isSettledCurrentTrackPage = jump == null && pageTrack.id == track.id

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onClick() }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .then(
                                        if (isSettledCurrentTrackPage) {
                                            Modifier.sharedElement(
                                                rememberSharedContentState(key = "album_art_${track.id}"),
                                                animatedVisibilityScope = animatedVisibilityScope
                                            )
                                        } else Modifier
                                    )
                                    .clip(RoundedCornerShape(animatedCornerRadius))
                                    .background(FoamTheme.colors.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                if (pageTrack.albumArtPath != null) {
                                    AsyncImage(
                                        model = pageTrack.albumArtPath,
                                        contentDescription = "Album Art",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    BasicText(
                                        text = pageTrack.format.take(3),
                                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.primaryAccent)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                BasicText(
                                    text = pageTrack.title,
                                    style = FoamTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (isSettledCurrentTrackPage) {
                                        Modifier.sharedBounds(
                                            rememberSharedContentState(key = "track_title_${track.id}"),
                                            animatedVisibilityScope = animatedVisibilityScope
                                        )
                                    } else Modifier
                                )
                                BasicText(
                                    text = "${pageTrack.artist} • ${pageTrack.format}",
                                    style = FoamTheme.typography.caption,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (isSettledCurrentTrackPage) {
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

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .sharedBounds(
                                rememberSharedContentState(key = "play_button_${track.id}"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                            .clip(RoundedCornerShape(18.dp))
                            .background(FoamTheme.colors.primaryAccent)
                            .clickable { onPlayPauseClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) FoamTheme.icons.current.pause else FoamTheme.icons.current.play,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            tint = FoamTheme.colors.background,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                val visualProgress by remember(
                    playbackState.currentPositionMs,
                    playbackState.durationMs,
                    currentIndex
                ) {
                    derivedStateOf {
                        val progress = if (playbackState.durationMs > 0) {
                            playbackState.currentPositionMs.toFloat() / playbackState.durationMs
                        } else 0f
                        val scrollPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        val distanceFromCurrent = abs(scrollPosition - currentIndex.coerceAtLeast(0))
                        val activeFraction = (1f - distanceFromCurrent).coerceIn(0f, 1f)
                        progress * activeFraction
                    }
                }

                FoamSlider(
                    value = visualProgress,
                    onValueChange = onSeek,
                    trackHeight = 2.dp,
                    activeColor = if (playbackState.audioOutputInfo?.isBitPerfectMode == true)
                        FoamTheme.colors.bitPerfectActive
                    else FoamTheme.colors.primaryAccent,
                    modifier = Modifier.height(12.dp)
                )
            }
        }
    }
}