package com.foamplayer.app.ui.components

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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.request.ImageRequest
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.navigation.SharedElementEasing
import com.foamplayer.app.ui.navigation.SharedElementTransitionDurationMs
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
 * Where the hero's shuffle/play/search button row lands below the list's top
 * when search opens on an immersive layout (see
 * [CollectionSearchScrollToBarEffect]): the floating back row covers the list's
 * first 48dp (the status-bar inset is consumed at the nav root), plus 8dp of
 * breathing room, so the open search bar ends up right below the fixed chrome
 * instead of colliding with the back button.
 */
val CollectionSearchImmersiveTopClearance = 56.dp

/** Fraction of the screen height reserved below a list while its search bar is open. */
private const val CollectionSearchOpenReserveScreenFraction = 0.6f

/**
 * Duration of the search-open bottom padding's grow/shrink: matched to the
 * keyboard's dismiss animation and the search bar's own exit, so the list
 * settles back in step with them instead of snapping when the bar closes.
 */
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

    /** Increments on every [open]: hosts scroll the bar to the screen top (see [CollectionSearchScrollToBarEffect]). */
    var openRequest by mutableIntStateOf(0)
        private set

    /**
     * Root-space y of the hero's button row (shuffle/play/search toggles) top,
     * reported by that row itself on every layout pass — the anchor of the
     * search-open scroll (the bar hangs 8dp below the row, and the row's
     * position is identical whether the bar is open or not, so the effect never
     * has to wait for the bar's own entry animation). NaN while the hero is not
     * laid out (e.g. scrolled off-screen — where search cannot be opened from).
     */
    var buttonRowTopYInRootPx by mutableFloatStateOf(Float.NaN)
        internal set

    /** Opens the search bar (the hero's search icon swaps to a close cross). */
    fun open() {
        open = true
        openRequest++
    }

    /**
     * Closes the search bar. The query is deliberately NOT cleared here: at
     * this instant the field is still focused and composed, and mutating a
     * focused editable's text restarts its IME connection, which briefly
     * re-shows the keyboard a frame after the hide request went out (the
     * hide -> show -> hide flicker). The query is cleared instead when the
     * field leaves composition at the end of the bar's exit (see
     * [CollectionHeroSearchBar]); until then [matches] already ignores it via
     * the closed-[open] short-circuit, so the rows restore immediately.
     */
    fun close() {
        open = false
    }

    /** Matches a case-insensitive substring anywhere in the given field. */
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
 * Bottom content padding a host list gains while its hero's search bar is open
 * (feed it into the lazy container's [androidx.compose.foundation.layout.PaddingValues]):
 * the keyboard's own inset keeps the filtered rows visible above the open
 * keyboard, and a screen-fraction reserve gives even a handful of rows enough
 * scroll range for [CollectionSearchScrollToBarEffect] to bring the bar to the
 * screen top — a short list otherwise has no scrollable range at all, so the
 * open scroll would silently do nothing.
 *
 * The value ANIMATES on both edges: growing when the bar opens (the rows part
 * smoothly instead of being pushed apart in one frame) and, just as
 * importantly, shrinking progressively when it closes — without the animation
 * the padding would teleport from keyboard+reserve to 0dp in a single layout
 * pass and the whole list would visibly jump. The duration is matched to the
 * keyboard's dismiss and the bar's own exit ([CollectionSearchPaddingAnimationMs]),
 * and the lazy list clamps its scroll against the shrinking padding frame by
 * frame, so the settle reads as a natural slide rather than a snap. 0dp while
 * the bar is closed.
 */
@Composable
fun collectionSearchOpenBottomPadding(searchState: CollectionSearchState?): Dp {
    val target = if (searchState == null || !searchState.open) {
        0.dp
    } else {
        val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        // Fraction of the screen height: covers every hero layout's worst-case
        // distance between the screen top and the bar (the Normal hero's full-width
        // square artwork is the tallest) with room to spare, while scaling with the
        // actual window (landscape, tablets, foldables).
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
 * On every search-open request (see [CollectionSearchState.openRequest]) scrolls
 * the host's list so the hero's shuffle/play/search button row — with the search
 * bar hanging right under it — lands at the top of the screen instead of opening
 * wherever the hero currently sits (also scrolling back a little when the bar
 * opened partially hidden above the viewport).
 *
 * [listTopYInRootPx] supplies the lazy container's own root-space top y and
 * [topChromeHeight] how much of the list's top is covered by floating chrome:
 * on compact/normal layouts the list starts below its fixed top-bar row, so 0dp
 * puts the row at the very top of the scrolling content; on the immersive ones
 * the floating back row covers the list's first 48dp, so
 * [CollectionSearchImmersiveTopClearance] keeps the bar clear of it. The anchor
 * row reports itself through [CollectionSearchState.buttonRowTopYInRootPx]; the
 * effect is a no-op when that report is missing (header scrolled off-screen).
 *
 * Hosts must pad their lists with [collectionSearchOpenBottomPadding] while the
 * bar is open: without that reserve a list with only a few rows has no scroll
 * range, and the scroll here would be clamped to nothing.
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
        val rowTopY = searchState.buttonRowTopYInRootPx
        if (rowTopY.isNaN()) return@LaunchedEffect
        val listTopY = listTopYInRootPx()
        if (listTopY.isNaN()) return@LaunchedEffect
        val delta = rowTopY - listTopY - chromePx
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

/** Overlay-block vertical position for [layout], as a fraction of the image height. */
private fun collectionHeroOverlayFraction(layout: CollectionHeroLayout): Float = when (layout) {
    CollectionHeroLayout.Immersive -> CollectionHeroImmersiveOverlayFraction
    else -> CollectionHeroCompactImmersiveOverlayFraction
}

/**
 * The immersive layouts' leading list item: the hero's overlay block itself —
 * title/subtitle/stats and the optional play pill — hanging at the artwork's
 * overlay position, with the hero artwork's room reserved around it.
 *
 * The block is real, interactive content composed inside the list's own
 * subtree: the play pill receives taps directly (there is no invisible
 * hit-target twin behind the list) and the whole block scrolls away 1:1 with
 * the list exactly like a compact/normal header item, fading with the host
 * hierarchy's own transitions the same way. The item's height is not
 * estimated — it measures its own content — so the list's first row clears
 * the play pill by [CollectionHeroImmersiveListTopPadding] with no dead space
 * below the artwork, whatever the title/subtitle/button add up to.
 *
 * [scrollOffsetPx] — the host list's current scroll in px — drives the block's
 * scroll-out fade positionally: the block stays fully opaque until its top
 * nears the screen top, then fades out across [CollectionScrollTopBarFadeDistance]
 * so it is fully gone as the top reaches the status bar — instead of fading
 * with a raw scroll fraction, which completes while the block is mid-screen.
 * [transitionAlpha] — the host's combined flight alpha (see
 * [rememberCollectionHeroFlightFraction]) — fades the block with the
 * shared-element flight: the block is lifted into the transition overlay
 * ([collectionHeroOverlayLayer]) so it draws above the flying artwork instead
 * of being covered by it, and a lifted layer escapes the host hierarchy's own
 * fades unless its alpha compensates for both.
 *
 * [onVisibleHeightPx] reports the measured total (px) once the item is laid
 * out, so the host screen can hand the hero artwork's 1:1 scroll-away over to
 * the same constant when the item is scrolled past (see [CollectionHero]'s
 * [CollectionHero.scrollOffsetPx]).
 */@OptIn(ExperimentalSharedTransitionApi::class)
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
    // The overlay block hangs off a full-width hero Box with 16dp side
    // padding; measuring at the same width keeps line wraps identical.
    val overlayMaxWidthPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.roundToPx()
    }
    // Chrome flight fade: while a shared transition is active the block is
    // lifted above the flying artwork and escapes the host hierarchy's fades,
    // so its alpha rides the flight explicitly (same fraction as the hero).
    val flightFraction = rememberCollectionHeroFlightFraction(
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
    // Scroll-out fade distance in px: matches the scroll top bar's title fade
    // so the two swap places over the same stretch of scrolling.
    val fadeDistancePx = with(density) { CollectionScrollTopBarFadeDistance.toPx() }
    // Set by the measure pass below: the overlay block's height, anchoring the
    // draw-phase fade to the block's BOTTOM (the play pill). Read untracked.
    val blockHeightPx = remember { mutableIntStateOf(0) }

    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val block = subcompose(Unit) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .collectionHeroOverlayLayer(sharedTransitionScope)
                    .graphicsLayer {
                        // Positional fade anchored to the block's bottom (the
                        // play pill): full alpha until the pill is within the
                        // fade distance of the screen top, then fades out so it
                        // is fully transparent as the pill reaches the top.
                        // The window shrinks when the block starts closer to
                        // the top than the distance (compact-immersive) so it
                        // is always fully opaque at rest. Falls back to the raw
                        // [overlayTextAlpha] when the host cannot report
                        // scroll (scrollOffsetPx == null).
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
 * share the same content slots; [CollectionHeroLayout.Compact] is the classic
 * image-left/text-right header, [CollectionHeroLayout.Normal] a big rounded
 * artwork with centered text below, and the immersive variants bleed the
 * artwork under the status bar with the text block overlaid on it.
 *
 * Compact/Normal render the whole header (texts and play pill included) and
 * are composed as the list's leading item. The immersive variants render ONLY
 * the artwork layer: place this composable from a screen-level [Box] behind
 * the list (it offsets itself up behind the status bar), and make the list's
 * leading item a [CollectionHeroImmersiveHeader], which carries the overlaid
 * text block and play pill as real, interactive list content. Because the
 * artwork layer holds no interactive content, the play pill lives where taps
 * land and fades with the host hierarchy exactly like the compact/normal
 * heroes' — no behind-the-list hit-target copy is needed.
 * [transitionAlpha] (the host's combined flight alpha, see
 * [rememberCollectionHeroFlightFraction]) fades the status-bar scrim with the
 * shared-element flight, and [scrollOffsetPx] — the list's current scroll in
 * px — makes the artwork scroll away 1:1 with the list, exactly like a header
 * item would.
 *
 * When a shared-element context is provided AND the layout is immersive, the
 * artwork's corner radius (theme percentage -> 0) and bottom fade (none ->
 * faded) animate across the shared-element transition, so the flight departs
 * from the source row's rounded, unfaded look and lands on the square, faded
 * hero. Compact and Normal keep the static theme shape and no fade.
 *
 * NOTE on flight rendering: the shared-element overlay draws only the
 * becoming-visible side's own content and clip. On open that is this hero, so
 * the morph below (animated overlay clip + fade) is what renders. On close the
 * DRAWN side is the source row instead — which is why the row side carries its
 * own mirrored morph (see [collectionFlightArtwork]) rather than relying on
 * this hero's.
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
    searchState: CollectionSearchState? = null,
    onShuffleAllClick: (() -> Unit)? = null,
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
            icon = icon,
            onPlayAllClick = onPlayAllClick,
            onShuffleAllClick = onShuffleAllClick,
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
            icon = icon,
            onPlayAllClick = onPlayAllClick,
            onShuffleAllClick = onShuffleAllClick,
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
    onShuffleAllClick: (() -> Unit)?,
    searchState: CollectionSearchState?,
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
                        CollectionHeroPlayButton(onClick = onPlayAllClick, accentColor = accentColor)
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
    onShuffleAllClick: (() -> Unit)?,
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
                        CollectionHeroPlayButton(onClick = onPlayAllClick, accentColor = accentColor)
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
    // Read in composition: FoamTheme.shapes is @Composable, so it cannot be
    // touched inside the draw-phase graphicsLayer block below.
    val flightRounding = FoamTheme.shapes.miniPlayerRounding

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            .offset {
                // Bleed behind the status bar at rest, then ride the list's
                // scroll 1:1 so the hero scrolls away like a header item. The
                // overlay block (title/play pill) lives in the list's own
                // [CollectionHeroImmersiveHeader] item and scrolls with it.
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
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    // In-flight in-place look: the shared-element overlay only
                    // takes over once the match's bounds are configured, and
                    // screens opened without a fade render in place from frame
                    // one. The artwork must therefore carry the departing row's
                    // rounded corners ITSELF on every frame it renders in place,
                    // instead of flashing the settled square hero (radius 0).
                    shape = AlbumArtFlightShape(flightRounding) { flightFraction.value }
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
    }
}

/**
 * The immersive overlay block's content: title/subtitle/stats and the optional
 * play pill, inset 16dp like the rest of the hero. Composed by
 * [CollectionHeroImmersiveHeader] as the list's real interactive header item.
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
                        accentColor = accentColor
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

/**
 * Reports the hero's shuffle/play/search button row's root-space top y into
 * [searchState] (see [CollectionSearchState.buttonRowTopYInRootPx]) — the anchor
 * of the search-open scroll ([CollectionSearchScrollToBarEffect]). No-op when
 * [searchState] is null (play-pill-only heroes don't need it).
 */
private fun Modifier.reportHeroButtonRowTop(searchState: CollectionSearchState?): Modifier =
    onGloballyPositioned { info ->
        searchState?.buttonRowTopYInRootPx = info.positionInRoot().y
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
                .memoryCacheKey(albumArtMemoryCacheKey(imageUrl))
                .crossfade(false)
                .build()
        }
        AlbumArtImage(
            model = heroArtRequest,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            placeholderSize = 40.dp,
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

/** The accent play pill. */
@Composable
private fun CollectionHeroPlayButton(
    onClick: () -> Unit,
    accentColor: Color
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(accentColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary)
            ) { onClick() }
            .padding(horizontal = 24.dp, vertical = 10.dp),
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
            Spacer(modifier = Modifier.width(8.dp))
            BasicText(
                text = "Play",
                style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.colors.background)
            )
        }
    }
}

