package com.foamplayer.app.ui.screens.browse

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Folder
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.TrackSortOptions
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.CollectionAlbumEntry
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.components.CollectionInfo
import com.foamplayer.app.ui.components.CollectionKind
import com.foamplayer.app.ui.components.CollectionListItem
import com.foamplayer.app.ui.components.CollectionListScreen
import com.foamplayer.app.ui.components.LocalCollectionFoamModalState
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.components.TrackCenterRequest
import com.foamplayer.app.ui.components.TrackListScreen
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.components.distinctCollectionAlbums
import com.foamplayer.app.ui.components.distinctCollectionArtistNames
import com.foamplayer.app.ui.components.caseInsensitiveComparator
import com.foamplayer.app.ui.components.rememberSortedList
import com.foamplayer.app.ui.components.rememberSortedTracks
import com.foamplayer.app.ui.navigation.isActiveSharedCollection
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Horizontal inset of one list row; grid cells get their own symmetric padding instead. */
private fun Modifier.listRowInset(density: MediaDensity): Modifier =
    padding(horizontal = if (density.isGrid) 0.dp else 16.dp)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumsBrowseScreen(
    onAlbumClick: (AlbumSearchResult) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.NAME,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val albums by viewModel.albums.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()
    val sortedAlbums = rememberSortedList(albums, sortMode, sortInverted) { mode ->
        when (mode) {
            CollectionSortMode.ARTIST ->
                caseInsensitiveComparator(AlbumSearchResult::artist)
                    .then(caseInsensitiveComparator(AlbumSearchResult::album))
            else -> caseInsensitiveComparator(AlbumSearchResult::album)
        }
    }

    CollectionListScreen(
        title = "Albums",
        itemCount = sortedAlbums.size,
        onBack = onBack,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = listOf(
            CollectionSortMode.NAME,
            CollectionSortMode.ARTIST,
            CollectionSortMode.RANDOM
        ),
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange
    ) {
        items(sortedAlbums, key = { "browse_album_${it.album}" }) { album ->
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
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ArtistsBrowseScreen(
    onArtistClick: (artist: String, imageUrl: String?) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.NAME,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val artists by viewModel.artists.collectAsState()
    val artistImages by viewModel.artistImages.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()
    val sortedArtists = rememberSortedList(artists, sortMode, sortInverted) {
        caseInsensitiveComparator { it }
    }

    CollectionListScreen(
        title = "Artists",
        itemCount = sortedArtists.size,
        onBack = onBack,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange
    ) {
        items(sortedArtists, key = { "browse_artist_$it" }) { artist ->
            Box(modifier = Modifier.listRowInset(density)) {
                CollectionListItem(
                    title = artist,
                    subtitle = null,
                    imageUrl = artistImages[artist],
                    imageIsEmbeddedArt = false,
                    onClick = { onArtistClick(artist, artistImages[artist]) },
                    active = CollectionId.artist(artist) == activeCollectionId,
                    density = density,
                    onLongClick = {
                        longPressScope.launch {
                            collectionModalState?.show(
                                CollectionInfo(
                                    title = artist,
                                    kind = CollectionKind.Artist,
                                    tracks = viewModel.tracksForArtist(artist)
                                )
                            )
                        }
                    },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    // Only the clicked row flies (see LocalActiveSharedCollection).
                    sharedElementKey = artist.takeIf { isActiveSharedCollection("artist", artist) },
                    sharedElementPrefix = "artist",
                    immersiveFlightHero = LocalCollectionHeroLayoutLookup
                        .current(CollectionHeroTarget.Artist)
                        .isImmersive
                )
            }
        }
    }
}

@Composable
fun GenresBrowseScreen(
    onGenreClick: (String) -> Unit,
    onBack: () -> Unit,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.NAME,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val genres by viewModel.genres.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()
    val sortedGenres = rememberSortedList(genres, sortMode, sortInverted) {
        caseInsensitiveComparator { it }
    }

    CollectionListScreen(
        title = "Genres",
        itemCount = sortedGenres.size,
        onBack = onBack,
        headerIcon = Icons.Rounded.Category,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange
    ) {
        items(sortedGenres, key = { "browse_genre_$it" }) { genre ->
            Box(modifier = Modifier.listRowInset(density)) {
                CollectionListItem(
                    title = genre,
                    subtitle = null,
                    icon = Icons.Rounded.Category,
                    onClick = { onGenreClick(genre) },
                    active = CollectionId.genre(genre) == activeCollectionId,
                    density = density,
                    onLongClick = {
                        longPressScope.launch {
                            collectionModalState?.show(
                                CollectionInfo(
                                    title = genre,
                                    icon = Icons.Rounded.Category,
                                    kind = CollectionKind.Genre,
                                    tracks = viewModel.tracksForGenre(genre)
                                )
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun YearsBrowseScreen(
    onYearClick: (Int) -> Unit,
    onBack: () -> Unit,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.NAME,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val years by viewModel.years.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()
    // NAME on a years list is the year's own ascending order (the DAO starts
    // newest-first; the sort modes give the user the reverse for free).
    val sortedYears = rememberSortedList(years, sortMode, sortInverted) {
        compareBy { it }
    }

    CollectionListScreen(
        title = "Years",
        itemCount = sortedYears.size,
        onBack = onBack,
        headerIcon = Icons.Rounded.CalendarToday,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange
    ) {
        items(sortedYears, key = { "browse_year_$it" }) { year ->
            Box(modifier = Modifier.listRowInset(density)) {
                CollectionListItem(
                    title = year.toString(),
                    subtitle = null,
                    icon = Icons.Rounded.CalendarToday,
                    onClick = { onYearClick(year) },
                    active = CollectionId.year(year) == activeCollectionId,
                    density = density,
                    onLongClick = {
                        longPressScope.launch {
                            collectionModalState?.show(
                                CollectionInfo(
                                    title = year.toString(),
                                    icon = Icons.Rounded.CalendarToday,
                                    kind = CollectionKind.Year,
                                    tracks = viewModel.tracksForYear(year)
                                )
                            )
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AllTracksBrowseScreen(
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    activeTrackId: Long?,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.NAME,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    sharedTrackId: Long? = null,
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val tracks by viewModel.allTracks.collectAsState()
    val sortedTracks = rememberSortedTracks(tracks, sortMode, sortInverted)

    TrackListScreen(
        title = "All Tracks",
        tracks = sortedTracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = { if (sortedTracks.isNotEmpty()) onTrackClick(sortedTracks, 0) },
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GenreTracksScreen(
    genre: String,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    activeTrackId: Long?,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.NAME,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    sharedTrackId: Long? = null,
    centerTrackRequest: TrackCenterRequest? = null,
    isPlaying: Boolean = true,
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    immersiveMode: Boolean = false,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val tracks by viewModel.genreTracks.collectAsState()

    LaunchedEffect(genre) {
        viewModel.loadGenreTracks(genre)
    }

    val sortedTracks = rememberSortedTracks(tracks, sortMode, sortInverted)

    TrackListScreen(
        title = genre,
        tracks = sortedTracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = { if (sortedTracks.isNotEmpty()) onTrackClick(sortedTracks, 0) },
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
        centerTrackRequest = centerTrackRequest,
        isPlaying = isPlaying,
        heroLayout = heroLayout,
        immersiveMode = immersiveMode
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun YearTracksScreen(
    year: Int,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    activeTrackId: Long?,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.ALBUM,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    sharedTrackId: Long? = null,
    centerTrackRequest: TrackCenterRequest? = null,
    isPlaying: Boolean = true,
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    immersiveMode: Boolean = false,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val tracks by viewModel.yearTracks.collectAsState()

    LaunchedEffect(year) {
        viewModel.loadYearTracks(year)
    }

    val sortedTracks = rememberSortedTracks(tracks, sortMode, sortInverted)

    TrackListScreen(
        title = year.toString(),
        tracks = sortedTracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = { if (sortedTracks.isNotEmpty()) onTrackClick(sortedTracks, 0) },
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
        centerTrackRequest = centerTrackRequest,
        isPlaying = isPlaying,
        heroLayout = heroLayout,
        immersiveMode = immersiveMode
    )
}

@Composable
fun FolderBrowseScreen(
    path: String?,
    onFolderClick: (String) -> Unit,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    activeTrackId: Long?,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.NAME,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val content by viewModel.folderContent.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()

    LaunchedEffect(path) {
        viewModel.loadFolderContent(path)
    }

    val title = path
        ?.substringAfterLast('/')
        ?.ifEmpty { path.substringAfterLast(':') }
        ?: "Folders"

    val folders = content?.folders.orEmpty()
    val sortedFolders = rememberSortedList(folders, sortMode, sortInverted) {
        caseInsensitiveComparator { it.name }
    }
    val tracks = content?.tracks.orEmpty()

    CollectionListScreen(
        title = title,
        itemCount = sortedFolders.size + tracks.size,
        onBack = onBack,
        headerIcon = Icons.Rounded.Folder,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange
    ) {
        items(sortedFolders, key = { "browse_folder_${it.path}" }) { folder ->
            Box(modifier = Modifier.listRowInset(density)) {
                CollectionListItem(
                    title = folder.name,
                    subtitle = "${folder.trackCount} tracks",
                    icon = Icons.Rounded.Folder,
                    onClick = { onFolderClick(folder.path) },
                    active = CollectionId.folder(folder.path) == activeCollectionId,
                    density = density,
                    onLongClick = {
                        longPressScope.launch {
                            collectionModalState?.show(
                                CollectionInfo(
                                    title = folder.name,
                                    subtitle = "${folder.trackCount} tracks",
                                    icon = Icons.Rounded.Folder,
                                    kind = CollectionKind.Folder,
                                    tracks = viewModel.tracksForFolder(folder.path),
                                    location = folder.path
                                )
                            )
                        }
                    }
                )
            }
        }

        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            Box(modifier = Modifier.listRowInset(density)) {
                TrackListItem(
                    track = track,
                    onClick = { onTrackClick(tracks, index) },
                    active = track.id == activeTrackId,
                    density = density
                )
            }
        }

        if (sortedFolders.isEmpty() && tracks.isEmpty()) {
            item(key = "browse_folder_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = "No tracks found",
                        style = FoamTheme.typography.body
                    )
                }
            }
        }
    }
}

/** One row of [CollectionArtistsScreen]: an artist plus how many of the source collection's albums it appears on. */
private data class CollectionArtistRow(
    val name: String,
    val albumCount: Int
)

private fun List<TrackEntity>.toCollectionArtistRows(): List<CollectionArtistRow> =
    distinctCollectionArtistNames()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
        .map { name ->
            val albumCount = filter { track ->
                listOfNotNull(track.artist, track.albumArtist).any { it.split("; ").contains(name) }
            }.map { it.album }.distinct().size
            CollectionArtistRow(name, albumCount)
        }

private fun collectionMembersTitle(kind: String, key: String): String = when (kind) {
    CollectionKind.Folder.name -> key.substringAfterLast('/').ifEmpty { key.substringAfterLast(':') }
    else -> key
}

private suspend fun BrowseViewModel.tracksForSource(kind: String, key: String): List<TrackEntity> =
    when (kind) {
        CollectionKind.Genre.name -> tracksForGenre(key)
        CollectionKind.Year.name -> tracksForYear(key.toIntOrNull() ?: 0)
        else -> tracksForFolder(key)
    }

/**
 * List of the artists held by one source collection (folder, genre or year),
 * opened from the collection modal's "See Artist" row when the collection
 * spans several artists. Tapping a row opens the artist's album list.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CollectionArtistsScreen(
    kind: String,
    key: String,
    onArtistClick: (artist: String, imageUrl: String?) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.NAME,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    var artistRows by remember { mutableStateOf<List<CollectionArtistRow>>(emptyList()) }
    val artistImages by viewModel.artistImages.collectAsState()

    LaunchedEffect(kind, key) {
        artistRows = withContext(Dispatchers.Default) {
            viewModel.tracksForSource(kind, key).toCollectionArtistRows()
        }
    }

    val sortedRows = rememberSortedList(artistRows, sortMode, sortInverted) {
        caseInsensitiveComparator { it.name }
    }

    CollectionListScreen(
        title = collectionMembersTitle(kind, key),
        subtitle = "Artists",
        itemCount = sortedRows.size,
        onBack = onBack,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange
    ) {
        items(sortedRows, key = { "artists_in_${kind}_${it.name}" }) { row ->
            Box(modifier = Modifier.listRowInset(density)) {
                CollectionListItem(
                    title = row.name,
                    subtitle = if (row.albumCount == 1) "1 album" else "${row.albumCount} albums",
                    imageUrl = artistImages[row.name],
                    imageIsEmbeddedArt = false,
                    onClick = { onArtistClick(row.name, artistImages[row.name]) },
                    active = CollectionId.artist(row.name) == activeCollectionId,
                    density = density,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    // Only the clicked row flies (see LocalActiveSharedCollection).
                    sharedElementKey = row.name
                        .takeIf { isActiveSharedCollection("artist", row.name) },
                    sharedElementPrefix = "artist",
                    immersiveFlightHero = LocalCollectionHeroLayoutLookup
                        .current(CollectionHeroTarget.Artist)
                        .isImmersive
                )
            }
        }

        if (artistRows.isEmpty()) {
            item(key = "artists_in_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = "No artists found",
                        style = FoamTheme.typography.body
                    )
                }
            }
        }
    }
}

/**
 * List of the albums held by one source collection (folder, genre or year),
 * opened from the collection modal's "See Album" row when the collection
 * spans several albums. Tapping a row opens the album detail screen.
 */
@Composable
fun CollectionAlbumsScreen(
    kind: String,
    key: String,
    onAlbumClick: (CollectionAlbumEntry) -> Unit,
    onBack: () -> Unit,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.NAME,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    var albumRows by remember { mutableStateOf<List<CollectionAlbumEntry>>(emptyList()) }

    LaunchedEffect(kind, key) {
        albumRows = withContext(Dispatchers.Default) {
            viewModel.tracksForSource(kind, key)
                .distinctCollectionAlbums()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.album })
        }
    }

    val sortedRows = rememberSortedList(albumRows, sortMode, sortInverted) { mode ->
        when (mode) {
            CollectionSortMode.ARTIST ->
                caseInsensitiveComparator<CollectionAlbumEntry> { it.artist ?: "" }
                    .then(caseInsensitiveComparator { it.album })
            else -> caseInsensitiveComparator { it.album }
        }
    }

    CollectionListScreen(
        title = collectionMembersTitle(kind, key),
        subtitle = "Albums",
        itemCount = sortedRows.size,
        onBack = onBack,
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = listOf(
            CollectionSortMode.NAME,
            CollectionSortMode.ARTIST,
            CollectionSortMode.RANDOM
        ),
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange
    ) {
        items(sortedRows, key = { "albums_in_${kind}_${it.album}" }) { entry ->
            Box(modifier = Modifier.listRowInset(density)) {
                CollectionListItem(
                    title = entry.album,
                    subtitle = entry.artist,
                    imageUrl = entry.albumArtPath,
                    onClick = { onAlbumClick(entry) },
                    active = CollectionId.album(entry.album) == activeCollectionId,
                    density = density
                )
            }
        }

        if (albumRows.isEmpty()) {
            item(key = "albums_in_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = "No albums found",
                        style = FoamTheme.typography.body
                    )
                }
            }
        }
    }
}
