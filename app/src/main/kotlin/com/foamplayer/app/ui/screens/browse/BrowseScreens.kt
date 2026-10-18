package com.foamplayer.app.ui.screens.browse

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.TrackSortOptions
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.modals.CollectionAlbumEntry
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.CollectionKind
import com.foamplayer.app.ui.components.CollectionListItem
import com.foamplayer.app.ui.components.CollectionListScreen
import com.foamplayer.app.ui.components.MediaArtKind
import com.foamplayer.app.ui.components.MediaArtKindScope
import com.foamplayer.app.ui.modals.LocalCollectionFoamModalState
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.components.TrackCenterRequest
import com.foamplayer.app.ui.components.TrackListScreen
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.modals.distinctCollectionAlbums
import com.foamplayer.app.ui.modals.distinctCollectionArtistNames
import com.foamplayer.app.ui.components.caseInsensitiveComparator
import com.foamplayer.app.ui.components.rememberCollectionSearchState
import com.foamplayer.app.ui.components.rememberFilteredList
import com.foamplayer.app.ui.components.rememberSortedList
import com.foamplayer.app.ui.components.rememberSortedTracks
import com.foamplayer.app.ui.components.scrollSectionLetter
import com.foamplayer.app.ui.navigation.isActiveSharedCollection
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.distant.DistantUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Horizontal inset of one list row; grid cells get their own symmetric padding instead. */
private fun Modifier.listRowInset(density: MediaDensity): Modifier =
    padding(horizontal = if (density.isGrid) 0.dp else 6.dp)

/**
 * Fast-scroll section-letter source for a name-alphabetical collection list:
 * maps a lazy item index (item 0 is the hero header) onto [items]' [text].
 * Null when [mode] isn't alphabetical, which hides the scrollbar's bubble.
 */
private fun <T> alphabeticalCollectionLabels(
    mode: CollectionSortMode,
    items: List<T>,
    text: (T) -> String?
): ((Int) -> String?)? = if (mode == CollectionSortMode.NAME) {
    { index -> scrollSectionLetter(items.getOrNull(index - 1)?.let(text)) }
} else {
    null
}

