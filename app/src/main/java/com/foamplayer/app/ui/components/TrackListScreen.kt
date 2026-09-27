package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.foamplayer.app.data.db.entities.TrackEntity
import androidx.compose.ui.platform.LocalContext
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.ui.screens.queue.rememberDragDropListState
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.TimeUtils
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.utils.rememberPausedArtScale
import kotlinx.coroutines.CompletableDeferred

/**
 * Dismiss-to-origin-collection coordination: asked by the nav root when the full
 * player's swipe-down starts over this page, so the playing track's row can be
 * scrolled to the vertical middle of the screen and become the shared-element
 * target of the artwork flight. [requestId] makes otherwise-equal requests
 * distinguishable for the consuming LaunchedEffect.
 *
 * The row's shared-element tag is held back until [scrolledIntoPlace] completes:
 * if the element paired while the list was still at its restored scroll position,
 * the centering scroll would drag the already-matched overlay art off the hero
 * (the shared bounds follow list scrolling), which reads as the image jumping at
 * the start of the swipe. Pairing must therefore happen only after the scroll.
 *
 * Once the scroll has landed, [rowArtCenterYPx] reports the row artwork's center in
 * root coordinates so the dismiss swipe can be sized to the actual artwork travel
 * distance (1:1 finger tracking).
 */
class TrackCenterRequest(val trackId: Long, val requestId: Int) {
    internal val scrolledIntoPlace = CompletableDeferred<Unit>()
    var rowArtCenterYPx: Float = Float.NaN
}

/**
 * Instantly scrolls the list so the item at [index] sits as close to the vertical
 * middle of the viewport as the list's bounds allow: the raw scroll is clamped by
 * [scrollBy] at both ends, so short lists (not enough content above the item) keep
 * it near the top and end-of-list items stay as high as the content permits. Both
 * steps are synchronous (scrollToItem force-remeasures), so the returned info is
 * already the settled, post-centering placement.
 */
private suspend fun LazyListState.scrollToCenteredItem(index: Int): LazyListItemInfo? {
    scrollToItem(index)
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val delta = (
        item.offset + item.size / 2 -
                (info.viewportEndOffset + info.viewportStartOffset) / 2
        ).toFloat()
    if (delta != 0f) scrollBy(delta)
    return layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
}

