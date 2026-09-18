package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.request.ImageRequest
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.theme.FoamTheme

/** Height of the immersive hero image, as a fraction of the full screen height. */
const val CollectionHeroImmersiveHeightFraction = 0.5f

/** Height of the compact-immersive hero image, as a fraction of the full screen height. */
const val CollectionHeroCompactImmersiveHeightFraction = 0.30f

/** Point (fraction of the image height) where the immersive artwork's bottom fade begins. */
const val CollectionHeroImmersiveFadeStartFraction = 0.65f

/** Vertical position of the immersive text/play block, as a fraction of the image height. */
const val CollectionHeroImmersiveOverlayFraction = 0.75f

/** Vertical position of the compact-immersive text/play block, as a fraction of the image height. */
const val CollectionHeroCompactImmersiveOverlayFraction = 0.6f

/** Alpha of the subtle scrim over the immersive hero's status-bar zone (icon contrast). */
const val CollectionHeroStatusBarScrimAlpha = 0.35f

/** Extra height of the status-bar scrim below the inset itself. */
val CollectionHeroStatusBarScrimExtraHeight = 12.dp

/** Height of the scroll top bar's gradient zone, measured below the status-bar inset. */
val CollectionScrollTopBarZoneHeight = 64.dp

/** Scroll distance over which the scroll top bar fades from invisible to fully shown. */
val CollectionScrollTopBarFadeDistance = 160.dp

/** Peak alpha of the scroll top bar's black gradient at the very top of the screen. */
const val CollectionScrollTopBarMaxAlpha = 0.55f

/** Horizontal padding kept clear of the back/more buttons for the scroll top bar's title. */
val CollectionScrollTopBarTitleHorizontalPadding = 56.dp

/** Padding between the immersive overlay block and the list's first row. */
val CollectionHeroImmersiveListTopPadding = 16.dp

/**
 * Hero layout of the "list of lists" screens (genres, years, folders and every
 * other collection that is not an album/artist/playlist detail), resolved from
 * the Default hero-layout setting at the app root.
 */
val LocalDefaultCollectionHeroLayout = compositionLocalOf { CollectionHeroLayout.Compact }

/** Total height of an immersive hero image for [layout] (status-bar bleed included). */
@Composable
fun collectionHeroImmersiveHeight(layout: CollectionHeroLayout): Dp {
    val fraction = when (layout) {
        CollectionHeroLayout.Immersive -> CollectionHeroImmersiveHeightFraction
        CollectionHeroLayout.CompactImmersive -> CollectionHeroCompactImmersiveHeightFraction
        else -> 0f
    }
    return LocalConfiguration.current.screenHeightDp.dp * fraction
}

/** Overlay-block vertical position for [layout], as a fraction of the image height. */
private fun collectionHeroOverlayFraction(layout: CollectionHeroLayout): Float = when (layout) {
    CollectionHeroLayout.Immersive -> CollectionHeroImmersiveOverlayFraction
    else -> CollectionHeroCompactImmersiveOverlayFraction
}

/**
 * The immersive layouts' leading list item: an invisible spacer reserving the
 * hero's room. Instead of estimating the overlay block's height, it
 * subcomposes the block's real content (the same [CollectionHeroOverlayContent]
 * the hero overlays, minus the shared-element wiring, which has no effect on
 * layout) and measures it — so the list's first row clears the play pill by
 * [CollectionHeroImmersiveListTopPadding] with no dead space below the artwork,
 * whatever the title/subtitle/button add up to.
 *
 * The measured copy is also placed at the overlay's exact position, invisible
 * (ghost content, semantics cleared) but hit-testable: the hero draws the visible
 * block from BEHIND the list, and a full-screen scrollable shields the
 * siblings behind it from hit-testing (its pointer-input chain does not share
 * input with siblings), so taps over the hero's play pill would never reach
 * it. This invisible twin lives inside the list's own subtree, where taps do
 * land, and forwards [onPlayAllClick] — the visible pill stays purely visual.
 * The twin's copy is ghost-rendered rather than alpha-0 so the play pill's
 * ripple (drawn by the twin, the real hit target) is visible over the hero.
 *
 * [onVisibleHeightPx] reports the measured total (px) once the spacer is laid
 * out, so the host screen can hand the hero's 1:1 scroll-away over to the same
 * constant when the spacer is scrolled past (see [CollectionHero]'s
 * [CollectionHero.scrollOffsetPx]).
 */
