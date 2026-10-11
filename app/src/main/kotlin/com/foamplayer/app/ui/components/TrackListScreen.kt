package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import androidx.compose.ui.platform.LocalContext
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.ui.modals.DensityOrganisationModal
import com.foamplayer.app.ui.modals.OrganisationMenuButton
import com.foamplayer.app.ui.screens.queue.rememberDragDropListState
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.adaptAccentForTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.TimeUtils
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.utils.rememberPausedArtScale
import kotlinx.coroutines.CompletableDeferred

/**
 * Dismiss-to-origin-collection coordination: asked by the nav root when the
 * full player's swipe-down starts over this page, so the playing track's row
 * can be scrolled to the vertical middle of the screen and become the
 * shared-element target of the artwork flight. [requestId] distinguishes
 * otherwise-equal requests.
 *
 * The row's shared-element tag is held back until [scrolledIntoPlace]
 * completes: pairing while the list is still at its restored position would
 * drag the matched overlay art off the hero as the centering scroll runs,
 * which reads as the image jumping at the start of the swipe.
 *
 * Once the scroll has landed, [rowArtCenterYPx] reports the row artwork's
 * center in root coordinates so the dismiss swipe can be sized to the actual
 * artwork travel distance.
 */
class TrackCenterRequest(val trackId: Long, val requestId: Int) {
    internal val scrolledIntoPlace = CompletableDeferred<Unit>()
    var rowArtCenterYPx: Float = Float.NaN
}

/**
 * Scrolls the list so the item at [index] sits as close to the vertical middle
 * of the viewport as the list's bounds allow (the raw scroll is clamped at
 * both ends). Both steps are synchronous, so the returned info is already the
 * settled placement.
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

/** Grid counterpart of [LazyListState.scrollToCenteredItem]. */
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

