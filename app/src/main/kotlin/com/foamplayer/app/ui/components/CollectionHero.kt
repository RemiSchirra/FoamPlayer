package com.foamplayer.app.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.navigation.SharedElementEasing
import com.foamplayer.app.ui.navigation.SharedElementTransitionDurationMs
import com.foamplayer.app.ui.theme.FoamTheme

/** Immersive hero image height, as a fraction of the full screen height. */
const val CollectionHeroImmersiveHeightFraction = 0.5f

/** Compact-immersive hero image height, as a fraction of the full screen height. */
const val CollectionHeroCompactImmersiveHeightFraction = 0.30f

/** Point (fraction of the image height) where the immersive artwork's bottom fade begins. */
const val CollectionHeroImmersiveFadeStartFraction = 0.65f

/** Vertical position of the immersive text/play block, as a fraction of the image height. */
const val CollectionHeroImmersiveOverlayFraction = 0.85f

/** Vertical position of the compact-immersive text/play block, as a fraction of the image height. */
const val CollectionHeroCompactImmersiveOverlayFraction = 0.72f

/** Alpha of the subtle scrim over the immersive hero's status-bar zone. */
const val CollectionHeroStatusBarScrimAlpha = 0.35f

/** Extra height of the status-bar scrim below the inset itself. */
val CollectionHeroStatusBarScrimExtraHeight = 12.dp

/** Height of the scroll top bar's gradient zone, measured below the status-bar inset. */
val CollectionScrollTopBarZoneHeight = 64.dp

/**
 * Height of the screens' floating back/more top-bar row. Compact/Normal lists
 * reserve it as their top content padding so the hero rests below the chrome
 * while the list still scrolls full-bleed beneath it.
 */
val CollectionTopBarRowHeight = 64.dp

/** Scroll distance over which the scroll top bar fades from invisible to fully shown. */
val CollectionScrollTopBarFadeDistance = 160.dp

/** Peak alpha of the scroll top bar's gradient at the very top of the screen. */
const val CollectionScrollTopBarMaxAlpha = 0.55f

/** Horizontal padding kept clear of the back/more buttons for the top bar's title. */
val CollectionScrollTopBarTitleHorizontalPadding = 56.dp

/** Padding between the immersive overlay block and the list's first row. */
val CollectionHeroImmersiveListTopPadding = 16.dp

/**
 * Where the hero's shuffle/play/search row lands below the list's top when
 * search opens: the floating back row covers the list's first 48dp plus 8dp of
 * breathing room, so the open search bar ends up right below the floating
 * chrome instead of colliding with the back button.
 */
val CollectionSearchImmersiveTopClearance = 56.dp

/** Fraction of the screen height reserved below a list while its search bar is open. */
private const val CollectionSearchOpenReserveScreenFraction = 0.6f

/** Duration of the search-open bottom padding's grow/shrink. */
private const val CollectionSearchPaddingAnimationMs = 250

/**
 * Hero layout of the "list of lists" screens (genres, years, folders and every
 * other collection that is not an album/artist/playlist detail), resolved from
 * the Default hero-layout setting at the app root.
 */
val LocalDefaultCollectionHeroLayout = compositionLocalOf { CollectionHeroLayout.Compact }

/** State of a collection hero's in-page item search (see [CollectionHeroSearchBar]). */
class CollectionSearchState {
    var open by mutableStateOf(false)
        private set
    var query by mutableStateOf("")

    /** Increments on every [open]; hosts scroll the bar to the screen top on it. */
    var openRequest by mutableIntStateOf(0)
        private set

    /**
     * Whether the field has already been focused for the current open session.
     * The hero header is a lazy-list item: scrolling it out of view disposes
     * the field and scrolling back recomposes it, and the field's focus request
     * would re-fire on every such recomposition — re-showing a keyboard the
     * user had dismissed. The bar requests focus only while this is false;
     * [open] resets it for the next session.
     */
    var focusGranted by mutableStateOf(false)
        internal set

    /**
     * Root-space y of the hero's button row top — the FALLBACK anchor of the
     * search-open scroll. NaN while the hero is not laid out (e.g. scrolled
     * off-screen, where search cannot be opened from).
     */
    var buttonRowTopYInRootPx by mutableFloatStateOf(Float.NaN)
        internal set

    /**
     * Root-space y of the hero's search bar container top — the preferred
     * anchor, reported by an always-present wrapper around the bar so the
     * value exists before the bar's own first layout. Set only by heroes whose
     * bar does NOT hang right under the button row (the compact one).
     */
    var searchBarTopYInRootPx by mutableFloatStateOf(Float.NaN)
        internal set

    /** Opens the search bar (the hero's search icon swaps to a close cross). */
    fun open() {
        open = true
        openRequest++
        focusGranted = false
    }

    /**
     * Closes the search bar. The query is deliberately NOT cleared here:
     * resetting a focused editable's text restarts its IME connection, which
     * re-shows the keyboard for a frame right after the hide request. The
     * query is cleared when the field leaves composition after this close;
     * until then [matches] already ignores it via the closed-[open]
     * short-circuit.
     */
    fun close() {
        open = false
    }