@Composable
fun CollectionHeroImmersiveSpacer(
    layout: CollectionHeroLayout,
    title: String,
    subtitle: String?,
    statsText: String,
    onVisibleHeightPx: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = FoamTheme.accentColor,
    onPlayAllClick: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    val statusBarHeightPx = WindowInsets.statusBars.getTop(density)
    val heroHeight = collectionHeroImmersiveHeight(layout)
    val heroVisiblePx = with(density) { heroHeight.roundToPx() - statusBarHeightPx }
    val overlayTopPx = with(density) {
        (heroHeight * collectionHeroOverlayFraction(layout)).roundToPx() - statusBarHeightPx
    }
    val listTopPaddingPx = with(density) { CollectionHeroImmersiveListTopPadding.roundToPx() }
    // The overlay hangs off a full-width Box with 16dp side padding; measuring
    // at the same width keeps line wraps identical to the real block.
    val overlayMaxWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }

    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val overlayContent = subcompose(Unit) {
            Box(
                modifier = Modifier.clearAndSetSemantics { }
            ) {
                CollectionHeroOverlayContent(
                    title = title,
                    subtitle = subtitle,
                    statsText = statsText,
                    accentColor = accentColor,
                    centered = layout == CollectionHeroLayout.Immersive,
                    onPlayAllClick = onPlayAllClick,
                    ghost = true
                )
            }
        }.first().measure(Constraints(maxWidth = overlayMaxWidthPx))
        val visibleHeightPx = maxOf(
            heroVisiblePx,
            overlayTopPx + overlayContent.height + listTopPaddingPx
        )
        onVisibleHeightPx(visibleHeightPx)
        layout(constraints.maxWidth, visibleHeightPx) {
            overlayContent.placeRelative(0, overlayTopPx)
        }
    }
}

