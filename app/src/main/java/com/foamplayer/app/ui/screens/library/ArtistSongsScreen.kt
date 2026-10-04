package com.foamplayer.app.ui.screens.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.TrackCenterRequest
import com.foamplayer.app.ui.components.TrackListScreen

/**
 * Every track naming the artist as artist or album artist, alphabetical by
 * title, on the shared track-list scaffold. The header artwork (first track's
 * embedded art) doubles as the backdrop and accent color source.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ArtistSongsScreen(
    artistName: String,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionVisibilityScope: AnimatedVisibilityScope,
    sharedTrackId: Long?,
    activeTrackId: Long?,
    onListBackdropArt: (String?, Boolean) -> Unit = { _, _ -> },
    centerTrackRequest: TrackCenterRequest? = null,
    isPlaybackActive: Boolean = true,
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    immersiveMode: Boolean = false,
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsState()

    LaunchedEffect(artistName) {
        viewModel.loadArtistTracks(artistName)
    }

    val imageUrl = tracks.firstOrNull()?.albumArtPath

    LaunchedEffect(imageUrl) {
        onListBackdropArt(imageUrl, true)
    }

    TrackListScreen(
        title = artistName,
        tracks = tracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = {
            if (tracks.isNotEmpty()) {
                onTrackClick(tracks, 0)
            }
        },
        onShuffleAllClick = { shuffled ->
            if (shuffled.isNotEmpty()) {
                onTrackClick(shuffled, 0)
            }
        },
        onBack = onBack,
        imageUrl = imageUrl,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedTrackId = sharedTrackId,
        activeTrackId = activeTrackId,
        playerTransitionVisibilityScope = playerTransitionVisibilityScope,
        centerTrackRequest = centerTrackRequest,
        isPlaybackActive = isPlaybackActive,
        heroLayout = heroLayout,
        immersiveMode = immersiveMode,
        // The single artist's track list is short enough to page through; no
        // fast scrollbar there (the browse list of artists keeps its own).
        showScrollbar = false
    )
}
