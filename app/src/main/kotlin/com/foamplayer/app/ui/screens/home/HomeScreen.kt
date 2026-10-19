package com.foamplayer.app.ui.screens.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.HomeSection
import com.foamplayer.app.data.db.dao.AlbumRecommendation
import com.foamplayer.app.data.db.dao.PlaylistRecommendation
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.components.HomeRow
import com.foamplayer.app.ui.components.HomeRowCard
import com.foamplayer.app.ui.components.HomeRowCardSmallWidth
import com.foamplayer.app.ui.components.HomeRowSpacing
import com.foamplayer.app.ui.components.MediaArtKind
import com.foamplayer.app.ui.components.MediaArtKindScope
import com.foamplayer.app.ui.components.ScreenTitle
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.CollectionKind
import com.foamplayer.app.ui.modals.DeletePlaylistConfirmModal
import com.foamplayer.app.ui.modals.LocalCollectionFoamModalState
import com.foamplayer.app.ui.modals.LocalTrackFoamModalState
import com.foamplayer.app.ui.modals.PlaylistFoamModal
import com.foamplayer.app.ui.modals.LocalOpenPlaylistPickerForPlaylist
import com.foamplayer.app.ui.modals.PlaylistModalStats
import com.foamplayer.app.ui.modals.RenamePlaylistModal
import com.foamplayer.app.ui.modals.rememberPlaylistFoamModalState
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.playlists.PlaylistsViewModel
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.launch

/** How many items a Home row's pager shows (its "See all" page holds up to 50). */
private const val HomeRowItemCount = 5

/**
 * Stable tag of each Home row. The same album/artist/track can appear in more
 * than one row at once, so item identity alone cannot pick the card that was
 * actually clicked: every matching card would register the same shared-element
 * key and the flight would lag between them / leave from the wrong source.
 * HomeScreen remembers the tag of the row whose card started the current
 * transition ([clickedRowTag]) and only that row's cards join the flight.
 */
private object HomeRowTag {
    const val RANDOM_TRACKS = "home_row_random_tracks"
    const val RANDOM_ALBUMS = "home_row_random_albums"
    const val RANDOM_ARTISTS = "home_row_random_artists"
    const val PLAYLISTS = "home_row_playlists"
    const val ALBUMS = "home_row_albums"
    const val RECENT_TRACKS = "home_row_recent"
    const val FAVORITE_TRACKS = "home_row_favorites"
    const val OLD_FAVORITES = "home_row_old_favorites"
    const val OLD_FAVORITE_ALBUMS = "home_row_old_favorite_albums"
    const val ARTISTS = "home_row_artists"
    const val RECENTLY_ADDED_ALBUMS = "home_row_recently_added_albums"
    const val RECENTLY_ADDED_ARTISTS = "home_row_recently_added_artists"
}

/** Identity of one clicked collection card: row + kind + item id. */
private fun collectionKey(rowTag: String, kind: String, id: String) = "$rowTag|$kind|$id"

private val ImageMimeTypes = arrayOf("image/*")

/** Gap between the "Home" title and the first section. */
private val HomeHeaderGap = 16.dp

/** Size of the spinner shown beside the "Home" title while the library scans. */
private val HomeScanSpinnerSize = 24.dp

