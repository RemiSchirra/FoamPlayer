package com.foamplayer.app.ui.screens.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.TrackSortOptions
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.TrackCenterRequest
import com.foamplayer.app.ui.components.TrackListScreen
import com.foamplayer.app.ui.components.rememberSortedTracks

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    initialName: String,
    initialImageUrl: String?,
    initialImageIsEmbeddedArt: Boolean = false,
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
    sortMode: CollectionSortMode = CollectionSortMode.CUSTOM,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    centerTrackRequest: TrackCenterRequest? = null,
    isPlaying: Boolean = true,
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    immersiveMode: Boolean = false,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsState()
    val playlist by viewModel.playlist.collectAsState()
    val editMode by viewModel.editMode.collectAsState()
    val firstTrackArt by viewModel.firstTrackArt.collectAsState()

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylist(playlistId)
    }

    // System back mirrors the top-bar arrow: leave edit mode first.
    BackHandler(enabled = editMode) {
        viewModel.setEditMode(false)
    }

    // Reordering is only meaningful in the playlist's own (custom) order: any
    // other sort immediately leaves edit mode, and the edit pencil is hidden
    // below so it cannot be re-entered while a sort is applied.
    LaunchedEffect(sortMode) {
        if (sortMode != CollectionSortMode.CUSTOM) {
            viewModel.setEditMode(false)
        }
    }

    // CUSTOM keeps the user-defined list order; any other mode re-sorts the
    // display (edit mode is custom-only, so the drag list never diverges).
    val sortedTracks = rememberSortedTracks(tracks, sortMode, sortInverted)

    // The nav-route seed keeps the header correct on its very first frame (the
    // DB-loaded playlist takes a moment to arrive) so the shared-element
    // transition from the playlists list never flashes the letter fallback.
    // A seed carries its own load mode (custom covers are plain image URIs,
    // the automatic first-track fallback is embedded art); the DB-loaded
    // custom cover, once it arrives, always wins and is a plain image URI.
    val seedImageUrl = initialImageUrl?.takeIf { it.isNotBlank() }
    val customCover = playlist?.imageUrl ?: seedImageUrl

    // Cover resolution, artist-style: custom image first, else the first
    // track in the playlist's custom order that carries embedded art (live
    // fallback query), else the displayed tracks' first art — no image at all
    // lets the hero render its letter monogram.
    val heroImageUrl = customCover
        ?: firstTrackArt
        ?: sortedTracks.firstOrNull { it.albumArtPath != null }?.albumArtPath
    val heroImageIsEmbeddedArt = when {
        heroImageUrl == null -> false
        customCover == null -> true
        playlist?.imageUrl == null -> initialImageIsEmbeddedArt
        else -> false
    }

    // The hero art drives the blurred list backdrop, like every other detail
    // screen; without any art there is NO backdrop: the app-wide background
    // of the currently playing track shows through instead.
    LaunchedEffect(heroImageUrl, heroImageIsEmbeddedArt) {
        onListBackdropArt(heroImageUrl, heroImageIsEmbeddedArt)
    }

    // Edit mode keeps the view's own organisation: row densities reorder through
    // the row drag handle, grid densities through the playlists-style cell drag.
    TrackListScreen(
        title = playlist?.name ?: initialName.ifEmpty { "Playlist" },
        tracks = sortedTracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = {
            if (sortedTracks.isNotEmpty()) {
                onTrackClick(sortedTracks, 0)
            }
        },
        onShuffleAllClick = { shuffled ->
            if (shuffled.isNotEmpty()) {
                onTrackClick(shuffled, 0)
            }
        },
        onBack = onBack,
        imageUrl = heroImageUrl,
        imageIsEmbeddedArt = heroImageIsEmbeddedArt,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedElementKey = playlistId.toString(),
        sharedElementPrefix = "playlist",
        sharedTrackId = sharedTrackId,
        activeTrackId = activeTrackId,
        playerTransitionVisibilityScope = playerTransitionVisibilityScope,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = listOf(CollectionSortMode.CUSTOM) + TrackSortOptions,
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange,
        centerTrackRequest = centerTrackRequest,
        isPlaying = isPlaying,
        heroLayout = heroLayout,
        immersiveMode = immersiveMode,
        editMode = editMode,
        onEditModeChange = if (sortMode == CollectionSortMode.CUSTOM) viewModel::setEditMode else null,
        onRemoveTrack = { track -> viewModel.removeTrack(playlistId, track.id) },
        onPersistTrackOrder = { orderedIds ->
            // The rows hand over the displayed order; when the custom order is
            // displayed inverted, the canonical (stored) order is its reverse.
            val storedOrder =
                if (sortInverted) orderedIds.asReversed() else orderedIds
            viewModel.persistTrackOrder(playlistId, storedOrder)
        }
    )
}
