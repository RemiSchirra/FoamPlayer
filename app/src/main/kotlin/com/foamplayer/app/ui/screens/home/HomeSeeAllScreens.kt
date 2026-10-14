package com.foamplayer.app.ui.screens.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.TrackSortOptions
import com.foamplayer.app.data.db.dao.AlbumRecommendation
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.dao.PlaylistRecommendation
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.scanner.PlaylistCover
import com.foamplayer.app.ui.components.AlbumArtImage
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.AlbumArtShape
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.components.NoAlbumArtLetter
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.CollectionKind
import com.foamplayer.app.ui.components.CollectionListItem
import com.foamplayer.app.ui.components.CollectionListScreen
import com.foamplayer.app.ui.components.MediaArtKind
import com.foamplayer.app.ui.components.MediaArtKindScope
import com.foamplayer.app.ui.modals.LocalCollectionFoamModalState
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.components.MediaGridCell
import com.foamplayer.app.ui.components.MediaListRow
import com.foamplayer.app.ui.components.TrackListScreen
import com.foamplayer.app.ui.components.caseInsensitiveComparator
import com.foamplayer.app.ui.components.rememberCollectionSearchState
import com.foamplayer.app.ui.components.rememberFilteredList
import com.foamplayer.app.ui.components.rememberSortedList
import com.foamplayer.app.ui.components.rememberSortedTracks
import com.foamplayer.app.ui.navigation.isActiveSharedCollection
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.launch

/** Horizontal inset of one list row; grid cells get their own symmetric padding instead. */
private fun Modifier.listRowInset(density: MediaDensity): Modifier =
    padding(horizontal = if (density.isGrid) 0.dp else 16.dp)

/**
 * Sort modes of the Home recommendation pages: the row's own ranking first
 * (the default), then the user-facing name/artist orderings, then a seeded
 * shuffle.
 */
private val RecommendedSortOptions = listOf(
    CollectionSortMode.RECOMMENDED,
    CollectionSortMode.NAME,
    CollectionSortMode.RANDOM
)

/** Sort options of the Home "Recently added" pages: the recency ranking first. */
private val RecentlyAddedSortOptions = RecommendedSortOptions

/**
 * "See all" page behind the Home "Recommended playlists" row: up to 50
 * playlists in the row's aggregate play/like order (the default, kept by
 * [CollectionSortMode].RECOMMENDED]). Playlist covers resolve through the
 * automatic cover chain (custom image first, else first-track embedded art),
 * so these rows load both plain image URIs and embedded art directly instead
 * of [CollectionListItem] (whose artwork loader expects embedded art only).
 */
@Composable
fun RecommendedPlaylistsSeeAllScreen(
    onPlaylistClick: (PlaylistRecommendation) -> Unit,
    onBack: () -> Unit,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.RECOMMENDED,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val playlists by viewModel.recommendedPlaylists.collectAsState()
    val playlistCovers by viewModel.playlistCovers.collectAsState()
    val searchState = rememberCollectionSearchState()
    // rememberFilteredList keeps the incoming list identity while the hero's
    // search bar is closed/blank (see rememberFilteredList): no fresh list
    // instance per unrelated recomposition feeding rememberSortedList below.
    val searchedPlaylists = rememberFilteredList(playlists, searchState) {
        searchState.matches(it.name)
    }
    val sortedPlaylists = rememberSortedList(
        searchedPlaylists,
        sortMode,
        sortInverted
    ) { mode ->
        when (mode) {
            // RECOMMENDED never reaches the comparator: it keeps the DAO's
            // ranking in [rememberSortedList].
            CollectionSortMode.RECOMMENDED -> compareBy { 0 }
            else -> caseInsensitiveComparator { it.name }
        }
    }

    CollectionListScreen(
        title = stringResource(R.string.home_row_recommended_playlists),
        itemCount = sortedPlaylists.size,
        onBack = onBack,
        headerIcon = FoamTheme.icons.current.playlists,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = RecommendedSortOptions,
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange,
        searchState = searchState
    ) {
        items(sortedPlaylists, key = { "home_playlist_${it.playlistId}" }) { playlist ->
            Box(modifier = Modifier.listRowInset(density)) {
                HomePlaylistRow(
                    playlist = playlist,
                    cover = playlistCovers[playlist.playlistId],
                    onClick = { onPlaylistClick(playlist) },
                    density = density
                )
            }
        }

        if (playlists.isEmpty()) {
            item(key = "home_playlists_empty") {
                SeeAllEmptyState(text = stringResource(R.string.no_playlists_yet))
            }
        }
    }
}