    /** Matches a case-insensitive substring anywhere in the given fields. */
    fun matches(vararg fields: String?): Boolean {
        if (!open || query.isEmpty()) return true
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return fields.any { it?.contains(needle, ignoreCase = true) == true }
    }
}

/** Remembered [CollectionSearchState] for a collection screen's hero search. */
@Composable
fun rememberCollectionSearchState(): CollectionSearchState = remember { CollectionSearchState() }

/** Duration of the search-open scroll itself. */
private const val SearchOpenScrollDurationMs = 300

/**
 * Bottom content padding a host list gains while its hero's search bar is
 * open (feed into the lazy container's PaddingValues): the keyboard's own
 * inset keeps the filtered rows visible above it, and a screen-fraction
 * reserve gives even a handful of rows enough scroll range for the
 * search-open scroll to bring the bar to the screen top.
 *
 * The value ANIMATES on both edges so the list settles in step with the
 * keyboard's dismiss and the bar's exit instead of snapping.
 */
@Composable
fun collectionSearchOpenBottomPadding(searchState: CollectionSearchState?): Dp {
    val target = if (searchState == null || !searchState.open) {
        0.dp
    } else {
        val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        // Scales with the actual window (landscape, tablets, foldables) and
        // covers every hero layout's worst-case bar distance from the top.
        val reserve = LocalConfiguration.current.screenHeightDp.dp *
                CollectionSearchOpenReserveScreenFraction
        imeBottom + reserve
    }
    return animateDpAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = CollectionSearchPaddingAnimationMs,
            easing = FastOutSlowInEasing
        ),
        label = "collectionSearchOpenBottomPadding"
    ).value
}

/**
 * On every search-open request, scrolls the host's list so the hero's search
 * bar lands at the top of the screen instead of opening wherever the hero
 * currently sits.
 *
 * The anchor is the search bar's own container top when reported (compact
 * hero), otherwise the button row. [listTopYInRootPx] supplies the container's
 * root-space top y and [topChromeHeight] how much of the list's top is covered
 * by floating chrome. A missing anchor report (NaN) makes the effect a no-op.
 */
@Composable
fun CollectionSearchScrollToBarEffect(
    searchState: CollectionSearchState?,
    listState: LazyListState?,
    gridState: LazyGridState?,
    useGrid: Boolean,
    listTopYInRootPx: () -> Float,
    topChromeHeight: Dp = 0.dp
) {
    if (searchState == null) return
    val chromePx = with(LocalDensity.current) { topChromeHeight.toPx() }
    LaunchedEffect(searchState.openRequest) {
        if (searchState.openRequest == 0) return@LaunchedEffect
        val barAnchorY = searchState.searchBarTopYInRootPx
        val anchorY = if (barAnchorY.isNaN()) searchState.buttonRowTopYInRootPx else barAnchorY
        if (anchorY.isNaN()) return@LaunchedEffect
        val listTopY = listTopYInRootPx()
        if (listTopY.isNaN()) return@LaunchedEffect
        val delta = anchorY - listTopY - chromePx
        if (delta != 0f) {
            if (useGrid && gridState != null) {
                gridState.animateScrollBy(
                    delta,
                    tween(durationMillis = SearchOpenScrollDurationMs, easing = FastOutSlowInEasing)
                )
            } else if (listState != null) {
                listState.animateScrollBy(
                    delta,
                    tween(durationMillis = SearchOpenScrollDurationMs, easing = FastOutSlowInEasing)
                )
            }
        }
    }
}

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

private fun collectionHeroOverlayFraction(layout: CollectionHeroLayout): Float = when (layout) {
    CollectionHeroLayout.Immersive -> CollectionHeroImmersiveOverlayFraction
    else -> CollectionHeroCompactImmersiveOverlayFraction
}

