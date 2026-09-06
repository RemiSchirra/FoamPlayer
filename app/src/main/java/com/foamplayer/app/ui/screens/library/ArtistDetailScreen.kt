package com.foamplayer.app.ui.screens.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.ui.screens.search.AlbumSearchResultItem
import com.foamplayer.app.ui.theme.FoamTheme

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    onAlbumClick: (AlbumSearchResult) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val albums by viewModel.albums.collectAsState()

    LaunchedEffect(artistName) {
        viewModel.loadArtistAlbums(artistName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onBack() }
                    .padding(4.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            BasicText(
                text = artistName,
                style = FoamTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item {
                BasicText(
                    text = "Albums",
                    style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.accentColor),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(albums) { album ->
                AlbumSearchResultItem(
                    album = album,
                    onClick = { onAlbumClick(album) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}