/**
 * The hero's shuffle toggle, rendered LEFT of the play pill: queues the
 * collection's items in shuffled order (play behaviour, shuffled queue).
 * Hidden when no [onShuffleAllClick] is provided.
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
            contentDescription = "Shuffle",
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * The hero's search toggle, rendered right of the play pill: the search icon at
 * rest, a close cross while the search bar is open. Tapping opens the bar (the
 * bar focuses its own field on entry, and the host scrolls it up to the screen
 * top — see [CollectionSearchScrollToBarEffect]); tapping the cross closes it
 * (the query clears as the bar collapses — see [CollectionHeroSearchBar]).
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
                contentDescription = if (open) "Close search" else "Search",
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * The hero's inline search field, shown right under the play pill while
 * [CollectionSearchState.open] is true: filters the collection's rows live
 * (empty text shows everything; closing clears the query). The field's caret
 * rides the hero's own [accentColor] — the collection's palette-derived accent
 * (the same color as its play pill) — not the global app accent.
 *
 * Focus handling:
 * - Closing the bar tears the field's focus down IMMEDIATELY (not when the
 *   exiting content is removed at the end of the shrink animation), so the
 *   keyboard starts hiding in the same frame the cross is tapped instead of
 *   after a visible delay.
 * - "Done" on the keyboard hides it (clearing focus closes the IME session).
 * - When the keyboard is dismissed under an open bar (system back gesture),
 *   the field's focus is dropped too, so the caret stops blinking — the bar
 *   itself stays open and the field can be re-tapped to bring the keyboard
 *   back. Guarded by [keyboardShownThisOpen] so the clear never races the
 *   open flow's own focus request while the keyboard is still coming up.
 * - The QUERY is cleared only when the field leaves composition (exit done):
 *   [CollectionSearchState.close] deliberately leaves it — resetting a focused
 *   editable's text restarts its IME connection, which re-shows the keyboard
 *   for a frame right after the hide request went out (the hide -> show ->
 *   hide flicker on track taps).
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
    // keyboard-closed focus drop below against the open flow's own focus
    // request (the keyboard takes a few frames to come up; clearing focus
    // in that window would kill the show-request before it lands).
    var keyboardShownThisOpen by remember { mutableStateOf(false) }
    LaunchedEffect(searchState.openRequest) { keyboardShownThisOpen = false }
    // Closing the bar: clear focus the moment [open] flips, so the IME
    // session is torn down immediately (clearing focus closes the keyboard)
    // instead of waiting for the AnimatedVisibility exit to remove the field.
    LaunchedEffect(searchState.open) {
        if (!searchState.open) focusManager.clearFocus(force = true)
    }
    // Keyboard dismissed under an open bar (back gesture / Done): drop focus
    // so the caret stops blinking while the bar stays open.
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
        // The query is cleared HERE — when the field is gone from composition —
        // and not in close(): resetting a focused editable's text restarts its
        // IME connection, which briefly re-shows the keyboard after the hide
        // request (see the doc above).
        DisposableEffect(Unit) {
            onDispose { searchState.query = "" }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
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
 * the transition is in flight (the departing screen's look), 0f once settled
 * (Visible). Consumed inside draw/graphicsLayer blocks, every frame of the
 * flight is draw-only. Returns a constant 0f when either scope is missing
 * (no shared-element context, e.g. heroes rendered without transition wiring).
 *
 * The fraction animates with the SAME duration/easing tween as the NavHost's screen
 * fade and [AlbumArtBoundsTransform] (SharedElementTransitionDurationMs/
 * SharedElementEasing), so the chrome fading with the flight lands on the
 * same frame as the screen fade and the flying artwork instead of trailing
 * them on a spring — which read as a pop at flight ends.
 *
 * Used both by the hero itself (artwork corner/fade morph, chrome fades), by
 * [CollectionHeroImmersiveHeader]'s overlay block and by host screens fading
 * their top-bar chrome in sync with the flight (see [collectionHeroOverlayLayer]).
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
        AlbumArtFlightShape(FoamTheme.shapes.miniPlayerRounding) { flightFraction.value }
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
    // The fades ride the SAME duration/easing tween as the bounds flight
    // (AlbumArtBoundsTransform) instead of sharedBounds' spring-based
    // defaults: with the defaults both texts stayed opaque well into the
    // flight and the departing one lingered past the landing — an instant
    // scale pop plus a dirty overlapping crossfade.
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
            boundsTransform = AlbumArtBoundsTransform,
            // Above the flying artwork so the title never disappears behind it
            // mid-flight (the artwork is zIndexInOverlay 0f).
            zIndexInOverlay = 1f
        )
    }
}

/**
 * Resolves a collection's hero layout from the app settings. Provided at the
 * nav root (FoamNavigation) so any screen hosting rows that fly into a
 * collection detail hero can know, without new parameter threading, whether
 * that hero uses an immersive layout (square + faded at rest) and therefore
 * whether its rows must carry the mirrored flight look (see
 * [collectionFlightArtwork]). The default resolves every target to Compact.
 */
val LocalCollectionHeroLayoutLookup: ProvidableCompositionLocal<(CollectionHeroTarget) -> CollectionHeroLayout> =
    compositionLocalOf { { CollectionHeroLayout.Compact } }

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
