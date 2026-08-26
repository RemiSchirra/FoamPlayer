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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.screens.library.TrackListItem
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.TimeUtils

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TrackListScreen(
    title: String,
    tracks: List<TrackEntity>,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onPlayAllClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    artistName: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementKey: String? = null
) {
    val totalDurationMs = remember(tracks) { tracks.sumOf { it.duration } }
    val durationText = remember(totalDurationMs) { TimeUtils.formatDurationLong(totalDurationMs) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onBack() }
                    .padding(4.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                HeaderSection(
                    title = title,
                    subtitle = artistName,
                    trackCount = tracks.size,
                    durationText = durationText,
                    imageUrl = imageUrl,
                    onPlayAllClick = onPlayAllClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedElementKey = sharedElementKey
                )
            }

            itemsIndexed(tracks) { index, track ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TrackListItem(
                        track = track,
                        onClick = { onTrackClick(tracks, index) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HeaderSection(
    title: String,
    subtitle: String?,
    trackCount: Int,
    durationText: String,
    imageUrl: String?,
    onPlayAllClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?
) {
    // Helper: only wraps with a shared bounds modifier when a shared-element
    // context was actually provided (e.g. when navigating in from Search/Artist).
    @Composable
    fun Modifier.sharedIfAvailable(key: String): Modifier {
        if (sharedTransitionScope == null || animatedVisibilityScope == null || sharedElementKey == null) {
            return this
        }
        return with(sharedTransitionScope) {
            this@sharedIfAvailable.sharedBounds(
                rememberSharedContentState(key = key),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover Art
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .sharedIfAvailable("album-art-$sharedElementKey")
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = title.take(1).uppercase(),
                    style = FoamTheme.typography.titleLarge.copy(fontSize = 48.sp)
                )
            }
        }

        Spacer(modifier = Modifier.width(20.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedIfAvailable("album-title-$sharedElementKey")
            )

            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.colors.primaryAccent),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedIfAvailable("album-artist-$sharedElementKey")
                )
            }
            BasicText(
                text = "$trackCount Tracks • $durationText",
                style = FoamTheme.typography.body,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Play Button
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(FoamTheme.colors.primaryAccent)
                    .clickable { onPlayAllClick() }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = FoamTheme.icons.current.play,
                        contentDescription = null,
                        tint = FoamTheme.colors.background,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicText(
                        text = "Play All",
                        style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.colors.background)
                    )
                }
            }
        }
    }
}