/**
 * "See all" page behind the Home "Recommended albums" row: up to 50 albums in
 * the row's aggregate play/like order (the default, kept by
 * [CollectionSortMode.RECOMMENDED]), mirroring the albums browse screen
 (long-press collection actions and shared elements included).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RecommendedAlbumsSeeAllScreen(
    onAlbumClick: (AlbumRecommendation) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.RECOMMENDED,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val albums by viewModel.recommendedAlbums.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()
    val searchState = rememberCollectionSearchState()
    val searchedAlbums = rememberFilteredList(albums, searchState) {
        searchState.matches(it.album, it.artist)
    }
    val sortedAlbums = rememberSortedList(
        searchedAlbums,
        sortMode,
        sortInverted
    ) { mode ->
        when (mode) {
            // RECOMMENDED never reaches the comparator: it keeps the DAO's
            // ranking in [rememberSortedList].
            CollectionSortMode.RECOMMENDED -> compareBy { 0 }
            CollectionSortMode.ARTIST ->
                compareBy(String.CASE_INSENSITIVE_ORDER, AlbumRecommendation::artist)
                    .then(compareBy(String.CASE_INSENSITIVE_ORDER, AlbumRecommendation::album))
            else -> compareBy(String.CASE_INSENSITIVE_ORDER, AlbumRecommendation::album)
        }
    }

    MediaArtKindScope(MediaArtKind.ALBUM) {
        CollectionListScreen(
            title = stringResource(R.string.home_row_recommended_albums),
            itemCount = sortedAlbums.size,
            onBack = onBack,
            density = density,
            onDensityChange = onDensityChange,
            sortOptions = RecommendedSortOptions,
            currentSort = sortMode,
            onSortChange = onSortChange,
            sortInverted = sortInverted,
            onSortInvertedChange = onSortInvertedChange,
            searchState = searchState
        ) {
            items(sortedAlbums, key = { "home_album_${it.album}" }) { album ->
                Box(modifier = Modifier.listRowInset(density)) {
                    CollectionListItem(
                        title = album.album,
                        subtitle = album.artist,
                        imageUrl = album.albumArtPath,
                        onClick = { onAlbumClick(album) },
                        active = CollectionId.album(album.album) == activeCollectionId,
                        density = density,
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
                        // Only the clicked row flies (see LocalActiveSharedCollection).
                        sharedElementKey = album.album
                            .takeIf { isActiveSharedCollection("album", album.album) },
                        immersiveFlightHero = LocalCollectionHeroLayoutLookup
                            .current(CollectionHeroTarget.Album)
                            .isImmersive
                    )
                }
            }

            if (albums.isEmpty()) {
                item(key = "home_albums_empty") {
                    SeeAllEmptyState(text = stringResource(R.string.no_audio_detected))
                }
            }
        }
    }
}

/** "See all" page behind the Home "Recent listens" row: up to 50 newest-played tracks. */
@Composable
fun RecentTracksSeeAllScreen(
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    activeTrackId: Long?,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.LISTEN_DATE,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    sharedTrackId: Long? = null,
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val tracks by viewModel.recentTracks.collectAsState()
    val sortedTracks = rememberSortedTracks(tracks, sortMode, sortInverted)

    TrackListScreen(
        title = stringResource(R.string.home_row_recent_listens),
        tracks = sortedTracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = { if (sortedTracks.isNotEmpty()) onTrackClick(sortedTracks, 0) },
        onShuffleAllClick = { shuffled ->
            if (shuffled.isNotEmpty()) onTrackClick(shuffled, 0)
        },
        onBack = onBack,
        activeTrackId = activeTrackId,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = TrackSortOptions,
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange,
        sharedTransitionScope = sharedTransitionScope,
        playerTransitionVisibilityScope = playerTransitionVisibilityScope,
        sharedTrackId = sharedTrackId,
        heroLayout = heroLayout
    )
}

/**
 * "See all" page behind the Home "Favorite tracks" row: up to 50 tracks in
 * the row's aggregate play/like order.
 */
@Composable
fun FavoriteTracksSeeAllScreen(
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    activeTrackId: Long?,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.LISTEN_COUNT,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    sharedTrackId: Long? = null,
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val tracks by viewModel.favoriteTracks.collectAsState()
    val sortedTracks = rememberSortedTracks(tracks, sortMode, sortInverted)

    TrackListScreen(
        title = stringResource(R.string.home_row_favorite_tracks),
        tracks = sortedTracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = { if (sortedTracks.isNotEmpty()) onTrackClick(sortedTracks, 0) },
        onShuffleAllClick = { shuffled ->
            if (shuffled.isNotEmpty()) onTrackClick(shuffled, 0)
        },
        onBack = onBack,
        activeTrackId = activeTrackId,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = TrackSortOptions,
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange,
        sharedTransitionScope = sharedTransitionScope,
        playerTransitionVisibilityScope = playerTransitionVisibilityScope,
        sharedTrackId = sharedTrackId,
        heroLayout = heroLayout
    )
}

