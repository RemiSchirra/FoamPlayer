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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Horizontal breathing room around a Home row's header and its card pager.
 * The Home list itself has no horizontal padding, so the pager scrolls its
 * cards all the way to the screen edges instead of being masked short of them.
 */
private val HomeRowEdgePadding = 24.dp

/** Gap between a row's title and its "See all" arrow button. */
private val HomeRowHeaderButtonGap = 16.dp

/** Gap between two cards of a Home row's pager. */
private val HomeRowCardSpacing = 8.dp

/** Gap between a row and its neighbor sections. */
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
 * items look identical. [itemContent] runs in [LazyItemScope] so cards can
 * opt into placement animations with Modifier.animateItem() -- recommendations
 * reorder as listening data refreshes, and the cards glide instead of popping.
 * Placement animation only takes effect when a [key] is given.
 */
@Composable
fun <T> HomeRow(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    onSeeAllClick: (() -> Unit)? = null,
    key: ((item: T) -> Any)? = null,
    itemContent: @Composable LazyItemScope.(item: T) -> Unit
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
                        .padding(start = HomeRowHeaderButtonGap)
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
 * [placeholderIcon] shows while the cover loads: the music note by default,
 * or the row's own icon on album/artist cards (the album disc, a person) so
 * the loading look matches the collection.
 *
 * When [sharedArtKey]/[sharedTitleKey] and both transition scopes are given,
 * the artwork and title join the app's shared-element transitions:
 * [sharedInPlayerTransition] true styles them as the source of a player
 * open/close flight (like [TrackListItem]: default bounds transform, no
 * overlay clip), false styles them for a destination navigation (like
 * [CollectionListItem]: [AlbumArtBoundsTransform] + the artwork shape as
 * overlay clip). With [flightHeroTarget] set AND the target collection's hero
 * resolving to an immersive layout, the artwork additionally carries the
 * mirrored collection flight morph (see [collectionRowFlightArtwork]): on
 * closing flights the shared-element overlay renders only this card's own
 * content, which must then depart from the hero's square, faded look.
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
    placeholderIcon: ImageVector = Icons.Rounded.MusicNote,
    round: Boolean = false,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedArtKey: String? = null,
    sharedTitleKey: String? = null,
    sharedInPlayerTransition: Boolean = false,
    flightHeroTarget: CollectionHeroTarget? = null
) {
    val context = LocalContext.current
    val artShape = if (round) CircleShape else AlbumArtShape()
    // Gentle radius on the press-highlight container: a large one would clip
    // the title into the card's bottom rounded corners.
    val cellShape = RoundedCornerShape(FoamTheme.shapes.cardRounding)
    val immersiveFlightHero = !sharedInPlayerTransition &&
            flightHeroTarget != null &&
            LocalCollectionHeroLayoutLookup.current(flightHeroTarget).isImmersive
    val sharedArtModifier = if (immersiveFlightHero) {
        Modifier.collectionRowFlightArtwork(
            key = sharedArtKey.orEmpty(),
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = sharedArtKey != null,
            restShape = artShape,
            immersiveFlightHero = true,
            // Fully round cards (artists) land their flight morph on the circle
            // too, so the radius never pops between the circle and the album
            // rounding at flight end.
            fullyRounded = round
        )
    } else {
        Modifier.rowSharedArtwork(
            key = sharedArtKey.orEmpty(),
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = sharedArtKey != null,
            overlayClip = if (sharedInPlayerTransition) null else artShape,
            boundsTransform = if (sharedInPlayerTransition) null else AlbumArtBoundsTransform
        )
    }
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
                // The flight variant carries its own graphicsLayer shape (the
                // morph) -- a static clip here would re-round every flight frame.
                // The rest shadow sits with the static look: zero during a nav
                // flight, like every other artwork shadow.
                .let {
                    if (immersiveFlightHero) it
                    else it.shadow(elevation = MediaItemArtElevation, shape = artShape)
                }
                .let { if (immersiveFlightHero) it else it.clip(artShape) }
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                // Collection cards decode under the destination hero's
                // deterministic key (albumArtMemoryCacheKey, as read by
                // CollectionListScreen/CollectionHero): with the auto key the
                // hero misses the cache and the open flight's first frame
                // shows the placeholder. Track cards keep the auto key -- the
                // full player's art requests read it.
                val heroKeyed = flightHeroTarget != null
                val artRequest = remember(imageUrl, imageIsEmbeddedArt, heroKeyed) {
                    ImageRequest.Builder(context)
                        .data(
                            if (imageIsEmbeddedArt) {
                                EmbeddedAlbumArt(imageUrl.toUri())
                            } else {
                                imageUrl
                            }
                        )
                        .size(AlbumArtRequestSize)
                        .memoryCacheKey(if (heroKeyed) albumArtMemoryCacheKey(imageUrl) else null)
                        .build()
                }
                AlbumArtImage(
                    model = artRequest,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    placeholderSize = 32.dp,
                    placeholderIcon = placeholderIcon
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = HomeRowCardPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.then(sharedTitleModifier)
            )
        }
    }
}