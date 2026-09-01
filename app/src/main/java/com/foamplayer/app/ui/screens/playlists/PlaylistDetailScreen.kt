package com.foamplayer.app.ui.screens.playlists

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.AnimatedVisibilityScope
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
fun PlaylistDetailScreen(
    playlistId: Long,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionVisibilityScope: AnimatedVisibilityScope,
    sharedTrackId: Long?,
    activeTrackId: Long?,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsState()
    val playlist by viewModel.playlist.collectAsState()

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylist(playlistId)
    }

    TrackListScreen(
        title = playlist?.name ?: "Playlist",
        tracks = tracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = {
            if (tracks.isNotEmpty()) {
                onTrackClick(tracks, 0)
            }
        },
        onBack = onBack,
        imageUrl = tracks.firstOrNull { it.albumArtPath != null }?.albumArtPath,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedTrackId = sharedTrackId,
        activeTrackId = activeTrackId,
        playerTransitionVisibilityScope = playerTransitionVisibilityScope
    )
}
