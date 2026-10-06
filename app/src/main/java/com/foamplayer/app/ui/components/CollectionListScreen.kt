package com.foamplayer.app.ui.components

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.DensityOrganisationModal
import com.foamplayer.app.ui.modals.LocalCollectionFoamModalState
import com.foamplayer.app.ui.modals.OrganisationMenuButton
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Receiver handed to [CollectionListScreen]'s content lambda: it routes the
 * item declarations to whichever lazy layout the current density selected (a
 * plain [LazyColumn] for the ROW_* modes, a [LazyVerticalGrid] for the GRID_*
 * ones), so callers can keep a single item list for both layouts.
 */
interface MediaListContentScope {
    fun <T> items(
        items: List<T>,
        key: ((item: T) -> Any)? = null,
        itemContent: @Composable (item: T) -> Unit
    )

    fun <T> itemsIndexed(
        items: List<T>,
        key: ((index: Int, item: T) -> Any)? = null,
        itemContent: @Composable (index: Int, item: T) -> Unit
    )

    /** A standalone item; in grids it always spans the full line (headers, empty states, spacers). */
    fun item(key: Any? = null, content: @Composable () -> Unit)
}

private class ListMediaContentScope(private val scope: LazyListScope) : MediaListContentScope {
    override fun <T> items(items: List<T>, key: ((item: T) -> Any)?, itemContent: @Composable (item: T) -> Unit) {
        scope.items(items, key = key) { itemContent(it) }
    }

    override fun <T> itemsIndexed(
        items: List<T>,
        key: ((index: Int, item: T) -> Any)?,
        itemContent: @Composable (index: Int, item: T) -> Unit
    ) {
        scope.itemsIndexed(items, key = key) { index, item -> itemContent(index, item) }
    }

    override fun item(key: Any?, content: @Composable () -> Unit) {
        scope.item(key = key) { content() }
    }
}

private class GridMediaContentScope(private val scope: LazyGridScope) : MediaListContentScope {
    override fun <T> items(items: List<T>, key: ((item: T) -> Any)?, itemContent: @Composable (item: T) -> Unit) {
        scope.gridItems(items, key = key) { itemContent(it) }
    }

    override fun <T> itemsIndexed(
        items: List<T>,
        key: ((index: Int, item: T) -> Any)?,
        itemContent: @Composable (index: Int, item: T) -> Unit
    ) {
        scope.gridItemsIndexed(items, key = key) { index, item -> itemContent(index, item) }
    }

    override fun item(key: Any?, content: @Composable () -> Unit) {
        scope.item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
    }
}

/**
 * "List of lists" screen: the TrackListScreen equivalent for screens that hold a list
 * of collections (albums, genres, years, folders...) instead of tracks. Same layout:
 * back arrow in a top bar, artwork + title header, then the rows supplied by [content].
 *
 * [density] switches the body between the row list and a square-art grid
 (columns fixed by the GRID_* mode); [onDensityChange], when set, adds the
 * 3-dots top-bar button that opens the organisation modal.
 *
 * Every hero layout scrolls full-bleed: the lazy container is the screen's
 * root (its top content padding reserves the floating chrome zone, see
 * [CollectionTopBarRowHeight]) and the scroll top bar's gradient plus the
 * floating back/more row are drawn ON TOP of it, while the list itself fades
 * its own pixels transparent across the chrome zone
 * ([collectionListTopFade]) — rows dissolve into the chrome instead of being
 * hard-clipped, on every layout. Long content also gains the accent fast
 * scrollbar ([FoamScrollbar]) along the container's end edge unless
 * [showScrollbar] is false (albums/artists lists opt out).
 */
