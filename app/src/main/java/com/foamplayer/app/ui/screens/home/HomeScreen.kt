package com.foamplayer.app.ui.screens.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.data.db.dao.AlbumRecommendation
import com.foamplayer.app.data.db.dao.PlaylistRecommendation
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.CollectionInfo
import com.foamplayer.app.ui.components.CollectionKind
import com.foamplayer.app.ui.components.HomeRow
import com.foamplayer.app.ui.components.HomeRowCard
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.navigation.isActiveSharedCollection
import com.foamplayer.app.ui.components.HomeRowCardSmallWidth
import com.foamplayer.app.ui.components.HomeRowSpacing
import com.foamplayer.app.ui.components.LocalCollectionFoamModalState
import com.foamplayer.app.ui.components.LocalTrackFoamModalState
import com.foamplayer.app.ui.components.ScreenTitle
import com.foamplayer.app.ui.screens.playlists.DeletePlaylistConfirmModal
import com.foamplayer.app.ui.screens.playlists.PlaylistFoamModal
import com.foamplayer.app.ui.screens.playlists.PlaylistModalStats
import com.foamplayer.app.ui.screens.playlists.PlaylistsViewModel
import com.foamplayer.app.ui.screens.playlists.RenamePlaylistModal
import com.foamplayer.app.ui.screens.playlists.rememberPlaylistFoamModalState
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.launch

/** How many items a Home row's pager shows (its "See all" page holds up to 50). */
private const val HomeRowItemCount = 5

private val ImageMimeTypes = arrayOf("image/*")

