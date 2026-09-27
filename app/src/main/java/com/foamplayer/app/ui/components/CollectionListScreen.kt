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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
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
    content: MediaListContentScope.() -> Unit
) {
    var showOrganisationModal by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val topBarFraction = rememberCollectionScrollFraction(listState, gridState, density.isGrid)
    // Measured by the header item's layout pass (see [CollectionHeroImmersiveHeader]);
    // read untracked inside the offset lambda, and consumed by the search-open
    // scroll effect (see [CollectionSearchScrollToBarEffect]).
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
    // back row on the immersive layouts; the non-immersive lists start below
    // their fixed top-bar row, so no extra clearance is needed there. The open
    // bottom padding keeps the bar reachable even when the collection has only
    // a handful of rows (no scroll range otherwise) and the rows visible above
    // the keyboard.
    CollectionSearchScrollToBarEffect(
        searchState = searchState,
        listState = listState,
        gridState = gridState,
        useGrid = density.isGrid,
        listTopYInRootPx = { listTopYInRootPx },
        topChromeHeight = if (heroLayout.isImmersive) CollectionSearchImmersiveTopClearance else 0.dp
    )

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
                statsText = "$itemCount items",
                imageUrl = headerImageUrl,
                icon = headerIcon,
                onPlayAllClick = null,
                scrollOffsetPx = heroScrollOffsetPx,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        if (!heroLayout.isImmersive) {
            // Compact/Normal: composed BEFORE the Column (whose first child is
            // the top-bar row) so the gradient can never dim the buttons; the
            // list never scrolls into the bar's zone in these layouts.
            CollectionScrollTopBar(
                title = title,
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

                    if (onDensityChange != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        OrganisationMenuButton(
                            contentDescription = "Organisation",
                            onClick = { showOrganisationModal = true }
                        )
                    }
                }
            }

            if (density.isGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(density.gridColumns),
                    modifier = Modifier.fillMaxSize().then(listTopObserver),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        top = 0.dp,
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
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().then(listTopObserver),
                    state = listState,
                    contentPadding = PaddingValues(
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
            }
        }

        if (heroLayout.isImmersive) {
            // Above the scrolling list (so content dims under it) but below the
            // overlay back/more row.
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
                        contentDescription = "Back",
                        tint = FoamTheme.colors.textPrimary,
                        modifier = Modifier
                            .size(32.dp)
                            .clickable { onBack() }
                            .padding(4.dp)
                    )

                    if (onDensityChange != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        OrganisationMenuButton(
                            contentDescription = "Organisation",
                            onClick = { showOrganisationModal = true }
                        )
                    }
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
    if (heroLayout.isImmersive) {
        CollectionHeroImmersiveHeader(
            layout = heroLayout,
            title = title,
            subtitle = subtitle,
            statsText = "$itemCount items",
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
        statsText = "$itemCount items",
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
 * the row must itself morph from the hero's square, faded rest look to its own
 * rounded, unfaded one (see [collectionRowFlightArtwork]). The hosts resolve
 * the flag from the destination hero's layout via
 * [LocalCollectionHeroLayoutLookup].
 *
 * [fullyRoundedArtwork] renders the artwork as a FULL circle (artist rows)
 * everywhere: the rest clip, the rest shadow shape and the flight morph's
 * landing radius all follow the circle, so a round source row lands on a round
 * hero (or morphs to the immersive hero's square with the radius riding the
 * flight) instead of popping between a circle and the album rounding.
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

    val sharedArtModifier = Modifier
        .collectionRowFlightArtwork(
            key = "$sharedElementPrefix-art-$elementKey",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = elementKey != null,
            restShape = artShape,
            immersiveFlightHero = immersiveFlightHero,
            fullyRounded = fullyRoundedArtwork
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
            subtitleModifier = sharedSubtitleModifier
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
        subtitleModifier = sharedSubtitleModifier
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
            contentDescription = "Album Art",
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
