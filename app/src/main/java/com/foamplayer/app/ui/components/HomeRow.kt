package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.foamplayer.app.R
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.theme.FoamTheme

/** Horizontal breathing room around a Home row's header and its card pager. */
private val HomeRowEdgePadding = 16.dp

/** Gap between two cards of a Home row's pager. */
private val HomeRowCardSpacing = 8.dp

/** Gap between a Home row and its neighbor sections. */
val HomeRowSpacing = 40.dp

/** Width of the large cards in a Home row's pager (albums, playlists). */
val HomeRowCardWidth = 140.dp

/** Width of the small cards in a Home row's pager (tracks, artists). */
val HomeRowCardSmallWidth = 112.dp

/** Padding around a card's content (artwork + title), tighter than grid cells. */
private val HomeRowCardPadding = 4.dp

/** Gap between a row's header line and its card pager. */
private val HomeRowHeaderSpacing = 4.dp

/**
 * One horizontal section of the Home screen: a header line with the row title
 * (left) and an optional arrow button (right) leading to the row's "See all"
 * page, above a lazily scrolling pager of cards built through [itemContent].
 * The row hides itself entirely while [items] is empty so sparse libraries
 * stay graceful.
 *
 * Callers compose each card out of the shared [HomeRowCard] so every row's
 * items look identical.
 */
@Composable
fun <T> HomeRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    onSeeAllClick: (() -> Unit)? = null,
    key: ((item: T) -> Any)? = null,
    itemContent: @Composable (item: T) -> Unit
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HomeRowEdgePadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium.copy(fontSize = 20.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (onSeeAllClick != null) {
                Box(
                    modifier = Modifier
                        .padding(start = HomeRowEdgePadding)
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                            onClick = onSeeAllClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FoamTheme.icons.current.arrowForward,
                        contentDescription = stringResource(R.string.home_see_all),
                        tint = FoamTheme.accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(HomeRowHeaderSpacing))

        LazyRow(
            contentPadding = PaddingValues(horizontal = HomeRowEdgePadding),
            horizontalArrangement = Arrangement.spacedBy(HomeRowCardSpacing)
        ) {
            items(items, key = key) { item ->
                itemContent(item)
            }
        }
    }
}

/**
 * The shared card component for every Home row: a big square artwork on top
 * (the item's cover, or its initial when there is none) and a small
 * single-line, ellipsized title below — the horizontal-pager counterpart of
 * [MediaGridCell]. [imageIsEmbeddedArt] selects how [imageUrl] loads: audio
 * file paths embed their art (tracks, albums), playlist covers are plain
 * image URIs. [round] clips the artwork into a full circle (artists) instead
 * of the app's rounded-square album shape. [width] sizes the card: large for
 * albums/playlists, small for tracks and artists. [onLongClick] opens the
 * item's actions modal (track / collection / playlist).
 *
 * When [sharedArtKey]/[sharedTitleKey] and both transition scopes are given,
 * the artwork and title join the app's shared-element transitions:
 * [sharedInPlayerTransition] true styles them as the source of a player
 * open/close flight (like [TrackListItem]: default bounds transform, no
 * overlay clip), false styles them for a destination navigation (like
 * [CollectionListItem]: [AlbumArtBoundsTransform] + the artwork shape as
 * overlay clip).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeRowCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp = HomeRowCardWidth,
    imageIsEmbeddedArt: Boolean = true,
    icon: ImageVector? = null,
    round: Boolean = false,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedArtKey: String? = null,
    sharedTitleKey: String? = null,
    sharedInPlayerTransition: Boolean = false
) {
    val context = LocalContext.current
    val artShape = if (round) CircleShape else AlbumArtShape()
    // Gentle radius on the press-highlight container: a large one would clip
    // the title into the card's bottom rounded corners.
    val cellShape = RoundedCornerShape(FoamTheme.shapes.cardRounding)
    val sharedArtModifier = Modifier.rowSharedArtwork(
        key = sharedArtKey.orEmpty(),
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        enabled = sharedArtKey != null,
        overlayClip = if (sharedInPlayerTransition) null else artShape,
        boundsTransform = if (sharedInPlayerTransition) null else AlbumArtBoundsTransform
    )
    val sharedTitleModifier = Modifier.rowSharedText(
        key = sharedTitleKey.orEmpty(),
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        enabled = sharedTitleKey != null,
        boundsTransform = if (sharedInPlayerTransition) null else AlbumArtBoundsTransform
    )

    Column(
        modifier = modifier
            .width(width)
            .clip(cellShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(HomeRowCardPadding)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(sharedArtModifier)
                .clip(artShape)
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                val artRequest = remember(imageUrl, imageIsEmbeddedArt) {
                    ImageRequest.Builder(context)
                        .data(
                            if (imageIsEmbeddedArt) {
                                EmbeddedAlbumArt(imageUrl.toUri())
                            } else {
                                imageUrl
                            }
                        )
                        .size(AlbumArtRequestSize)
                        .build()
                }
                AlbumArtImage(
                    model = artRequest,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    placeholderSize = 32.dp
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
                    style = FoamTheme.typography.titleLarge.copy(fontSize = 32.sp)
                )
            }
        }

        BasicText(
            text = title,
            style = FoamTheme.typography.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = HomeRowCardPadding)
                .then(sharedTitleModifier)
        )
    }
}
