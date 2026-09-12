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
    onListBackdropArt: (String?, Boolean) -> Unit = { _, _ -> },
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsState()
    val playlist by viewModel.playlist.collectAsState()

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylist(playlistId)
    }

    // The playlist's own cover drives the blurred list backdrop (loaded as a
    // direct image). Without one there is NO backdrop: the app-wide background
    // of the currently playing track shows through instead.
    LaunchedEffect(playlist?.imageUrl) {
        val cover = playlist?.imageUrl
        if (cover != null) {
            onListBackdropArt(cover, false)
        } else {
            onListBackdropArt(null, true)
        }
    }

    // The user-chosen cover wins; without one, fall back to the first track's art.
    val coverImage = playlist?.imageUrl

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
        imageUrl = coverImage ?: tracks.firstOrNull { it.albumArtPath != null }?.albumArtPath,
        imageIsEmbeddedArt = coverImage == null,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedTrackId = sharedTrackId,
        activeTrackId = activeTrackId,
        playerTransitionVisibilityScope = playerTransitionVisibilityScope
    )
}
