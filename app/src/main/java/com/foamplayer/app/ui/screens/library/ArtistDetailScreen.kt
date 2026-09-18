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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.AlbumArtImage
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.AlbumArtShape
import com.foamplayer.app.ui.components.CollectionHero
import com.foamplayer.app.ui.components.CollectionHeroImmersiveSpacer
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.components.CollectionListItem
import com.foamplayer.app.ui.components.CollectionScrollTopBar
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.components.collectionHeroOverlayLayer
import com.foamplayer.app.ui.components.rememberCollectionHeroFlightFraction
import com.foamplayer.app.ui.components.rememberCollectionScrollFraction
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
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
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

    val gridState = rememberLazyGridState()
    val topBarFraction = rememberCollectionScrollFraction(gridState)
    // Flight fraction of this screen's nav transition: the back row is lifted
    // into the shared-element overlay while a transition is active (see
    // collectionHeroOverlayLayer), and the overlay escapes the hierarchy's own
    // fade — so the row fades with the flight explicitly.
    val flightFraction =
        rememberCollectionHeroFlightFraction(sharedTransitionScope, animatedVisibilityScope)
    // Measured by the header spacer's layout pass (see [CollectionHeroImmersiveSpacer]);
    // read untracked inside the offset lambda, so it is always the settled value.
    val heroVisibleHeightPx = remember { mutableIntStateOf(0) }
    val heroScrollOffsetPx: () -> Int = {
        if (gridState.firstVisibleItemIndex == 0) {
            gridState.firstVisibleItemScrollOffset
        } else {
            heroVisibleHeightPx.intValue
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (heroLayout.isImmersive) {
            CollectionHero(
                layout = heroLayout,
                title = artistName,
                subtitle = null,
                statsText = "${tracks.size} Tracks",
                imageUrl = headerArtPath,
                onPlayAllClick = { if (tracks.isNotEmpty()) onTrackClick(tracks, 0) },
                accentColor = accentColor,
                overlayTextAlpha = { 1f - topBarFraction() },
                scrollOffsetPx = heroScrollOffsetPx,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        if (!heroLayout.isImmersive) {
            // Compact/Normal: composed BEFORE the Column (whose first child is
            // the top-bar row) so the gradient can never dim the button; the
            // grid never scrolls into the bar's zone in these layouts.
            CollectionScrollTopBar(
                title = artistName,
                fraction = topBarFraction,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (!heroLayout.isImmersive) {
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
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(ArtistAlbumGridColumns),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                item(key = "artist_header", span = { GridItemSpan(maxLineSpan) }) {
                    ArtistHero(
                        heroLayout = heroLayout,
                        title = artistName,
                        imageUrl = headerArtPath,
                        trackCount = tracks.size,
                        accentColor = accentColor,
                        onPlayAllClick = { if (tracks.isNotEmpty()) onTrackClick(tracks, 0) },
                        onVisibleHeightPx = { heroVisibleHeightPx.intValue = it }
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

        if (heroLayout.isImmersive) {
            // Above the scrolling grid (so content dims under it) but below the
            // overlay back row.
            CollectionScrollTopBar(
                title = artistName,
                fraction = topBarFraction,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Lifted into the shared-element overlay during transitions so the
            // flying artwork passes beneath the back button; the alpha mirrors
            // the host hierarchy's fade.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .collectionHeroOverlayLayer(sharedTransitionScope)
                    .graphicsLayer { alpha = 1f - flightFraction.value }
            ) {
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
            }
        }
    }
}

/**
 * The artist page's leading grid item: the hero for compact/normal layouts, a
 * spacer measured against the immersive hero's real overlay block otherwise
 * (its [CollectionHeroImmersiveSpacer.onVisibleHeightPx] keeps the hero's
 * scroll-away constant in sync).
 */
@Composable
private fun ArtistHero(
    heroLayout: CollectionHeroLayout,
    title: String,
    imageUrl: String?,
    trackCount: Int,
    accentColor: Color,
    onPlayAllClick: () -> Unit,
    onVisibleHeightPx: (Int) -> Unit
) {
    if (heroLayout.isImmersive) {
        CollectionHeroImmersiveSpacer(
            layout = heroLayout,
            title = title,
            subtitle = null,
            statsText = "$trackCount Tracks",
            accentColor = accentColor,
            onPlayAllClick = onPlayAllClick,
            onVisibleHeightPx = onVisibleHeightPx
        )
        return
    }

    CollectionHero(
        layout = heroLayout,
        title = title,
        subtitle = null,
        statsText = "$trackCount Tracks",
        imageUrl = imageUrl,
        onPlayAllClick = onPlayAllClick,
        accentColor = accentColor
    )
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