/**
 * The app's landing tab: five recommendation rows (recommended playlists,
 * recommended albums, recent listens, old favorites, favorite artists), each
 * a horizontal card pager with an optional "See all" page behind it. Rows with
 * no data hide themselves; when nothing can be shown at all, a centered
 * placeholder takes the screen.
 *
 * Long-pressing a card opens the item's corresponding actions modal: the
 * track modal for songs (hosted at the nav root), the collection modal for
 * albums and artists, and the playlist modal for playlists (hosted here,
 * with the same edit/rename/delete wiring as the playlists screen).
 *
 * Cards also join the app's shared-element transitions: track cards morph
 * into the full player's artwork when their track starts with auto-open on
 * (keyed on [sharedTrackId]), and album/playlist cards animate into the
 * matching detail screen's header.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlaylistClick: (PlaylistRecommendation) -> Unit,
    onAlbumClick: (AlbumRecommendation) -> Unit,
    onArtistClick: (artist: String, imageUrl: String?) -> Unit,
    onTrackClick: (TrackEntity) -> Unit,
    onSeeAllAlbums: () -> Unit,
    onSeeAllRecent: () -> Unit,
    onSeeAllFavorites: () -> Unit,
    onSeeAllArtists: () -> Unit,
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    sharedTrackId: Long? = null
) {
    val playlists by viewModel.recommendedPlaylists.collectAsState()
    val albums by viewModel.recommendedAlbums.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val oldFavorites by viewModel.oldFavoriteTracks.collectAsState()
    val artists by viewModel.favoriteArtists.collectAsState()
    val artistImages by viewModel.artistImages.collectAsState()
    val allPlaylists by viewModel.playlists.collectAsState()

    val trackModalState = LocalTrackFoamModalState.current
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()

    // Long-press actions for playlist cards: the playlist actions modal,
    // hosted here with the same edit/rename/delete wiring as PlaylistsScreen.
    val playlistModalState = rememberPlaylistFoamModalState()
    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<PlaylistEntity?>(null) }

    // Cover image picker: armed by the modal's edit-image action, the result
    // applies to the playlist it was opened for.
    var imagePickerTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    val context = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = imagePickerTarget
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            playlistsViewModel.setPlaylistImage(target.playlistId, uri.toString())
        }
        imagePickerTarget = null
    }

    // Track stats for the modal's details page, loaded for the shown playlist.
    var modalStats by remember { mutableStateOf<PlaylistModalStats?>(null) }
    LaunchedEffect(playlistModalState.playlist?.playlistId) {
        val id = playlistModalState.playlist?.playlistId
        modalStats = null
        if (id != null) {
            val (count, durationMs) = playlistsViewModel.playlistTrackStats(id)
            modalStats = PlaylistModalStats(id, count, durationMs)
        }
    }

    if (playlists.isEmpty() && albums.isEmpty() && recentTracks.isEmpty() &&
        oldFavorites.isEmpty() && artists.isEmpty()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = stringResource(R.string.no_audio_detected),
                style = FoamTheme.typography.body,
                modifier = Modifier.fillMaxWidth()
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(HomeRowSpacing),
        contentPadding = PaddingValues(top = 16.dp, start = 8.dp, end = 8.dp, bottom = LocalBottomBarClearance.current)
    ) {
        item(key = "home_header") {
            ScreenTitle(
                icon = FoamTheme.icons.current.home,
                title = stringResource(R.string.home)
            )
        }

        item(key = "home_row_playlists") {
            HomeRow(
                title = stringResource(R.string.home_row_recommended_playlists),
                items = playlists.take(HomeRowItemCount),
                key = { it.playlistId }
            ) { playlist ->
                HomeRowCard(
                    title = playlist.name,
                    imageUrl = playlist.imageUrl,
                    imageIsEmbeddedArt = false,
                    onClick = { onPlaylistClick(playlist) },
                    onLongClick = {
                        val entity = allPlaylists.firstOrNull { it.playlistId == playlist.playlistId }
                            ?: PlaylistEntity(
                                playlistId = playlist.playlistId,
                                name = playlist.name,
                                imageUrl = playlist.imageUrl
                            )
                        playlistModalState.show(entity)
                    },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    // Only the clicked card flies: shared keys are gated on the
                    // nav root's active-collection tag so albums/playlists that
                    // merely share the screen with the tapped card stay put.
                    sharedArtKey = "playlist-art-${playlist.playlistId}"
                        .takeIf { isActiveSharedCollection("playlist", "${playlist.playlistId}") },
                    sharedTitleKey = "playlist-title-${playlist.playlistId}"
                        .takeIf { isActiveSharedCollection("playlist", "${playlist.playlistId}") },
                    flightHeroTarget = CollectionHeroTarget.Playlist
                )
            }
        }

        item(key = "home_row_albums") {
            HomeRow(
                title = stringResource(R.string.home_row_recommended_albums),
                items = albums.take(HomeRowItemCount),
                onSeeAllClick = onSeeAllAlbums,
                key = { it.album }
            ) { album ->
                HomeRowCard(
                    title = album.album,
                    imageUrl = album.albumArtPath,
                    onClick = { onAlbumClick(album) },
                    onLongClick = {
                        longPressScope.launch {
                            collectionModalState?.show(
                                CollectionInfo(
                                    title = album.album,
                                    artist = album.artist,
                                    imageUrl = album.albumArtPath,
                                    kind = CollectionKind.Album,
                                    tracks = viewModel.tracksForAlbum(album.album)
                                )
                            )
                        }
                    },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedArtKey = "album-art-${album.album}"
                        .takeIf { isActiveSharedCollection("album", album.album) },
                    sharedTitleKey = "album-title-${album.album}"
                        .takeIf { isActiveSharedCollection("album", album.album) },
                    flightHeroTarget = CollectionHeroTarget.Album
                )
            }
        }

        item(key = "home_row_recent") {
            HomeRow(
                title = stringResource(R.string.home_row_recent_listens),
                items = recentTracks.take(HomeRowItemCount),
                onSeeAllClick = onSeeAllRecent,
                key = { it.id }
            ) { track ->
                HomeRowCard(
                    title = track.title,
                    imageUrl = track.albumArtPath,
                    width = HomeRowCardSmallWidth,
                    onClick = { onTrackClick(track) },
                    onLongClick = { trackModalState?.show(track) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = playerTransitionVisibilityScope,
                    sharedArtKey = "album_art_${track.id}".takeIf { track.id == sharedTrackId },
                    sharedTitleKey = "track_title_${track.id}".takeIf { track.id == sharedTrackId },
                    sharedInPlayerTransition = true
                )
            }
        }

        item(key = "home_row_favorites") {
            HomeRow(
                title = stringResource(R.string.home_row_old_favorites),
                items = oldFavorites.take(HomeRowItemCount),
                onSeeAllClick = onSeeAllFavorites,
                key = { it.id }
            ) { track ->
                HomeRowCard(
                    title = track.title,
                    imageUrl = track.albumArtPath,
                    width = HomeRowCardSmallWidth,
                    onClick = { onTrackClick(track) },
                    onLongClick = { trackModalState?.show(track) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = playerTransitionVisibilityScope,
                    sharedArtKey = "album_art_${track.id}".takeIf { track.id == sharedTrackId },
                    sharedTitleKey = "track_title_${track.id}".takeIf { track.id == sharedTrackId },
                    sharedInPlayerTransition = true
                )
            }
        }

        item(key = "home_row_artists") {
            HomeRow(
                title = stringResource(R.string.home_row_favorite_artists),
                items = artists.take(HomeRowItemCount),
                onSeeAllClick = onSeeAllArtists,
                key = { it.name }
            ) { artist ->
                HomeRowCard(
                    title = artist.name,
                    imageUrl = artistImages[artist.name],
                    imageIsEmbeddedArt = false,
                    width = HomeRowCardSmallWidth,
                    round = true,
                    onClick = { onArtistClick(artist.name, artistImages[artist.name]) },
                    onLongClick = {
                        longPressScope.launch {
                            collectionModalState?.show(
                                CollectionInfo(
                                    title = artist.name,
                                    kind = CollectionKind.Artist,
                                    tracks = viewModel.tracksForArtist(artist.name)
                                )
                            )
                        }
                    },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedArtKey = "artist-art-${artist.name}"
                        .takeIf { isActiveSharedCollection("artist", artist.name) },
                    sharedTitleKey = "artist-title-${artist.name}"
                        .takeIf { isActiveSharedCollection("artist", artist.name) },
                    flightHeroTarget = CollectionHeroTarget.Artist
                )
            }
        }
    }

    PlaylistFoamModal(
        state = playlistModalState,
        stats = modalStats,
        onEditImage = { playlist ->
            imagePickerTarget = playlist
            coverPicker.launch(ImageMimeTypes)
        },
        onEditName = { playlist -> renameTarget = playlist },
        onDelete = { playlist -> deleteConfirmTarget = playlist },
        onDismiss = { playlistModalState.dismiss() }
    )

    RenamePlaylistModal(
        playlist = renameTarget,
        onRename = { playlistId, newName ->
            renameTarget = null
            playlistsViewModel.renamePlaylist(playlistId, newName)
        },
        onDismiss = { renameTarget = null }
    )

    DeletePlaylistConfirmModal(
        playlist = deleteConfirmTarget,
        onConfirm = { playlistId ->
            deleteConfirmTarget = null
            playlistsViewModel.deletePlaylist(playlistId)
        },
        onDismiss = { deleteConfirmTarget = null }
    )
}