/** Grid counterpart of [LazyListState-scrollToCenteredItem]. */
private suspend fun LazyGridState.scrollToCenteredItem(index: Int): LazyGridItemInfo? {
    scrollToItem(index)
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val delta = (
        item.offset.y + item.size.height / 2 -
                (info.viewportEndOffset + info.viewportStartOffset) / 2
        ).toFloat()
    if (delta != 0f) scrollBy(delta)
    return layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TrackListScreen(
    title: String,
    tracks: List<TrackEntity>,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onPlayAllClick: () -> Unit,
    /** Called with a shuffled copy of the displayed tracks to queue them in random order. */
    onShuffleAllClick: ((List<TrackEntity>) -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    /** False when [imageUrl] is a plain image URI (e.g. a playlist cover) instead of an audio file to extract art from. */
    imageIsEmbeddedArt: Boolean = true,
    artistName: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementKey: String? = null,
    /** First path segment of the shared element keys ("album-art-*", ...). Detail screens of other entity kinds pass their own. */
    sharedElementPrefix: String = "album",
    sharedTrackId: Long? = null,
    activeTrackId: Long? = null,
    showTrackNumbers: Boolean = false,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    /** This view's remembered list density; ROW_NORMAL keeps the classic layout. */
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    /** When set, the top bar shows the 3-dots button opening the organisation modal. */
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    /** Sort modes the list supports; non-empty adds the sort section to the organisation modal. */
    sortOptions: List<CollectionSortMode> = emptyList(),
    /** The list's current sort (applied by the caller to the [tracks] it passes in). */
    currentSort: CollectionSortMode = CollectionSortMode.NAME,
    /** When set, the sort radio list is shown in the organisation modal. */
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    /** Whether the current sort is inverted. */
    sortInverted: Boolean = false,
    /** When set, the "Inverse" checkbox is shown under the sort radio list. */
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    /** Dismiss-to-origin-collection scroll request (see [TrackCenterRequest]). */
    centerTrackRequest: TrackCenterRequest? = null,
    /** Live play/pause bit, feeding the flight artwork's paused shrink when a row of this list is the shared-element target. */
    isPlaying: Boolean = true,
    /** Hero layout resolved from the per-collection settings; Compact keeps the classic header. */
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    /** Immersive player mode: the flight overlay art must fade in like the hero does instead of popping in. */
    immersiveMode: Boolean = false,
    /** Playlist edit mode: rows gain swipe-to-remove and a drag handle for reordering. */
    editMode: Boolean = false,
    /** When set, the top bar shows the edit-mode pencil; the back arrow exits edit mode first. */
    onEditModeChange: ((Boolean) -> Unit)? = null,
    /** Edit-mode removal: a row's swipe-to-dismiss calls this with the swiped track. */
    onRemoveTrack: ((TrackEntity) -> Unit)? = null,
    /** Edit-mode reorder persistence: called with the final displayed order's track ids when a drag settles. */
    onPersistTrackOrder: ((List<Long>) -> Unit)? = null
) {
    val totalDurationMs = remember(tracks) { tracks.sumOf { it.duration } }
    val durationText = remember(totalDurationMs) { TimeUtils.formatDurationLong(totalDurationMs) }
    val trackCount = tracks.size
    val context = LocalContext.current
    var showOrganisationModal by remember { mutableStateOf(false) }

    // Hero search: typing in the opened bar filters the displayed rows live;
    // matches against title and artist. Empty query (or closed bar) shows all.
    val searchState = rememberCollectionSearchState()

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val topBarFraction = rememberCollectionScrollFraction(listState, gridState, density.isGrid)
    // Flight fractions of this screen's two transitions: the nav flight
    // (open/close of the collection itself) and the player-container flight
    // (full player expanding over / collapsing back onto this screen). The hero
    // chrome and the top bar are lifted into the shared-element overlay while
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
        // While the spacer header (item 0) is visible its scroll offset IS the
        // hero's travel; past it the hero is fully scrolled out.
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

    // Only the row that can become the shared-element endpoint of the player-dismiss
    // flight needs the paused shrink; one animation is hoisted here and read by that
    // row inside its graphicsLayer, so play/pause flips stay draw-only for the list.
    val flightPausedArtScale = rememberPausedArtScale(isPlaying)

    // Edit-mode drag & drop (playlist detail): a local display list absorbs the
    // swaps live; the DB is written once when the drag settles. While a drag is
    // in flight, incoming track emissions are ignored so they can't fight the
    // reorder. LazyColumn item indices include the hero (item 0) and the bottom
    // spacer, so track index <-> list index conversions happen at the edges.
    var displayedTracks by remember { mutableStateOf(tracks) }
    var dragOriginIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(tracks, editMode) {
        if (dragOriginIndex == null) {
            displayedTracks = tracks
        }
    }
    val listDragState = rememberDragDropListState(
        lazyListState = listState,
        canMove = { from, to -> from > 0 && to in 1..displayedTracks.size },
        onMove = { from, to ->
            displayedTracks = displayedTracks.toMutableList().apply {
                add(to - 1, removeAt(from - 1))
            }
        }
    )
    // Grid counterpart for GRID_* densities in edit mode: the same handle-only
    // drag & drop as the playlists screen. The hero header (index 0) and the
    // bottom spacer never take part in a swap.
    val gridDragState = rememberDragDropGridState(
        gridState = gridState,
        canMove = { from, to -> from > 0 && to in 1..displayedTracks.size },
        onMove = { from, to ->
            displayedTracks = displayedTracks.toMutableList().apply {
                add(to - 1, removeAt(from - 1))
            }
        }
    )
    // True once the finger lifts: the item keeps flying to its slot (settle
    // animation) but the lift scale already animates back down in parallel.
    var dragReleased by remember { mutableStateOf(false) }
    // Fresh reads for the drag callbacks: pointerInput is keyed on the item's
    // track id, so a gesture handler can outlive the state it was created with.
    val latestDisplayedTracks by rememberUpdatedState(displayedTracks)
    val latestTracks by rememberUpdatedState(tracks)
    val latestOnRemoveTrack by rememberUpdatedState(onRemoveTrack)
    val latestOnPersistTrackOrder by rememberUpdatedState(onPersistTrackOrder)

    // The drag display list only diverges from the DB list mid-drag; the search
    // filters whatever is currently displayed (never active in edit mode).
    val shownTracks = if (editMode) {
        displayedTracks
    } else {
        tracks.filter { searchState.matches(it.title, it.artist) }
    }

    // Track taps: clicking a row settles the search session — the bar closes
    // (query cleared, keyboard hidden) as playback starts. The click lambda
    // captures the PRE-close [shownTracks] instance, so the queue handed to
    // [onTrackClick] is exactly the filtered list the user saw on screen.
    val onRowClick: (Int) -> Unit = if (editMode) {
        // Taps must not start playback while editing.
        { _ -> }
    } else {
        { index ->
            searchState.close()
            onTrackClick(shownTracks, index)
        }
    }

    // Dismiss-to-origin-collection: bring the playing track's row to the vertical
    // middle of the screen (clamped to the list's bounds) BEFORE the row is allowed
    // to pair with the player's artwork -- see [TrackCenterRequest] for why the
    // ordering matters. Runs while the player still covers the screen.
    var centeringSettled by remember { mutableStateOf(false) }

    // Root-space top of the list container, needed to report the target row's center
    // (in the same coordinates as the player's hero art) for the swipe distance.
    var listRootYPx by remember { mutableFloatStateOf(Float.NaN) }
    val listRootObserver = Modifier.onGloballyPositioned { listRootYPx = it.positionInRoot().y }

    LaunchedEffect(centerTrackRequest) {
        val request = centerTrackRequest ?: return@LaunchedEffect
        centeringSettled = false
        // The centering scroll targets the row's position in the DISPLAYED list
        // (the search filter may hide rows above it); falls back to the full
        // list if the row is filtered out.
        val trackIndex = shownTracks.indexOfFirst { it.id == request.trackId }
            .takeIf { it >= 0 } ?: tracks.indexOfFirst { it.id == request.trackId }
        if (trackIndex >= 0) {
            val itemIndex = trackIndex + 1 // header item sits at index 0
            if (density.isGrid) {
                val item = gridState.scrollToCenteredItem(itemIndex)
                if (item != null && !listRootYPx.isNaN()) {
                    // Grid cells: the square art fills the cell's top with symmetric
                    // side padding, so its center lands at itemTop + cellWidth / 2
                    // (the paddings cancel out).
                    request.rowArtCenterYPx = listRootYPx +
                            (item.offset.y - gridState.layoutInfo.viewportStartOffset) +
                            item.size.width / 2f
                }
            } else {
                val item = listState.scrollToCenteredItem(itemIndex)
                if (item != null && !listRootYPx.isNaN()) {
                    // Row densities: the artwork is vertically centered in the row,
                    // so the row center IS the art center.
                    request.rowArtCenterYPx = listRootYPx +
                            (item.offset - listState.layoutInfo.viewportStartOffset) +
                            item.size / 2f
                }
            }
        }
        centeringSettled = true
        request.scrolledIntoPlace.complete(Unit)
    }

    // The playing row only carries the shared-element tag once any pending centering
    // scroll has landed; open flights (no request pending) tag immediately.
    val rowSharedTagActive: (Long) -> Boolean = { trackId ->
        trackId == sharedTrackId && (centerTrackRequest == null || centeringSettled)
    }

    LaunchedEffect(imageUrl, imageIsEmbeddedArt) {
        if (imageUrl != null) {
            AlbumArtPaletteCache.ensure(this, context, imageUrl, imageIsEmbeddedArt)
        }
    }

    val heroAccentColor = imageUrl?.let { path -> AlbumArtPaletteCache.colors[path] }
        ?: FoamTheme.accentColor

    Box(modifier = modifier.fillMaxSize()) {
        if (heroLayout.isImmersive) {
            // Artwork layer only: bleeds under the status bar behind the list.
            // The overlay block (title/play pill) is the list's own
            // [CollectionHeroImmersiveHeader] item, so it fades with this
            // screen's transitions exactly like the compact/normal heroes and
            // takes taps directly.
            CollectionHero(
                layout = heroLayout,
                title = title,
                subtitle = artistName,
                statsText = "$trackCount Tracks • $durationText",
                imageUrl = imageUrl,
                imageIsEmbeddedArt = imageIsEmbeddedArt,
                onPlayAllClick = null,
                accentColor = heroAccentColor,
                transitionAlpha = transitionAlpha,
                scrollOffsetPx = heroScrollOffsetPx,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedElementKey = sharedElementKey,
                sharedElementPrefix = sharedElementPrefix,
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
                TrackListTopBarRow(
                    onBack = onBack,
                    accentColor = heroAccentColor,
                    editMode = editMode,
                    onEditModeChange = onEditModeChange,
                    onDensityChange = onDensityChange,
                    onOpenOrganisation = { showOrganisationModal = true }
                )
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
                    item(
                        key = "grid_header",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        TrackListHero(
                            heroLayout = heroLayout,
                            title = title,
                            artistName = artistName,
                            statsText = "$trackCount Tracks • $durationText",
                            imageUrl = imageUrl,
                            imageIsEmbeddedArt = imageIsEmbeddedArt,
                            onPlayAllClick = onPlayAllClick,
                            onShuffleAllClick = onShuffleAllClick?.let { callback ->
                                { callback(shownTracks.shuffled()) }
                            },
                            searchState = searchState,
                            accentColor = heroAccentColor,
                            onVisibleHeightPx = { heroVisibleHeightPx.intValue = it },
                            scrollOffsetPx = heroScrollOffsetPx,
                            transitionAlpha = transitionAlpha,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            sharedElementKey = sharedElementKey,
                            sharedElementPrefix = sharedElementPrefix
                        )
                    }

                    itemsIndexed(shownTracks, key = { _, track -> track.id }) { index, track ->
                        // +1: the hero header sits at grid item 0; the drag-drop
                        // state works in raw LazyGrid indices.
                        val gridItemIndex = index + 1
                        // derivedStateOf: draggedIndex is shared by every cell, so
                        // reading it directly in each cell's scope would recompose
                        // the whole viewport on every swap; the derived boolean only
                        // invalidates the cells whose dragging state actually flips.
                        val isDragging by remember(gridItemIndex) {
                            derivedStateOf { gridDragState.isItemDragging(gridItemIndex) }
                        }
                        val currentIndex = rememberUpdatedState(index)
                        val liftScale by animateFloatAsState(
                            targetValue = if (isDragging && !dragReleased) GridDragLiftScale else 1f,
                            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                            label = "trackGridDragLift"
                        )

                        Box(
                            modifier = Modifier
                                .let { if (isDragging) it else it.animateItem() }
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    val itemInfo = gridState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == gridItemIndex }
                                    translationX = if (isDragging && itemInfo != null) {
                                        (gridDragState.draggingItemOffset.x - itemInfo.offset.x).toFloat()
                                    } else {
                                        0f
                                    }
                                    translationY = if (isDragging && itemInfo != null) {
                                        (gridDragState.draggingItemOffset.y - itemInfo.offset.y).toFloat()
                                    } else {
                                        0f
                                    }
                                    scaleX = liftScale
                                    scaleY = liftScale
                                }
                        ) {
                            TrackListItem(
                                track = track,
                                onClick = { onRowClick(index) },
                                onLongClick = if (editMode) {
                                    // The track actions modal stays closed while editing.
                                    ({ })
                                } else {
                                    null
                                },
                                active = track.id == activeTrackId,
                                trackNumberText = if (showTrackNumbers) {
                                    track.trackNumber?.toString() ?: (index + 1).toString()
                                } else {
                                    null
                                },
                                density = density,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = playerTransitionVisibilityScope ?: animatedVisibilityScope,
                                flightPausedArtScale = { flightPausedArtScale.value },
                                immersiveArt = immersiveMode,
                                showDragHandle = editMode,
                                dragHandleModifier = if (editMode) {
                                    // Reordering starts only from the drag chip;
                                    // the cell itself stays scroll/tap-through.
                                    Modifier.pointerInput(track.id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                dragOriginIndex = currentIndex.value + 1
                                                dragReleased = false
                                                gridDragState.onDragStart(currentIndex.value + 1)
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                gridDragState.onDrag(dragAmount)
                                            },
                                            onDragEnd = {
                                                val from = dragOriginIndex
                                                val to = gridDragState.draggedIndex
                                                // Scale-down starts with the release; the
                                                // settle animation flies the item to its slot.
                                                dragReleased = true
                                                gridDragState.onDragEnd()
                                                dragOriginIndex = null
                                                if (from != null && to != null && from != to) {
                                                    latestOnPersistTrackOrder
                                                        ?.invoke(latestDisplayedTracks.map { it.id })
                                                }
                                            },
                                            onDragCancel = {
                                                dragReleased = true
                                                gridDragState.onDragCancel()
                                                dragOriginIndex = null
                                                displayedTracks = latestTracks
                                            }
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                                isSharedTransitionSource = rowSharedTagActive(track.id) && !editMode
                            )
                        }
                    }

                    item(
                        key = "grid_bottom_spacer",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
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
                    item(key = "list_header") {
                        TrackListHero(
                            heroLayout = heroLayout,
                            title = title,
                            artistName = artistName,
                            statsText = "$trackCount Tracks • $durationText",
                            imageUrl = imageUrl,
                            imageIsEmbeddedArt = imageIsEmbeddedArt,
                            onPlayAllClick = onPlayAllClick,
                            onShuffleAllClick = onShuffleAllClick?.let { callback ->
                                { callback(shownTracks.shuffled()) }
                            },
                            searchState = searchState,
                            accentColor = heroAccentColor,
                            onVisibleHeightPx = { heroVisibleHeightPx.intValue = it },
                            scrollOffsetPx = heroScrollOffsetPx,
                            transitionAlpha = transitionAlpha,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            sharedElementKey = sharedElementKey,
                            sharedElementPrefix = sharedElementPrefix
                        )
                    }

                    itemsIndexed(shownTracks, key = { _, track -> track.id }) { index, track ->
                        // +1: the hero header sits at list item 0; the drag-drop
                        // state works in raw LazyColumn indices.
                        val listItemIndex = index + 1
                        // derivedStateOf: draggedIndex is shared by every row, so reading it
                        // directly in each row's scope would recompose the whole viewport on
                        // every swap; the derived boolean only invalidates the rows whose
                        // dragging state actually flips (the two rows involved in the swap).
                        val isDragging by remember(listItemIndex) {
                            derivedStateOf { listDragState.isItemDragging(listItemIndex) }
                        }
                        val currentIndex = rememberUpdatedState(index)

                        Box(
                            modifier = Modifier
                                .let { if (isDragging) it else it.animateItem() }
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) {
                                        val itemInfo = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == listItemIndex }
                                        if (itemInfo != null) {
                                            listDragState.draggingItemTop - itemInfo.offset
                                        } else 0f
                                    } else {
                                        0f
                                    }
                                }
                                .padding(horizontal = 16.dp)
                        ) {
                            TrackListItem(
                                track = track,
                                onClick = { onRowClick(index) },
                                onLongClick = if (editMode) {
                                    // The track actions modal stays closed while editing.
                                    ({ })
                                } else {
                                    null
                                },
                                active = track.id == activeTrackId,
                                trackNumberText = if (showTrackNumbers) {
                                    track.trackNumber?.toString() ?: (index + 1).toString()
                                } else {
                                    null
                                },
                                density = density,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = playerTransitionVisibilityScope ?: animatedVisibilityScope,
                                flightPausedArtScale = { flightPausedArtScale.value },
                                immersiveArt = immersiveMode,
                                showDragHandle = editMode,
                                dragHandleModifier = if (editMode) {
                                    Modifier.pointerInput(track.id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                dragOriginIndex = currentIndex.value + 1
                                                listDragState.onDragStart(currentIndex.value + 1)
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                listDragState.onDrag(dragAmount)
                                            },
                                            onDragEnd = {
                                                val from = dragOriginIndex
                                                val to = listDragState.draggedIndex
                                                listDragState.onDragEnd()
                                                dragOriginIndex = null
                                                if (from != null && to != null && from != to) {
                                                    latestOnPersistTrackOrder
                                                        ?.invoke(latestDisplayedTracks.map { it.id })
                                                }
                                            },
                                            onDragCancel = {
                                                listDragState.onDragCancel()
                                                dragOriginIndex = null
                                                displayedTracks = latestTracks
                                            }
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                                onRemove = if (editMode) {
                                    { latestOnRemoveTrack?.invoke(track) }
                                } else {
                                    null
                                },
                                isSharedTransitionSource = rowSharedTagActive(track.id) && !editMode
                            )
                        }
                    }

                    item(key = "list_bottom_spacer") { Spacer(modifier = Modifier.height(LocalBottomBarClearance.current)) }
                }
            }
        }

        if (heroLayout.isImmersive) {
            // Above the scrolling list (so tracks dim under it) but below the
            // overlay back/more row.
            CollectionScrollTopBar(
                title = title,
                fraction = topBarFraction,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Lifted into the shared-element overlay during transitions so the
            // flying artwork passes beneath the back/more buttons; the alpha
            // mirrors the host hierarchy's fades (see transitionAlpha above).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .collectionHeroOverlayLayer(sharedTransitionScope)
                    .graphicsLayer { alpha = transitionAlpha() }
            ) {
                TrackListTopBarRow(
                    onBack = onBack,
                    accentColor = heroAccentColor,
                    editMode = editMode,
                    onEditModeChange = onEditModeChange,
                    onDensityChange = onDensityChange,
                    onOpenOrganisation = { showOrganisationModal = true }
                )
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
 * The list's leading item: the full hero (artwork, texts and play pill) for
 * compact/normal layouts, [CollectionHeroImmersiveHeader] for the immersive
 * ones — the overlaid text block and play pill as real interactive list
 * content (its [CollectionHeroImmersiveHeader.onVisibleHeightPx] keeps the
 * hero artwork's scroll-away constant in sync).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TrackListHero(
    heroLayout: CollectionHeroLayout,
    title: String,
    artistName: String?,
    statsText: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    onPlayAllClick: () -> Unit,
    onShuffleAllClick: (() -> Unit)?,
    searchState: CollectionSearchState?,
    accentColor: Color,
    onVisibleHeightPx: (Int) -> Unit,
    scrollOffsetPx: () -> Int,
    transitionAlpha: () -> Float,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
    sharedElementPrefix: String
) {
    if (heroLayout.isImmersive) {
        CollectionHeroImmersiveHeader(
            layout = heroLayout,
            title = title,
            subtitle = artistName,
            statsText = statsText,
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
            sharedElementPrefix = sharedElementPrefix
        )
        return
    }

    CollectionHero(
        layout = heroLayout,
        title = title,
        subtitle = artistName,
        statsText = statsText,
        imageUrl = imageUrl,
        imageIsEmbeddedArt = imageIsEmbeddedArt,
        onPlayAllClick = onPlayAllClick,
        onShuffleAllClick = onShuffleAllClick,
        searchState = searchState,
        accentColor = accentColor,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedElementKey = sharedElementKey,
        sharedElementPrefix = sharedElementPrefix
    )
}

/** Static top bar of the track list: back arrow left, optional edit pencil + 3-dots organisation button right. */
@Composable
private fun TrackListTopBarRow(
    onBack: () -> Unit,
    accentColor: Color,
    editMode: Boolean,
    onEditModeChange: ((Boolean) -> Unit)?,
    onDensityChange: ((MediaDensity) -> Unit)?,
    onOpenOrganisation: () -> Unit
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
                .clickable {
                    // While editing, back leaves edit mode before it leaves the screen.
                    if (editMode) onEditModeChange?.invoke(false) else onBack()
                }
                .padding(4.dp)
        )

        if (onEditModeChange != null || onDensityChange != null) {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (onEditModeChange != null) {
            val editInteractionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = editInteractionSource,
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = { onEditModeChange(!editMode) }
                    )
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Edit",
                    tint = if (editMode) accentColor else FoamTheme.colors.textPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            if (onDensityChange != null) {
                Spacer(modifier = Modifier.width(12.dp))
            }
        }

        if (onDensityChange != null) {
            OrganisationMenuButton(
                contentDescription = "Organisation",
                onClick = onOpenOrganisation
            )
        }
    }
}

/** Scale-up applied to a track's grid cell while it is being dragged, for a "lifted" feel. */
private const val GridDragLiftScale = 1.05f