/**
 * "See all" page behind the Home "Old favorites" row: up to 50 long-standing
 * favorites in the row's aggregate play/like order.
 */
@Composable
fun OldFavoritesSeeAllScreen(
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    activeTrackId: Long?,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.LISTEN_COUNT,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    sharedTrackId: Long? = null,
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val tracks by viewModel.oldFavoriteTracks.collectAsState()
    val sortedTracks = rememberSortedTracks(tracks, sortMode, sortInverted)

    TrackListScreen(
        title = stringResource(R.string.home_row_old_favorites),
        tracks = sortedTracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = { if (sortedTracks.isNotEmpty()) onTrackClick(sortedTracks, 0) },
        onShuffleAllClick = { shuffled ->
            if (shuffled.isNotEmpty()) onTrackClick(shuffled, 0)
        },
        onBack = onBack,
        activeTrackId = activeTrackId,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = TrackSortOptions,
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange,
        sharedTransitionScope = sharedTransitionScope,
        playerTransitionVisibilityScope = playerTransitionVisibilityScope,
        sharedTrackId = sharedTrackId,
        heroLayout = heroLayout
    )
}

/**
 * "See all" page behind the Home "Old favorite albums" row: up to 50
 * long-standing favorite albums in the row's aggregate play/like order,
 * mirroring the recommended-albums page.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun OldFavoriteAlbumsSeeAllScreen(
    onAlbumClick: (AlbumRecommendation) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.RECOMMENDED,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val albums by viewModel.oldFavoriteAlbums.collectAsState()
    AlbumRecommendationsList(
        title = stringResource(R.string.home_row_old_favorite_albums),
        albums = albums,
        emptyStateText = stringResource(R.string.no_audio_detected),
        onAlbumClick = onAlbumClick,
        onBack = onBack,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        activeCollectionId = activeCollectionId,
        density = density,
        onDensityChange = onDensityChange,
        sortMode = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange,
        tracksForAlbum = viewModel::tracksForAlbum
    )
}

/**
 * "See all" page behind the Home "Recently added albums" row: up to 50 albums
 * newest-first by their earliest scanned track.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RecentlyAddedAlbumsSeeAllScreen(
    onAlbumClick: (AlbumRecommendation) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.RECOMMENDED,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val albums by viewModel.recentlyAddedAlbums.collectAsState()
    AlbumRecommendationsList(
        title = stringResource(R.string.home_row_recently_added_albums),
        albums = albums,
        emptyStateText = stringResource(R.string.no_audio_detected),
        onAlbumClick = onAlbumClick,
        onBack = onBack,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        activeCollectionId = activeCollectionId,
        density = density,
        onDensityChange = onDensityChange,
        sortMode = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange,
        tracksForAlbum = viewModel::tracksForAlbum
    )
}

/**
 * "See all" page behind the Home "Recently added artists" row: up to 50
 * artists newest-first by their earliest scanned track, mirroring the
 * favorite-artists page.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RecentlyAddedArtistsSeeAllScreen(
    onArtistClick: (artist: String, imageUrl: String?, imageIsEmbeddedArt: Boolean) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.RECOMMENDED,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val artists by viewModel.recentlyAddedArtists.collectAsState()
    val artistImages by viewModel.recentlyAddedArtistImages.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()
    val sortedArtists = rememberSortedList(artists, sortMode, sortInverted) { mode ->
        when (mode) {
            // RECOMMENDED never reaches the comparator: it keeps the flow's
            // recency ranking in [rememberSortedList].
            CollectionSortMode.RECOMMENDED -> compareBy { 0 }
            else -> caseInsensitiveComparator { it.name }
        }
    }

    MediaArtKindScope(MediaArtKind.ARTIST) {
        CollectionListScreen(
            title = stringResource(R.string.home_row_recently_added_artists),
            itemCount = sortedArtists.size,
            onBack = onBack,
            density = density,
            onDensityChange = onDensityChange,
            sortOptions = RecentlyAddedSortOptions,
            currentSort = sortMode,
            onSortChange = onSortChange,
            sortInverted = sortInverted,
            onSortInvertedChange = onSortInvertedChange
        ) {
            items(sortedArtists, key = { "home_recently_added_artist_${it.name}" }) { artist ->
                Box(modifier = Modifier.listRowInset(density)) {
                    CollectionListItem(
                        title = artist.name,
                        subtitle = null,
                        imageUrl = artistImages[artist.name]?.imageUrl,
                        imageIsEmbeddedArt = artistImages[artist.name]?.isEmbeddedArt ?: false,
                        onClick = {
                            val cover = artistImages[artist.name]
                            onArtistClick(artist.name, cover?.imageUrl, cover?.isEmbeddedArt ?: false)
                        },
                        active = CollectionId.artist(artist.name) == activeCollectionId,
                        density = density,
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
                        sharedElementKey = artist.name
                            .takeIf { isActiveSharedCollection("artist", artist.name) },
                        sharedElementPrefix = "artist",
                        immersiveFlightHero = LocalCollectionHeroLayoutLookup
                            .current(CollectionHeroTarget.Artist)
                            .isImmersive,
                        // Artist rows render fully round on every screen.
                        fullyRoundedArtwork = true
                    )
                }
            }

            if (artists.isEmpty()) {
                item(key = "home_recently_added_artists_empty") {
                    SeeAllEmptyState(text = stringResource(R.string.no_audio_detected))
                }
            }
        }
    }
}

/**
 * "See all" page behind the Home "Favorite artists" row: up to 50 artists by
 * their songs' total plays (the default, kept by
 * [CollectionSortMode.RECOMMENDED]), mirroring the artists browse screen.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FavoriteArtistsSeeAllScreen(
    onArtistClick: (artist: String, imageUrl: String?, imageIsEmbeddedArt: Boolean) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.RECOMMENDED,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val artists by viewModel.favoriteArtists.collectAsState()
    val artistImages by viewModel.artistImages.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()
    val sortedArtists = rememberSortedList(artists, sortMode, sortInverted) { mode ->
        when (mode) {
            // RECOMMENDED never reaches the comparator: it keeps the flow's
            // play-count ranking in [rememberSortedList].
            CollectionSortMode.RECOMMENDED -> compareBy { 0 }
            else -> caseInsensitiveComparator { it.name }
        }
    }

    MediaArtKindScope(MediaArtKind.ARTIST) {
        CollectionListScreen(
            title = stringResource(R.string.home_row_favorite_artists),
            itemCount = sortedArtists.size,
            onBack = onBack,
            density = density,
            onDensityChange = onDensityChange,
            sortOptions = RecommendedSortOptions,
            currentSort = sortMode,
            onSortChange = onSortChange,
            sortInverted = sortInverted,
            onSortInvertedChange = onSortInvertedChange
        ) {
            items(sortedArtists, key = { "home_artist_${it.name}" }) { artist ->
                Box(modifier = Modifier.listRowInset(density)) {
                    CollectionListItem(
                        title = artist.name,
                        subtitle = null,
                        imageUrl = artistImages[artist.name]?.imageUrl,
                        imageIsEmbeddedArt = artistImages[artist.name]?.isEmbeddedArt ?: false,
                        onClick = {
                            val cover = artistImages[artist.name]
                            onArtistClick(artist.name, cover?.imageUrl, cover?.isEmbeddedArt ?: false)
                        },
                        active = CollectionId.artist(artist.name) == activeCollectionId,
                        density = density,
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
                        sharedElementKey = artist.name
                            .takeIf { isActiveSharedCollection("artist", artist.name) },
                        sharedElementPrefix = "artist",
                        immersiveFlightHero = LocalCollectionHeroLayoutLookup
                            .current(CollectionHeroTarget.Artist)
                            .isImmersive,
                        // Artist rows render fully round on every screen.
                        fullyRoundedArtwork = true
                    )
                }
            }

            if (artists.isEmpty()) {
                item(key = "home_artists_empty") {
                    SeeAllEmptyState(text = stringResource(R.string.no_audio_detected))
                }
            }
        }
    }
}

/**
 * Shared body of the album-recommendation "See all" pages (recommended, old
 * favorites, recently added): the same list, differing only in data source
 * and title.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AlbumRecommendationsList(
    title: String,
    albums: List<AlbumRecommendation>,
    emptyStateText: String,
    onAlbumClick: (AlbumRecommendation) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    activeCollectionId: String?,
    density: MediaDensity,
    onDensityChange: ((MediaDensity) -> Unit)?,
    sortMode: CollectionSortMode,
    onSortChange: ((CollectionSortMode) -> Unit)?,
    sortInverted: Boolean,
    onSortInvertedChange: ((Boolean) -> Unit)?,
    tracksForAlbum: suspend (String) -> List<TrackEntity>
) {
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()
    val searchState = rememberCollectionSearchState()
    val searchedAlbums = rememberFilteredList(albums, searchState) {
        searchState.matches(it.album, it.artist)
    }
    val sortedAlbums = rememberSortedList(
        searchedAlbums,
        sortMode,
        sortInverted
    ) { mode ->
        when (mode) {
            // RECOMMENDED never reaches the comparator: it keeps the DAO's
            // ranking in [rememberSortedList].
            CollectionSortMode.RECOMMENDED -> compareBy { 0 }
            CollectionSortMode.ARTIST ->
                compareBy(String.CASE_INSENSITIVE_ORDER, AlbumRecommendation::artist)
                    .then(compareBy(String.CASE_INSENSITIVE_ORDER, AlbumRecommendation::album))
            else -> compareBy(String.CASE_INSENSITIVE_ORDER, AlbumRecommendation::album)
        }
    }

    MediaArtKindScope(MediaArtKind.ALBUM) {
        CollectionListScreen(
            title = title,
            itemCount = sortedAlbums.size,
            onBack = onBack,
            density = density,
            onDensityChange = onDensityChange,
            sortOptions = RecommendedSortOptions,
            currentSort = sortMode,
            onSortChange = onSortChange,
            sortInverted = sortInverted,
            onSortInvertedChange = onSortInvertedChange,
            searchState = searchState
        ) {
            items(sortedAlbums, key = { "home_album_${it.album}" }) { album ->
                Box(modifier = Modifier.listRowInset(density)) {
                    CollectionListItem(
                        title = album.album,
                        subtitle = album.artist,
                        imageUrl = album.albumArtPath,
                        onClick = { onAlbumClick(album) },
                        active = CollectionId.album(album.album) == activeCollectionId,
                        density = density,
                        onLongClick = {
                            longPressScope.launch {
                                collectionModalState?.show(
                                    CollectionInfo(
                                        title = album.album,
                                        artist = album.artist,
                                        imageUrl = album.albumArtPath,
                                        kind = CollectionKind.Album,
                                        tracks = tracksForAlbum(album.album)
                                    )
                                )
                            }
                        },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        // Only the clicked row flies (see LocalActiveSharedCollection).
                        sharedElementKey = album.album
                            .takeIf { isActiveSharedCollection("album", album.album) },
                        immersiveFlightHero = LocalCollectionHeroLayoutLookup
                            .current(CollectionHeroTarget.Album)
                            .isImmersive
                    )
                }
            }

            if (albums.isEmpty()) {
                item(key = "home_albums_empty") {
                    SeeAllEmptyState(text = emptyStateText)
                }
            }
        }
    }
}

/** One playlist row of [RecommendedPlaylistsSeeAllScreen]: cover, name and track count. */
@Composable
private fun HomePlaylistRow(
    playlist: PlaylistRecommendation,
    cover: PlaylistCover?,
    onClick: () -> Unit,
    density: MediaDensity,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverImageUrl = cover?.imageUrl
    val trackCountText = pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount)

    @Composable
    fun PlaylistArtwork() {
        if (coverImageUrl != null) {
            val isEmbedded = cover?.isEmbeddedArt ?: false
            val coverRequest = remember(coverImageUrl, isEmbedded) {
                ImageRequest.Builder(context)
                    .data(
                        if (isEmbedded) {
                            EmbeddedAlbumArt(coverImageUrl.toUri())
                        } else {
                            coverImageUrl
                        }
                    )
                    .size(AlbumArtRequestSize)
                    .build()
            }
            AlbumArtImage(
                model = coverRequest,
                contentDescription = playlist.name,
                modifier = Modifier.fillMaxSize(),
                placeholderSize = if (density.isGrid) 32.dp else 20.dp
            )
        } else {
            NoAlbumArtLetter(title = playlist.name)
        }
    }

    val artworkModifier = Modifier
        .clip(AlbumArtShape())
        .background(FoamTheme.colors.surfaceVariant)

    if (density.isGrid) {
        MediaGridCell(
            modifier = modifier,
            onClick = onClick,
            onLongClick = null,
            artworkModifier = artworkModifier,
            artworkContent = { PlaylistArtwork() },
            title = playlist.name,
            subtitle = trackCountText
        )
    } else {
        MediaListRow(
            modifier = modifier,
            onClick = onClick,
            onLongClick = null,
            artworkModifier = artworkModifier,
            artworkContent = { PlaylistArtwork() },
            title = playlist.name,
            subtitle = trackCountText,
            density = density
        )
    }
}

/** Centered placeholder for an empty see-all page. */
@Composable
private fun SeeAllEmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = FoamTheme.typography.body
        )
    }
}
