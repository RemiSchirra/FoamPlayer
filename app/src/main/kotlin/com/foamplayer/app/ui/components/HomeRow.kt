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
import com.foamplayer.app.ui.theme.foamSurfaceVariant

private val HomeRowEdgePadding = 24.dp
private val HomeRowHeaderButtonGap = 16.dp
private val HomeRowCardSpacing = 8.dp

/** Vertical gap between two Home rows. */
val HomeRowSpacing = 40.dp

/** Card width for albums/playlists. */
val HomeRowCardWidth = 140.dp

/** Card width for tracks/artists. */
val HomeRowCardSmallWidth = 112.dp

private val HomeRowCardPadding = 4.dp
private val HomeRowHeaderSpacing = 4.dp

/**
 * One horizontal Home screen section: a title with an optional "See all"
 * button, above a lazily scrolling pager of cards built through [itemContent].
 * Hides itself while [items] is empty.
 *
 * [itemContent] runs in [LazyItemScope] so cards can animate item placement
 * (recommendations reorder as listening data refreshes). Only takes effect
 * when [key] is set.
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
 * Shared card of every Home row: square artwork (or initial) with a
 * single-line title below — the horizontal-pager counterpart of
 * [MediaGridCell].
 *
 * [imageIsEmbeddedArt] selects how [imageUrl] loads: audio file paths embed
 * their art (tracks, albums), playlist covers are plain image URIs. [round]
 * clips the artwork into a circle (artists). [width] sizes the card (large
 * for albums/playlists, small for tracks/artists). [onLongClick] opens the
 * item's actions modal.
 *
 * When [sharedArtKey]/[sharedTitleKey] and both transition scopes are given,
 * the artwork and title join the shared-element transitions:
 * [sharedInPlayerTransition] true styles them as the source of a player
 * open/close flight, false as a destination navigation. With
 * [flightHeroTarget] resolving to an immersive layout, the artwork also
 * carries the mirrored collection flight morph (see
 * [collectionRowFlightArtwork]).
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
    sharedInPlayerTransition: Boolean = false,
    flightHeroTarget: CollectionHeroTarget? = null
) {
    val context = LocalContext.current
    val artShape = if (round) CircleShape else AlbumArtShape()
    // Small radius on the press-highlight container: a large one clips the title.
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
            // Fully round cards (artists) land their flight morph on the circle too.
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
                // morph); a static clip would re-round every flight frame.
                .let {
                    if (immersiveFlightHero) it
                    else it.shadow(elevation = MediaItemArtElevation, shape = artShape)
                }
                .let { if (immersiveFlightHero) it else it.clip(artShape) }
                .foamSurfaceVariant(),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                // Collection cards decode under the destination hero's
                // deterministic key so the open flight's first frame hits the
                // cache. Track cards keep the auto key (the full player reads it).
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
                NoAlbumArtLetter(title = title)
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
