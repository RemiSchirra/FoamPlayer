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
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.CollectionKind
import com.foamplayer.app.ui.components.HomeRow
import com.foamplayer.app.ui.components.HomeRowCard
import com.foamplayer.app.ui.components.MediaArtKind
import com.foamplayer.app.ui.components.MediaArtKindScope
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.navigation.isActiveSharedCollection
import com.foamplayer.app.ui.components.HomeRowCardSmallWidth
import com.foamplayer.app.ui.components.HomeRowSpacing
import com.foamplayer.app.ui.modals.LocalCollectionFoamModalState
import com.foamplayer.app.ui.modals.LocalTrackFoamModalState
import com.foamplayer.app.ui.components.ScreenTitle
import com.foamplayer.app.ui.modals.DeletePlaylistConfirmModal
import com.foamplayer.app.ui.modals.PlaylistFoamModal
import com.foamplayer.app.ui.modals.PlaylistModalStats
import com.foamplayer.app.ui.screens.playlists.PlaylistsViewModel
import com.foamplayer.app.ui.modals.RenamePlaylistModal
import com.foamplayer.app.ui.modals.rememberPlaylistFoamModalState
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.launch

/** How many items a Home row's pager shows (its "See all" page holds up to 50). */
private const val HomeRowItemCount = 5

private val ImageMimeTypes = arrayOf("image/*")