@Composable
fun CollectionListScreen(
    title: String,
    itemCount: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    headerImageUrl: String? = null,
    headerIcon: ImageVector? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    /** Sort modes the list supports; non-empty adds the sort section to the organisation modal. */
    sortOptions: List<CollectionSortMode> = emptyList(),
    /** The list's current sort (applied by the caller to the items it renders). */
    currentSort: CollectionSortMode = CollectionSortMode.NAME,
    /** When set, the sort radio list is shown in the organisation modal. */
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    /** Whether the current sort is inverted. */
    sortInverted: Boolean = false,
    /** When set, the "Inverse" checkbox is shown under the sort radio list. */
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    /** When set, the hero gains a search toggle + filter bar filtering the content lambda's items. */
    searchState: CollectionSearchState? = null,
    heroLayout: CollectionHeroLayout = LocalDefaultCollectionHeroLayout.current,
    /** Whether the long-content fast scrollbar rides the container's end edge. */
    showScrollbar: Boolean = true,
    content: MediaListContentScope.() -> Unit
) {
    var showOrganisationModal by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val topBarFraction = rememberCollectionScrollFraction(listState, gridState, density.isGrid)
    // Measured by the header item's layout pass (see [CollectionHeroImmersiveHeader]);
    // read untracked inside the offset lambda, and consumed by the search-open scroll effect
    // (see [CollectionSearchScrollToBarEffect]).
    val heroVisibleHeightPx = remember { mutableIntStateOf(0) }
    val heroScrollOffsetPx: () -> Int = {
        if (density.isGrid) {
            if (gridState.firstVisibleItemIndex == 0) {
                gridState.firstVisibleItemScrollOffset
            } else {
                heroVisibleHeightPx.intValue
            }
        } else {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset
            } else {
                heroVisibleHeightPx.intValue
            }
        }
    }
    // Root-space top y of the active scroll container, reported by the
    // listTopObserver below and consumed by the search-open scroll effect
    // (see [CollectionSearchScrollToBarEffect]).
    var listTopYInRootPx by remember { mutableFloatStateOf(Float.NaN) }
    val listTopObserver = Modifier.onGloballyPositioned {
        listTopYInRootPx = it.positionInRoot().y
    }
    // Search-open scroll: when the search toggle opens the hero's search bar
    // while the hero sits scrolled away (or hidden under the fixed chrome), the
    // list is brought up so the bar opens at the screen top — below the floating
    // back row, which covers every layout's list top (all of them scroll
    // full-bleed beneath it). The open bottom padding keeps the bar reachable
    // even when the collection has only a handful of rows (no scroll range
    // otherwise) and the rows visible above the keyboard.
    CollectionSearchScrollToBarEffect(
        searchState = searchState,
        listState = listState,
        gridState = gridState,
        useGrid = density.isGrid,
        listTopYInRootPx = { listTopYInRootPx },
        topChromeHeight = CollectionSearchImmersiveTopClearance
    )

    // Top content padding reserving the floating chrome zone: compact/normal
    // heroes must rest below the back/more row (their artwork is fully
    // visible), while the immersive heroes bleed under it on purpose.
    val heroTopPadding = if (heroLayout.isImmersive) 0.dp else CollectionTopBarRowHeight

    val itemsCountText = pluralStringResource(R.plurals.items_count, itemCount, itemCount)

    Box(modifier = modifier.fillMaxSize()) {
        if (heroLayout.isImmersive) {
            // Artwork layer only: bleeds under the status bar behind the list.
            // The text block is the list's own [CollectionHeroImmersiveHeader]
            // item (no play pill on a "list of lists" page) and fades with
            // this screen's transitions like the compact/normal heroes.
            CollectionHero(
                layout = heroLayout,
                title = title,
                subtitle = subtitle,
                statsText = itemsCountText,
                imageUrl = headerImageUrl,
                icon = headerIcon,
                onPlayAllClick = null,
                scrollOffsetPx = heroScrollOffsetPx,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        if (density.isGrid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(density.gridColumns),
                modifier = Modifier
                    .fillMaxSize()
                    .collectionListTopFade()
                    .then(listTopObserver),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 10.dp,
                    top = heroTopPadding,
                    end = 10.dp,
                    bottom = collectionSearchOpenBottomPadding(searchState)
                )
            ) {
                val gridScope = this
                item(
                    key = "collection_header",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    CollectionListHero(
                        heroLayout = heroLayout,
                        title = title,
                        subtitle = subtitle,
                        itemCount = itemCount,
                        imageUrl = headerImageUrl,
                        icon = headerIcon,
                        searchState = searchState,
                        onVisibleHeightPx = { heroVisibleHeightPx.intValue = it },
                        scrollOffsetPx = heroScrollOffsetPx
                    )
                }

                GridMediaContentScope(gridScope).content()

                item(key = "collection_bottom_spacer", span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(LocalBottomBarClearance.current))
                }
            }

            if (showScrollbar) {
                FoamScrollbar(
                    state = gridState,
                    // The thumb's track stays clear of the floating chrome zone
                    // above and the bottom-bar clearance below.
                    trackTopPadding = CollectionTopBarRowHeight,
                    trackBottomPadding = LocalBottomBarClearance.current,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .collectionListTopFade()
                    .then(listTopObserver),
                state = listState,
                contentPadding = PaddingValues(
                    top = heroTopPadding,
                    bottom = collectionSearchOpenBottomPadding(searchState)
                )
            ) {
                val listScope = this
                item(key = "collection_header") {
                    CollectionListHero(
                        heroLayout = heroLayout,
                        title = title,
                        subtitle = subtitle,
                        itemCount = itemCount,
                        imageUrl = headerImageUrl,
                        icon = headerIcon,
                        searchState = searchState,
                        onVisibleHeightPx = { heroVisibleHeightPx.intValue = it },
                        scrollOffsetPx = heroScrollOffsetPx
                    )
                }

                ListMediaContentScope(listScope).content()

                item(key = "collection_bottom_spacer") {
                    Spacer(modifier = Modifier.height(LocalBottomBarClearance.current))
                }
            }

            if (showScrollbar) {
                FoamScrollbar(
                    state = listState,
                    // The thumb's track stays clear of the floating chrome zone
                    // above and the bottom-bar clearance below.
                    trackTopPadding = CollectionTopBarRowHeight,
                    trackBottomPadding = LocalBottomBarClearance.current,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        // The scroll top bar's subtle gradient above the scrolling list (whose
        // own pixels fade transparent across the chrome zone), then the
        // floating back/more row above THAT — for every hero layout.
        CollectionScrollTopBar(
            title = title,
            fraction = topBarFraction,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        Box(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
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

                if (onDensityChange != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    OrganisationMenuButton(
                        contentDescription = stringResource(R.string.organisation),
                        onClick = { showOrganisationModal = true }
                    )
                }
            }
        }
    }

    if (onDensityChange != null) {
        DensityOrganisationModal(
            visible = showOrganisationModal,
            currentDensity = density,
            onSelectDensity = onDensityChange,
            sortOptions = if (onSortChange != null) sortOptions else emptyList(),
            currentSort = currentSort,
            onSelectSort = { onSortChange?.invoke(it) },
            sortInverted = sortInverted,
            onToggleSortInverted = { onSortInvertedChange?.invoke(it) },
            onDismiss = { showOrganisationModal = false }
        )
    }
}

/**
 * The list's leading item: the hero (no play button — a "list of lists" page
 * has nothing to play) for compact/normal layouts, [CollectionHeroImmersiveHeader]
 * for the immersive ones — the overlaid text block as real list content (its
 * [CollectionHeroImmersiveHeader.onVisibleHeightPx] keeps the hero artwork's
 * scroll-away constant in sync).
 */
@Composable
private fun CollectionListHero(
    heroLayout: CollectionHeroLayout,
    title: String,
    subtitle: String?,
    itemCount: Int,
    imageUrl: String?,
    icon: ImageVector?,
    searchState: CollectionSearchState?,
    onVisibleHeightPx: (Int) -> Unit,
    scrollOffsetPx: () -> Int
) {
    val itemsCountText = pluralStringResource(R.plurals.items_count, itemCount, itemCount)

    if (heroLayout.isImmersive) {
        CollectionHeroImmersiveHeader(
            layout = heroLayout,
            title = title,
            subtitle = subtitle,
            statsText = itemsCountText,
            searchState = searchState,
            onVisibleHeightPx = onVisibleHeightPx,
            scrollOffsetPx = scrollOffsetPx
        )
        return
    }

    CollectionHero(
        layout = heroLayout,
        title = title,
        subtitle = subtitle,
        statsText = itemsCountText,
        imageUrl = imageUrl,
        icon = icon,
        searchState = searchState,
        onPlayAllClick = null
    )
}

/**
 * TrackListItem equivalent for a collection (album, folder, genre, year...):
 * the shared [MediaListRow] scaffold at the ROW_* densities, or a square-art
 * [MediaGridCell] at the GRID_* ones. When a [sharedElementKey] and the
 * transition scopes are provided, the artwork and texts animate into the
 * matching album detail header using the same keys as SearchScreen's album
 * rows ("album-art-*", "album-title-*", "album-artist-*").
 *
 * [active] draws the "now playing" highlight: set it only when the queue was
 * actually started from this collection (PlaybackState.queueSourceId), never
 * merely because a track inside it happens to be playing.
 *
 * [immersiveFlightHero] styles the artwork as the DRAWN side of a close flight
 * from an immersive collection detail hero: during the flight the shared
 * element's overlay renders only this row's own content and overlay clip, so
 * the row must itself morph from the hero's square, faded look to its own
 * rounded, unfaded one (see [collectionRowFlightArtwork]). The hosts resolve
 * the flag from the destination hero's layout via
 * [LocalCollectionHeroLayoutLookup].
 *
 * [fullyRoundedArtwork] renders the artwork as a FULL circle (artist rows)
 * everywhere: the rest clip, the rest shadow shape and the flight morph's
 * landing radius all follow the circle, so a round source row lands on a round
 * hero (or morphs to the immersive hero's square with the radius riding the
 * flight) instead of popping between a circle and the album rounding.
 *
 * The loading placeholder icon resolves from the surrounding
 * [LocalMediaArtKind] scope (the music note by default), so hosts of one
 * collection kind scope themselves once instead of per-row.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CollectionListItem(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    active: Boolean = false,
    imageUrl: String? = null,
    imageIsEmbeddedArt: Boolean = true,
    icon: ImageVector? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementKey: String? = null,
    // Flight key namespace: album rows fly to/from album heroes, artist rows
    // (images resolved from Spotify) to/from the artist page's hero.
    sharedElementPrefix: String = "album",
    immersiveFlightHero: Boolean = false,
    fullyRoundedArtwork: Boolean = false
) {
    // Long-press opens the collection actions modal when FoamNavHost provides
    // one at the root; an explicit [onLongClick] always wins over that default.
    val collectionModalState = LocalCollectionFoamModalState.current
    val resolvedOnLongClick: (() -> Unit)? = when {
        onLongClick != null -> onLongClick
        collectionModalState != null -> {
            val openModal: () -> Unit = {
                collectionModalState.show(
                    CollectionInfo(
                        title = title,
                        subtitle = subtitle,
                        // Plain image URIs (playlist covers, Spotify artist cache
                        // files) ride through with [imageIsEmbeddedArt] == false so
                        // the modal header loads them directly instead of trying to
                        // extract a picture from them as audio containers.
                        imageUrl = imageUrl,
                        imageIsEmbeddedArt = imageIsEmbeddedArt,
                        icon = icon
                    )
                )
            }
            openModal
        }
        else -> null
    }

    // The percentage-based art shape equals the row radius at any artwork size
    // (half the size times the rounding percentage), so rows and grids — and
    // both ends of a shared-element transition — round identically. Artists
    // render fully round instead.
    val elementKey = sharedElementKey
    val artShape: Shape = if (fullyRoundedArtwork) CircleShape else AlbumArtShape()
    val highlightBackground = activeRowHighlight(active, imageUrl)

    // Close-flight shadow hand-off (mirrors TrackListItem's player-dismiss
    // handling): while a collection detail hero closes back onto THIS tagged
    // row, the flying artwork casts the row's static drop shadow itself,
    // elevation riding the flight (see [collectionRowFlightArtwork]'s
    // castsFlightShadow). The scaffold's static shadow — drawn by
    // [MediaListRow]/[MediaGridCell] OUTSIDE the sharedElement modifier —
    // would otherwise sit at the target position from the first flight frame
    // ("a static shadow at the target image position while the animation is
    // playing"), so it steps aside for the flight and resumes on the settle
    // frame, where the flight layer's elevation lands on exactly the same
    // value — an identical-strength hand-off.
    val closeFlightActive = rememberCollectionCloseFlightActive(
        sharedTransitionScope = sharedTransitionScope?.takeIf { elementKey != null },
        animatedVisibilityScope = animatedVisibilityScope
    ).value
    val artworkRestElevation = MediaItemArtElevation
    val artworkElevation = if (closeFlightActive) 0.dp else artworkRestElevation

    val sharedArtModifier = Modifier
        .collectionRowFlightArtwork(
            key = "$sharedElementPrefix-art-$elementKey",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = elementKey != null,
            restShape = artShape,
            immersiveFlightHero = immersiveFlightHero,
            fullyRounded = fullyRoundedArtwork,
            castsFlightShadow = true
        )
        .background(FoamTheme.colors.surfaceVariant)

    val sharedTitleModifier = Modifier.rowSharedText(
        key = "$sharedElementPrefix-title-$elementKey",
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        enabled = elementKey != null,
        boundsTransform = AlbumArtBoundsTransform
    )

    val sharedSubtitleModifier = if (subtitle != null) {
        Modifier.rowSharedText(
            key = "$sharedElementPrefix-artist-$elementKey",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = elementKey != null,
            boundsTransform = AlbumArtBoundsTransform
        )
    } else {
        Modifier
    }

    if (density.isGrid) {
        MediaGridCell(
            modifier = modifier,
            onClick = onClick,
            onLongClick = resolvedOnLongClick,
            artworkModifier = sharedArtModifier,
            artworkShape = artShape,
            artworkContent = {
                CollectionArtwork(
                    title,
                    imageUrl,
                    imageIsEmbeddedArt,
                    icon,
                    placeholderSize = 32.dp
                )
            },
            title = title,
            subtitle = subtitle,
            highlightBackground = highlightBackground,
            titleModifier = sharedTitleModifier,
            subtitleModifier = sharedSubtitleModifier,
            artworkElevation = artworkElevation
        )
        return
    }

    MediaListRow(
        modifier = modifier,
        onClick = onClick,
        onLongClick = resolvedOnLongClick,
        artworkModifier = sharedArtModifier,
        artworkShape = artShape,
        artworkContent = {
            CollectionArtwork(
                title,
                imageUrl,
                imageIsEmbeddedArt,
                icon,
                placeholderSize = 20.dp
            )
        },
        title = title,
        subtitle = subtitle,
        density = density,
        highlightBackground = highlightBackground,
        titleModifier = sharedTitleModifier,
        subtitleModifier = sharedSubtitleModifier,
        artworkElevation = artworkElevation
    )
}

/** Artwork content shared by CollectionListItem's row and grid renderings: image, icon or letter fallback. */
@Composable
private fun CollectionArtwork(
    title: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    icon: ImageVector?,
    placeholderSize: Dp
) {
    val context = LocalContext.current
    if (imageUrl != null) {
        val albumArtRequest = remember(imageUrl, imageIsEmbeddedArt) {
            ImageRequest.Builder(context)
                .data(
                    // Embedded art: the model is an audio file whose picture is
                    // extracted. Otherwise the model is a plain image (cache file).
                    if (imageIsEmbeddedArt) EmbeddedAlbumArt(imageUrl.toUri()) else imageUrl
                )
                .size(AlbumArtRequestSize)
                .memoryCacheKey(albumArtMemoryCacheKey(imageUrl))
                .build()
        }
        AlbumArtImage(
            model = albumArtRequest,
            contentDescription = stringResource(R.string.album_art),
            modifier = Modifier.fillMaxSize(),
            placeholderSize = placeholderSize
        )
    } else if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(24.dp)
        )
    } else {
        BasicText(
            text = title.take(1).uppercase(),
            style = FoamTheme.typography.titleMedium
        )
    }
}