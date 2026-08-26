package com.foamplayer.app.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.TrackListScreen

@Composable
fun AlbumDetailScreen(
    albumName: String,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsState()

    LaunchedEffect(albumName) {
        viewModel.loadAlbumTracks(albumName)
    }

    val firstTrack = tracks.firstOrNull()
    val artistName = firstTrack?.albumArtist ?: firstTrack?.artist

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
        imageUrl = firstTrack?.albumArtPath,
        artistName = artistName
    )
}