/**
 * The immersive layouts' leading list item: the hero's overlay block itself —
 * title/subtitle/stats and the optional play pill — hanging at the artwork's
 * overlay position, with the hero artwork's room reserved around it.
 *
 * The block is real, interactive list content: the play pill receives taps
 * directly and the whole block scrolls away 1:1 with the list. Its height is
 * not estimated — it measures its own content.
 *
 * [scrollOffsetPx] drives the block's scroll-out fade positionally: fully
 * opaque until its top nears the screen top, then fading out across
 * [CollectionScrollTopBarFadeDistance]. [transitionAlpha] fades the block with
 * the shared-element flight: the block is lifted into the transition overlay
 * ([collectionHeroOverlayLayer]), and a lifted layer escapes the host
 * hierarchy's own fades unless its alpha compensates.
 *
 * [onVisibleHeightPx] reports the measured total (px) once laid out, so the
 * host can hand the hero artwork's 1:1 scroll-away over to the same constant.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CollectionHeroImmersiveHeader(
    layout: CollectionHeroLayout,
    title: String,
    subtitle: String?,
    statsText: String,
    onVisibleHeightPx: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = FoamTheme.accentColor,
    onPlayAllClick: (() -> Unit)? = null,
    onShuffleAllClick: (() -> Unit)? = null,
    /** Tap on the subtitle line; receives the subtitle text (null = not tappable). */
    onSubtitleClick: ((String) -> Unit)? = null,
    searchState: CollectionSearchState? = null,
    scrollOffsetPx: (() -> Int)? = null,
    overlayTextAlpha: (() -> Float)? = null,
    transitionAlpha: (() -> Float)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementKey: String? = null,
    sharedElementPrefix: String = "album"
) {
    val density = LocalDensity.current
    val statusBarHeightPx = WindowInsets.statusBars.getTop(density)
    val heroHeight = collectionHeroImmersiveHeight(layout)
    val heroVisiblePx = with(density) { heroHeight.roundToPx() - statusBarHeightPx }
    val overlayTopPx = with(density) {
        (heroHeight * collectionHeroOverlayFraction(layout)).roundToPx() - statusBarHeightPx
    }
    val listTopPaddingPx = with(density) { CollectionHeroImmersiveListTopPadding.roundToPx() }
    // Measured at the same width the hero Box uses (16dp side padding), so
    // line wraps stay identical.
    val overlayMaxWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    // While a shared transition is active the block is lifted above the flying
    // artwork and escapes the host hierarchy's fades, so its alpha rides the
    // flight explicitly.
    val flightFraction = rememberCollectionHeroFlightFraction(
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
    val fadeDistancePx = with(density) { CollectionScrollTopBarFadeDistance.toPx() }
    val topFadeZonePx = with(density) { CollectionScrollTopBarZoneHeight.toPx() }
    // Set by the measure pass below; anchors the draw-phase fade to the
    // block's BOTTOM (the play pill). Read untracked.
    val blockHeightPx = remember { mutableIntStateOf(0) }

    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val block = subcompose(Unit) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .collectionHeroOverlayLayer(sharedTransitionScope)
                    .graphicsLayer {
                        // Positional fade anchored to the block's bottom: full
                        // alpha until the pill is within the fade distance of
                        // the screen top. The window shrinks when the block
                        // starts closer to the top than the distance, so it is
                        // always fully opaque at rest.
                        val scroll = scrollOffsetPx?.invoke()
                        val scrollAlpha = if (scroll != null && overlayTopPx > 0) {
                            val bottomPx = overlayTopPx + blockHeightPx.intValue
                            val windowPx = minOf(fadeDistancePx, bottomPx.toFloat())
                            1f - ((scroll - (bottomPx - windowPx)) / windowPx)
                                .coerceIn(0f, 1f)
                        } else {
                            overlayTextAlpha?.invoke() ?: 1f
                        }
                        alpha = scrollAlpha *
                                (1f - flightFraction.value) *
                                (transitionAlpha?.invoke() ?: 1f)
                        compositingStrategy = if (sharedTransitionScope?.isTransitionActive == true) {
                            CompositingStrategy.Offscreen
                        } else {
                            CompositingStrategy.Auto
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        // At rest the host list's [collectionListTopFade]
                        // dissolves this block into the top chrome. While a
                        // transition is active the block is lifted into the
                        // root overlay and escapes that mask, so re-apply the
                        // same mask here, in the block's own space. Only while
                        // lifted — in place the list's mask applies and doing
                        // both would fade the block twice.
                        val scroll = scrollOffsetPx?.invoke()
                        if (scroll != null && sharedTransitionScope?.isTransitionActive == true) {
                            val zoneStart = (scroll - overlayTopPx).toFloat()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0f),
                                    1f to Color.Black,
                                    startY = zoneStart,
                                    endY = zoneStart + topFadeZonePx
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                    }
            ) {
                CollectionHeroOverlayContent(
                    title = title,
                    subtitle = subtitle,
                    statsText = statsText,
                    accentColor = accentColor,
                    centered = layout == CollectionHeroLayout.Immersive,
                    onPlayAllClick = onPlayAllClick,
                    onShuffleAllClick = onShuffleAllClick,
                    onSubtitleClick = onSubtitleClick,
                    searchState = searchState,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedElementKey = sharedElementKey,
                    sharedElementPrefix = sharedElementPrefix
                )
            }
        }.first().measure(Constraints(maxWidth = overlayMaxWidthPx))
        blockHeightPx.intValue = block.height
        val visibleHeightPx = maxOf(
            heroVisiblePx,
            overlayTopPx + block.height + listTopPaddingPx
        )
        onVisibleHeightPx(visibleHeightPx)
        layout(constraints.maxWidth, visibleHeightPx) {
            block.placeRelative(0, overlayTopPx)
        }
    }
}

