package com.foamplayer.app.ui.screens.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
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
import com.foamplayer.app.ui.components.CollectionSearchScrollToBarEffect
import com.foamplayer.app.ui.components.CollectionSearchImmersiveTopClearance
import com.foamplayer.app.ui.components.CollectionSearchState
import com.foamplayer.app.ui.components.CollectionTopBarRowHeight
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.components.LocalMediaArtKind
import com.foamplayer.app.ui.components.MediaArtKind
import com.foamplayer.app.ui.components.MediaArtKindScope
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.components.collectionHeroOverlayLayer
import com.foamplayer.app.ui.components.collectionListTopFade
import com.foamplayer.app.ui.components.isNavTransitionInProgress
import com.foamplayer.app.ui.components.collectionSearchOpenBottomPadding
import com.foamplayer.app.ui.components.rememberCollectionHeroFlightFraction
import com.foamplayer.app.ui.components.rememberCollectionScrollFraction
import com.foamplayer.app.ui.components.rememberCollectionSearchState
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.adaptAccentForTheme
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.navigation.isActiveSharedCollection
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache

/** Number of columns of the artist page's album grid. */
private const val ArtistAlbumGridColumns = 3

/**
 * An artist page rendered like a collection: artwork + title + play-all header,
 * then the artist's top tracks, then their albums in a grid. The artwork comes
 * from the artist's first track in alphabetical order and also drives the
 * screen's backdrop and accent color.
 *
 * Every hero layout scrolls full-bleed: the grid's top content padding
 * ([CollectionTopBarRowHeight] on compact/normal, 0 on immersive — whose
 * artwork bleeds under the status bar) reserves the floating chrome zone, the
 * scroll top bar's subtle gradient plus the floating back row are drawn ON TOP
 * of the grid, and the grid itself fades its own pixels transparent across the
 * chrome zone ([collectionListTopFade]) — content dissolves into the chrome
 * instead of being hard-clipped, on every layout.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    seedImageUrl: String? = null,
    seedImageIsEmbeddedArt: Boolean = false,
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

    // Hero search: one query filters BOTH sections together. A track matches
    // when its title, artist or album name matches (typing an album name
    // reveals its tracks); an album matches when its name/artist matches or
    // any of its tracks matched (typing a track title reveals its album).
    // An empty query shows everything. While searching, the tracks section
    // expands from the top 5 to EVERY matching track.
    val searchState = rememberCollectionSearchState()
    val searchActive = searchState.open && searchState.query.isNotBlank()
    val searchSource = if (searchActive) tracks else topTracks
    val shownTopTracks = remember(searchSource, searchActive) {
        searchSource.filter { track ->
            !searchActive || listOfNotNull(track.title, track.artist, track.album).any {
                it.contains(searchState.query, ignoreCase = true)
            }
        }
    }
    val shownAlbums = remember(albums, searchActive) {
        albums.filter { album ->
            !searchActive || album.album.contains(searchState.query, ignoreCase = true) ||
                    album.artist?.contains(searchState.query, ignoreCase = true) == true
        }
    }

    // The first track in alphabetical order is the fallback artwork source.
    val headerArtPath = tracks.firstOrNull()?.albumArtPath

    // Artist picture: the cached Spotify image (seeded through the route by the
    // source screen so the shared-element flight never flashes the letter
    // fallback), else the automatic first-track fallback.
    val cachedArtistImage by viewModel.artistImage.collectAsState()
    val artistImage = cachedArtistImage ?: seedImageUrl?.takeIf { it.isNotBlank() }
    // A seed carries its own load mode; otherwise Spotify results are plain
    // cache files while the first-track fallback is embedded art.
    val artistImageIsEmbeddedArt = artistImage == null ||
            (cachedArtistImage == null && seedImageIsEmbeddedArt)

    // The hero art (artist picture, else the first track's album art) drives
    // the hero image, the screen's backdrop and the accent color.
    val heroImageUrl = artistImage ?: headerArtPath
    val heroImageIsEmbeddedArt = artistImage == null || artistImageIsEmbeddedArt

    LaunchedEffect(heroImageUrl, heroImageIsEmbeddedArt) {
        onListBackdropArt(heroImageUrl, heroImageIsEmbeddedArt)
    }

    val context = LocalContext.current
    LaunchedEffect(heroImageUrl, heroImageIsEmbeddedArt) {
        AlbumArtPaletteCache.ensure(this, context, heroImageUrl, heroImageIsEmbeddedArt)
    }
    val accentColor = heroImageUrl?.let { AlbumArtPaletteCache.colors[it] }
        ?.adaptAccentForTheme(FoamTheme.isDark)
        ?: FoamTheme.accentColor

    val gridState = rememberLazyGridState()
    val topBarFraction = rememberCollectionScrollFraction(gridState)
    // Flight fractions of this screen's two transitions: the nav flight
    // (open/close of the artist page itself) and the player-container flight
    // (full player expanding over / collapsing back onto this page). The
    // floating back row is lifted into the shared-element overlay while any
    // transition is active (collectionHeroOverlayLayer), and the overlay
    // escapes the host hierarchy's own fades — so its alpha must multiply
    // both fractions explicitly or it would pop at flight ends.
    val navFlightFraction =
        rememberCollectionHeroFlightFraction(sharedTransitionScope, animatedVisibilityScope)
    val containerFlightFraction =
        rememberCollectionHeroFlightFraction(
            sharedTransitionScope,
            playerTransitionVisibilityScope
        )
    val transitionAlpha: () -> Float = {
        (1f - navFlightFraction.value) * (1f - containerFlightFraction.value)
    }
    // Measured by the header item's layout pass (see [CollectionHeroImmersiveHeader]);
    // read untracked inside the offset lambda, so it is always the settled value.
    // SAVEABLE: the header is a lazy grid item — when this page is restored
    // scrolled PAST it (returning from a deeper page), the header is not
    // composed, so nothing re-reports the height. A plain remember would start
    // at 0 and the immersive artwork layer would render at offset 0 — the hero
    // flashing over the list as if unscrolled — until the first scroll composes
    // the header again and the offset jumps, vanishing the hero instantly. The
    // saved height restores the correct scrolled-away offset on the first frame.
    var heroVisibleHeightPx by rememberSaveable { mutableStateOf(0) }
    val heroScrollOffsetPx: () -> Int = {
        if (gridState.firstVisibleItemIndex == 0) {
            gridState.firstVisibleItemScrollOffset
        } else {
            heroVisibleHeightPx
        }
    }
    // Root-space top y of the grid container, reported by the gridTopObserver
    // below and consumed by the search-open scroll effect (see
    // [CollectionSearchScrollToBarEffect]).
    var gridTopYInRootPx by remember { mutableFloatStateOf(Float.NaN) }
    val gridTopObserver = Modifier.onGloballyPositioned {
        gridTopYInRootPx = it.positionInRoot().y
    }
    // Search-open scroll: when the search toggle opens the hero's search bar
    // while the hero sits scrolled away (or hidden under the fixed chrome), the
    // grid is brought up so the bar opens at the screen top — below the floating
    // back row, which covers the grid top on every hero layout (the grid
    // scrolls full-bleed beneath it). The open bottom padding keeps the bar
    // reachable even when the artist has only a handful of rows (no scroll range
    // otherwise) and the rows visible above the keyboard.
    CollectionSearchScrollToBarEffect(
        searchState = searchState,
        listState = null,
        gridState = gridState,
        useGrid = true,
        listTopYInRootPx = { gridTopYInRootPx },
        topChromeHeight = CollectionSearchImmersiveTopClearance
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // The whole page depicts the artist: the hero portrait (and any artwork
        // reusing it) loads under the person placeholder. The albums grid
        // re-scopes itself below.
        MediaArtKindScope(MediaArtKind.ARTIST) {
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
                    statsText = pluralTrackCount(tracks.size),
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
                    modifier = Modifier.align(Alignment.TopStart),
                    // The artist image is fully round at its sources; the immersive
                    // hero morphs that circle to its own square (radius 0) look
                    // during the flight.
                    fullyRoundedArtwork = true
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(ArtistAlbumGridColumns),
                modifier = Modifier
                    .fillMaxSize()
                    .collectionListTopFade()
                    .then(gridTopObserver),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 10.dp,
                    top = if (heroLayout.isImmersive) 0.dp else CollectionTopBarRowHeight,
                    end = 10.dp,
                    bottom = collectionSearchOpenBottomPadding(searchState)
                )
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
                        onVisibleHeightPx = { heroVisibleHeightPx = it },
                        scrollOffsetPx = heroScrollOffsetPx,
                        transitionAlpha = transitionAlpha,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        sharedElementKey = artistName
                    )
                }

                if (shownTopTracks.isNotEmpty()) {
                    item(key = "artist_top_tracks_header", span = { GridItemSpan(maxLineSpan) }) {
                        ArtistSectionHeader(
                            title = if (searchActive) stringResource(R.string.tracks)
                            else stringResource(R.string.stats_top_tracks),
                            accentColor = accentColor,
                            showArrow = !searchActive,
                            onClick = if (searchActive) null else onAllSongsClick
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
                                // The clicked track settles the search session: the bar
                                // closes (query cleared, keyboard hidden) as playback
                                // starts, while the queue still hands over the search
                                // context (the artist's full track list, indexed from it).
                                searchState.close()
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
                            title = stringResource(R.string.albums),
                            accentColor = accentColor,
                            showArrow = false
                        )
                    }

                    gridItems(shownAlbums, key = { "artist_album_${it.album}" }) { album ->
                        MediaArtKindScope(MediaArtKind.ALBUM) {
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
                }

                item(key = "artist_albums_spacer", span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(LocalBottomBarClearance.current))
                }
            }
        }

        // The scroll top bar's subtle gradient above the scrolling grid (whose
        // own pixels fade transparent across the chrome zone), then the
        // floating back row above THAT — for every hero layout. The row is
        // lifted into the shared-element overlay during transitions so the
        // flying artwork passes beneath the back button; its alpha mirrors
        // the host hierarchy's fades (see transitionAlpha above).
        // Lifted into the overlay (zIndex 0.5f: above the flying artwork, beneath
        // the back button) with the flight alpha multiplied in: drawn in place it
        // sat in the status-bar strip outside the NavHost bounds and dropped out
        // on the first frame of the close instead of fading with the screen.
        CollectionScrollTopBar(
            title = artistName,
            fraction = { topBarFraction() * transitionAlpha() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .collectionHeroOverlayLayer(
                    sharedTransitionScope = sharedTransitionScope,
                    zIndexInOverlay = 0.5f,
                    renderInOverlay = { animatedVisibilityScope.isNavTransitionInProgress() }
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .collectionHeroOverlayLayer(
                    sharedTransitionScope = sharedTransitionScope,
                    renderInOverlay = { animatedVisibilityScope.isNavTransitionInProgress() }
                )
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
                    contentDescription = stringResource(R.string.back),
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
 * The artwork renders fully round (matching the artist rows' circular rest
 * look) on compact/normal layouts; the immersive layouts keep their square,
 * faded hero and morph the circle into it during the flight.
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
    scrollOffsetPx: () -> Int,
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
            statsText = pluralTrackCount(trackCount),
            accentColor = accentColor,
            onPlayAllClick = onPlayAllClick,
            onShuffleAllClick = onShuffleAllClick,
            searchState = searchState,
            onVisibleHeightPx = onVisibleHeightPx,
            scrollOffsetPx = scrollOffsetPx,
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
        statsText = pluralTrackCount(trackCount),
        imageUrl = imageUrl,
        imageIsEmbeddedArt = imageIsEmbeddedArt,
        onPlayAllClick = onPlayAllClick,
        onShuffleAllClick = onShuffleAllClick,
        searchState = searchState,
        accentColor = accentColor,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedElementKey = sharedElementKey,
        sharedElementPrefix = "artist",
        // Non-immersive artist heroes rest on the same full circle as the
        // source rows; the flight keeps circle -> circle (no radius pop).
        fullyRoundedArtwork = true
    )
}

/** Localized "N tracks" subtitle from the [track_count] plurals. */
@Composable
private fun pluralTrackCount(count: Int): String =
    pluralStringResource(R.plurals.track_count, count = count, count)

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
                contentDescription = stringResource(R.string.home_see_all),
                tint = accentColor,
                modifier = Modifier
                    .size(24.dp)
                    .padding(2.dp)
            )
        }
    }
}