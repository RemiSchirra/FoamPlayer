package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.ui.theme.FoamTheme

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    playbackState: PlaybackState,
    onPlayPauseClick: () -> Unit,
    onSeek: (Float) -> Unit,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack ?: return

    with(sharedTransitionScope) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FoamTheme.colors.surfaceVariant)
                .clickable { onClick() }
                .padding(8.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .sharedElement(
                                rememberSharedContentState(key = "album_art_${track.id}"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                            .clip(RoundedCornerShape(FoamTheme.shapes.miniPlayerRounding))
                            .background(FoamTheme.colors.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        if (track.albumArtPath != null) {
                            AsyncImage(
                                model = track.albumArtPath,
                                contentDescription = "Album Art",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            BasicText(
                                text = track.format.take(3),
                                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.primaryAccent)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = track.title,
                            style = FoamTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.sharedBounds(
                                rememberSharedContentState(key = "track_title_${track.id}"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        )
                        BasicText(
                            text = "${track.artist} • ${track.format}",
                            style = FoamTheme.typography.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.sharedBounds(
                                rememberSharedContentState(key = "track_artist_${track.id}"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
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