/**
 * The shared collection hero: artwork plus title/subtitle/stats and an
 * optional play button, laid out per [CollectionHeroLayout]. All four layouts
 * share the same content slots; Compact is the classic image-left/text-right
 * header, Normal a big rounded artwork with centered text below, and the
 * immersive variants bleed the artwork under the status bar with the text
 * block overlaid on it.
 *
 * When [onSubtitleClick] is provided, the subtitle renders as a tappable link
 * (the album detail page uses it to open the artist page).
 *
 * Compact/Normal render the whole header and are composed as the list's
 * leading item. The immersive variants render ONLY the artwork layer: place
 * this composable from a screen-level Box behind the list, and make the list's
 * leading item a [CollectionHeroImmersiveHeader], which carries the overlaid
 * text block and play pill as real, interactive list content.
 *
 * When a shared-element context is provided AND the layout is immersive, the
 * artwork's corner radius and bottom fade animate across the transition, so
 * the flight departs from the source row's rounded, unfaded look and lands on
 * the square, faded hero. [fullyRoundedArtwork] styles the source row's look
 * as a full circle (artist images). Compact and Normal keep the static theme
 * shape and no fade.
 *
 * NOTE on flight rendering: the shared-element overlay draws only the
 * becoming-visible side's content and clip. On open that is this hero; on
 * close the drawn side is the source row instead — which is why the row side
 * carries its own mirrored morph (see [collectionFlightArtwork]).
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
    fullyRoundedArtwork: Boolean = false,
    icon: ImageVector? = null,
    searchState: CollectionSearchState? = null,
    onShuffleAllClick: (() -> Unit)? = null,
    /** Tap on the subtitle line; receives the subtitle text (null = not tappable). */
    onSubtitleClick: ((String) -> Unit)? = null,
    accentColor: Color = FoamTheme.accentColor,
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
            fullyRoundedArtwork = fullyRoundedArtwork,
            icon = icon,
            onPlayAllClick = onPlayAllClick,
            onShuffleAllClick = onShuffleAllClick,
            onSubtitleClick = onSubtitleClick,
            searchState = searchState,
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
            fullyRoundedArtwork = fullyRoundedArtwork,
            icon = icon,
            onPlayAllClick = onPlayAllClick,
            onShuffleAllClick = onShuffleAllClick,
            onSubtitleClick = onSubtitleClick,
            searchState = searchState,
            accentColor = accentColor,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedElementKey = sharedElementKey,
            sharedElementPrefix = sharedElementPrefix,
            modifier = modifier
        )
        CollectionHeroLayout.Immersive, CollectionHeroLayout.CompactImmersive ->
            CollectionHeroImmersiveArtwork(
                layout = layout,
                title = title,
                imageUrl = imageUrl,
                imageIsEmbeddedArt = imageIsEmbeddedArt,
                fullyRoundedArtwork = fullyRoundedArtwork,
                icon = icon,
                accentColor = accentColor,
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

/**
 * The classic image-left/text-right hero. The artwork + details sit in one
 * Row; the search bar hangs BELOW that Row at the hero's full width — not
 * squeezed into the (narrow) text column. That position also re-anchors the
 * search-open scroll (the bar's container top is reported instead of the
 * button row's).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CollectionHeroCompact(
    title: String,
    subtitle: String?,
    statsText: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    fullyRoundedArtwork: Boolean,
    icon: ImageVector?,
    onPlayAllClick: (() -> Unit)?,
    onShuffleAllClick: (() -> Unit)?,
    onSubtitleClick: ((String) -> Unit)?,
    searchState: CollectionSearchState?,
    accentColor: Color,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
    sharedElementPrefix: String,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .collectionHeroSharedArtwork(
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        sharedElementKey = sharedElementKey,
                        sharedElementPrefix = sharedElementPrefix,
                        flightFraction = null,
                        fullyRounded = fullyRoundedArtwork
                    )
                    .clip(heroArtworkShape(fullyRoundedArtwork))
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
                    onSubtitleClick = onSubtitleClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedElementKey = sharedElementKey,
                    sharedElementPrefix = sharedElementPrefix
                )

                if (onPlayAllClick != null || searchState != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.reportHeroButtonRowTop(searchState),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onShuffleAllClick != null && onPlayAllClick != null) {
                            CollectionHeroShuffleButton(onShuffleAllClick = onShuffleAllClick)
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        if (onPlayAllClick != null) {
                            // fill = false: the fixed toggles are measured
                            // before any weighted child, so a too-narrow column
                            // squeezes the PILL (which collapses to an
                            // icon-only circle) instead of starving the search
                            // toggle's icon.
                            CollectionHeroPlayButton(
                                onClick = onPlayAllClick,
                                accentColor = accentColor,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (searchState != null) {
                            if (onPlayAllClick != null) {
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            CollectionHeroSearchButton(searchState = searchState)
                        }
                    }
                }
            }
        }

        // Full-width search bar under the artwork + details Row; the wrapper
        // reports the bar's top as the search-open scroll anchor.
        if (searchState != null) {
            Box(modifier = Modifier.reportHeroSearchBarTop(searchState)) {
                CollectionHeroSearchBar(searchState = searchState, accentColor = accentColor)
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
    fullyRoundedArtwork: Boolean,
    icon: ImageVector?,
    onPlayAllClick: (() -> Unit)?,
    onShuffleAllClick: (() -> Unit)?,
    onSubtitleClick: ((String) -> Unit)?,
    searchState: CollectionSearchState?,
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
                    flightFraction = null,
                    fullyRounded = fullyRoundedArtwork
                )
                .clip(heroArtworkShape(fullyRoundedArtwork))
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
                onSubtitleClick = onSubtitleClick,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                sharedElementKey = sharedElementKey,
                sharedElementPrefix = sharedElementPrefix
            )

            if (onPlayAllClick != null || searchState != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.reportHeroButtonRowTop(searchState),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onShuffleAllClick != null && onPlayAllClick != null) {
                        CollectionHeroShuffleButton(onShuffleAllClick = onShuffleAllClick)
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    if (onPlayAllClick != null) {
                        CollectionHeroPlayButton(
                            onClick = onPlayAllClick,
                            accentColor = accentColor,
                            // Flexible member: yields to the fixed toggles
                            // when the row is narrow.
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    if (searchState != null) {
                        if (onPlayAllClick != null) {
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        CollectionHeroSearchButton(searchState = searchState)
                    }
                }
                CollectionHeroSearchBar(searchState = searchState, accentColor = accentColor)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CollectionHeroImmersiveArtwork(
    layout: CollectionHeroLayout,
    title: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    fullyRoundedArtwork: Boolean,
    icon: ImageVector?,
    accentColor: Color,
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
    // Read in composition: FoamTheme.shapes is @Composable and cannot be
    // touched inside the draw-phase graphicsLayer block below.
    val flightRestPercent = if (fullyRoundedArtwork) {
        AlbumArtFlightFullyRoundedPercent
    } else {
        FoamTheme.shapes.miniPlayerRounding * 50f
    }

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
                .fillMaxWidth()
                .height(heroHeight)
                .collectionHeroSharedArtwork(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    sharedElementKey = sharedElementKey,
                    sharedElementPrefix = sharedElementPrefix,
                    flightFraction = flightFraction,
                    fullyRounded = fullyRoundedArtwork
                )
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    // In-flight in-place look: the shared-element overlay only
                    // takes over once the match's bounds are configured, and
                    // screens opened without a fade render in place from frame
                    // one — so the artwork must carry the departing row's
                    // rounded corners ITSELF on every frame it renders in place.
                    shape = AlbumArtFlightShape(flightRestPercent) { flightFraction.value }
                    clip = true
                }
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
                .collectionHeroOverlayLayer(
                    sharedTransitionScope = sharedTransitionScope,
                    renderInOverlay = animatedVisibilityScope?.let { scope ->
                        { scope.isNavTransitionInProgress() }
                    }
                )
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
    }
}

/** Static rest shape of a non-immersive hero's artwork (circle for artists). */
@Composable
private fun heroArtworkShape(fullyRounded: Boolean): Shape =
    if (fullyRounded) CircleShape else AlbumArtShape()

/**
 * The immersive overlay block's content: title/subtitle/stats and the optional
 * play pill. Composed by [CollectionHeroImmersiveHeader] as the list's real
 * interactive header item.
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
    onShuffleAllClick: (() -> Unit)? = null,
    onSubtitleClick: ((String) -> Unit)? = null,
    searchState: CollectionSearchState? = null,
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
            onSubtitleClick = onSubtitleClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedElementKey = sharedElementKey,
            sharedElementPrefix = sharedElementPrefix
        )

        if (onPlayAllClick != null || searchState != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.reportHeroButtonRowTop(searchState),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onShuffleAllClick != null && onPlayAllClick != null) {
                    CollectionHeroShuffleButton(onShuffleAllClick = onShuffleAllClick)
                    Spacer(modifier = Modifier.width(12.dp))
                }
                if (onPlayAllClick != null) {
                    CollectionHeroPlayButton(
                        onClick = onPlayAllClick,
                        accentColor = accentColor,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (searchState != null) {
                    if (onPlayAllClick != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    CollectionHeroSearchButton(searchState = searchState)
                }
            }
            CollectionHeroSearchBar(searchState = searchState, accentColor = accentColor)
        }
    }
}

/** Reports the hero's button row's root-space top y into [searchState]. */
private fun Modifier.reportHeroButtonRowTop(searchState: CollectionSearchState?): Modifier =
    onGloballyPositioned { info ->
        searchState?.buttonRowTopYInRootPx = info.positionInRoot().y
    }

/** Reports the hero's search bar container's root-space top y into [searchState]. */
private fun Modifier.reportHeroSearchBarTop(searchState: CollectionSearchState?): Modifier =
    onGloballyPositioned { info ->
        searchState?.searchBarTopYInRootPx = info.positionInRoot().y
    }

/**
 * Artwork fill: embedded/plain image, collection icon, or the title's initial.
 *
 * When the image CHANGES in place (e.g. a playlist reorder picks a different
 * cover), the old image keeps rendering on a top layer whose alpha tweens to
 * 0 while the new image draws beneath it — a crossfade instead of a pop
 * through the placeholder. First compositions render the single image
 * directly: no extra layer, no fade.
 */
@Composable
private fun CollectionHeroArtworkContent(
    title: String,
    imageUrl: String?,
    imageIsEmbeddedArt: Boolean,
    icon: ImageVector?
) {
    val context = LocalContext.current
    if (imageUrl != null) {
        var displayed by remember { mutableStateOf(imageUrl to imageIsEmbeddedArt) }
        var fading by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        val fade = remember { Animatable(1f) }

        LaunchedEffect(imageUrl, imageIsEmbeddedArt) {
            if (imageUrl != displayed.first || imageIsEmbeddedArt != displayed.second) {
                fading = displayed
                displayed = imageUrl to imageIsEmbeddedArt
                fade.snapTo(1f)
                fade.animateTo(0f, tween(durationMillis = SharedElementTransitionDurationMs))
                fading = null
            }
        }

        val fadingArt = fading
        if (fadingArt == null) {
            CollectionHeroArtworkImage(
                displayed.first,
                displayed.second,
                context,
                Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                CollectionHeroArtworkImage(
                    displayed.first,
                    displayed.second,
                    context,
                    Modifier.fillMaxSize()
                )
                CollectionHeroArtworkImage(
                    fadingArt.first,
                    fadingArt.second,
                    context,
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = fade.value }
                )
            }
        }
    } else if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(48.dp)
        )
    } else {
        NoAlbumArtLetter(title = title)
    }
}

