package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
            CollectionHeroOverlayContent(
                title = title,
                subtitle = subtitle,
                statsText = statsText,
                accentColor = accentColor,
                centered = layout == CollectionHeroLayout.Immersive,
                onPlayAllClick = onPlayAllClick
            )
        }.first().measure(Constraints(maxWidth = overlayMaxWidthPx))
        val visibleHeightPx = maxOf(
            heroVisiblePx,
            overlayTopPx + overlayContent.height + listTopPaddingPx
        )
        onVisibleHeightPx(visibleHeightPx)
        layout(constraints.maxWidth, visibleHeightPx) { }
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
        animatedVisibilityScope = animatedVisibilityScope,
        sharedElementKey = sharedElementKey
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            .offset {
                // Bleed behind the status bar at rest, then ride the list's
                // scroll 1:1 so the hero scrolls away like a header item.
                IntOffset(0, -statusBarHeightPx - (scrollOffsetPx?.invoke() ?: 0))
            }
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
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
                .graphicsLayer { alpha = 1f - flightFraction.value }
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
                .graphicsLayer { alpha = overlayTextAlpha?.invoke() ?: 1f },
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
        modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun CollectionHeroPlayButton(
    onClick: () -> Unit,
    accentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(accentColor)
            .clickable { onClick() }
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

/**
 * The hero artwork's flight fraction: 1f while the artwork must look like the
 * SOURCE list row (theme percentage corners, no bottom fade), 0f once it is
 * settled as the immersive hero (square corners, faded bottom). It rides the
 * destination screen's own enter/exit transition, so it animates across the
 * shared-element flight with the exact same duration and easing; consumed
 * inside draw/graphicsLayer blocks, every frame of the flight is draw-only.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberCollectionHeroFlightFraction(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?
): State<Float> {
    val noFlight = remember { mutableStateOf(0f) }
    if (sharedTransitionScope == null || animatedVisibilityScope == null || sharedElementKey == null) {
        return noFlight
    }
    return animatedVisibilityScope.transition.animateFloat(label = "collectionHeroFlight") { state ->
        if (state == EnterExitState.Visible) 0f else 1f
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
            boundsTransform = AlbumArtBoundsTransform
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
            boundsTransform = AlbumArtBoundsTransform
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
