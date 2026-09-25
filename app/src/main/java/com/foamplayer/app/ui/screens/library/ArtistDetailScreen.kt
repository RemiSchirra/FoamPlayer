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
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.AlbumArtImage
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.AlbumArtShape
import com.foamplayer.app.ui.components.CollectionHero
import com.foamplayer.app.ui.components.CollectionHeroImmersiveHeader
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.components.CollectionListItem
import com.foamplayer.app.ui.components.CollectionScrollTopBar
import com.foamplayer.app.ui.components.CollectionSearchState
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.components.collectionHeroOverlayLayer
import com.foamplayer.app.ui.components.rememberCollectionHeroFlightFraction
import com.foamplayer.app.ui.components.rememberCollectionScrollFraction
import com.foamplayer.app.ui.components.rememberCollectionSearchState
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.navigation.isActiveSharedCollection
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
    seedImageUrl: String? = null,
    onAlbumClick: (AlbumSearchResult) -> Unit,
    onAllSongsClick: () -> Unit,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionVisibilityScope: AnimatedVisibilityScope,
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
        viewModel.loadArtistImage(artistName)
    }

    // Hero search: while the bar is open, typing filters both the top-tracks
    // section and the albums grid live (title/artist, album name respectively);
    // an empty query shows everything.
    val searchState = rememberCollectionSearchState()
    val shownTopTracks = topTracks.filter { searchState.matches(it.title, it.artist) }
    val shownAlbums = albums.filter { searchState.matches(it.album, it.artist) }

    // The first track in alphabetical order is the fallback artwork source.
    val headerArtPath = tracks.firstOrNull()?.albumArtPath

    // Spotify-resolved artist picture: seeded through the route by the source
    // screen (available on the very first frame so the shared-element flight
    // never flashes the letter fallback), then confirmed from the cache.
    val cachedArtistImage by viewModel.artistImage.collectAsState()
    val artistImage = cachedArtistImage ?: seedImageUrl?.takeIf { it.isNotBlank() }

    // The hero art (artist picture, else the first track's album art) drives
    // the hero image, the screen's backdrop and the accent color.
    val heroImageUrl = artistImage ?: headerArtPath
    // The Spotify picture is a plain cache file; the fallback is a track file
    // whose artwork must be extracted from the audio container.
    val heroImageIsEmbeddedArt = artistImage == null

    LaunchedEffect(heroImageUrl, heroImageIsEmbeddedArt) {
        onListBackdropArt(heroImageUrl, heroImageIsEmbeddedArt)
    }

    val context = LocalContext.current
    LaunchedEffect(heroImageUrl, heroImageIsEmbeddedArt) {
        AlbumArtPaletteCache.ensure(this, context, heroImageUrl, heroImageIsEmbeddedArt)
    }
    val accentColor = heroImageUrl?.let { AlbumArtPaletteCache.colors[it] }
        ?: FoamTheme.accentColor

    val gridState = rememberLazyGridState()
    val topBarFraction = rememberCollectionScrollFraction(gridState)
    // Flight fractions of this screen's two transitions: the nav flight
    // (open/close of the artist page itself) and the player-container flight
    // (full player expanding over / collapsing back onto this page). The hero
    // chrome and the back row are lifted into the shared-element overlay while
    // any transition is active (collectionHeroOverlayLayer), and the overlay
    // escapes the host hierarchy's own fades — so their alpha must multiply
    // both fractions explicitly or they would pop at flight ends.
    val navFlightFraction =
        rememberCollectionHeroFlightFraction(sharedTransitionScope, animatedVisibilityScope)
    val containerFlightFraction =
        rememberCollectionHeroFlightFraction(sharedTransitionScope, playerTransitionVisibilityScope)
    val transitionAlpha: () -> Float = {
        (1f - navFlightFraction.value) * (1f - containerFlightFraction.value)
    }
    // Measured by the header item's layout pass (see [CollectionHeroImmersiveHeader]);
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
            // Artwork layer only: bleeds under the status bar behind the grid.
            // The overlay block (title/play pill) is the grid's own
            // [CollectionHeroImmersiveHeader] item, so it fades with this
            // screen's transitions exactly like the compact/normal heroes and
            // takes taps directly.
            CollectionHero(
                layout = heroLayout,
                title = artistName,
                subtitle = null,
                statsText = "${tracks.size} Tracks",
                imageUrl = heroImageUrl,
                imageIsEmbeddedArt = heroImageIsEmbeddedArt,
                onPlayAllClick = null,
                accentColor = accentColor,
                transitionAlpha = transitionAlpha,
                scrollOffsetPx = heroScrollOffsetPx,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedElementKey = artistName,
                sharedElementPrefix = "artist",
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
                        imageUrl = heroImageUrl,
                        imageIsEmbeddedArt = heroImageIsEmbeddedArt,
                        trackCount = tracks.size,
                        accentColor = accentColor,
                        onPlayAllClick = { if (tracks.isNotEmpty()) onTrackClick(tracks, 0) },
                        onShuffleAllClick = {
                            if (tracks.isNotEmpty()) onTrackClick(tracks.shuffled(), 0)
                        },
                        searchState = searchState,
                        onVisibleHeightPx = { heroVisibleHeightPx.intValue = it },
                        overlayTextAlpha = { 1f - topBarFraction() },
                        transitionAlpha = transitionAlpha,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        sharedElementKey = artistName
                    )
                }

            if (shownTopTracks.isNotEmpty()) {
                item(key = "artist_top_tracks_header", span = { GridItemSpan(maxLineSpan) }) {
                    ArtistSectionHeader(
                        title = "Top Tracks",
                        accentColor = accentColor,
                        showArrow = true,
                        onClick = onAllSongsClick
                    )
                }

                gridItems(
                    shownTopTracks,
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

            if (shownAlbums.isNotEmpty()) {
                item(key = "artist_albums_header", span = { GridItemSpan(maxLineSpan) }) {
                    ArtistSectionHeader(
                        title = "Albums",
                        accentColor = accentColor,
                        showArrow = false
                    )
                }

                gridItems(shownAlbums, key = { "artist_album_${it.album}" }) { album ->
                    CollectionListItem(
                        title = album.album,
                        subtitle = album.artist,
                        imageUrl = album.albumArtPath,
                        onClick = { onAlbumClick(album) },
                        active = CollectionId.album(album.album) == activeCollectionId,
                        density = MediaDensity.GRID_NORMAL,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        // Only an album the user taps flies (into the album
                        // detail hero): gating on the nav root's tag keeps the
                        // albums from pairing with their twins on the source
                        // screen while THIS page opens.
                        sharedElementKey = album.album
                            .takeIf { isActiveSharedCollection("album", album.album) },
                        immersiveFlightHero = LocalCollectionHeroLayoutLookup
                            .current(CollectionHeroTarget.Album)
                            .isImmersive
                    )
                }
            }

            item(key = "artist_albums_spacer", span = { GridItemSpan(maxLineSpan) }) {
                Spacer(modifier = Modifier.height(LocalBottomBarClearance.current))
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
            // the host hierarchy's fades (see transitionAlpha above).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .collectionHeroOverlayLayer(sharedTransitionScope)
                    .graphicsLayer { alpha = transitionAlpha() }
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
 * The artist page's leading grid item: the full hero (artwork, title and
 * play-all pill) for compact/normal layouts, [CollectionHeroImmersiveHeader]
 * for the immersive ones — the overlaid text block and play pill as real
 * interactive grid content (its [CollectionHeroImmersiveHeader.onVisibleHeightPx]
 * keeps the hero artwork's scroll-away constant in sync).
 *
 * The artwork and title are the shared-element landing points of the clicked
 * artist card's flight ("artist-art-*" / "artist-title-*", see HomeScreen):
 * heroes always register their keys, the source tags only the clicked card.
 */
@Composable
private fun ArtistHero(
    heroLayout: CollectionHeroLayout,
    title: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    trackCount: Int,
    accentColor: Color,
    onPlayAllClick: () -> Unit,
    onShuffleAllClick: (() -> Unit)?,
    searchState: CollectionSearchState?,
    onVisibleHeightPx: (Int) -> Unit,
    overlayTextAlpha: () -> Float,
    transitionAlpha: () -> Float,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?
) {
    if (heroLayout.isImmersive) {
        CollectionHeroImmersiveHeader(
            layout = heroLayout,
            title = title,
            subtitle = null,
            statsText = "$trackCount Tracks",
            accentColor = accentColor,
            onPlayAllClick = onPlayAllClick,
            onShuffleAllClick = onShuffleAllClick,
            searchState = searchState,
            onVisibleHeightPx = onVisibleHeightPx,
            overlayTextAlpha = overlayTextAlpha,
            transitionAlpha = transitionAlpha,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedElementKey = sharedElementKey,
            sharedElementPrefix = "artist"
        )
        return
    }

    CollectionHero(
        layout = heroLayout,
        title = title,
        subtitle = null,
        statsText = "$trackCount Tracks",
        imageUrl = imageUrl,
        imageIsEmbeddedArt = imageIsEmbeddedArt,
        onPlayAllClick = onPlayAllClick,
        onShuffleAllClick = onShuffleAllClick,
        searchState = searchState,
        accentColor = accentColor,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedElementKey = sharedElementKey,
        sharedElementPrefix = "artist"
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
