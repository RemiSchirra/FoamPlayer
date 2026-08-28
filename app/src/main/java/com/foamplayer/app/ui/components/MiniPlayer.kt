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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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
import com.foamplayer.app.ui.utils.extractPaletteColor
import kotlinx.coroutines.launch

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
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack ?: return
    val queue = playbackState.queue
    val currentIndex = playbackState.queueIndex

    val context = LocalContext.current
    val colorCache = remember { mutableStateMapOf<String, Color>() }

    // Pager state to handle the progressive swipe animation
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceAtLeast(0),
        pageCount = { queue.size }
    )

    // Prefetch colors for nearby tracks to enable smooth interpolation
    LaunchedEffect(pagerState.currentPage, queue) {
        val indices = listOf(
            pagerState.currentPage - 1,
            pagerState.currentPage,
            pagerState.currentPage + 1
        )

        val pathsToKeep = indices.mapNotNull { i ->
            queue.getOrNull(i)?.albumArtPath
        }.toSet()

        colorCache.keys.retainAll(pathsToKeep)

        pathsToKeep.forEach { path ->
            if (!colorCache.containsKey(path)) {
                launch {
                    val color = extractPaletteColor(context, path)
                    if (color != null) {
                        colorCache[path] = color
                    }
                }
            }
        }
    }

    // Intercept pager progress to drive the dynamic accent color transition
    LaunchedEffect(pagerState, queue, colorCache) {
        snapshotFlow {
            val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val indexA = absoluteOffset.toInt().coerceIn(0, (queue.size - 1).coerceAtLeast(0))
            val indexB = (indexA + 1).coerceAtMost((queue.size - 1).coerceAtLeast(0))
            val fraction = (absoluteOffset - indexA).coerceIn(0f, 1f)

            val pathA = queue.getOrNull(indexA)?.albumArtPath
            val pathB = queue.getOrNull(indexB)?.albumArtPath

            val cA = colorCache[pathA]
            val cB = colorCache[pathB]

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

    // Sync pager when track changes elsewhere
    LaunchedEffect(currentIndex) {
        if (currentIndex in queue.indices && pagerState.currentPage != currentIndex) {
            pagerState.animateScrollToPage(currentIndex)
        }
    }

    // Trigger track change in engine when a swipe settles
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != currentIndex && pagerState.settledPage in queue.indices) {
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
                        val pageTrack = queue.getOrNull(page) ?: return@HorizontalPager
                        
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
                                        if (pageTrack.id == track.id) {
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
                                    modifier = if (pageTrack.id == track.id) {
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
                                    modifier = if (pageTrack.id == track.id) {
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

                val progress = if (playbackState.durationMs > 0) {
                    playbackState.currentPositionMs.toFloat() / playbackState.durationMs
                } else 0f

                FoamSlider(
                    value = progress,
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
