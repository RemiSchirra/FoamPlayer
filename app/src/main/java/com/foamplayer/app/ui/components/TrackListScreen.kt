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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.foamplayer.app.data.db.entities.TrackEntity
import androidx.compose.ui.platform.LocalContext
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.TimeUtils
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
    /** Dismiss-to-origin-collection scroll request (see [TrackCenterRequest]). */
    centerTrackRequest: TrackCenterRequest? = null,
    /** Live play/pause bit, feeding the flight artwork's paused shrink when a row of this list is the shared-element target. */
    isPlaying: Boolean = true,
    /** Hero layout resolved from the per-collection settings; Compact keeps the classic header. */
    heroLayout: CollectionHeroLayout = CollectionHeroLayout.Compact,
    /** Immersive player mode: the flight overlay art must fade in like the hero does instead of popping in. */
    immersiveMode: Boolean = false
) {
    val totalDurationMs = remember(tracks) { tracks.sumOf { it.duration } }
    val durationText = remember(totalDurationMs) { TimeUtils.formatDurationLong(totalDurationMs) }
    val trackCount = tracks.size
    val context = LocalContext.current
    var showOrganisationModal by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val topBarFraction = rememberCollectionScrollFraction(listState, gridState, density.isGrid)
    val heroScrolledOutPx = with(LocalDensity.current) {
        collectionHeroImmersiveVisibleHeight(heroLayout).roundToPx()
    }
    val heroScrollOffsetPx: () -> Int = {
        // While the spacer header (item 0) is visible its scroll offset IS the
        // hero's travel; past it the hero is fully scrolled out.
        if (density.isGrid) {
            if (gridState.firstVisibleItemIndex == 0) {
                gridState.firstVisibleItemScrollOffset
            } else {
                heroScrolledOutPx
            }
        } else {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset
            } else {
                heroScrolledOutPx
            }
        }
    }

    // Only the row that can become the shared-element endpoint of the player-dismiss
    // flight needs the paused shrink; one animation is hoisted here and read by that
    // row inside its graphicsLayer, so play/pause flips stay draw-only for the list.
    val flightPausedArtScale = rememberPausedArtScale(isPlaying)

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
        val trackIndex = tracks.indexOfFirst { it.id == request.trackId }
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
            CollectionHero(
                layout = heroLayout,
                title = title,
                subtitle = artistName,
                statsText = "$trackCount Tracks • $durationText",
                imageUrl = imageUrl,
                imageIsEmbeddedArt = imageIsEmbeddedArt,
                onPlayAllClick = onPlayAllClick,
                accentColor = heroAccentColor,
                overlayTextAlpha = { 1f - topBarFraction() },
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
                    onDensityChange = onDensityChange,
                    onOpenOrganisation = { showOrganisationModal = true }
                )
            }

            if (density.isGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(density.gridColumns),
                    modifier = Modifier.fillMaxSize().then(listRootObserver),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = 10.dp)
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
                            accentColor = heroAccentColor,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            sharedElementKey = sharedElementKey,
                            sharedElementPrefix = sharedElementPrefix
                        )
                    }

                    itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                        TrackListItem(
                            track = track,
                            onClick = { onTrackClick(tracks, index) },
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
                            isSharedTransitionSource = rowSharedTagActive(track.id)
                        )
                    }

                    item(
                        key = "grid_bottom_spacer",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().then(listRootObserver),
                    state = listState
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
                            accentColor = heroAccentColor,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            sharedElementKey = sharedElementKey,
                            sharedElementPrefix = sharedElementPrefix
                        )
                    }

                    itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            TrackListItem(
                                track = track,
                                onClick = { onTrackClick(tracks, index) },
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
                                isSharedTransitionSource = rowSharedTagActive(track.id)
                            )
                        }
                    }

                    item(key = "list_bottom_spacer") { Spacer(modifier = Modifier.height(80.dp)) }
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

            Box(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                TrackListTopBarRow(
                    onBack = onBack,
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
            onDismiss = { showOrganisationModal = false }
        )
    }
}

/** The list's leading item: the hero for compact/normal layouts, a spacer matching the immersive hero's visible height otherwise. */
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
    accentColor: Color,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
    sharedElementPrefix: String
) {
    if (heroLayout.isImmersive) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(collectionHeroImmersiveVisibleHeight(heroLayout))
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
        accentColor = accentColor,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        sharedElementKey = sharedElementKey,
        sharedElementPrefix = sharedElementPrefix
    )
}

/** Static top bar of the track list: back arrow left, 3-dots organisation button right. */
@Composable
private fun TrackListTopBarRow(
    onBack: () -> Unit,
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
                .clickable { onBack() }
                .padding(4.dp)
        )

        if (onDensityChange != null) {
            Spacer(modifier = Modifier.weight(1f))
            OrganisationMenuButton(
                contentDescription = "Organisation",
                onClick = onOpenOrganisation
            )
        }
    }
}
