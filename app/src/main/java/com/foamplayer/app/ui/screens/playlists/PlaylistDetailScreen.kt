package com.foamplayer.app.ui.screens.playlists

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.TrackListScreen

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    initialName: String,
    initialImageUrl: String?,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionVisibilityScope: AnimatedVisibilityScope,
    sharedTrackId: Long?,
    activeTrackId: Long?,
    onListBackdropArt: (String?, Boolean) -> Unit = { _, _ -> },
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
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
    LaunchedEffect(playlist?.imageUrl, initialImageUrl) {
        val cover = playlist?.imageUrl ?: initialImageUrl
        if (cover != null) {
            onListBackdropArt(cover, false)
        } else {
            onListBackdropArt(null, true)
        }
    }

    // The nav-route seeds keep the header correct on its very first frame (the
    // DB-loaded playlist takes a moment to arrive) so the shared-element
    // transition from the playlists list never flashes the letter fallback.
    val coverImage = playlist?.imageUrl ?: initialImageUrl

    TrackListScreen(
        title = playlist?.name ?: initialName.ifEmpty { "Playlist" },
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
        sharedElementKey = playlistId.toString(),
        sharedElementPrefix = "playlist",
        sharedTrackId = sharedTrackId,
        activeTrackId = activeTrackId,
        playerTransitionVisibilityScope = playerTransitionVisibilityScope,
        density = density,
        onDensityChange = onDensityChange
    )
}
