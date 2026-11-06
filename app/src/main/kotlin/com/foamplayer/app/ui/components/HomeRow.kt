package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
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
import com.foamplayer.app.ui.navigation.isActiveSharedCollectionFlightKey
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurfaceVariant

private val HomeRowEdgePadding = 12.dp
private val HomeRowHeaderButtonGap = 16.dp

/** Cards sit flush: the press highlight spans the full card, so no gap. */
private val HomeRowCardSpacing = 0.dp

/** Vertical gap between two Home rows. */
val HomeRowSpacing = 40.dp

/** Card width for albums/playlists. */
val HomeRowCardWidth = 140.dp

/** Card width for tracks/artists. */
val HomeRowCardSmallWidth = 112.dp

/**
 * Inner breathing room of a card. The ripple highlight fills the whole card
 * (adjacent cards touch), so this is the gap between the artwork and the
 * highlight's bounding box.
 */
private val HomeRowCardPadding = MediaGridCellPadding
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
    val cellShape = MediaCellHighlightShape(
        inset = HomeRowCardPadding,
        rounding = FoamTheme.shapes.miniPlayerRounding,
        fullyRounded = round
    )
    val collectionFlight = !sharedInPlayerTransition && flightHeroTarget != null
    val immersiveFlightHero = collectionFlight &&
            LocalCollectionHeroLayoutLookup.current(flightHeroTarget!!).isImmersive
    val closeFlightActive = rememberCollectionCloseFlightActive(
        sharedTransitionScope = sharedTransitionScope?.takeIf { collectionFlight && sharedArtKey != null },
        animatedVisibilityScope = animatedVisibilityScope
    ).value
    val staticArtElevation =
        if (isActiveSharedCollectionFlightKey(sharedArtKey.orEmpty()) && closeFlightActive) {
            0.dp
        } else {
            MediaItemArtElevation
        }
    val sharedArtModifier = if (collectionFlight) {
        Modifier.collectionRowFlightArtwork(
            key = sharedArtKey.orEmpty(),
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            restShape = artShape,
            immersiveFlightHero = immersiveFlightHero,
            fullyRounded = round,
            castsFlightShadow = true
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
        collectionFlight = !sharedInPlayerTransition && flightHeroTarget != null,
        enabled = sharedTitleKey != null,
        boundsTransform = if (sharedInPlayerTransition) null else AlbumArtBoundsTransform
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier = modifier.width(width)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(cellShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onLongClick = onLongClick,
                    onClick = onClick
                )
        )
        Column(modifier = Modifier.padding(HomeRowCardPadding)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .let {
                        if (collectionFlight) {
                            // Shadow outside, shared artwork inside (see above).
                            it.shadow(elevation = staticArtElevation, shape = artShape)
                                .then(sharedArtModifier)
                        } else {
                            it.then(sharedArtModifier)
                                .shadow(elevation = MediaItemArtElevation, shape = artShape)
                                .clip(artShape)
                        }
                    }
                    .foamSurfaceVariant(),
                contentAlignment = Alignment.Center
            ) {
                if (imageUrl != null) {
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
                            .placeholderMemoryCacheKey(
                                if (heroKeyed) albumArtMemoryCacheKey(imageUrl) else null
                            )
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

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(cellShape)
                .indication(
                    interactionSource = interactionSource,
                    indication = ripple(color = FoamTheme.colors.textPrimary)
                )
        )
    }
}