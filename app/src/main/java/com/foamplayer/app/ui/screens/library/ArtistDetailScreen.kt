package com.foamplayer.app.ui.screens.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import coil.request.ImageRequest
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.AlbumArtImage
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.AlbumArtShape
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.components.CollectionListItem
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache

/** Number of columns of the artist's album grid. */
private const val ArtistAlbumGridColumns = 3

/**
 * An artist page rendered like a collection: artwork + title + play-all header,
 * then the artist's top tracks, then their albums in a grid. The artwork comes
 * from the artist's first track in alphabetical order and also drives the
 * screen's backdrop and accent color.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    onAlbumClick: (AlbumSearchResult) -> Unit,
    onAllSongsClick: () -> Unit,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    activeCollectionId: String? = null,
    activeTrackId: Long? = null,
    onListBackdropArt: (String?, Boolean) -> Unit = { _, _ -> },
    viewModel: ArtistDetailViewModel = hiltViewModel()
) {
    val albums by viewModel.albums.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val topTracks by viewModel.topTracks.collectAsState()

    LaunchedEffect(artistName) {
        viewModel.loadArtistAlbums(artistName)
        viewModel.loadArtistTracks(artistName)
    }

    // The first track in alphabetical order provides the collection's artwork
    // (header image, backdrop and accent color).
    val headerArtPath = tracks.firstOrNull()?.albumArtPath

    LaunchedEffect(headerArtPath) {
        onListBackdropArt(headerArtPath, true)
    }

    val context = LocalContext.current
    LaunchedEffect(headerArtPath) {
        AlbumArtPaletteCache.ensure(this, context, headerArtPath)
    }
    val accentColor = headerArtPath?.let { AlbumArtPaletteCache.colors[it] }
        ?: FoamTheme.accentColor

    Column(modifier = Modifier.fillMaxSize()) {
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
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(ArtistAlbumGridColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp)
        ) {
            item(key = "artist_header", span = { GridItemSpan(maxLineSpan) }) {
                ArtistHeaderSection(
                    title = artistName,
                    imageUrl = headerArtPath,
                    trackCount = tracks.size,
                    accentColor = accentColor,
                    onPlayAllClick = { if (tracks.isNotEmpty()) onTrackClick(tracks, 0) }
                )
            }

            if (topTracks.isNotEmpty()) {
                item(key = "artist_top_tracks_header", span = { GridItemSpan(maxLineSpan) }) {
                    ArtistSectionHeader(
                        title = "Top Tracks",
                        accentColor = accentColor,
                        showArrow = true,
                        onClick = onAllSongsClick
                    )
                }

                gridItems(
                    topTracks,
                    key = { "artist_top_${it.id}" },
                    span = { GridItemSpan(maxLineSpan) }
                ) { track ->
                    TrackListItem(
                        track = track,
                        onClick = {
                            val index = tracks.indexOfFirst { it.id == track.id }
                            if (index >= 0) onTrackClick(tracks, index)
                        },
                        active = track.id == activeTrackId,
                        density = MediaDensity.ROW_NORMAL
                    )
                }
            }

            if (albums.isNotEmpty()) {
                item(key = "artist_albums_header", span = { GridItemSpan(maxLineSpan) }) {
                    ArtistSectionHeader(
                        title = "Albums",
                        accentColor = accentColor,
                        showArrow = false
                    )
                }

                gridItems(albums, key = { "artist_album_${it.album}" }) { album ->
                    CollectionListItem(
                        title = album.album,
                        subtitle = album.artist,
                        imageUrl = album.albumArtPath,
                        onClick = { onAlbumClick(album) },
                        active = CollectionId.album(album.album) == activeCollectionId,
                        density = MediaDensity.GRID_NORMAL,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        sharedElementKey = album.album
                    )
                }
            }

            item(key = "artist_albums_spacer", span = { GridItemSpan(maxLineSpan) }) {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

/**
 * Collection-style header: artwork on the left (the artist's first track's
 * embedded art), title and track count on the right above a play-all button.
 */
@Composable
private fun ArtistHeaderSection(
    title: String,
    imageUrl: String?,
    trackCount: Int,
    accentColor: Color,
    onPlayAllClick: () -> Unit
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(AlbumArtShape())
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                val headerArtRequest = remember(imageUrl) {
                    ImageRequest.Builder(context)
                        .data(EmbeddedAlbumArt(imageUrl.toUri()))
                        .size(AlbumArtRequestSize)
                        .crossfade(false)
                        .build()
                }
                AlbumArtImage(
                    model = headerArtRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    placeholderSize = 40.dp
                )
            } else {
                BasicText(
                    text = title.take(1).uppercase(),
                    style = FoamTheme.typography.titleLarge.copy(fontSize = 48.sp)
                )
            }
        }

        Spacer(modifier = Modifier.width(20.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = "$trackCount Tracks",
                style = FoamTheme.typography.body,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accentColor)
                    .clickable { onPlayAllClick() }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = FoamTheme.icons.current.play,
                        contentDescription = null,
                        tint = FoamTheme.colors.background,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicText(
                        text = "Play All",
                        style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.colors.background)
                    )
                }
            }
        }
    }
}

/**
 * Section header row: title on the left, and when [showArrow] a right-pointing
 * arrow on the right; the whole row is clickable ([onClick], e.g. "Top Tracks"
 * opening the artist's full song list).
 */
@Composable
private fun ArtistSectionHeader(
    title: String,
    accentColor: Color,
    showArrow: Boolean,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = title,
            style = FoamTheme.typography.titleMedium.copy(color = accentColor),
            modifier = Modifier.weight(1f)
        )
        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = "See all",
                tint = accentColor,
                modifier = Modifier
                    .size(24.dp)
                    .padding(2.dp)
            )
        }
    }
}
