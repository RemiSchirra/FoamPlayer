package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.times
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.screens.lyrics.LyricsScreen
import com.foamplayer.app.ui.screens.queue.QueueScreen
import com.foamplayer.app.ui.theme.FoamTheme

internal enum class SubViewMode(val labelRes: Int) {
    LYRICS(R.string.lyrics),
    QUEUE(R.string.queue)
}

@Composable
internal fun SubViewSelectorRow(
    subViewMode: SubViewMode,
    progress: () -> Float,
    onSelect: (SubViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        SubViewMode.entries.forEach { mode ->
            val targetColor = if (mode == subViewMode) FoamTheme.accentColor else FoamTheme.colors.textSecondary
            val color = lerp(FoamTheme.colors.textPrimary, targetColor, progress().coerceIn(0f, 1f))
            val icon = when (mode) {
                SubViewMode.LYRICS -> FoamTheme.icons.current.lyrics
                SubViewMode.QUEUE -> FoamTheme.icons.current.queue
            }

            val label = stringResource(mode.labelRes)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelect(mode) }.padding(8.dp)
            ) {
                Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                BasicText(text = label, style = FoamTheme.typography.body.copy(color = color))
            }
        }
    }
}

/** Layer 2 content: mini header (draggable handle) plus the lyrics or queue subview. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.SubViewContent(
    track: TrackEntity,
    subViewMode: SubViewMode,
    viewModel: PlayerViewModel,
    headerVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier,
    onCollapse: () -> Unit,
    upTransitionState: SeekableTransitionState<Boolean>
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
        Column(modifier = Modifier.fillMaxWidth().then(modifier)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(FoamTheme.colors.borderOverlay)
                    .clickable(onClick = onCollapse)
            )
            MiniPlayerHeader(
                track = track,
                headerVisibilityScope = headerVisibilityScope,
                onBack = onCollapse,
                upTransitionState = upTransitionState
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = subViewMode,
                transitionSpec = {
                    fadeIn(androidx.compose.animation.core.tween(TRANSITION_DURATION_MS)) togetherWith
                            fadeOut(androidx.compose.animation.core.tween(TRANSITION_DURATION_MS))
                },
                label = "SubViewContent"
            ) { mode ->
                when (mode) {
                    SubViewMode.LYRICS -> LyricsScreen(backgroundColor = Color.Transparent)
                    SubViewMode.QUEUE -> QueueScreen(viewModel = viewModel, showTitle = false, backgroundColor = Color.Transparent)
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.MiniPlayerHeader(
    track: TrackEntity,
    headerVisibilityScope: AnimatedVisibilityScope,
    onBack: () -> Unit,
    upTransitionState: SeekableTransitionState<Boolean>
) {
    val configuration = LocalConfiguration.current
    val miniImageSize = 48.dp
    val fullImageSize = configuration.screenWidthDp.dp - 48.dp
    
    val miniRadius = (miniImageSize / 2) * FoamTheme.shapes.miniPlayerRounding
    val fullRadius = (fullImageSize / 2) * FoamTheme.shapes.fullPlayerRounding

    val cornerRadius = lerp(
        start = fullRadius,
        stop = miniRadius,
        fraction = EaseOutCubic.transform(upTransitionState.progress())
    )
    
    val containerPadding = 8.dp
    val containerRadius = cornerRadius + containerPadding

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(containerRadius))
            .background(Color.Black.copy(alpha = 0.2f))
            .clickable(onClick = onBack)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Animate the song coming in
        AnimatedContent(
            targetState = track,
            transitionSpec = {
                (fadeIn(androidx.compose.animation.core.tween(TRANSITION_DURATION_MS)) +
                        slideInVertically(androidx.compose.animation.core.tween(TRANSITION_DURATION_MS)) { height -> height / 3 }) togetherWith
                        fadeOut(androidx.compose.animation.core.tween(TRANSITION_DURATION_MS))
            },
            contentKey = { it.id },
            modifier = Modifier.weight(1f),
            label = "MiniPlayerHeaderTrackInfo"
        ) { headerTrack ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .sharedElement(rememberSharedContentState(key = "album_art_header_${headerTrack.id}"), animatedVisibilityScope = headerVisibilityScope)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(FoamTheme.colors.surfaceVariant)
                ) {
                    headerTrack.albumArtPath?.let { path ->
                        AsyncImage(model = EmbeddedAlbumArt(path.toUri()),
                            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = headerTrack.title,
                        style = FoamTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedBounds(rememberSharedContentState(key = "track_title_header_${headerTrack.id}"), animatedVisibilityScope = headerVisibilityScope)
                    )
                    BasicText(
                        text = headerTrack.artist,
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedBounds(rememberSharedContentState(key = "track_artist_header_${headerTrack.id}"), animatedVisibilityScope = headerVisibilityScope)
                    )
                }
            }
        }
        Icon(imageVector = Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.collapse), tint = FoamTheme.accentColor, modifier = Modifier.size(28.dp))
    }
}
