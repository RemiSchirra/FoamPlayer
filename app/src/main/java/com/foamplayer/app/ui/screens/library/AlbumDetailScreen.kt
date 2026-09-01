package com.foamplayer.app.ui.screens.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.TrackListScreen

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumDetailScreen(
    albumName: String,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionVisibilityScope: AnimatedVisibilityScope,
    sharedTrackId: Long?,
    activeTrackId: Long?,
    initialImageUrl: String? = null,
    initialArtistName: String? = null,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsState()

    LaunchedEffect(albumName) {
        viewModel.loadAlbumTracks(albumName)
    }

    val firstTrack = tracks.firstOrNull()
    // Fall back to the values passed in via navigation (known at click-time) until the
    // DB query resolves, so the shared-element target never renders without art on
    // its first frame. Once tracks load these agree anyway, so there's no visible swap.
    val imageUrl = firstTrack?.albumArtPath ?: initialImageUrl
    val artistName = firstTrack?.albumArtist ?: firstTrack?.artist ?: initialArtistName

    TrackListScreen(
        title = albumName,
        tracks = tracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = {
            if (tracks.isNotEmpty()) {
                onTrackClick(tracks, 0)
            }
        },
        onBack = onBack,
        imageUrl = imageUrl,
        artistName = artistName,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedElementKey = albumName,
        sharedTrackId = sharedTrackId,
        activeTrackId = activeTrackId,
        playerTransitionVisibilityScope = playerTransitionVisibilityScope
    )
}