/** Scale-up applied to a track's grid cell while it is being dragged. */
private const val GridDragLiftScale = 1.05f

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
    /** False when [imageUrl] is a plain image URI (e.g. a playlist cover). */
    imageIsEmbeddedArt: Boolean = true,
    artistName: String? = null,
    /** When set, the hero's artist line becomes a tappable link. */
    onArtistClick: ((String) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementKey: String? = null,
    /** First path segment of the shared element keys; detail screens of other kinds pass their own. */
    sharedElementPrefix: String = "album",
    sharedTrackId: Long? = null,
    activeTrackId: Long? = null,
    showTrackNumbers: Boolean = false,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    /** This view's remembered list density. */
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
    /**
     * Combined playback-active bit (isPlaying || isPlaybackRequested) of the
     * playing track, feeding the flight artwork's paused shrink when a row of
     * this list is the shared-element target (both ends must match or the art
     * pops at the hand-off) — see rememberPausedArtScale.
     */
    isPlaybackActive: Boolean = true,
    /** Hero layout resolved from the per-collection settings. */
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    /** Immersive player mode: the flight overlay art must fade in like the hero. */
    immersiveMode: Boolean = false,
    /** Playlist edit mode: rows gain swipe-to-remove and a drag handle. */
    editMode: Boolean = false,
    /** When set, the top bar shows the edit-mode pencil; back exits edit mode first. */
    onEditModeChange: ((Boolean) -> Unit)? = null,
    /** Edit-mode removal: a row's swipe-to-dismiss calls this with the swiped track. */
    onRemoveTrack: ((TrackEntity) -> Unit)? = null,
    /** Edit-mode reorder persistence: called with the final displayed order's track ids. */
    onPersistTrackOrder: ((List<Long>) -> Unit)? = null,
    /** Whether the long-content fast scrollbar rides the container's end edge. */
    showScrollbar: Boolean = true
) {
    val totalDurationMs = remember(tracks) { tracks.sumOf { it.duration } }
    val durationText = remember(totalDurationMs) { TimeUtils.formatDurationLong(totalDurationMs) }
    val trackCount = tracks.size
    val statsText = stringResource(R.string.track_count_duration, trackCount, durationText)
    val context = LocalContext.current
    var showOrganisationModal by remember { mutableStateOf(false) }

    val searchState = rememberCollectionSearchState()

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val topBarFraction = rememberCollectionScrollFraction(listState, gridState, density.isGrid)
    // Flight fractions of this screen's two transitions: the nav flight and
    // the player-container flight. The floating back/more row is lifted into
    // the shared-element overlay while any transition is active
    // (collectionHeroOverlayLayer), and a lifted layer escapes the host
    // hierarchy's own fades — so its alpha must multiply both fractions.
    val navFlightFraction =
        rememberCollectionHeroFlightFraction(sharedTransitionScope, animatedVisibilityScope)
    val containerFlightFraction =
        rememberCollectionHeroFlightFraction(sharedTransitionScope, playerTransitionVisibilityScope)
    val transitionAlpha: () -> Float = {
        (1f - navFlightFraction.value) * (1f - containerFlightFraction.value)
    }
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
    var listTopYInRootPx by remember { mutableFloatStateOf(Float.NaN) }
    val listTopObserver = Modifier.onGloballyPositioned {
        listTopYInRootPx = it.positionInRoot().y
    }
    CollectionSearchScrollToBarEffect(
        searchState = searchState,
        listState = listState,
        gridState = gridState,
        useGrid = density.isGrid,
        listTopYInRootPx = { listTopYInRootPx },
        topChromeHeight = CollectionSearchImmersiveTopClearance
    )

    val heroTopPadding = if (heroLayout.isImmersive) 0.dp else CollectionTopBarRowHeight

    // One hoisted paused-scale animation, read by the flight-endpoint row
    // inside its graphicsLayer, so play/pause flips stay draw-only for the list.
    val flightPausedArtScale = rememberPausedArtScale(isPlaybackActive)

    // Whether a player-dismiss artwork flight is currently landing on this
    // list's rows: while live, the flying artwork casts the shadow itself and
    // the target row's static shadow steps aside. One derived state for the
    // whole list, consumed per row gated on that row being the tagged target.
    val dismissFlightActive = rememberPlayerDismissFlightActive(
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = playerTransitionVisibilityScope
    )

    // Edit-mode drag & drop: a local display list absorbs the swaps live; the
    // DB is written once when the drag settles. While a drag is in flight,
    // incoming track emissions are ignored so they can't fight the reorder.
    // Lazy item indices include the hero (item 0) and the bottom spacer.
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
    val gridDragState = rememberDragDropGridState(
        gridState = gridState,
        canMove = { from, to -> from > 0 && to in 1..displayedTracks.size },
        onMove = { from, to ->
            displayedTracks = displayedTracks.toMutableList().apply {
                add(to - 1, removeAt(from - 1))
            }
        }
    )
    // True once the finger lifts: the item keeps flying to its slot but the
    // lift scale already animates back down in parallel.
    var dragReleased by remember { mutableStateOf(false) }
    // Fresh reads for the drag callbacks: pointerInput is keyed on the item's
    // track id, so a gesture handler can outlive the state it was created with.
    val latestDisplayedTracks by rememberUpdatedState(displayedTracks)
    val latestTracks by rememberUpdatedState(tracks)
    val latestOnRemoveTrack by rememberUpdatedState(onRemoveTrack)
    val latestOnPersistTrackOrder by rememberUpdatedState(onPersistTrackOrder)

    // The drag display list only diverges from the DB list mid-drag; the
    // search filters whatever is currently displayed (never active in edit
    // mode). rememberFilteredList keeps the incoming list identity while the
    // search bar is closed/blank.
    val shownTracks = if (editMode) {
        displayedTracks
    } else {
        rememberFilteredList(tracks, searchState) { searchState.matches(it.title, it.artist) }
    }

    // Track taps settle the search session. The click lambda captures the
    // PRE-close [shownTracks] instance, so the queue handed to [onTrackClick]
    // is exactly the filtered list the user saw on screen.
    val onRowClick: (Int) -> Unit = if (editMode) {
        { _ -> }
    } else {
        { index ->
            searchState.close()
            onTrackClick(shownTracks, index)
        }
    }

    // Dismiss-to-origin-collection: bring the playing track's row to the
    // vertical middle of the screen BEFORE the row is allowed to pair with the
    // player's artwork. Runs while the player still covers the screen.
    var centeringSettled by remember { mutableStateOf(false) }

    var listRootYPx by remember { mutableFloatStateOf(Float.NaN) }
    val listRootObserver = Modifier.onGloballyPositioned { listRootYPx = it.positionInRoot().y }

    LaunchedEffect(centerTrackRequest) {
        val request = centerTrackRequest ?: return@LaunchedEffect
        centeringSettled = false
        // The centering scroll targets the row's position in the DISPLAYED
        // list (the search filter may hide rows above it); falls back to the
        // full list if the row is filtered out.
        val trackIndex = shownTracks.indexOfFirst { it.id == request.trackId }
            .takeIf { it >= 0 } ?: tracks.indexOfFirst { it.id == request.trackId }
        if (trackIndex >= 0) {
            val itemIndex = trackIndex + 1 // header item sits at index 0
            if (density.isGrid) {
                val item = gridState.scrollToCenteredItem(itemIndex)
                if (item != null && !listRootYPx.isNaN()) {
                    // Grid cells: the square art fills the cell's top with
                    // symmetric side padding, so its center lands at
                    // itemTop + cellWidth / 2.
                    request.rowArtCenterYPx = listRootYPx +
                            (item.offset.y - gridState.layoutInfo.viewportStartOffset) +
                            item.size.width / 2f
                }
            } else {
                val item = listState.scrollToCenteredItem(itemIndex)
                if (item != null && !listRootYPx.isNaN()) {
                    // Row densities: the artwork is vertically centered, so the
                    // row center IS the art center.
                    request.rowArtCenterYPx = listRootYPx +
                            (item.offset - listState.layoutInfo.viewportStartOffset) +
                            item.size / 2f
                }
            }
        }
        centeringSettled = true
        request.scrolledIntoPlace.complete(Unit)
    }

    // The playing row only carries the shared-element tag once any pending
    // centering scroll has landed; open flights (no request) tag immediately.
    val rowSharedTagActive: (Long) -> Boolean = { trackId ->
        trackId == sharedTrackId && (centerTrackRequest == null || centeringSettled)
    }

    LaunchedEffect(imageUrl, imageIsEmbeddedArt) {
        if (imageUrl != null) {
            AlbumArtPaletteCache.ensure(this, context, imageUrl, imageIsEmbeddedArt)
        }
    }

    val heroAccentColor = imageUrl?.let { path -> AlbumArtPaletteCache.colors[path] }
        ?.adaptAccentForTheme(FoamTheme.isDark)
        ?: FoamTheme.accentColor

    Box(modifier = modifier.fillMaxSize()) {
        if (heroLayout.isImmersive) {
            // Artwork layer only; the overlay block is the list's own
            // [CollectionHeroImmersiveHeader] item.
            CollectionHero(
                layout = heroLayout,
                title = title,
                subtitle = artistName,
                statsText = statsText,
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
                item(
                    key = "grid_header",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    TrackListHero(
                        heroLayout = heroLayout,
                        title = title,
                        artistName = artistName,
                        statsText = statsText,
                        imageUrl = imageUrl,
                        imageIsEmbeddedArt = imageIsEmbeddedArt,
                        onPlayAllClick = onPlayAllClick,
                        onShuffleAllClick = onShuffleAllClick?.let { callback ->
                            { callback(shownTracks.shuffled()) }
                        },
                        onArtistClick = onArtistClick,
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
                    // derivedStateOf: draggedIndex is shared by every cell; the
                    // derived boolean only invalidates cells whose dragging
                    // state actually flips.
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
                            // The drag layer only exists while a drag is live:
                            // at rest every value it produces is the identity
                            // transform.
                            .then(
                                if (isDragging) {
                                    Modifier.graphicsLayer {
                                        val itemInfo = gridState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == gridItemIndex }
                                        translationX = if (itemInfo != null) {
                                            (gridDragState.draggingItemOffset.x - itemInfo.offset.x).toFloat()
                                        } else {
                                            0f
                                        }
                                        translationY = if (itemInfo != null) {
                                            (gridDragState.draggingItemOffset.y - itemInfo.offset.y).toFloat()
                                        } else {
                                            0f
                                        }
                                        scaleX = liftScale
                                        scaleY = liftScale
                                    }
                                } else {
                                    Modifier
                                }
                            )
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
                            isDismissFlightActive = { dismissFlightActive.value },
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

            // Fast scrollbar; lifted into the shared-element overlay (zIndex
            // 0.5f: above the flying artwork at 0f, beneath the buttons at 1f)
            // so the flying hero artwork doesn't sweep over it mid-flight. Its
            // alpha mirrors the host hierarchy's fades, which a lifted layer
            // escapes.
            if (showScrollbar && !editMode) {
                FoamScrollbar(
                    state = gridState,
                    color = heroAccentColor,
                    trackTopPadding = CollectionTopBarRowHeight,
                    trackBottomPadding = LocalBottomBarClearance.current,
                    alpha = transitionAlpha,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .collectionHeroOverlayLayer(
                            sharedTransitionScope = sharedTransitionScope,
                            zIndexInOverlay = 0.5f,
                            renderInOverlay = animatedVisibilityScope?.let { scope ->
                                { scope.isNavTransitionInProgress() }
                            }
                        )
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
                item(key = "list_header") {
                    TrackListHero(
                        heroLayout = heroLayout,
                        title = title,
                        artistName = artistName,
                        statsText = statsText,
                        imageUrl = imageUrl,
                        imageIsEmbeddedArt = imageIsEmbeddedArt,
                        onPlayAllClick = onPlayAllClick,
                        onShuffleAllClick = onShuffleAllClick?.let { callback ->
                            { callback(shownTracks.shuffled()) }
                        },
                        onArtistClick = onArtistClick,
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
                    val listItemIndex = index + 1
                    val isDragging by remember(listItemIndex) {
                        derivedStateOf { listDragState.isItemDragging(listItemIndex) }
                    }
                    val currentIndex = rememberUpdatedState(index)

                    Box(
                        modifier = Modifier
                            .let { if (isDragging) it else it.animateItem() }
                            .zIndex(if (isDragging) 1f else 0f)
                            .then(
                                if (isDragging) {
                                    Modifier.graphicsLayer {
                                        translationY = run {
                                            val itemInfo = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.index == listItemIndex }
                                            if (itemInfo != null) {
                                                listDragState.draggingItemTop - itemInfo.offset
                                            } else 0f
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 16.dp)
                    ) {
                        TrackListItem(
                            track = track,
                            onClick = { onRowClick(index) },
                            onLongClick = if (editMode) {
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
                                            dragReleased = true
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
                            isDismissFlightActive = { dismissFlightActive.value },
                            isSharedTransitionSource = rowSharedTagActive(track.id) && !editMode
                        )
                    }
                }

                item(key = "list_bottom_spacer") { Spacer(modifier = Modifier.height(LocalBottomBarClearance.current)) }
            }

            if (showScrollbar && !editMode) {
                FoamScrollbar(
                    state = listState,
                    color = heroAccentColor,
                    trackTopPadding = CollectionTopBarRowHeight,
                    trackBottomPadding = LocalBottomBarClearance.current,
                    alpha = transitionAlpha,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .collectionHeroOverlayLayer(
                            sharedTransitionScope = sharedTransitionScope,
                            zIndexInOverlay = 0.5f,
                            renderInOverlay = animatedVisibilityScope?.let { scope ->
                                { scope.isNavTransitionInProgress() }
                            }
                        )
                )
            }
        }

        // The scroll top bar's gradient above the scrolling list, then the
        // floating back/more row above THAT. Both are lifted into the
        // shared-element overlay during transitions and multiplied by the
        // nav/container flight alpha (drawn in place, the top bar sits in the
        // status-bar strip outside the NavHost bounds and drops out on the
        // first frame of the close instead of fading with the screen).
        CollectionScrollTopBar(
            title = title,
            fraction = { topBarFraction() * transitionAlpha() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .collectionHeroOverlayLayer(
                    sharedTransitionScope = sharedTransitionScope,
                    zIndexInOverlay = 0.5f,
                    renderInOverlay = animatedVisibilityScope?.let { scope ->
                        { scope.isNavTransitionInProgress() }
                    }
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .collectionHeroOverlayLayer(
                    sharedTransitionScope = sharedTransitionScope,
                    renderInOverlay = animatedVisibilityScope?.let { scope ->
                        { scope.isNavTransitionInProgress() }
                    }
                )
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
 * The list's leading item: the full hero for compact/normal layouts,
 * [CollectionHeroImmersiveHeader] for the immersive ones.
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
    onArtistClick: ((String) -> Unit)?,
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
            onSubtitleClick = onArtistClick,
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
        onSubtitleClick = onArtistClick,
        searchState = searchState,
        accentColor = accentColor,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedElementKey = sharedElementKey,
        sharedElementPrefix = sharedElementPrefix
    )
}

/** Static top bar: back arrow left, optional edit pencil + organisation button right. */
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
            contentDescription = stringResource(R.string.back),
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier
                .size(32.dp)
                .clickable {
                    // While editing, back leaves edit mode before the screen.
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
                    contentDescription = stringResource(R.string.edit),
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
                contentDescription = stringResource(R.string.organisation),
                onClick = onOpenOrganisation
            )
        }
    }
}
