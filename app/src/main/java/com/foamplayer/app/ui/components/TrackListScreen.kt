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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import androidx.core.net.toUri
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
    /** Immersive player mode: the flight overlay art must fade in like the hero does instead of popping in. */
    immersiveMode: Boolean = false
) {
    val totalDurationMs = remember(tracks) { tracks.sumOf { it.duration } }
    val durationText = remember(totalDurationMs) { TimeUtils.formatDurationLong(totalDurationMs) }
    val context = LocalContext.current
    var showOrganisationModal by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

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

    Box(modifier = modifier.fillMaxSize()) {
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

                if (onDensityChange != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    OrganisationMenuButton(
                        contentDescription = "Organisation",
                        onClick = { showOrganisationModal = true }
                    )
                }
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
                        HeaderSection(
                            title = title,
                            subtitle = artistName,
                            trackCount = tracks.size,
                            durationText = durationText,
                            imageUrl = imageUrl,
                            imageIsEmbeddedArt = imageIsEmbeddedArt,
                            onPlayAllClick = onPlayAllClick,
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
                        HeaderSection(
                            title = title,
                            subtitle = artistName,
                            trackCount = tracks.size,
                            durationText = durationText,
                            imageUrl = imageUrl,
                            imageIsEmbeddedArt = imageIsEmbeddedArt,
                            onPlayAllClick = onPlayAllClick,
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HeaderSection(
    title: String,
    subtitle: String?,
    trackCount: Int,
    durationText: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    onPlayAllClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
    sharedElementPrefix: String
) {
    val context = LocalContext.current
    val accentColor = imageUrl?.let { path -> AlbumArtPaletteCache.colors[path] }
        ?: FoamTheme.accentColor

    // Helper: only wraps with a shared bounds modifier when a shared-element
    // context was actually provided (e.g. when navigating in from Search/Artist).
    // For the title/artist TEXT: content genuinely differs between screens (different
    // font sizes), so sharedBounds' cross-fade between the two is the right behavior.
    @Composable
    fun Modifier.sharedBoundsIfAvailable(key: String): Modifier {
        if (sharedTransitionScope == null || animatedVisibilityScope == null || sharedElementKey == null) {
            return this
        }
        return with(sharedTransitionScope) {
            this@sharedBoundsIfAvailable.sharedBounds(
                rememberSharedContentState(key = key),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = AlbumArtBoundsTransform
            )
        }
    }

    // For the ARTWORK: it's the same image on both screens, just a different size and
    // corner radius. sharedElement animates the bounds of a single element instead of
    // cross-fading two overlapping copies, which is what was causing the "two images
    // clipping together" / transparency glitch. clipInOverlayDuringTransition keeps the
    // rounded corners applied for every frame of the animation, not just the resting state.
    @Composable
    fun Modifier.sharedArtworkIfAvailable(key: String): Modifier {
        if (sharedTransitionScope == null || animatedVisibilityScope == null || sharedElementKey == null) {
            return this
        }
        return with(sharedTransitionScope) {
            this@sharedArtworkIfAvailable.sharedElement(
                rememberSharedContentState(key = key),
                animatedVisibilityScope = animatedVisibilityScope,
                clipInOverlayDuringTransition = OverlayClip(AlbumArtShape()),
                boundsTransform = AlbumArtBoundsTransform
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover Art
        Box(
            modifier = Modifier
                .size(120.dp)
                .sharedArtworkIfAvailable("$sharedElementPrefix-art-$sharedElementKey")
                .clip(AlbumArtShape())
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                val albumArtRequest = remember(imageUrl, imageIsEmbeddedArt) {
                    // Embedded art is pulled out of the audio file itself; playlist
                    // covers are plain image URIs Coil loads directly.
                    val data = if (imageIsEmbeddedArt) {
                        EmbeddedAlbumArt(imageUrl.toUri())
                    } else {
                        imageUrl
                    }
                    ImageRequest.Builder(context)
                        .data(data)
                        .size(AlbumArtRequestSize)
                        .crossfade(false)
                        .build()
                }
                AlbumArtImage(
                    model = albumArtRequest,
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
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsIfAvailable("$sharedElementPrefix-title-$sharedElementKey")
            )

            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.titleMedium.copy(color = accentColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedBoundsIfAvailable("$sharedElementPrefix-artist-$sharedElementKey")
                )
            }
            BasicText(
                text = "$trackCount Tracks â€¢ $durationText",
                style = FoamTheme.typography.body,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Play Button
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