/** One hero artwork image request + [AlbumArtImage] render, shared by the crossfade layers. */
@Composable
private fun CollectionHeroArtworkImage(
    imageUrl: String,
    imageIsEmbeddedArt: Boolean,
    context: Context,
    modifier: Modifier
) {
    val heroArtRequest = remember(imageUrl, imageIsEmbeddedArt) {
        val data = if (imageIsEmbeddedArt) {
            EmbeddedAlbumArt(imageUrl.toUri())
        } else {
            imageUrl
        }
        ImageRequest.Builder(context)
            .data(data)
            .size(AlbumArtRequestSize)
            .memoryCacheKey(albumArtMemoryCacheKey(imageUrl))
            .crossfade(false)
            .build()
    }
    AlbumArtImage(
        model = heroArtRequest,
        contentDescription = null,
        modifier = modifier,
        placeholderSize = 40.dp
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CollectionHeroTexts(
    title: String,
    subtitle: String?,
    statsText: String,
    accentColor: Color,
    centered: Boolean,
    onSubtitleClick: ((String) -> Unit)? = null,
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
            modifier = Modifier.collectionHeroSharedText(
                key = "$sharedElementPrefix-title-$sharedElementKey",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                enabled = sharedElementKey != null
            )
        )

        if (subtitle != null) {
            val subtitleSharedText = Modifier.collectionHeroSharedText(
                key = "$sharedElementPrefix-artist-$sharedElementKey",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                enabled = sharedElementKey != null
            )
            if (onSubtitleClick != null) {
                val linkHorizontalPadding = 8.dp
                val linkVerticalPadding = 3.dp
                val linkShape = RoundedCornerShape(10.dp)
                val subtitleInteractionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = (if (centered) {
                        Modifier
                    } else {
                        Modifier.offset(x = -linkHorizontalPadding)
                    })
                        .clip(linkShape)
                        .clickable(
                            interactionSource = subtitleInteractionSource,
                            indication = ripple(color = FoamTheme.colors.textPrimary)
                        ) { onSubtitleClick(subtitle) }
                        .padding(horizontal = linkHorizontalPadding, vertical = linkVerticalPadding)
                ) {
                    BasicText(
                        text = subtitle,
                        style = subtitleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = subtitleSharedText
                    )
                }
            } else {
                BasicText(
                    text = subtitle,
                    style = subtitleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = subtitleSharedText
                )
            }
        }
        BasicText(
            text = statsText,
            style = statsStyle,
            modifier = textWidth.padding(vertical = 4.dp)
        )
    }
}

