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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
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
 * (columns fixed by the GRID_* mode); [onDensityChange], when set, adds the
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
    content: MediaListContentScope.() -> Unit
) {
    var showOrganisationModal by remember { mutableStateOf(false) }

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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    val gridScope = this
                    item(
                        key = "collection_header",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        CollectionHeaderSection(
                            title = title,
                            subtitle = subtitle,
                            itemCount = itemCount,
                            imageUrl = headerImageUrl,
                            icon = headerIcon
                        )
                    }

                    GridMediaContentScope(gridScope).content()

                    item(key = "collection_bottom_spacer", span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val listScope = this
                    item(key = "collection_header") {
                        CollectionHeaderSection(
                            title = title,
                            subtitle = subtitle,
                            itemCount = itemCount,
                            imageUrl = headerImageUrl,
                            icon = headerIcon
                        )
                    }

                    ListMediaContentScope(listScope).content()

                    item(key = "collection_bottom_spacer") {
                        Spacer(modifier = Modifier.height(80.dp))
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
            onDismiss = { showOrganisationModal = false }
        )
    }
}

@Composable
private fun CollectionHeaderSection(
    title: String,
    subtitle: String?,
    itemCount: Int,
    imageUrl: String?,
    icon: ImageVector?
) {
    val context = LocalContext.current

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
                AsyncImage(
                    model = headerArtRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(48.dp)
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

            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.accentColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicText(
                text = "$itemCount items",
                style = FoamTheme.typography.body,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
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
    icon: ImageVector? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementKey: String? = null
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
                        imageUrl = imageUrl,
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
    // both ends of a shared-element transition — round identically.
    val elementKey = sharedElementKey
    val artShape = AlbumArtShape()
    val highlightBackground = activeRowHighlight(active)

    val sharedArtModifier = Modifier
        .rowSharedArtwork(
            key = "album-art-$elementKey",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = elementKey != null,
            overlayClip = artShape,
            boundsTransform = AlbumArtBoundsTransform
        )
        .clip(artShape)
        .background(FoamTheme.colors.surfaceVariant)

    val sharedTitleModifier = Modifier.rowSharedText(
        key = "album-title-$elementKey",
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        enabled = elementKey != null,
        boundsTransform = AlbumArtBoundsTransform
    )

    val sharedSubtitleModifier = if (subtitle != null) {
        Modifier.rowSharedText(
            key = "album-artist-$elementKey",
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
            artworkContent = { CollectionArtwork(title, imageUrl, icon) },
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
        artworkContent = { CollectionArtwork(title, imageUrl, icon) },
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
    icon: ImageVector?
) {
    val context = LocalContext.current
    if (imageUrl != null) {
        val albumArtRequest = remember(imageUrl) {
            ImageRequest.Builder(context)
                .data(EmbeddedAlbumArt(imageUrl.toUri()))
                .size(AlbumArtRequestSize)
                .build()
        }
        AsyncImage(
            model = albumArtRequest,
            contentDescription = "Album Art",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
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
