package com.foamplayer.app.ui.screens.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.theme.FoamTheme

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TrackListItem(
    track: TrackEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    isSharedTransitionSource: Boolean = false
) {
    @Composable
    fun Modifier.sharedArtwork(): Modifier =
        if (!isSharedTransitionSource || sharedTransitionScope == null || animatedVisibilityScope == null) this
        else with(sharedTransitionScope) {
            this@sharedArtwork.sharedElement(
                rememberSharedContentState(key = "album_art_${track.id}"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }

    @Composable
    fun Modifier.sharedText(key: String): Modifier =
        if (!isSharedTransitionSource || sharedTransitionScope == null || animatedVisibilityScope == null) this
        else with(sharedTransitionScope) {
            this@sharedText.sharedBounds(
                rememberSharedContentState(key = key),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamTheme.shapes.miniPlayerRounding + 4.dp))
            .background(
                if (active) FoamTheme.colors.primaryAccent.copy(alpha = 0.4f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .sharedArtwork()
                .clip(RoundedCornerShape(FoamTheme.shapes.miniPlayerRounding))
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (track.albumArtPath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(track.albumArtPath)
                        .size(AlbumArtRequestSize)
                        .crossfade(false)
                        .build(),
                    contentDescription = "Album Art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = track.format,
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
                modifier = Modifier.sharedText("track_title_${track.id}")
            )
            BasicText(
                text = "${track.artist} • ${track.album}",
                style = FoamTheme.typography.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedText("track_artist_${track.id}")
            )
        }

        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                val thickness = 3.dp
                val length = 16.dp
                Box(
                    modifier = Modifier
                        .width(length)
                        .height(thickness)
                        .graphicsLayer(rotationZ = 45f)
                        .background(FoamTheme.colors.textSecondary, RoundedCornerShape(thickness / 2))
                )
                Box(
                    modifier = Modifier
                        .width(length)
                        .height(thickness)
                        .graphicsLayer(rotationZ = -45f)
                        .background(FoamTheme.colors.textSecondary, RoundedCornerShape(thickness / 2))
                )
            }
        }

        if (showDragHandle) {
            Spacer(modifier = Modifier.width(if (onRemove != null) 20.dp else 12.dp))

            Column(
                modifier = dragHandleModifier
                    .padding(8.dp), // enlarges the touch target beyond the two visible lines
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(FoamTheme.colors.textSecondary)
                    )
                }
            }
        }
    }
}