/**
 * The shared collection hero: artwork plus title/subtitle/stats and an
 * optional play button, laid out per [CollectionHeroLayout]. All four layouts
 * share the same content slots; [CollectionHeroLayout.Compact] is the classic
 * image-left/text-right header, [CollectionHeroLayout.Normal] a big rounded
 * artwork with centered text below, and the immersive variants bleed the
 * artwork under the status bar with the text block overlaid on it.
 *
 * For the immersive layouts the composable is a screen-level layer: place it
 * from a screen-level [Box] (it offsets itself up behind the status bar) and
 * make the list's leading item a [CollectionHeroImmersiveSpacer].
 * [listScrollOffsetPx] — the list's
 * current scroll in px — makes the hero scroll away 1:1 with the list, exactly
 * like a header item would. [overlayTextAlpha] lets the screen crossfade the
 * overlaid text out while the scroll top bar's title fades in.
 *
 * When a shared-element context is provided AND the layout is immersive, the
 * artwork's corner radius (theme percentage -> 0) and bottom fade (none ->
 * faded) animate across the shared-element transition, so the flight departs
 * from the source row's rounded, unfaded look and lands on the square, faded
 * hero. Compact and Normal keep the static theme shape and no fade.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CollectionHero(
    layout: CollectionHeroLayout,
    title: String,
    subtitle: String?,
    statsText: String,
    imageUrl: String?,
    onPlayAllClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    imageIsEmbeddedArt: Boolean = true,
    icon: ImageVector? = null,
    accentColor: Color = FoamTheme.accentColor,
    overlayTextAlpha: (() -> Float)? = null,
    transitionAlpha: (() -> Float)? = null,
    scrollOffsetPx: (() -> Int)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementKey: String? = null,
    sharedElementPrefix: String = "album"
) {
    when (layout) {
        CollectionHeroLayout.Compact -> CollectionHeroCompact(
            title = title,
            subtitle = subtitle,
            statsText = statsText,
            imageUrl = imageUrl,
            imageIsEmbeddedArt = imageIsEmbeddedArt,
            icon = icon,
            onPlayAllClick = onPlayAllClick,
            accentColor = accentColor,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedElementKey = sharedElementKey,
            sharedElementPrefix = sharedElementPrefix,
            modifier = modifier
        )
        CollectionHeroLayout.Normal -> CollectionHeroNormal(
            title = title,
            subtitle = subtitle,
            statsText = statsText,
            imageUrl = imageUrl,
            imageIsEmbeddedArt = imageIsEmbeddedArt,
            icon = icon,
            onPlayAllClick = onPlayAllClick,
            accentColor = accentColor,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedElementKey = sharedElementKey,
            sharedElementPrefix = sharedElementPrefix,
            modifier = modifier
        )
        CollectionHeroLayout.Immersive, CollectionHeroLayout.CompactImmersive -> CollectionHeroImmersive(
            layout = layout,
            title = title,
            subtitle = subtitle,
            statsText = statsText,
            imageUrl = imageUrl,
            imageIsEmbeddedArt = imageIsEmbeddedArt,
            icon = icon,
            onPlayAllClick = onPlayAllClick,
            accentColor = accentColor,
            overlayTextAlpha = overlayTextAlpha,
            transitionAlpha = transitionAlpha,
            scrollOffsetPx = scrollOffsetPx,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedElementKey = sharedElementKey,
            sharedElementPrefix = sharedElementPrefix,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CollectionHeroCompact(
    title: String,
    subtitle: String?,
    statsText: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    icon: ImageVector?,
    onPlayAllClick: (() -> Unit)?,
    accentColor: Color,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
    sharedElementPrefix: String,
    modifier: Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .collectionHeroSharedArtwork(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedElementKey = sharedElementKey,
                    sharedElementPrefix = sharedElementPrefix,
                    flightFraction = null
                )
                .clip(AlbumArtShape())
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CollectionHeroArtworkContent(
                title = title,
                imageUrl = imageUrl,
                imageIsEmbeddedArt = imageIsEmbeddedArt,
                icon = icon
            )
        }

        Spacer(modifier = Modifier.width(20.dp))

        Column(modifier = Modifier.weight(1f)) {
            CollectionHeroTexts(
                title = title,
                subtitle = subtitle,
                statsText = statsText,
                accentColor = accentColor,
                centered = false,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedElementKey = sharedElementKey,
                sharedElementPrefix = sharedElementPrefix
            )

            if (onPlayAllClick != null) {
                Spacer(modifier = Modifier.height(8.dp))
                CollectionHeroPlayButton(onClick = onPlayAllClick, accentColor = accentColor)
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CollectionHeroNormal(
    title: String,
    subtitle: String?,
    statsText: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    icon: ImageVector?,
    onPlayAllClick: (() -> Unit)?,
    accentColor: Color,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
    sharedElementPrefix: String,
    modifier: Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .aspectRatio(1f)
                .collectionHeroSharedArtwork(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedElementKey = sharedElementKey,
                    sharedElementPrefix = sharedElementPrefix,
                    flightFraction = null
                )
                .clip(AlbumArtShape())
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CollectionHeroArtworkContent(
                title = title,
                imageUrl = imageUrl,
                imageIsEmbeddedArt = imageIsEmbeddedArt,
                icon = icon
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CollectionHeroTexts(
                title = title,
                subtitle = subtitle,
                statsText = statsText,
                accentColor = accentColor,
                centered = true,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedElementKey = sharedElementKey,
                sharedElementPrefix = sharedElementPrefix
            )

            if (onPlayAllClick != null) {
                Spacer(modifier = Modifier.height(8.dp))
                CollectionHeroPlayButton(onClick = onPlayAllClick, accentColor = accentColor)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CollectionHeroImmersive(
    layout: CollectionHeroLayout,
    title: String,
    subtitle: String?,
    statsText: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    icon: ImageVector?,
    onPlayAllClick: (() -> Unit)?,
    accentColor: Color,
    overlayTextAlpha: (() -> Float)?,
    transitionAlpha: (() -> Float)?,
    scrollOffsetPx: (() -> Int)?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
    sharedElementPrefix: String,
    modifier: Modifier
) {
    val density = LocalDensity.current
    val statusBarHeightPx = WindowInsets.statusBars.getTop(density)
    val statusBarHeight = with(density) { statusBarHeightPx.toDp() }
    val heroHeight = collectionHeroImmersiveHeight(layout)
    val flightFraction = rememberCollectionHeroFlightFraction(
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
    // The overlay block hangs off the hero at [collectionHeroOverlayFraction]
    // and is taller than the room left inside the hero image, so it extends
    // below the Box's bottom edge. Drawing is never clipped, but hit-testing
    // is gated by every ancestor's layout bounds — so the root Box must grow
    // to cover the overlay or the play pill, however far it hangs, would be
    // visible yet untappable.
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val heroHeightPx = with(density) { heroHeight.roundToPx() }
    val overlayTopPx = with(density) {
        (heroHeight * collectionHeroOverlayFraction(layout)).roundToPx()
    }
    val rootHeight = with(density) {
        maxOf(heroHeightPx, overlayTopPx + overlayHeightPx).toDp()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rootHeight)
            .offset {
                // Bleed behind the status bar at rest, then ride the list's
                // scroll 1:1 so the hero scrolls away like a header item.
                IntOffset(0, -statusBarHeightPx - (scrollOffsetPx?.invoke() ?: 0))
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .collectionHeroSharedArtwork(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedElementKey = sharedElementKey,
                    sharedElementPrefix = sharedElementPrefix,
                    flightFraction = flightFraction
                )
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fadeStrength = 1f - flightFraction.value
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            CollectionHeroImmersiveFadeStartFraction to Color.Black,
                            1f to Color.Black.copy(alpha = 1f - fadeStrength)
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CollectionHeroArtworkContent(
                title = title,
                imageUrl = imageUrl,
                imageIsEmbeddedArt = imageIsEmbeddedArt,
                icon = icon
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(statusBarHeight + CollectionHeroStatusBarScrimExtraHeight)
                .collectionHeroOverlayLayer(sharedTransitionScope)
                .graphicsLayer {
                    alpha = (1f - flightFraction.value) * (transitionAlpha?.invoke() ?: 1f)
                }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = CollectionHeroStatusBarScrimAlpha),
                        1f to Color.Transparent
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .offset(y = heroHeight * collectionHeroOverlayFraction(layout))
                .onSizeChanged { overlayHeightPx = it.height }
                .collectionHeroOverlayLayer(sharedTransitionScope)
                .graphicsLayer {
                    alpha = (overlayTextAlpha?.invoke() ?: 1f) *
                            (1f - flightFraction.value) * (transitionAlpha?.invoke() ?: 1f)
                },
            horizontalAlignment = if (layout == CollectionHeroLayout.Immersive) {
                Alignment.CenterHorizontally
            } else {
                Alignment.Start
            }
        ) {
            CollectionHeroOverlayContent(
                title = title,
                subtitle = subtitle,
                statsText = statsText,
                accentColor = accentColor,
                centered = layout == CollectionHeroLayout.Immersive,
                onPlayAllClick = onPlayAllClick,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedElementKey = sharedElementKey,
                sharedElementPrefix = sharedElementPrefix
            )
        }
    }
}

/**
 * The immersive overlay block's content: title/subtitle/stats and the optional
 * play pill, inset 16dp like the rest of the hero. Shared by the hero's real
 * overlay and [CollectionHeroImmersiveSpacer]'s measuring copy, so the two can
 * never drift apart.
 *
 * [ghost] renders everything invisible for that measuring copy — texts and
 * pill visuals at alpha 0 — while keeping the pill hit-testable with a live
 * ripple, which is drawn by the clickable's indication layer and therefore
 * unaffected by the ghosting.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CollectionHeroOverlayContent(
    title: String,
    subtitle: String?,
    statsText: String,
    accentColor: Color,
    centered: Boolean,
    onPlayAllClick: (() -> Unit)?,
    ghost: Boolean = false,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementKey: String? = null,
    sharedElementPrefix: String = "album"
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        CollectionHeroTexts(
            title = title,
            subtitle = subtitle,
            statsText = statsText,
            accentColor = accentColor,
            centered = centered,
            ghost = ghost,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedElementKey = sharedElementKey,
            sharedElementPrefix = sharedElementPrefix
        )

        if (onPlayAllClick != null) {
            Spacer(modifier = Modifier.height(8.dp))
            CollectionHeroPlayButton(
                onClick = onPlayAllClick,
                accentColor = accentColor,
                ghost = ghost
            )
        }
    }
}

/** Artwork fill: embedded/plain image, collection icon, or the title's initial. */
@Composable
private fun CollectionHeroArtworkContent(
    title: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    icon: ImageVector?
) {
    val context = LocalContext.current
    if (imageUrl != null) {
        val heroArtRequest = remember(imageUrl, imageIsEmbeddedArt) {
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
            model = heroArtRequest,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            placeholderSize = 40.dp
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CollectionHeroTexts(
    title: String,
    subtitle: String?,
    statsText: String,
    accentColor: Color,
    centered: Boolean,
    ghost: Boolean = false,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
    sharedElementPrefix: String
) {
    val titleStyle = FoamTheme.typography.titleLarge.let { style ->
        if (centered) style.copy(textAlign = TextAlign.Center) else style
    }
    val subtitleStyle = FoamTheme.typography.titleMedium.copy(color = accentColor).let { style ->
        if (centered) style.copy(textAlign = TextAlign.Center) else style
    }
    val statsStyle = FoamTheme.typography.body.let { style ->
        if (centered) style.copy(textAlign = TextAlign.Center) else style
    }
    val textWidth = if (centered) Modifier.fillMaxWidth() else Modifier

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (ghost) 0f else 1f },
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        BasicText(
            text = title,
            style = titleStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = textWidth.collectionHeroSharedText(
                key = "$sharedElementPrefix-title-$sharedElementKey",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                enabled = sharedElementKey != null
            )
        )

        if (subtitle != null) {
            BasicText(
                text = subtitle,
                style = subtitleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = textWidth.collectionHeroSharedText(
                    key = "$sharedElementPrefix-artist-$sharedElementKey",
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    enabled = sharedElementKey != null
                )
            )
        }
        BasicText(
            text = statsText,
            style = statsStyle,
            modifier = textWidth.padding(vertical = 4.dp)
        )
    }
}

/**
 * The accent play pill. [ghost] hides the pill's own visuals (background,
 * icon, label) while keeping it hit-testable with a live ripple — used by
 * [CollectionHeroImmersiveSpacer]'s twin, whose ripple must stay visible over
 * the hero even though the twin renders no content of its own.
 */
@Composable
private fun CollectionHeroPlayButton(
    onClick: () -> Unit,
    accentColor: Color,
    ghost: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (ghost) Color.Transparent else accentColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary)
            ) { onClick() }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.graphicsLayer { alpha = if (ghost) 0f else 1f },
            verticalAlignment = Alignment.CenterVertically
        ) {
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

/**
 * The shared-element flight fraction of the given transition scopes: 1f while
 * the transition is in flight (the departing screen's look), 0f once settled
 * (Visible). Consumed inside draw/graphicsLayer blocks, every frame of the
 * flight is draw-only. Returns a constant 0f when either scope is missing
 * (no shared-element context, e.g. heroes rendered without transition wiring).
 *
 * Used both by the hero itself (artwork corner/fade morph, chrome fades) and by
 * host screens fading their top-bar chrome in sync with the flight (see
 * [collectionHeroOverlayLayer]).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun rememberCollectionHeroFlightFraction(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): State<Float> {
    val noFlight = remember { mutableStateOf(0f) }
    if (sharedTransitionScope == null || animatedVisibilityScope == null) {
        return noFlight
    }
    return animatedVisibilityScope.transition.animateFloat(label = "collectionHeroFlight") { state ->
        if (state == EnterExitState.Visible) 0f else 1f
    }
}

/**
 * Lifts a hero chrome layer (status-bar scrim, overlay text/play block) into
 * the shared-element transition's overlay while a transition is active, so it
 * draws ABOVE the flying artwork (zIndexInOverlay 0f) instead of beneath it —
 * otherwise the artwork hides the chrome mid-flight and the chrome pops back
 * the instant the overlay empties at the end of the flight. No-op at rest and
 * without a [SharedTransitionScope]: the layer then draws in its normal place,
 * where the host hierarchy's own fades apply.
 *
 * Pair the lift with a flight alpha —
 * `(1f - rememberCollectionHeroFlightFraction(...).value) * scrollAlpha` — so
 * the chrome fades with the flight instead of sitting at full alpha over the
 * departing/arriving artwork.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.collectionHeroOverlayLayer(
    sharedTransitionScope: SharedTransitionScope?,
    zIndexInOverlay: Float = 1f
): Modifier {
    if (sharedTransitionScope == null) return this
    return with(sharedTransitionScope) {
        renderInSharedTransitionScopeOverlay(zIndexInOverlay = zIndexInOverlay)
    }
}

/**
 * Animated variant of [AlbumArtShape]: the theme's percentage corner radius
 * scaled by [fraction] (1f = fully rounded row look, 0f = square). Used as the
 * shared element's OverlayClip so the flight's rendered artwork keeps its
 * corners riding the transition instead of snapping between the two rest
 * looks. The fraction is read per outline creation (the overlay redraws every
 * flight frame), so no recomposition is involved.
 */
private class HeroFlightCornerShape(
    private val rounding: Float,
    private val fraction: () -> Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val percent = (rounding * 50f * fraction().coerceIn(0f, 1f)).coerceIn(0f, 50f)
        val radius = CornerRadius(size.width * percent / 100f, size.height * percent / 100f)
        return Outline.Rounded(
            RoundRect(
                rect = size.toRect(),
                topLeft = radius,
                topRight = radius,
                bottomRight = radius,
                bottomLeft = radius
            )
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.collectionHeroSharedArtwork(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
    sharedElementPrefix: String,
    flightFraction: State<Float>?
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null || sharedElementKey == null) {
        return this
    }
    val overlayShape = if (flightFraction != null) {
        HeroFlightCornerShape(FoamTheme.shapes.miniPlayerRounding) { flightFraction.value }
    } else {
        AlbumArtShape()
    }
    return with(sharedTransitionScope) {
        this@collectionHeroSharedArtwork.sharedElement(
            rememberSharedContentState(key = "$sharedElementPrefix-art-$sharedElementKey"),
            animatedVisibilityScope = animatedVisibilityScope,
            clipInOverlayDuringTransition = OverlayClip(overlayShape),
            boundsTransform = AlbumArtBoundsTransform,
            // Bottom of the transition overlay: every chrome layer lifted by
            // [collectionHeroOverlayLayer] and the shared texts (zIndex 1f)
            // draw above the flying artwork.
            zIndexInOverlay = 0f
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.collectionHeroSharedText(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    enabled: Boolean
): Modifier {
    if (!enabled || sharedTransitionScope == null || animatedVisibilityScope == null) {
        return this
    }
    return with(sharedTransitionScope) {
        this@collectionHeroSharedText.sharedBounds(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = AlbumArtBoundsTransform,
            // Above the flying artwork so the title never disappears behind it
            // mid-flight (the artwork is zIndexInOverlay 0f).
            zIndexInOverlay = 1f
        )
    }
}

/**
 * The scroll-triggered top bar: a black vertical gradient from the very top of
 * the screen (status bar included) down past the top-bar zone, with the
 * collection title fading in progressively. [fraction] (0f hidden, 1f shown)
 * comes from the list's scroll state; it is consumed inside a graphicsLayer,
 * so every scroll frame is draw-only. The bar draws NO buttons: the screen's
 * existing back/more controls render above it, keeping single hit targets.
 */
@Composable
fun CollectionScrollTopBar(
    title: String,
    fraction: () -> Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val statusBarHeightPx = WindowInsets.statusBars.getTop(density)
    val statusBarHeight = with(density) { statusBarHeightPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarHeight + CollectionScrollTopBarZoneHeight)
            .offset { IntOffset(0, -statusBarHeightPx) }
            .graphicsLayer { alpha = fraction() }
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = CollectionScrollTopBarMaxAlpha),
                        1f to Color.Transparent
                    )
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                // The status-bar insets are already consumed by the nav root, so
                // a statusBarsPadding() here would be a no-op; pad by the raw
                // measured inset instead so the title sits exactly on the same
                // level as the screen's back/more buttons (16dp below it).
                .padding(top = statusBarHeight)
                .height(CollectionScrollTopBarZoneHeight)
                .padding(horizontal = CollectionScrollTopBarTitleHorizontalPadding),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Grid-only counterpart used by screens that only ever render a
 * [LazyVerticalGrid] (the artist page).
 */
@Composable
fun rememberCollectionScrollFraction(gridState: LazyGridState): () -> Float {
    val fadeDistancePx = with(LocalDensity.current) { CollectionScrollTopBarFadeDistance.toPx() }
    return remember(gridState, fadeDistancePx) {
        {
            if (gridState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (gridState.firstVisibleItemScrollOffset / fadeDistancePx).coerceIn(0f, 1f)
            }
        }
    }
}

/**
 * Scroll fraction (0f at rest, 1f once the list has scrolled
 * [CollectionScrollTopBarFadeDistance]) driving [CollectionScrollTopBar].
 * Works for both a [LazyListState] and a [LazyGridState]: whichever state the
 * current density renders with is read inside the returned lambda, so callers
 * can consume it from draw phases without per-frame recomposition.
 */
@Composable
fun rememberCollectionScrollFraction(
    listState: LazyListState,
    gridState: LazyGridState?,
    useGrid: Boolean
): () -> Float {
    val fadeDistancePx = with(LocalDensity.current) { CollectionScrollTopBarFadeDistance.toPx() }
    return remember(listState, gridState, useGrid, fadeDistancePx) {
        {
            val index: Int
            val offset: Int
            if (useGrid && gridState != null) {
                index = gridState.firstVisibleItemIndex
                offset = gridState.firstVisibleItemScrollOffset
            } else {
                index = listState.firstVisibleItemIndex
                offset = listState.firstVisibleItemScrollOffset
            }
            if (index > 0) 1f else (offset / fadeDistancePx).coerceIn(0f, 1f)
        }
    }
}