/**
 * The accent play pill. The pill is the hero button row's only flexible member
 * (`Modifier.weight(1f, fill = false)`): the fixed toggles are measured
 * before any weighted child, so when the row's width cannot fit the labelled
 * pill, it collapses to an icon-only circle instead of starving the search
 * toggle. The switch compares the incoming [BoxWithConstraints.maxWidth]
 * against the exact labelled width, so the label is either rendered in full
 * or not at all.
 */
@Composable
private fun CollectionHeroPlayButton(
    onClick: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val playLabel = stringResource(R.string.play)
    BoxWithConstraints(modifier = modifier) {
        // The pill's chrome around the label: 48dp horizontal padding + 20dp icon + 8dp gap.
        val labelChromeWidth = 76.dp
        val labelStyle = FoamTheme.typography.titleMedium
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val labelWidth = remember(textMeasurer, labelStyle, density, playLabel) {
            with(density) { textMeasurer.measure(playLabel, labelStyle).size.width.toDp() }
        }
        val showLabel = maxWidth >= labelChromeWidth + labelWidth

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(accentColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = FoamTheme.colors.textPrimary)
                ) { onClick() }
                // The label supplied the pill's semantics; keep them when it
                // collapses away.
                .then(
                    if (showLabel) {
                        Modifier
                    } else {
                        Modifier.semantics { contentDescription = playLabel }
                    }
                )
                .padding(
                    horizontal = if (showLabel) 24.dp else 10.dp,
                    vertical = 10.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FoamTheme.icons.current.play,
                    contentDescription = null,
                    tint = FoamTheme.colors.background,
                    modifier = Modifier.size(20.dp)
                )
                if (showLabel) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicText(
                        text = playLabel,
                        style = labelStyle.copy(color = FoamTheme.colors.background)
                    )
                }
            }
        }
    }
}

