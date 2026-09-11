package com.foamplayer.app.ui.screens.browse

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.CollectionListItem
import com.foamplayer.app.ui.components.CollectionListScreen
import com.foamplayer.app.ui.components.TrackListScreen
import com.foamplayer.app.ui.screens.library.TrackListItem
import com.foamplayer.app.ui.theme.FoamTheme

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumsBrowseScreen(
    onAlbumClick: (AlbumSearchResult) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val albums by viewModel.albums.collectAsState()

    CollectionListScreen(
        title = "Albums",
        itemCount = albums.size,
        onBack = onBack
    ) {
        items(albums, key = { "browse_album_${it.album}" }) { album ->
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                CollectionListItem(
                    title = album.album,
                    subtitle = album.artist,
                    imageUrl = album.albumArtPath,
                    onClick = { onAlbumClick(album) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedElementKey = album.album
                )
            }
        }
    }
}

@Composable
fun ArtistsBrowseScreen(
    onArtistClick: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val artists by viewModel.artists.collectAsState()

    CollectionListScreen(
        title = "Artists",
        itemCount = artists.size,
        onBack = onBack
    ) {
        items(artists, key = { "browse_artist_$it" }) { artist ->
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                CollectionListItem(
                    title = artist,
                    subtitle = null,
                    onClick = { onArtistClick(artist) }
                )
            }
        }
    }
}

@Composable
fun GenresBrowseScreen(
    onGenreClick: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val genres by viewModel.genres.collectAsState()

    CollectionListScreen(
        title = "Genres",
        itemCount = genres.size,
        onBack = onBack,
        headerIcon = Icons.Rounded.Category
    ) {
        items(genres, key = { "browse_genre_$it" }) { genre ->
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                CollectionListItem(
                    title = genre,
                    subtitle = null,
                    icon = Icons.Rounded.Category,
                    onClick = { onGenreClick(genre) }
                )
            }
        }
    }
}

@Composable
fun YearsBrowseScreen(
    onYearClick: (Int) -> Unit,
    onBack: () -> Unit,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val years by viewModel.years.collectAsState()

    CollectionListScreen(
        title = "Years",
        itemCount = years.size,
        onBack = onBack,
        headerIcon = Icons.Rounded.CalendarToday
    ) {
        items(years, key = { "browse_year_$it" }) { year ->
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                CollectionListItem(
                    title = year.toString(),
                    subtitle = null,
                    icon = Icons.Rounded.CalendarToday,
                    onClick = { onYearClick(year) }
                )
            }
        }
    }
}

@Composable
fun GenreTracksScreen(
    genre: String,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    activeTrackId: Long?,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val tracks by viewModel.genreTracks.collectAsState()

    LaunchedEffect(genre) {
        viewModel.loadGenreTracks(genre)
    }

    TrackListScreen(
        title = genre,
        tracks = tracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = { if (tracks.isNotEmpty()) onTrackClick(tracks, 0) },
        onBack = onBack,
        activeTrackId = activeTrackId
    )
}

@Composable
fun YearTracksScreen(
    year: Int,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    activeTrackId: Long?,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val tracks by viewModel.yearTracks.collectAsState()

    LaunchedEffect(year) {
        viewModel.loadYearTracks(year)
    }

    TrackListScreen(
        title = year.toString(),
        tracks = tracks,
        onTrackClick = onTrackClick,
        onPlayAllClick = { if (tracks.isNotEmpty()) onTrackClick(tracks, 0) },
        onBack = onBack,
        activeTrackId = activeTrackId
    )
}

@Composable
fun FolderBrowseScreen(
    path: String?,
    onFolderClick: (String) -> Unit,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    activeTrackId: Long?,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val content by viewModel.folderContent.collectAsState()

    LaunchedEffect(path) {
        viewModel.loadFolderContent(path)
    }

    val title = path
        ?.substringAfterLast('/')
        ?.ifEmpty { path.substringAfterLast(':') }
        ?: "Folders"

    val folders = content?.folders.orEmpty()
    val tracks = content?.tracks.orEmpty()

    CollectionListScreen(
        title = title,
        itemCount = folders.size + tracks.size,
        onBack = onBack,
        headerIcon = Icons.Rounded.Folder
    ) {
        items(folders, key = { "browse_folder_${it.path}" }) { folder ->
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                CollectionListItem(
                    title = folder.name,
                    subtitle = "${folder.trackCount} tracks",
                    icon = Icons.Rounded.Folder,
                    onClick = { onFolderClick(folder.path) }
                )
            }
        }

        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                TrackListItem(
                    track = track,
                    onClick = { onTrackClick(tracks, index) },
                    active = track.id == activeTrackId
                )
            }
        }

        if (folders.isEmpty() && tracks.isEmpty()) {
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