/**
 * The app's landing tab: a "Start playing" grid of random songs, then five
 * recommendation rows (recommended playlists, recommended albums, recent
 * listens, old favorites, favorite artists), each a horizontal card pager with
 * an optional "See all" page behind it. Rows with no data hide themselves;
 * when nothing can be shown at all, a centered placeholder takes the screen.
 *
 * Long-pressing a card opens the item's corresponding actions modal: the
 * track modal for songs (hosted at the nav root), the collection modal for
 * albums and artists (also hosted at the nav root), and the playlist modal
 * (hosted here, with the same edit/rename/delete wiring as the playlists
 * screen).
 *
 * Cards also join the app's shared-element transitions: track cards morph
 * into the full player's artwork when their track starts with auto-open on
 * (keyed on [sharedTrackId]), and album/playlist cards animate into the
 * matching detail screen's header. When recommendation rows reorder, their
 * cards glide to the new slot instead of popping (except any card currently
 * mid shared-element flight, which skips placement animation).
 *
 * Each card row scopes its artwork kind ([MediaArtKindScope]): album cards
 * load their covers under the album disc, artist cards their portraits under
 * the person icon — no per-card parameter threading.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlaylistClick: (PlaylistRecommendation) -> Unit,
    onAlbumClick: (AlbumRecommendation) -> Unit,
    onArtistClick: (artist: String, imageUrl: String?, imageIsEmbeddedArt: Boolean) -> Unit,
    onTrackClick: (TrackEntity) -> Unit,
    onSeeAllAlbums: () -> Unit,
    onSeeAllRecent: () -> Unit,
    onSeeAllFavorites: () -> Unit,
    onSeeAllArtists: () -> Unit,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    playlistsViewModel: PlaylistsViewModel = hiltViewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    sharedTrackId: Long? = null
) {
    val playlists by viewModel.recommendedPlaylists.collectAsState()
    val playlistCovers by viewModel.playlistCovers.collectAsState()
    val albums by viewModel.recommendedAlbums.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val oldFavorites by viewModel.oldFavoriteTracks.collectAsState()
    val artists by viewModel.favoriteArtists.collectAsState()
    val artistImages by viewModel.artistImages.collectAsState()
    val allPlaylists by viewModel.playlists.collectAsState()
    val randomTracks by viewModel.randomTracks.collectAsState()

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
        contentPadding = PaddingValues(top = 16.dp, bottom = LocalBottomBarClearance.current)
    ) {
        item(key = "home_header") {
            Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                ScreenTitle(
                    icon = FoamTheme.icons.current.home,
                    title = stringResource(R.string.home)
                )
            }
        }

        item(key = "home_row_start_playing") {
            StartPlayingSection(
                tracks = randomTracks,
                onTrackClick = onTrackClick,
                onTrackLongClick = { track -> trackModalState?.show(track) },
                onRefresh = viewModel::reshuffleRandomTracks
            )
        }

        item(key = "home_row_playlists") {
            HomeRow(
                title = stringResource(R.string.home_row_recommended_playlists),
                items = playlists.take(HomeRowItemCount),
                key = { it.playlistId }
            ) { playlist ->
                val cover = playlistCovers[playlist.playlistId]
                val inFlight = isActiveSharedCollection("playlist", "${playlist.playlistId}")
                HomeRowCard(
                    title = playlist.name,
                    imageUrl = cover?.imageUrl ?: playlist.imageUrl,
                    imageIsEmbeddedArt = cover?.isEmbeddedArt ?: false,
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
                    sharedArtKey = "playlist-art-${playlist.playlistId}".takeIf { inFlight },
                    sharedTitleKey = "playlist-title-${playlist.playlistId}".takeIf { inFlight },
                    flightHeroTarget = CollectionHeroTarget.Playlist,
                    // Cards glide to their new slot when recommendations reorder --
                    // except the one mid-flight into the collection header, which
                    // must not also be driven by lazy placement animations.
                    modifier = Modifier
                        .let { if (inFlight) it else it.animateItem() }
                )
            }
        }

        item(key = "home_row_albums") {
            MediaArtKindScope(MediaArtKind.ALBUM) {
                HomeRow(
                    title = stringResource(R.string.home_row_recommended_albums),
                    items = albums.take(HomeRowItemCount),
                    onSeeAllClick = onSeeAllAlbums,
                    key = { it.album }
                ) { album ->
                    val inFlight = isActiveSharedCollection("album", album.album)
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
                        sharedArtKey = "album-art-${album.album}".takeIf { inFlight },
                        sharedTitleKey = "album-title-${album.album}".takeIf { inFlight },
                        flightHeroTarget = CollectionHeroTarget.Album,
                        modifier = Modifier
                            .let { if (inFlight) it else it.animateItem() }
                    )
                }
            }
        }

        item(key = "home_row_recent") {
            HomeRow(
                title = stringResource(R.string.home_row_recent_listens),
                items = recentTracks.take(HomeRowItemCount),
                onSeeAllClick = onSeeAllRecent,
                key = { it.id }
            ) { track ->
                val inPlayerFlight = track.id == sharedTrackId
                HomeRowCard(
                    title = track.title,
                    imageUrl = track.albumArtPath,
                    width = HomeRowCardSmallWidth,
                    onClick = { onTrackClick(track) },
                    onLongClick = { trackModalState?.show(track) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = playerTransitionVisibilityScope,
                    sharedArtKey = "album_art_${track.id}".takeIf { inPlayerFlight },
                    sharedTitleKey = "track_title_${track.id}".takeIf { inPlayerFlight },
                    sharedInPlayerTransition = true,
                    modifier = Modifier
                        .let { if (inPlayerFlight) it else it.animateItem() }
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
                val inPlayerFlight = track.id == sharedTrackId
                HomeRowCard(
                    title = track.title,
                    imageUrl = track.albumArtPath,
                    width = HomeRowCardSmallWidth,
                    onClick = { onTrackClick(track) },
                    onLongClick = { trackModalState?.show(track) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = playerTransitionVisibilityScope,
                    sharedArtKey = "album_art_${track.id}".takeIf { inPlayerFlight },
                    sharedTitleKey = "track_title_${track.id}".takeIf { inPlayerFlight },
                    sharedInPlayerTransition = true,
                    modifier = Modifier
                        .let { if (inPlayerFlight) it else it.animateItem() }
                )
            }
        }

        item(key = "home_row_artists") {
            MediaArtKindScope(MediaArtKind.ARTIST) {
                HomeRow(
                    title = stringResource(R.string.home_row_favorite_artists),
                    items = artists.take(HomeRowItemCount),
                    onSeeAllClick = onSeeAllArtists,
                    key = { it.name }
                ) { artist ->
                    val inFlight = isActiveSharedCollection("artist", artist.name)
                    HomeRowCard(
                        title = artist.name,
                        imageUrl = artistImages[artist.name]?.imageUrl,
                        imageIsEmbeddedArt = artistImages[artist.name]?.isEmbeddedArt ?: false,
                        width = HomeRowCardSmallWidth,
                        round = true,
                        onClick = {
                            val cover = artistImages[artist.name]
                            onArtistClick(
                                artist.name,
                                cover?.imageUrl,
                                cover?.isEmbeddedArt ?: false
                            )
                        },
                        onLongClick = {
                            longPressScope.launch {
                                collectionModalState?.show(
                                    CollectionInfo(
                                        title = artist.name,
                                        // Either a Spotify cache file (plain) or an
                                        // audio file (embedded art): the flag routes
                                        // the modal header to the right Coil model.
                                        imageUrl = artistImages[artist.name]?.imageUrl,
                                        imageIsEmbeddedArt = artistImages[artist.name]?.isEmbeddedArt ?: false,
                                        kind = CollectionKind.Artist,
                                        tracks = viewModel.tracksForArtist(artist.name)
                                    )
                                )
                            }
                        },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        sharedArtKey = "artist-art-${artist.name}".takeIf { inFlight },
                        sharedTitleKey = "artist-title-${artist.name}".takeIf { inFlight },
                        flightHeroTarget = CollectionHeroTarget.Artist,
                        modifier = Modifier
                            .let { if (inFlight) it else it.animateItem() }
                    )
                }
            }
        }
    }

    PlaylistFoamModal(
        state = playlistModalState,
        onPlayNext = { playlist ->
            longPressScope.launch {
                playerViewModel.playNext(playlistsViewModel.playlistTracks(playlist.playlistId))
            }
        },
        onAddToQueue = { playlist ->
            longPressScope.launch {
                playerViewModel.addToQueue(playlistsViewModel.playlistTracks(playlist.playlistId))
            }
        },
        stats = modalStats,
        cover = playlistModalState.playlist?.let { playlistCovers[it.playlistId] },
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
