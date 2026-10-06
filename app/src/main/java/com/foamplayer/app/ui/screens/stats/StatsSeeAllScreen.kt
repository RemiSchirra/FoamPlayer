package com.foamplayer.app.ui.screens.stats

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.TrackSortOptions
import com.foamplayer.app.data.db.dao.TopAlbumStat
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.components.MediaArtKind
import com.foamplayer.app.ui.components.MediaArtKindScope
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.CollectionKind
import com.foamplayer.app.ui.components.CollectionListScreen
import com.foamplayer.app.ui.components.CollectionListItem
import com.foamplayer.app.ui.modals.LocalCollectionFoamModalState
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
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
 * "See all" page behind one of the stats overview's ranked cards (top tracks /
 * artists / albums): the period's full ranked list on the app's standard media
 * scaffolds, so rows behave exactly like the browse and Home see-all pages —
 * tap plays the song / opens the album or artist, long-press opens the actions
 * modal, and density/sort/organisation work per view. The period is fixed by
 * the route; top tracks are uncapped.
 *
 * Each section scopes its artwork kind once ([MediaArtKindScope]): artist rows
 * load their portraits under the person placeholder, album rows their covers
 * under the album disc — no per-row parameter threading.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun StatsSeeAllScreen(
    onBack: () -> Unit,
    onArtistClick: (artist: String, imageUrl: String?, imageIsEmbeddedArt: Boolean) -> Unit,
    onAlbumClick: (album: String, artUrl: String?, artist: String?) -> Unit,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    activeTrackId: Long? = null,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.NAME,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    sharedTrackId: Long? = null,
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    viewModel: StatsSeeAllViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()

    when (viewModel.kind) {
        StatsTopKind.TRACKS -> {
            val sortedTracks = rememberSortedTracks(
                tracks.map { it.track },
                sortMode,
                sortInverted
            )
            TrackListScreen(
                title = stringResource(R.string.stats_top_tracks),
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

        StatsTopKind.ARTISTS -> {
            val artistImages by viewModel.artistImages.collectAsState()
            val searchState = rememberCollectionSearchState()
            // Search filtering keeps the incoming list identity while the bar
            // is closed/blank (see rememberFilteredList): an unconditional
            // .filter here re-allocated the ranked list on every recomposition
            // and re-launched the async sort below each time.
            val searchedArtists = rememberFilteredList(artists, searchState) {
                searchState.matches(it.name)
            }
            val sortedArtists = rememberSortedList(
                searchedArtists,
                sortMode,
                sortInverted
            ) {
                caseInsensitiveComparator { it.name }
            }
            MediaArtKindScope(MediaArtKind.ARTIST) {
                CollectionListScreen(
                    title = stringResource(R.string.stats_top_artists),
                    subtitle = periodSubtitle(viewModel.period),
                    itemCount = sortedArtists.size,
                    onBack = onBack,
                    headerIcon = FoamTheme.icons.current.stats,
                    density = density,
                    onDensityChange = onDensityChange,
                    sortOptions = listOf(CollectionSortMode.NAME, CollectionSortMode.RANDOM),
                    currentSort = sortMode,
                    onSortChange = onSortChange,
                    sortInverted = sortInverted,
                    onSortInvertedChange = onSortInvertedChange,
                    searchState = searchState
                ) {
                    items(sortedArtists, key = { "stats_artist_${it.name}" }) { artist ->
                        Box(modifier = Modifier.listRowInset(density)) {
                            CollectionListItem(
                                title = artist.name,
                                subtitle = null,
                                imageUrl = artistImages[artist.name]?.imageUrl,
                                imageIsEmbeddedArt = artistImages[artist.name]?.isEmbeddedArt ?: false,
                                onClick = {
                                    val cover = artistImages[artist.name]
                                    onArtistClick(
                                        artist.name,
                                        cover?.imageUrl,
                                        cover?.isEmbeddedArt ?: false
                                    )
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
                                // Only the clicked row flies (see LocalActiveSharedCollection).
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

                    if (sortedArtists.isEmpty()) {
                        item(key = "stats_artists_empty") {
                            StatsSeeAllEmptyState()
                        }
                    }
                }
            }
        }

        StatsTopKind.ALBUMS -> {
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
                        compareBy(String.CASE_INSENSITIVE_ORDER, TopAlbumStat::artist)
                            .then(compareBy(String.CASE_INSENSITIVE_ORDER, TopAlbumStat::album))
                    else -> compareBy(String.CASE_INSENSITIVE_ORDER, TopAlbumStat::album)
                }
            }
            MediaArtKindScope(MediaArtKind.ALBUM) {
                CollectionListScreen(
                    title = stringResource(R.string.stats_top_albums),
                    subtitle = periodSubtitle(viewModel.period),
                    itemCount = sortedAlbums.size,
                    onBack = onBack,
                    headerIcon = FoamTheme.icons.current.stats,
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
                    searchState = searchState
                ) {
                    items(sortedAlbums, key = { "stats_album_${it.album}" }) { album ->
                        Box(modifier = Modifier.listRowInset(density)) {
                            CollectionListItem(
                                title = album.album,
                                subtitle = album.artist,
                                imageUrl = album.albumArtPath,
                                onClick = { onAlbumClick(album.album, album.albumArtPath, album.artist) },
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

                    if (sortedAlbums.isEmpty()) {
                        item(key = "stats_albums_empty") {
                            StatsSeeAllEmptyState()
                        }
                    }
                }
            }
        }
    }
}

/** Centered placeholder for an empty see-all page. */
@Composable
private fun StatsSeeAllEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = stringResource(R.string.stats_empty_body),
            style = FoamTheme.typography.body
        )
    }
}