/**
 * The hero's shuffle toggle, rendered LEFT of the play pill: queues the
 * collection's items in shuffled order. Hidden without [onShuffleAllClick].
 */
@Composable
private fun CollectionHeroShuffleButton(
    onShuffleAllClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary)
            ) { onShuffleAllClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Shuffle,
            contentDescription = stringResource(R.string.action_shuffle),
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * The hero's search toggle: the search icon at rest, a close cross while the
 * bar is open. Tapping opens the bar (which focuses its own field and is
 * scrolled up by the host); tapping the cross closes it.
 */
@Composable
private fun CollectionHeroSearchButton(searchState: CollectionSearchState) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary)
            ) {
                if (searchState.open) searchState.close() else searchState.open()
            },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = searchState.open,
            animationSpec = tween(durationMillis = 150),
            label = "collectionSearchToggleIcon"
        ) { open ->
            Icon(
                imageVector = if (open) {
                    FoamTheme.icons.current.close
                } else {
                    FoamTheme.icons.current.search
                },
                contentDescription = stringResource(
                    if (open) R.string.close_search else R.string.search
                ),
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * The hero's inline search field, shown while [CollectionSearchState.open] is
 * true: filters the collection's rows live. The caret rides the hero's own
 * [accentColor], not the global app accent. Hosts place the bar wherever
 * their layout wants it.
 *
 * Focus handling: focus is granted ONCE per open session (scrolling the bar
 * out of the lazy viewport and back must not re-show the keyboard); closing
 * clears focus immediately so the keyboard hides in the same frame; "Done"
 * hides the keyboard; a keyboard dismissed under an open bar drops focus so
 * the caret stops blinking. The query is cleared only when the field leaves
 * composition AFTER a close (see [CollectionSearchState.close]).
 */
@Composable
private fun CollectionHeroSearchBar(
    searchState: CollectionSearchState?,
    accentColor: Color
) {
    if (searchState == null) return
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    // Whether the keyboard has been up during THIS open: guards the
    // keyboard-closed focus drop against the open flow's own focus request.
    var keyboardShownThisOpen by remember { mutableStateOf(false) }
    LaunchedEffect(searchState.openRequest) { keyboardShownThisOpen = false }
    LaunchedEffect(searchState.open) {
        if (!searchState.open) focusManager.clearFocus(force = true)
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            keyboardShownThisOpen = true
        } else if (keyboardShownThisOpen) {
            keyboardShownThisOpen = false
            focusManager.clearFocus(force = true)
        }
    }
    AnimatedVisibility(
        visible = searchState.open,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        val focusRequester = remember { FocusRequester() }
        // The query is cleared HERE — when the field is gone from composition
        // after a close — and not in close() (see [CollectionSearchState.close]).
        // Guarded on the bar being closed: a disposal from the header scrolling
        // out of the viewport (bar still open) must keep the query.
        DisposableEffect(Unit) {
            onDispose {
                if (!searchState.open) searchState.query = ""
            }
        }
        // The flag lives on the screen-level state, so it survives this field
        // being disposed and recomposed; only [open] resets it.
        LaunchedEffect(Unit) {
            if (!searchState.focusGranted) {
                focusRequester.requestFocus()
                searchState.focusGranted = true
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(CircleShape)
                .background(FoamTheme.colors.surface.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = searchState.query,
                onValueChange = { searchState.query = it },
                textStyle = FoamTheme.typography.titleMedium.copy(color = FoamTheme.colors.textPrimary),
                cursorBrush = SolidColor(accentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
            )
        }
    }
}

/**
 * The shared-element flight fraction of the given transition scopes: 1f while
 * the transition is in flight, 0f once settled. Draw-phase only. Returns a
 * constant 0f when either scope is missing. Animates with the same
 * duration/easing as the NavHost's screen fade and [AlbumArtBoundsTransform],
 * so chrome fading with the flight lands on the same frame as the screen fade.
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
    return animatedVisibilityScope.transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = SharedElementTransitionDurationMs,
                easing = SharedElementEasing
            )
        },
        label = "collectionHeroFlight"
    ) { state ->
        if (state == EnterExitState.Visible) 0f else 1f
    }
}

/**
 * Lifts a hero chrome layer (status-bar scrim, overlay text/play block) into
 * the shared-element transition's overlay while a transition is active, so it
 * draws ABOVE the flying artwork instead of beneath it — otherwise the artwork
 * hides the chrome mid-flight and the chrome pops back the instant the overlay
 * empties. No-op at rest and without a [SharedTransitionScope].
 *
 * Pair the lift with a flight alpha —
 * `(1f - rememberCollectionHeroFlightFraction(...).value) * scrollAlpha` — so
 * the chrome fades with the flight.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.collectionHeroOverlayLayer(
    sharedTransitionScope: SharedTransitionScope?,
    zIndexInOverlay: Float = 1f,
    renderInOverlay: (() -> Boolean)? = null
): Modifier {
    if (sharedTransitionScope == null) return this
    return with(sharedTransitionScope) {
        // The default predicate (isTransitionActive) only covers shared
        // ELEMENTS in flight; callers can pass one covering the whole nav
        // transition (e.g. chrome overflowing the NavHost bounds).
        if (renderInOverlay == null) {
            renderInSharedTransitionScopeOverlay(zIndexInOverlay = zIndexInOverlay)
        } else {
            renderInSharedTransitionScopeOverlay(
                zIndexInOverlay = zIndexInOverlay,
                renderInOverlay = renderInOverlay
            )
        }
    }
}

/**
 * True from the first frame a screen starts entering or exiting until it has
 * fully settled. Unlike the shared-element scope's isTransitionActive it also
 * covers route pairs without any shared element.
 */
fun AnimatedVisibilityScope.isNavTransitionInProgress(): Boolean =
    transition.currentState != EnterExitState.Visible ||
            transition.targetState != EnterExitState.Visible

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.collectionHeroSharedArtwork(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?,
    sharedElementPrefix: String,
    flightFraction: State<Float>?,
    fullyRounded: Boolean = false
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null || sharedElementKey == null) {
        return this
    }
    val restPercent = if (fullyRounded) {
        AlbumArtFlightFullyRoundedPercent
    } else {
        FoamTheme.shapes.miniPlayerRounding * 50f
    }
    val overlayShape = when {
        flightFraction != null ->
            AlbumArtFlightShape(restPercent) { flightFraction.value }
        fullyRounded -> CircleShape
        else -> AlbumArtShape()
    }
    return with(sharedTransitionScope) {
        this@collectionHeroSharedArtwork.sharedElement(
            rememberSharedContentState(key = "$sharedElementPrefix-art-$sharedElementKey"),
            animatedVisibilityScope = animatedVisibilityScope,
            clipInOverlayDuringTransition = OverlayClip(overlayShape),
            boundsTransform = AlbumArtBoundsTransform,
            // Bottom of the transition overlay: chrome layers lifted by
            // [collectionHeroOverlayLayer] and the shared texts draw above.
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
    // The fades ride the same duration/easing tween as the bounds flight
    // instead of sharedBounds' spring defaults; see [rowSharedText].
    val fadeSpec = tween<Float>(
        durationMillis = SharedElementTransitionDurationMs,
        easing = SharedElementEasing
    )
    return with(sharedTransitionScope) {
        this@collectionHeroSharedText.sharedBounds(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
            enter = fadeIn(fadeSpec),
            exit = fadeOut(fadeSpec),
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillHeight, Alignment.Center),
            boundsTransform = AlbumArtBoundsTransform,
            zIndexInOverlay = 1f
        )
    }
}

/**
 * Resolves a collection's hero layout from the app settings. Provided at the
 * nav root so any screen hosting rows that fly into a collection detail hero
 * can know whether that hero uses an immersive layout and therefore whether
 * its rows must carry the mirrored flight look (see [collectionFlightArtwork]).
 */
val LocalCollectionHeroLayoutLookup: ProvidableCompositionLocal<(CollectionHeroTarget) -> CollectionHeroLayout> =
    compositionLocalOf { { CollectionHeroLayout.Compact } }

/**
 * The scroll-triggered top bar: a vertical gradient from the very top of the
 * screen down past the top-bar zone, with the collection title fading in
 * progressively. [fraction] is consumed inside a graphicsLayer, so every
 * scroll frame is draw-only. Draws NO buttons: the screen's own back/more
 * controls render above it, keeping single hit targets.
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
                        0f to FoamTheme.colors.background.copy(alpha = CollectionScrollTopBarMaxAlpha),
                        1f to Color.Transparent
                    )
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                // Insets are consumed at the nav root, so pad by the raw
                // measured inset to sit exactly on the same level as the
                // screen's back/more buttons.
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
 * Alpha-fades the host list's OWN pixels to transparent across the floating
 * chrome zone at its top: rows scrolling full-bleed under the chrome dissolve
 * into it instead of just being dimmed by the (subtle) gradient scrim. At
 * rest nothing is drawn inside the fade zone (the top content padding covers
 * it). Draw-phase only, with an offscreen layer — without it the DstIn blend
 * would erase everything already drawn beneath the list.
 */
fun Modifier.collectionListTopFade(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0f),
                    1f to Color.Black,
                    startY = 0f,
                    endY = CollectionScrollTopBarZoneHeight.toPx()
                ),
                blendMode = BlendMode.DstIn
            )
        }

/** Grid-only counterpart used by screens that only ever render a [LazyVerticalGrid]. */
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
 * Works for both a [LazyListState] and a [LazyGridState]; consumed from draw
 * phases without per-frame recomposition.
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
