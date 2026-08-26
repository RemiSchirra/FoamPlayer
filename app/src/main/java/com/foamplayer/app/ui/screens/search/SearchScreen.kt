package com.foamplayer.app.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.ui.screens.library.TrackListItem
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun SearchScreen(
    searchViewModel: SearchViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    var query by remember { mutableStateOf("") }
    val uiState by searchViewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        BasicText(
            text = "Search Library",
            style = FoamTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Search Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(FoamTheme.colors.surfaceVariant)
                .padding(16.dp)
        ) {
            if (query.isEmpty()) {
                BasicText(
                    text = "Search tracks, artists, albums...",
                    style = FoamTheme.typography.body
                )
            }
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    searchViewModel.search(it)
                },
                textStyle = FoamTheme.typography.titleMedium,
                cursorBrush = SolidColor(FoamTheme.colors.primaryAccent),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Artists Section
            if (uiState.artists.isNotEmpty()) {
                item {
                    SectionHeader("Artists")
                }
                items(uiState.artists) { artist ->
                    ArtistSearchResultItem(artist = artist, onClick = { /* TODO: Navigate to artist */ })
                }
            }

            // Albums Section
            if (uiState.albums.isNotEmpty()) {
                item {
                    SectionHeader("Albums")
                }
                items(uiState.albums) { album ->
                    AlbumSearchResultItem(album = album, onClick = { /* TODO: Navigate to album */ })
                }
            }

            // Tracks Section
            if (uiState.tracks.isNotEmpty()) {
                item {
                    SectionHeader("Tracks")
                }
                items(uiState.tracks) { track ->
                    TrackListItem(
                        track = track,
                        onClick = { playerViewModel.playQueue(listOf(track), 0) }
                    )
                }
            }
            
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    BasicText(
        text = title,
        style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.colors.primaryAccent),
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun ArtistSearchResultItem(artist: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = artist.take(1).uppercase(),
                style = FoamTheme.typography.titleMedium
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        BasicText(
            text = artist,
            style = FoamTheme.typography.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AlbumSearchResultItem(album: AlbumSearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (album.albumArtPath != null) {
                AsyncImage(
                    model = album.albumArtPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = "A",
                    style = FoamTheme.typography.caption
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            BasicText(
                text = album.album,
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = album.artist,
                style = FoamTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