/**
 * The app's landing tab. Its sections are user-configurable (Settings > UI >
 * Home): each [HomeSection] renders in the configured order when enabled, and
 * hides itself while empty. The random sections (tracks/albums/artists) share
 * the "Start playing"-style card grid with a reshuffle button; every other
 * section is a [HomeRow] pager with an optional "See all" page behind it.
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
    onSeeAllFavoriteTracks: () -> Unit,
    onSeeAllOldFavoriteAlbums: () -> Unit,
    onSeeAllRecentlyAddedAlbums: () -> Unit,
    onSeeAllRecentlyAddedArtists: () -> Unit,
    onOpenHomeSettings: () -> Unit,
    onOpenLibrarySettings: () -> Unit,
    sections: List<HomeSection>,
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
    val favoriteTracks by viewModel.favoriteTracks.collectAsState()
    val oldFavoriteTracks by viewModel.oldFavoriteTracks.collectAsState()
    val oldFavoriteAlbums by viewModel.oldFavoriteAlbums.collectAsState()
    val artists by viewModel.favoriteArtists.collectAsState()
    val artistImages by viewModel.artistImages.collectAsState()
    val recentlyAddedAlbums by viewModel.recentlyAddedAlbums.collectAsState()
    val recentlyAddedArtists by viewModel.recentlyAddedArtists.collectAsState()
    val recentlyAddedArtistImages by viewModel.recentlyAddedArtistImages.collectAsState()
    val allPlaylists by viewModel.playlists.collectAsState()
    val randomTracks by viewModel.randomTracks.collectAsState()
    val randomAlbums by viewModel.randomAlbums.collectAsState()
    val randomArtists by viewModel.randomArtists.collectAsState()
    val randomArtistCovers by viewModel.randomArtistCovers.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()
    val libraryLoaded by viewModel.libraryLoaded.collectAsState()
    val libraryEmpty by viewModel.libraryEmpty.collectAsState()

    // True when at least one enabled section would actually render a row.
    val anyRowVisible = sections.any { section ->
        when (section) {
            HomeSection.RANDOM_TRACKS -> randomTracks.isNotEmpty()
            HomeSection.RANDOM_ALBUMS -> randomAlbums.isNotEmpty()
            HomeSection.RANDOM_ARTISTS -> randomArtists.isNotEmpty()
            HomeSection.RECOMMENDED_PLAYLISTS -> playlists.isNotEmpty()
            HomeSection.RECOMMENDED_ALBUMS -> albums.isNotEmpty()
            HomeSection.RECENT_TRACKS -> recentTracks.isNotEmpty()
            HomeSection.FAVORITE_TRACKS -> favoriteTracks.isNotEmpty()
            HomeSection.OLD_FAVORITES -> oldFavoriteTracks.isNotEmpty()
            HomeSection.OLD_FAVORITE_ALBUMS -> oldFavoriteAlbums.isNotEmpty()
            HomeSection.FAVORITE_ARTISTS -> artists.isNotEmpty()
            HomeSection.RECENTLY_ADDED_ALBUMS -> recentlyAddedAlbums.isNotEmpty()
            HomeSection.RECENTLY_ADDED_ARTISTS -> recentlyAddedArtists.isNotEmpty()
        }
    }

    val trackModalState = LocalTrackFoamModalState.current
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()

    var clickedRowTag by rememberSaveable { mutableStateOf<String?>(null) }

    var clickedCollectionKey by rememberSaveable { mutableStateOf<String?>(null) }

    // Long-press actions for playlist cards: the playlist actions modal,
    // hosted here with the same edit/rename/delete wiring as PlaylistsScreen.
    val playlistModalState = rememberPlaylistFoamModalState()
    val openPlaylistPicker = LocalOpenPlaylistPickerForPlaylist.current
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

    // No row loaded or empty library
    if (libraryLoaded && !anyRowVisible) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(scanStatus = scanStatus)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = LocalBottomBarClearance.current),
                contentAlignment = Alignment.Center
            ) {
                if (libraryEmpty) {
                    HomeEmptyState(
                        body = stringResource(R.string.no_audio_detected),
                        buttonLabel = stringResource(R.string.home_empty_open_library),
                        onButtonClick = onOpenLibrarySettings
                    )
                } else {
                    HomeEmptyState(
                        body = stringResource(R.string.home_empty_body),
                        buttonLabel = stringResource(R.string.home_empty_open_settings),
                        onButtonClick = onOpenHomeSettings
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(HomeRowSpacing),
        contentPadding = PaddingValues(top = 16.dp, bottom = LocalBottomBarClearance.current)
    ) {
        item(key = "home_header") { HomeHeader(scanStatus = scanStatus) }

        sections.forEach { section ->
            when (section) {
                HomeSection.RANDOM_TRACKS -> homeSectionItem(key = "home_row_random_tracks", visible = randomTracks.isNotEmpty()) {
                    StartPlayingSection(
                        tracks = randomTracks,
                        onTrackClick = { track ->
                            clickedRowTag = HomeRowTag.RANDOM_TRACKS
                            onTrackClick(track)
                        },
                        onTrackLongClick = { track ->
                            longPressScope.launch {
                                trackModalState?.show(viewModel.freshTrack(track))
                            }
                        },
                        onRefresh = viewModel::reshuffleRandomTracks
                    )
                }
                HomeSection.RANDOM_ALBUMS -> homeSectionItem(key = "home_row_random_albums", visible = randomAlbums.isNotEmpty()) {
                    RandomAlbumsSection(
                        albums = randomAlbums,
                        onAlbumClick = { album ->
                            clickedRowTag = HomeRowTag.RANDOM_ALBUMS
                            clickedCollectionKey = collectionKey(HomeRowTag.RANDOM_ALBUMS, "album", album.album)
                            onAlbumClick(
                                AlbumRecommendation(
                                    album = album.album,
                                    artist = album.artist,
                                    albumArtPath = album.albumArtPath,
                                    totalScore = 0
                                )
                            )
                        },
                        onAlbumLongClick = { album ->
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
                        onRefresh = viewModel::reshuffleRandomAlbums
                    )
                }
                HomeSection.RANDOM_ARTISTS -> homeSectionItem(key = "home_row_random_artists", visible = randomArtists.isNotEmpty()) {
                    RandomArtistsSection(
                        artists = randomArtists,
                        covers = randomArtistCovers,
                        onArtistClick = { artist ->
                            clickedRowTag = HomeRowTag.RANDOM_ARTISTS
                            clickedCollectionKey = collectionKey(HomeRowTag.RANDOM_ARTISTS, "artist", artist.name)
                            val cover = randomArtistCovers.covers[artist.name]
                            onArtistClick(artist.name, cover?.imageUrl, cover?.isEmbeddedArt ?: false)
                        },
                        onArtistLongClick = { artist ->
                            longPressScope.launch {
                                collectionModalState?.show(
                                    CollectionInfo(
                                        title = artist.name,
                                        imageUrl = randomArtistCovers.covers[artist.name]?.imageUrl,
                                        imageIsEmbeddedArt = randomArtistCovers.covers[artist.name]?.isEmbeddedArt ?: false,
                                        kind = CollectionKind.Artist,
                                        tracks = viewModel.tracksForArtist(artist.name)
                                    )
                                )
                            }
                        },
                        onRefresh = viewModel::reshuffleRandomArtists
                    )
                }
                HomeSection.RECOMMENDED_PLAYLISTS -> homeSectionItem(key = "home_row_playlists", visible = playlists.isNotEmpty()) {
                    HomeRow(
                        title = stringResource(R.string.home_row_recommended_playlists),
                        items = playlists.take(HomeRowItemCount),
                        key = { it.playlistId }
                    ) { playlist ->
                        val cover = playlistCovers[playlist.playlistId]
                        val inFlight = clickedCollectionKey == collectionKey(HomeRowTag.PLAYLISTS, "playlist", "${playlist.playlistId}")
                        HomeRowCard(
                            title = playlist.name,
                            imageUrl = cover?.imageUrl ?: playlist.imageUrl,
                            imageIsEmbeddedArt = cover?.isEmbeddedArt ?: false,
                            onClick = {
                                clickedRowTag = HomeRowTag.PLAYLISTS
                                clickedCollectionKey = collectionKey(HomeRowTag.PLAYLISTS, "playlist", "${playlist.playlistId}")
                                onPlaylistClick(playlist)
                            },
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
                            sharedArtKey = "playlist-art-${playlist.playlistId}".takeIf { inFlight },
                            sharedTitleKey = "playlist-title-${playlist.playlistId}".takeIf { inFlight },
                            flightHeroTarget = CollectionHeroTarget.Playlist,
                            modifier = Modifier
                                .let { if (inFlight) it else it.animateItem() }
                        )
                    }
                }
                HomeSection.RECOMMENDED_ALBUMS -> homeSectionItem(key = "home_row_albums", visible = albums.isNotEmpty()) {
                    MediaArtKindScope(MediaArtKind.ALBUM) {
                        HomeRow(
                            title = stringResource(R.string.home_row_recommended_albums),
                            items = albums.take(HomeRowItemCount),
                            onSeeAllClick = onSeeAllAlbums,
                            key = { it.album }
                        ) { album ->
                            val inFlight = clickedCollectionKey == collectionKey(HomeRowTag.ALBUMS, "album", album.album)
                            HomeRowCard(
                                title = album.album,
                                imageUrl = album.albumArtPath,
                                onClick = {
                                    clickedRowTag = HomeRowTag.ALBUMS
                                    clickedCollectionKey = collectionKey(HomeRowTag.ALBUMS, "album", album.album)
                                    onAlbumClick(album)
                                },
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
                HomeSection.RECENT_TRACKS -> homeSectionItem(key = "home_row_recent", visible = recentTracks.isNotEmpty()) {
                    HomeRow(
                        title = stringResource(R.string.home_row_recent_listens),
                        items = recentTracks.take(HomeRowItemCount),
                        onSeeAllClick = onSeeAllRecent,
                        key = { it.id }
                    ) { track ->
                        val inPlayerFlight = track.id == sharedTrackId &&
                                clickedRowTag == HomeRowTag.RECENT_TRACKS
                        HomeRowCard(
                            title = track.title,
                            imageUrl = track.albumArtPath,
                            width = HomeRowCardSmallWidth,
                            onClick = {
                                clickedRowTag = HomeRowTag.RECENT_TRACKS
                                onTrackClick(track)
                            },
                            onLongClick = {
                longPressScope.launch {
                    trackModalState?.show(viewModel.freshTrack(track))
                }
            },
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
                HomeSection.FAVORITE_TRACKS -> homeSectionItem(key = "home_row_favorites", visible = favoriteTracks.isNotEmpty()) {
                    HomeRow(
                        title = stringResource(R.string.home_row_favorite_tracks),
                        items = favoriteTracks.take(HomeRowItemCount),
                        onSeeAllClick = onSeeAllFavoriteTracks,
                        key = { it.id }
                    ) { track ->
                        val inPlayerFlight = track.id == sharedTrackId &&
                                clickedRowTag == HomeRowTag.FAVORITE_TRACKS
                        HomeRowCard(
                            title = track.title,
                            imageUrl = track.albumArtPath,
                            width = HomeRowCardSmallWidth,
                            onClick = {
                                clickedRowTag = HomeRowTag.FAVORITE_TRACKS
                                onTrackClick(track)
                            },
                            onLongClick = {
                longPressScope.launch {
                    trackModalState?.show(viewModel.freshTrack(track))
                }
            },
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
                HomeSection.OLD_FAVORITES -> homeSectionItem(key = "home_row_old_favorites", visible = oldFavoriteTracks.isNotEmpty()) {
                    HomeRow(
                        title = stringResource(R.string.home_row_old_favorites),
                        items = oldFavoriteTracks.take(HomeRowItemCount),
                        onSeeAllClick = onSeeAllFavorites,
                        key = { it.id }
                    ) { track ->
                        val inPlayerFlight = track.id == sharedTrackId &&
                                clickedRowTag == HomeRowTag.OLD_FAVORITES
                        HomeRowCard(
                            title = track.title,
                            imageUrl = track.albumArtPath,
                            width = HomeRowCardSmallWidth,
                            onClick = {
                                clickedRowTag = HomeRowTag.OLD_FAVORITES
                                onTrackClick(track)
                            },
                            onLongClick = {
                longPressScope.launch {
                    trackModalState?.show(viewModel.freshTrack(track))
                }
            },
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
                HomeSection.OLD_FAVORITE_ALBUMS -> homeSectionItem(key = "home_row_old_favorite_albums", visible = oldFavoriteAlbums.isNotEmpty()) {
                    MediaArtKindScope(MediaArtKind.ALBUM) {
                        HomeRow(
                            title = stringResource(R.string.home_row_old_favorite_albums),
                            items = oldFavoriteAlbums.take(HomeRowItemCount),
                            onSeeAllClick = onSeeAllOldFavoriteAlbums,
                            key = { it.album }
                        ) { album ->
                            val inFlight = clickedCollectionKey == collectionKey(HomeRowTag.OLD_FAVORITE_ALBUMS, "album", album.album)
                            HomeRowCard(
                                title = album.album,
                                imageUrl = album.albumArtPath,
                                onClick = {
                                    clickedRowTag = HomeRowTag.OLD_FAVORITE_ALBUMS
                                    clickedCollectionKey = collectionKey(HomeRowTag.OLD_FAVORITE_ALBUMS, "album", album.album)
                                    onAlbumClick(album)
                                },
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
                HomeSection.FAVORITE_ARTISTS -> homeSectionItem(key = "home_row_artists", visible = artists.isNotEmpty()) {
                    MediaArtKindScope(MediaArtKind.ARTIST) {
                        HomeRow(
                            title = stringResource(R.string.home_row_favorite_artists),
                            items = artists.take(HomeRowItemCount),
                            onSeeAllClick = onSeeAllArtists,
                            key = { it.name }
                        ) { artist ->
                            val inFlight = clickedCollectionKey == collectionKey(HomeRowTag.ARTISTS, "artist", artist.name)
                            HomeRowCard(
                                title = artist.name,
                                imageUrl = artistImages[artist.name]?.imageUrl,
                                imageIsEmbeddedArt = artistImages[artist.name]?.isEmbeddedArt ?: false,
                                width = HomeRowCardSmallWidth,
                                round = true,
                                onClick = {
                                    clickedRowTag = HomeRowTag.ARTISTS
                                    clickedCollectionKey = collectionKey(HomeRowTag.ARTISTS, "artist", artist.name)
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
                HomeSection.RECENTLY_ADDED_ALBUMS -> homeSectionItem(key = "home_row_recently_added_albums", visible = recentlyAddedAlbums.isNotEmpty()) {
                    MediaArtKindScope(MediaArtKind.ALBUM) {
                        HomeRow(
                            title = stringResource(R.string.home_row_recently_added_albums),
                            items = recentlyAddedAlbums.take(HomeRowItemCount),
                            onSeeAllClick = onSeeAllRecentlyAddedAlbums,
                            key = { it.album }
                        ) { album ->
                            val inFlight = clickedCollectionKey == collectionKey(HomeRowTag.RECENTLY_ADDED_ALBUMS, "album", album.album)
                            HomeRowCard(
                                title = album.album,
                                imageUrl = album.albumArtPath,
                                onClick = {
                                    clickedRowTag = HomeRowTag.RECENTLY_ADDED_ALBUMS
                                    clickedCollectionKey = collectionKey(HomeRowTag.RECENTLY_ADDED_ALBUMS, "album", album.album)
                                    onAlbumClick(album)
                                },
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
                HomeSection.RECENTLY_ADDED_ARTISTS -> homeSectionItem(key = "home_row_recently_added_artists", visible = recentlyAddedArtists.isNotEmpty()) {
                    MediaArtKindScope(MediaArtKind.ARTIST) {
                        HomeRow(
                            title = stringResource(R.string.home_row_recently_added_artists),
                            items = recentlyAddedArtists.take(HomeRowItemCount),
                            onSeeAllClick = onSeeAllRecentlyAddedArtists,
                            key = { it.name }
                        ) { artist ->
                            val inFlight = clickedCollectionKey == collectionKey(HomeRowTag.RECENTLY_ADDED_ARTISTS, "artist", artist.name)
                            HomeRowCard(
                                title = artist.name,
                                imageUrl = recentlyAddedArtistImages[artist.name]?.imageUrl,
                                imageIsEmbeddedArt = recentlyAddedArtistImages[artist.name]?.isEmbeddedArt ?: false,
                                width = HomeRowCardSmallWidth,
                                round = true,
                                onClick = {
                                    clickedRowTag = HomeRowTag.RECENTLY_ADDED_ARTISTS
                                    clickedCollectionKey = collectionKey(HomeRowTag.RECENTLY_ADDED_ARTISTS, "artist", artist.name)
                                    val cover = recentlyAddedArtistImages[artist.name]
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
                                                imageUrl = recentlyAddedArtistImages[artist.name]?.imageUrl,
                                                imageIsEmbeddedArt = recentlyAddedArtistImages[artist.name]?.isEmbeddedArt ?: false,
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
        onAddToPlaylist = { playlist ->
            openPlaylistPicker(playlist)
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

/**
 * Emits a Home section's item only when it has content: an empty item would
 * still receive spacing on both sides from the LazyColumn's
 * [Arrangement.spacedBy] arrangement, leaving a double gap between its
 * neighbors.
 */