/**
 * Album/artist browse screens: each screen scopes its artwork kind once
 * ([MediaArtKindScope]) so every row's cover/portrait loads under the
 * collection's own placeholder icon — no per-row parameter threading.
 */
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
    // Hero search: filters the displayed albums while the bar is open. The
    // raw filter used to run on EVERY recomposition of the screen (play/pause
    // flips, active-track changes...), allocating a fresh multi-thousand-item
    // list on the main thread and re-launching the async sort below each time.
    // rememberFilteredList keeps the incoming list identity while the bar is
    // closed/blank and moves big passes off the main thread.
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
            CollectionSortMode.ARTIST ->
                caseInsensitiveComparator(AlbumSearchResult::artist)
                    .then(caseInsensitiveComparator(AlbumSearchResult::album))
            else -> caseInsensitiveComparator(AlbumSearchResult::album)
        }
    }

    MediaArtKindScope(MediaArtKind.ALBUM) {
        CollectionListScreen(
            title = stringResource(R.string.albums),
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
            onSortInvertedChange = onSortInvertedChange,
            searchState = searchState,
            labelForIndex = alphabeticalCollectionLabels(sortMode, sortedAlbums) { it.album }
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
}

@Composable
fun ArtistsBrowseScreen(
    onArtistClick: (artist: String, imageUrl: String?, imageIsEmbeddedArt: Boolean) -> Unit,
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
    val searchState = rememberCollectionSearchState()
    val searchedArtists = rememberFilteredList(artists, searchState) {
        searchState.matches(it)
    }
    val sortedArtists = rememberSortedList(
        searchedArtists,
        sortMode,
        sortInverted
    ) {
        caseInsensitiveComparator { it }
    }

    MediaArtKindScope(MediaArtKind.ARTIST) {
        CollectionListScreen(
            title = stringResource(R.string.artists),
            itemCount = sortedArtists.size,
            onBack = onBack,
            density = density,
            onDensityChange = onDensityChange,
            sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
            currentSort = sortMode,
            onSortChange = onSortChange,
            sortInverted = sortInverted,
            onSortInvertedChange = onSortInvertedChange,
            searchState = searchState,
            labelForIndex = alphabeticalCollectionLabels(sortMode, sortedArtists) { it }
        ) {
            items(sortedArtists, key = { "browse_artist_$it" }) { artist ->
                Box(modifier = Modifier.listRowInset(density)) {
                    CollectionListItem(
                        title = artist,
                        subtitle = null,
                        imageUrl = artistImages[artist]?.imageUrl,
                        imageIsEmbeddedArt = artistImages[artist]?.isEmbeddedArt ?: false,
                        onClick = {
                            artistImages[artist]?.let { cover ->
                                onArtistClick(artist, cover.imageUrl, cover.isEmbeddedArt)
                            } ?: onArtistClick(artist, null, false)
                        },
                        active = CollectionId.artist(artist) == activeCollectionId,
                        density = density,
                        onLongClick = {
                            longPressScope.launch {
                                collectionModalState?.show(
                                    CollectionInfo(
                                        title = artist,
                                        // Either a Spotify cache file (plain) or an
                                        // audio file (embedded art): the flag routes
                                        // the modal header to the right Coil model.
                                        imageUrl = artistImages[artist]?.imageUrl,
                                        imageIsEmbeddedArt = artistImages[artist]?.isEmbeddedArt ?: false,
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
                            .isImmersive,
                        // Artist rows render fully round on every screen.
                        fullyRoundedArtwork = true
                    )
                }
            }
        }
    }
}

@Composable
fun ComposersBrowseScreen(
    onComposerClick: (String) -> Unit,
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
    val composers by viewModel.composers.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()
    val searchState = rememberCollectionSearchState()
    val searchedComposers = rememberFilteredList(composers, searchState) {
        searchState.matches(it)
    }
    val sortedComposers = rememberSortedList(
        searchedComposers,
        sortMode,
        sortInverted
    ) {
        caseInsensitiveComparator { it }
    }

    CollectionListScreen(
            title = stringResource(R.string.composers),
            itemCount = sortedComposers.size,
            onBack = onBack,
            headerIcon = Icons.Rounded.Person,
            density = density,
            onDensityChange = onDensityChange,
            sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
            currentSort = sortMode,
            onSortChange = onSortChange,
            sortInverted = sortInverted,
            onSortInvertedChange = onSortInvertedChange,
            searchState = searchState,
            labelForIndex = alphabeticalCollectionLabels(sortMode, sortedComposers) { it }
        ) {
        items(sortedComposers, key = { "browse_composer_$it" }) { composer ->
            Box(modifier = Modifier.listRowInset(density)) {
                CollectionListItem(
                    title = composer,
                    subtitle = null,
                    icon = Icons.Rounded.Person,
                    onClick = { onComposerClick(composer) },
                    active = CollectionId.composer(composer) == activeCollectionId,
                    density = density,
                    onLongClick = {
                        longPressScope.launch {
                            collectionModalState?.show(
                                CollectionInfo(
                                    title = composer,
                                    icon = Icons.Rounded.Person,
                                    kind = CollectionKind.Composer,
                                    tracks = viewModel.tracksForComposer(composer)
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
    val searchState = rememberCollectionSearchState()
    val searchedGenres = rememberFilteredList(genres, searchState) {
        searchState.matches(it)
    }
    val sortedGenres = rememberSortedList(
        searchedGenres,
        sortMode,
        sortInverted
    ) {
        caseInsensitiveComparator { it }
    }

    CollectionListScreen(
            title = stringResource(R.string.genres),
            itemCount = sortedGenres.size,
            onBack = onBack,
            headerIcon = Icons.Rounded.Category,
            density = density,
            onDensityChange = onDensityChange,
            sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
            currentSort = sortMode,
            onSortChange = onSortChange,
            sortInverted = sortInverted,
            onSortInvertedChange = onSortInvertedChange,
            searchState = searchState,
            labelForIndex = alphabeticalCollectionLabels(sortMode, sortedGenres) { it }
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
    val searchState = rememberCollectionSearchState()
    // The year's text is built inside the matcher, so the closed-bar case
    // (identity passthrough) no longer allocates a string per element.
    val searchedYears = rememberFilteredList(years, searchState) {
        searchState.matches(it.toString())
    }
    val sortedYears = rememberSortedList(
        searchedYears,
        sortMode,
        sortInverted
    ) {
        compareBy { it }
    }

    CollectionListScreen(
            title = stringResource(R.string.years),
            itemCount = sortedYears.size,
            onBack = onBack,
            headerIcon = Icons.Rounded.CalendarToday,
            density = density,
            onDensityChange = onDensityChange,
            sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
            currentSort = sortMode,
            onSortChange = onSortChange,
            sortInverted = sortInverted,
            onSortInvertedChange = onSortInvertedChange,
            searchState = searchState,
            labelForIndex = alphabeticalCollectionLabels(sortMode, sortedYears) { it.toString() }
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
        title = stringResource(R.string.all_tracks),
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
    isPlaybackActive: Boolean = true,
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
        heroLayout = heroLayout,
        immersiveMode = immersiveMode
    )
}

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
    isPlaybackActive: Boolean = true,
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
        heroLayout = heroLayout,
        immersiveMode = immersiveMode
    )
}

@Composable
fun ComposerTracksScreen(
    composer: String,
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
    isPlaybackActive: Boolean = true,
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    immersiveMode: Boolean = false,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val tracks by viewModel.composerTracks.collectAsState()

    LaunchedEffect(composer) {
        viewModel.loadComposerTracks(composer)
    }

    val sortedTracks = rememberSortedTracks(tracks, sortMode, sortInverted)

    TrackListScreen(
        title = composer,
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
    remember(path) { viewModel.loadFolderContent(path); viewModel }
    val content by viewModel.folderContent.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()

    // Distant server roots get a "Jellyfin:example.com" title derived from the
    // server URL's domain instead of the raw server id segment of their URI.
    val title = path?.let { viewModel.folderTitle(it) } ?: stringResource(R.string.folders)

    val folders = content?.folders.orEmpty()
    val searchState = rememberCollectionSearchState()
    val searchedFolders = rememberFilteredList(folders, searchState) {
        searchState.matches(it.name)
    }
    val sortedFolders = rememberSortedList(
        searchedFolders,
        sortMode,
        sortInverted
    ) {
        caseInsensitiveComparator { it.name }
    }
    val tracks = content?.tracks.orEmpty()
    val shownTracks = rememberFilteredList(tracks, searchState) {
        searchState.matches(it.title, it.artist)
    }

    CollectionListScreen(
        title = title,
        itemCount = sortedFolders.size + shownTracks.size,
        onBack = onBack,
        headerIcon = if (path != null && DistantUris.isDistant(path)) {
            Icons.Rounded.Cloud
        } else {
            Icons.Rounded.Folder
        },
        density = density,
        onDensityChange = onDensityChange,
        sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
        currentSort = sortMode,
        onSortChange = onSortChange,
        sortInverted = sortInverted,
        onSortInvertedChange = onSortInvertedChange,
        searchState = searchState,
        // Folders come first (alphabetical), then the folder's own tracks.
        labelForIndex = if (sortMode == CollectionSortMode.NAME) {
            { index ->
                val pos = index - 1
                val folder = sortedFolders.getOrNull(pos)
                scrollSectionLetter(
                    if (folder != null) {
                        folder.name
                    } else {
                        shownTracks.getOrNull(pos - sortedFolders.size)?.title
                    }
                )
            }
        } else {
            null
        }
    ) {
        items(sortedFolders, key = { "browse_folder_${it.path}" }) { folder ->
            val folderSubtitle = pluralTrackCount(folder.trackCount)
            Box(modifier = Modifier.listRowInset(density)) {
                CollectionListItem(
                    title = folder.name,
                    subtitle = folderSubtitle,
                    icon = if (folder.isDistant) Icons.Rounded.Cloud else Icons.Rounded.Folder,
                    onClick = {
                        // Build the destination's listing from this screen's
                        // warm snapshot now, so its first frame has content.
                        viewModel.precacheFolder(folder.path)
                        onFolderClick(folder.path)
                    },
                    active = CollectionId.folder(folder.path) == activeCollectionId,
                    density = density,
                    onLongClick = {
                        longPressScope.launch {
                            collectionModalState?.show(
                                CollectionInfo(
                                    title = folder.name,
                                    subtitle = folderSubtitle,
                                    icon = if (folder.isDistant) Icons.Rounded.Cloud else Icons.Rounded.Folder,
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

        itemsIndexed(shownTracks, key = { _, track -> track.id }) { index, track ->
            Box(modifier = Modifier.listRowInset(density)) {
                TrackListItem(
                    track = track,
                    onClick = { onTrackClick(shownTracks, index) },
                    active = track.id == activeTrackId,
                    density = density
                )
            }
        }

        // "No tracks found" is only truthful once the folder list has loaded;
        // the pre-load frame (content == null) must render blank instead of
        // flashing the empty state before the listing arrives.
        if (content != null && sortedFolders.isEmpty() && shownTracks.isEmpty()) {
            item(key = "browse_folder_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = stringResource(R.string.no_tracks_found),
                        style = FoamTheme.typography.body
                    )
                }
            }
        }
    }
}

/** Localized "N tracks" subtitle from the [track_count] plurals. */
@Composable
internal fun pluralTrackCount(count: Int): String =
    pluralStringResource(R.plurals.track_count, count = count, count)

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

private fun collectionMembersTitle(
    kind: String,
    key: String,
    viewModel: BrowseViewModel
): String = when (kind) {
    CollectionKind.Folder.name -> viewModel.folderTitle(key)
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
@Composable
fun CollectionArtistsScreen(
    kind: String,
    key: String,
    onArtistClick: (artist: String, imageUrl: String?, imageIsEmbeddedArt: Boolean) -> Unit,
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

    val searchState = rememberCollectionSearchState()
    val searchedRows = rememberFilteredList(artistRows, searchState) {
        searchState.matches(it.name)
    }
    val sortedRows = rememberSortedList(
        searchedRows,
        sortMode,
        sortInverted
    ) {
        caseInsensitiveComparator { it.name }
    }

    MediaArtKindScope(MediaArtKind.ARTIST) {
        CollectionListScreen(
            title = collectionMembersTitle(kind, key, viewModel),
            subtitle = stringResource(R.string.artists),
            itemCount = sortedRows.size,
            onBack = onBack,
            density = density,
            onDensityChange = onDensityChange,
            sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
            currentSort = sortMode,
            onSortChange = onSortChange,
            sortInverted = sortInverted,
            onSortInvertedChange = onSortInvertedChange,
            searchState = searchState,
            labelForIndex = alphabeticalCollectionLabels(sortMode, sortedRows) { it.name }
        ) {
            items(sortedRows, key = { "artists_in_${kind}_${it.name}" }) { row ->
                Box(modifier = Modifier.listRowInset(density)) {
                    CollectionListItem(
                        title = row.name,
                        subtitle = pluralAlbumCount(row.albumCount),
                        imageUrl = artistImages[row.name]?.imageUrl,
                        imageIsEmbeddedArt = artistImages[row.name]?.isEmbeddedArt ?: false,
                        onClick = {
                            val cover = artistImages[row.name]
                            onArtistClick(row.name, cover?.imageUrl, cover?.isEmbeddedArt ?: false)
                        },
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
                            .isImmersive,
                        // Artist rows render fully round on every screen.
                        fullyRoundedArtwork = true
                    )
                }
            }
        }

        if (artistRows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(R.string.no_artists_found),
                    style = FoamTheme.typography.body
                )
            }
        }
    }
}

/** Localized "N albums" subtitle from the [album_count] plurals. */
@Composable
private fun pluralAlbumCount(count: Int): String =
    pluralStringResource(R.plurals.album_count, count = count, count)

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

    val searchState = rememberCollectionSearchState()
    val searchedRows = rememberFilteredList(albumRows, searchState) {
        searchState.matches(it.album, it.artist)
    }
    val sortedRows = rememberSortedList(
        searchedRows,
        sortMode,
        sortInverted
    ) { mode ->
        when (mode) {
            CollectionSortMode.ARTIST ->
                caseInsensitiveComparator<CollectionAlbumEntry> { it.artist ?: "" }
                    .then(caseInsensitiveComparator { it.album })
            else -> caseInsensitiveComparator { it.album }
        }
    }

    MediaArtKindScope(MediaArtKind.ALBUM) {
        CollectionListScreen(
            title = collectionMembersTitle(kind, key, viewModel),
            subtitle = stringResource(R.string.albums),
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
            onSortInvertedChange = onSortInvertedChange,
            searchState = searchState,
            labelForIndex = alphabeticalCollectionLabels(sortMode, sortedRows) { it.album }
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
        }

        if (albumRows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(R.string.no_albums_found),
                    style = FoamTheme.typography.body
                )
            }
        }
    }
}
