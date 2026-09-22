package com.foamplayer.app.ui.screens.stats

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
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
import com.foamplayer.app.ui.components.CollectionInfo
import com.foamplayer.app.ui.components.CollectionKind
import com.foamplayer.app.ui.components.CollectionListScreen
import com.foamplayer.app.ui.components.CollectionListItem
import com.foamplayer.app.ui.components.LocalCollectionFoamModalState
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.components.TrackListScreen
import com.foamplayer.app.ui.components.caseInsensitiveComparator
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
 * tap plays the song / opens the album or artist, long-press opens the actions
 * modal, and density/sort/organisation work per view. The period is fixed by
 * the route; top tracks are uncapped.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun StatsSeeAllScreen(
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
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
            val sortedArtists = rememberSortedList(artists, sortMode, sortInverted) {
                caseInsensitiveComparator { it.name }
            }
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
                onSortInvertedChange = onSortInvertedChange
            ) {
                items(sortedArtists, key = { "stats_artist_${it.name}" }) { artist ->
                    Box(modifier = Modifier.listRowInset(density)) {
                        CollectionListItem(
                            title = artist.name,
                            subtitle = null,
                            onClick = { onArtistClick(artist.name) },
                            active = CollectionId.artist(artist.name) == activeCollectionId,
                            density = density,
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
                            }
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

        StatsTopKind.ALBUMS -> {
            val sortedAlbums = rememberSortedList(albums, sortMode, sortInverted) { mode ->
                when (mode) {
                    CollectionSortMode.ARTIST ->
                        compareBy(String.CASE_INSENSITIVE_ORDER, TopAlbumStat::artist)
                            .then(compareBy(String.CASE_INSENSITIVE_ORDER, TopAlbumStat::album))
                    else -> compareBy(String.CASE_INSENSITIVE_ORDER, TopAlbumStat::album)
                }
            }
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
                onSortInvertedChange = onSortInvertedChange
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