private fun LazyListScope.homeSectionItem(
    key: String,
    visible: Boolean,
    content: @Composable LazyItemScope.() -> Unit
) {
    if (visible) item(key = key, content = content)
}

/** The Home page's title row; shows the scan spinner while a scan is running. */
@Composable
private fun HomeHeader(scanStatus: ScanStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(HomeHeaderGap)) {
        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
            ScreenTitle(
                icon = FoamTheme.icons.current.home,
                title = stringResource(R.string.home),
                actions = {
                    if (scanStatus is ScanStatus.Scanning) {
                        CircularProgressIndicator(
                            color = FoamTheme.accentColor,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(HomeScanSpinnerSize)
                        )
                    }
                }
            )
        }
    }
}

/** Placeholder shown centered on the Home page when no rows are displayed. */
@Composable
private fun HomeEmptyState(
    body: String,
    buttonLabel: String,
    onButtonClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            text = stringResource(R.string.home_empty_title),
            style = FoamTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
        BasicText(
            text = body,
            style = FoamTheme.typography.caption.copy(textAlign = TextAlign.Center)
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .background(FoamTheme.accentColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                    onClick = onButtonClick
                )
                .padding(horizontal = FoamDimens.spacingMedium, vertical = FoamDimens.spacingSmall)
        ) {
            BasicText(
                text = buttonLabel,
                style = FoamTheme.typography.caption.copy(
                    color = FoamTheme.colors.background,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}