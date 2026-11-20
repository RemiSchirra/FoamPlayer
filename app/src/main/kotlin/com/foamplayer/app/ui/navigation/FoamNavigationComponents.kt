package com.foamplayer.app.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import androidx.navigation.NavBackStackEntry
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.data.settings.FoamIconStyle
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.LocalAccentTintState
import com.foamplayer.app.ui.theme.SurfaceAccentTint
import com.foamplayer.app.ui.theme.SurfaceVariantAccentTint
import com.foamplayer.app.ui.utils.consumeTaps
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Elevation of both bars' drop shadow. */
private val NavBarShadowElevation = 6.dp

/**
 * Drawn shadow used by [FloatingNavigationBar] when the bar is translucent with the
 * player open. An elevation shadow would show as a ring through the fading surface.
 */
private val NavBarShadowRadius = 6.dp
private const val NavBarShadowStrength = 0.35f

/** Tween shared by all nav-bar slot motion (fade, slide, pill width). */
private val NavBarSlotAnimation = tween<Float>(FoamMotion.Duration.STANDARD, easing = FoamMotion.Easings.STANDARD)

/** [NavBarSlotAnimation] for Dp values. */
private val NavBarSlotDpAnimation = tween<Dp>(FoamMotion.Duration.STANDARD, easing = FoamMotion.Easings.STANDARD)

/** Alpha of the bubble mode's selection pill (semi-transparent textPrimary). */
private const val NavBarBubbleAlpha = 0.15f

/** How much the bubble mode's selection pill grows while it is dragged. */
private const val NavBarBubbleDragScale = 1.12f

/** Slot width shared by the item rows and the bubble: slots spread over the enabled items when there's room. */
private fun navSlotWidth(maxWidth: Dp, slotCount: Int, itemWidth: Dp, stretchBetweenItems: Boolean): Dp =
    if (stretchBetweenItems && slotCount > 0) {
        (maxWidth / slotCount).coerceIn(itemWidth, NavBarDimens.ItemHitTargetMaxWidth)
    } else {
        itemWidth
    }

/** Shape of every item's hit target: a circle in a packed row, a pill when it has room. */
private val NavItemHitShape = RoundedCornerShape(percent = 50)

/**
 * Height of the bottom zone (nav bar, floating mini player, system inset).
 * Screens scroll behind it and add this as bottom padding. 0 when unconstrained.
 */
val LocalBottomBarClearance = compositionLocalOf { 0.dp }

/**
 * Tag ("<kind>:<key>", e.g. "album:Abbey Road") of the collection row clicked to
 * start the current navigation, or null.
 *
 * Only the matching row registers its shared-element keys, so a transition pairs
 * one source row with its hero. Reset by navigations that don't start from a
 * collection row (see [FoamNavGraphContext.navigateUnshared]).
 */
val LocalActiveSharedCollection = compositionLocalOf { null as String? }

/** Separates a tag or key from its row origin. A control char, so it can't clash with names. */
private const val SharedCollectionOriginSeparator = '\u0000'

/**
 * Tag of a collection row. [origin] tells apart several copies of the same
 * collection on one screen (e.g. Home sections).
 */
fun sharedCollectionTag(kind: String, key: String, origin: String? = null): String =
    if (origin.isNullOrEmpty()) {
        "$kind:$key"
    } else {
        "$kind:$key$SharedCollectionOriginSeparator$origin"
    }

/** Scopes a shared-element key to a row [origin]. */
fun sharedCollectionElementKey(baseKey: String, origin: String?): String =
    if (origin.isNullOrEmpty()) {
        baseKey
    } else {
        "$baseKey$SharedCollectionOriginSeparator$origin"
    }

/**
 * Saved-state key for the tag a detail destination was opened with. Keeps the
 * hero's key stable while other pages are opened on top and closed again.
 */
internal const val SharedCollectionTagKey = "foam.sharedCollectionTag"

/**
 * Tag the current collection detail destination was opened with, read from its own
 * [NavBackStackEntry]. Heroes use it to scope their shared-element keys.
 */
val LocalCollectionDetailTag = compositionLocalOf { null as String? }

/** Origin encoded in [tag], or null. */
fun activeSharedCollectionOrigin(tag: String?): String? =
    tag?.substringAfter(SharedCollectionOriginSeparator, missingDelimiterValue = "")
        ?.takeIf { it.isNotEmpty() }

/** True if this row is the one clicked to start the current transition. */
@Composable
fun isActiveSharedCollection(kind: String, key: String): Boolean =
    LocalActiveSharedCollection.current == sharedCollectionTag(kind, key)

/**
 * True if a collection flight key ("album-art-<row id>", "playlist-title-<row id>", ...)
 * belongs to the clicked row. Other keys (player flights, empty) always return false.
 */
@Composable
fun isActiveSharedCollectionFlightKey(key: String): Boolean {
    val kind = key.substringBefore("-")
    val rowKey = key.substringAfter("-").substringAfter("-")
    return LocalActiveSharedCollection.current == sharedCollectionTag(kind, rowKey)
}

/** Detail route prefix for a collection kind. */
private fun collectionDetailRoutePrefix(kind: String): String = when (kind) {
    "album" -> "album/"
    "artist" -> "artist/"
    else -> "playlist/"
}

/**
 * True if the transition is between a screen and this kind's detail page, the only
 * pair a collection flight may run in. Stops rows with the same key on different
 * screens from pairing up (e.g. when switching tabs).
 * See [com.foamplayer.app.ui.components.collectionRowFlightArtwork].
 */
internal fun AnimatedVisibilityScope.isCollectionFlightPair(kind: String): Boolean {
    val detailPrefix = collectionDetailRoutePrefix(kind)
    val currentRoute = (transition.currentState as? NavBackStackEntry)?.destination?.route
        ?: return false
    val targetRoute = (transition.targetState as? NavBackStackEntry)?.destination?.route
        ?: return false
    return currentRoute.startsWith(detailPrefix) || targetRoute.startsWith(detailPrefix)
}

/**
 * State version of [LocalActiveSharedCollection].
 *
 * Readable outside composition (needed by [collectionFlightConfig]) and already up
 * to date when the destination first composes.
 */
val LocalActiveSharedCollectionTagState =
    compositionLocalOf { mutableStateOf<String?>(null) }

/**
 * Enables a collection flight only for the clicked row. The getter is evaluated
 * lazily by the transition, so it reads live state.
 */
internal fun collectionFlightConfig(
    tagState: State<String?>,
    key: String
): SharedTransitionScope.SharedContentConfig {
    // "album-art-<id>" -> kind "album", row id "<id>"
    val kind = key.substringBefore("-")
    val rowKey = key.substringAfter("-").substringAfter("-")
    return object : SharedTransitionScope.SharedContentConfig {
        override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
            get() = tagState.value == sharedCollectionTag(kind, rowKey)
    }
}

/**
 * Request for the search screen to open focused (keyboard up, history shown).
 * Raised by a tap on the search icon, not by a long press.
 * [com.foamplayer.app.ui.screens.search.SearchScreen] consumes it once honored.
 */
class SearchFocusRequest {
    var isPending: Boolean by mutableStateOf(false)
        private set

    fun request() {
        isPending = true
    }

    fun consume() {
        isPending = false
    }
}

val LocalSearchFocusRequest = compositionLocalOf { SearchFocusRequest() }

/** Geometry of the nav bar and the mini player. */
object NavBarDimens {

    // ---- Docked bar -----------------------------------------------------------
    /** Icon row height, with 4dp slack around each hit target. */
    val DockedRowHeight = 56.dp

    /** Extra surface above the icon row. Zero, kept to tune the bar height in one place. */
    val DockedConnector = 0.dp

    /** Docked container height, constant in every state. */
    val DockedBarHeight = DockedRowHeight + DockedConnector

    /**
     * Gap below the icon row, on top of the system inset. The surface still runs
     * down through it to the screen bottom.
     */
    val DockedBarBottomMargin = 4.dp

    // ---- Floating / attached bar ----------------------------------------------
    val FloatingRowHeight = 56.dp

    /** Margin around the pill's sides and top. */
    val FloatingBarMargin = 12.dp

    /** Gap between the pill's bottom edge and the system nav bar inset. */
    val FloatingBarBottomMargin = 4.dp

    /** Mini player artwork size, which the pill's corner radius follows. */
    val MiniPlayerArtSize = 44.dp

    /** Vertical padding around the card; its visible edge sits this far inside its box. */
    val MiniPlayerCardVerticalPadding = 6.dp

    /** Inner padding of the card. */
    val MiniPlayerContainerPadding = 8.dp

    /** Gap between the artwork row and the seek slider. */
    val MiniPlayerContentSpacing = 6.dp

    /** Gap between pages in the mini player pager. */
    val MiniPlayerPageSpacing = 12.dp

    /** Height of the seek slider strip. */
    val MiniPlayerSliderHeight = 12.dp

    /** Visible card height: artwork row, spacing, slider and inner padding. */
    val MiniPlayerCardHeight = MiniPlayerArtSize +
            MiniPlayerContentSpacing +
            MiniPlayerSliderHeight +
            MiniPlayerContainerPadding * 2

    /**
     * How much the pill's corner radius exceeds the artwork radius. Matches the
     * card's container so the attached extension blends into its corners.
     */
    val FloatingCornerOverhang = MiniPlayerContainerPadding

    // ---- Shared item metrics ---------------------------------------------------
    val ItemIconSize = 28.dp

    /** Hit padding for the extra row's items. */
    val ItemHitAreaPadding = 6.dp

    /** Hit target height (Material's 48dp minimum), and its width in a packed row. */
    val ItemHitTargetSize = 48.dp

    /** Widest a hit target stretches when its row has room. */
    val ItemHitTargetMaxWidth = 72.dp

    /** Padding around the icon that gives [ItemHitTargetSize]. */
    val ItemHitTargetPadding = (ItemHitTargetSize - ItemIconSize) / 2

    /** Padding between the edge buttons and the container edges, equal on all sides. */
    val RowEdgePadding = (FloatingRowHeight - ItemHitTargetSize) / 2

    // ---- Expandable extra row ---------------------------------------------------
    /** Tighter hit padding for the extra row's icons. */
    val ExtraRowItemHitPadding = 2.dp

    /**
     * Visible height of the extra row. Its touch targets are taller and live in an
     * unclipped overlay (see [FoamNavExtraRow]).
     */
    val ExtraRowHeight = ItemIconSize + ExtraRowItemHitPadding * 2

    /** Wanted edge-to-edge gap between the extra row's icons and the main row's. */
    val ExtraRowIconGap = 12.dp

    /** Margin kept between the extra row's ripples and the surface's top edge. */
    val ExtraRowRippleMargin = (FloatingRowHeight - ItemHitTargetSize) / 2

    /**
     * Extra surface height while expanded, inserted between the two rows so the
     * top and bottom paddings never change. [miniPlayerBottomPadding] and
     * [attachedExtensionHeight] use it too, to follow the taller bar.
     */
    val ExtraRowExpandedSpacing = 8.dp

    /**
     * How far the extra row dips into the main row. The natural dip comes from
     * [ExtraRowIconGap]; it is clamped up so the row's ripples stay inside the
     * surface ([ExtraRowRippleMargin]).
     */
    fun extraRowBottomOverlap(mainRowHeight: Dp): Dp =
        (
                ExtraRowItemHitPadding +
                        (mainRowHeight - (ItemIconSize + ItemHitAreaPadding * 2)) / 2 +
                        ItemHitAreaPadding -
                        ExtraRowIconGap
                ).coerceAtLeast(
                (ItemHitTargetSize - ExtraRowHeight) / 2 + ExtraRowRippleMargin
            )

    /**
     * How far content above the bar rises while the extra row expands. Less than
     * the row's height because of the dip ([extraRowBottomOverlap]).
     */
    fun extraRowVisibleRide(mode: NavigationBarMode, expandFraction: Float = 1f): Dp {
        val expandedGrowth = ExtraRowHeight + ExtraRowExpandedSpacing
        val dip = when (mode) {
            NavigationBarMode.DOCKED -> extraRowBottomOverlap(DockedBarHeight)
            else -> extraRowBottomOverlap(FloatingRowHeight)
        }
        return (expandedGrowth - dip) * expandFraction
    }

    // ---- Mini player card placement --------------------------------------------
    /** Gap between the card and the docked bar. */
    val DockedMiniPlayerGap = 2.dp

    /** Gap between the card and the floating pill. */
    val FloatingMiniPlayerGap = 4.dp

    /**
     * How far the card sinks into the bar in ATTACHED mode. Tuned so that, with
     * the extra row closed, the gap between the buttons and the seek strip
     * equals the gap between the buttons and the bar's bottom edge
     * ([RowEdgePadding] + [ItemHitTargetPadding]). The expanded card position
     * doesn't depend on this.
     */
    val AttachedCardDrop = 22.dp

    /**
     * ATTACHED, fully expanded: where the card's visible bottom edge lands relative
     * to the top of the extra row's icons. Negative, so the card sinks past them.
     */
    val AttachedExpandedIconGap = (-12).dp

    // ---- Player backdrop reach into the nav zone -------------------------------
    /**
     * Distance from the screen bottom (above the system inset) to the card's box
     * bottom edge. [expandFraction] is the animated expansion of the extra row.
     *
     * DOCKED/FLOATING: the card rises by the row's visible growth.
     * ATTACHED: the card lifts until it hovers just above the extra row's icons.
     */
    fun miniPlayerBottomPadding(mode: NavigationBarMode, expandFraction: Float = 0f): Dp {
        val attachedBase =
            FloatingBarBottomMargin + FloatingRowHeight + FloatingMiniPlayerGap - AttachedCardDrop
        // Extra bar height at full expansion
        val expandedGrowth = ExtraRowHeight + ExtraRowExpandedSpacing
        val base = when (mode) {
            NavigationBarMode.ATTACHED -> {
                val pillTop = FloatingBarBottomMargin + FloatingRowHeight + expandedGrowth
                val iconsTop = pillTop -
                        extraRowBottomOverlap(FloatingRowHeight) -
                        ExtraRowItemHitPadding
                val fullLift = iconsTop + AttachedExpandedIconGap -
                        MiniPlayerCardVerticalPadding - attachedBase - expandedGrowth
                attachedBase + fullLift * expandFraction
            }
            NavigationBarMode.FLOATING -> FloatingRowHeight + FloatingBarBottomMargin + FloatingMiniPlayerGap
            else -> DockedBarHeight + DockedMiniPlayerGap + DockedBarBottomMargin
        }
        val ride = when (mode) {
            NavigationBarMode.ATTACHED -> expandedGrowth
            NavigationBarMode.FLOATING -> expandedGrowth - extraRowBottomOverlap(FloatingRowHeight)
            else -> expandedGrowth - extraRowBottomOverlap(DockedBarHeight)
        }
        return base + ride * expandFraction
    }

    /**
     * Collapsed height of the nav zone, without the system inset. The extra row
     * grows upwards as an overlay, so this never changes.
     */
    fun navBarZoneHeight(mode: NavigationBarMode): Dp = when (mode) {
        NavigationBarMode.DOCKED -> DockedBarHeight + DockedBarBottomMargin
        else -> FloatingRowHeight + FloatingBarMargin + FloatingBarBottomMargin
    }

    /** Fade length of the elevation scrim at a bar surface's top edge. */
    val BarElevationScrimHeight = 16.dp

    /**
     * How far the attached pill's surface must reach above its top edge to sit flush
     * with the card's top. In ATTACHED mode this extension is the card's container.
     *
     * [miniPlayerBoxHeight] is the measured box height of the mini player; the
     * default falls back to the derived [MiniPlayerCardHeight].
     */
    fun attachedExtensionHeight(
        expandFraction: Float = 0f,
        miniPlayerBoxHeight: Dp = MiniPlayerCardHeight + MiniPlayerCardVerticalPadding * 2
    ): Dp {
        val cardTop = miniPlayerBottomPadding(NavigationBarMode.ATTACHED, expandFraction) +
                miniPlayerBoxHeight -
                MiniPlayerCardVerticalPadding
        val pillTop = FloatingBarBottomMargin + FloatingRowHeight +
                (ExtraRowHeight + ExtraRowExpandedSpacing) * expandFraction
        return (cardTop - pillTop).coerceAtLeast(0.dp)
    }
}

/**
 * Icon of a nav item, tinted by selection, with no interaction.
 * Split from [FoamNavItem] so [FoamNavExtraRow] can draw icons in its clipped layer
 * and keep the touch targets in an unclipped one.
 *
 * The tint is resolved at draw time: the route-selection amount is animated
 * with [tintTiming]'s duration — its spec is only read when [selected] flips,
 * so the fade lasts exactly as long as the screen transition that caused it —
 * and the player fraction is read per frame, so swiping the player open/closed
 * fades the accent with the gesture. Accent-color changes redraw without
 * recomposing.
 *
 * [activeIcon] and [inactiveIcon] crossfade on the same route-selection
 * fraction that drives the color highlight, so the icon swap and the accent
 * fade together: the inactive variant fades out while the active one fades
 * in, both at the selection tint. Identical vectors (a disabled dynamic-icon
 * switch) draw once with no crossfade.
 */
@Composable
private fun NavItemVisual(
    activeIcon: ImageVector,
    inactiveIcon: ImageVector,
    selected: Boolean,
    tintTiming: ScreenSwitchTiming,
    playerClosedFraction: () -> Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val routeSelection by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (selected) tintTiming.enterMillis else tintTiming.exitMillis,
            easing = FoamMotion.Easings.STANDARD
        ),
        label = "NavRouteSelection"
    )
    val activePainter = rememberVectorPainter(activeIcon)
    val inactivePainter = rememberVectorPainter(inactiveIcon)
    val secondary = FoamTheme.colors.textSecondary
    val accentTint = LocalAccentTintState.current
    Box(
        modifier
            .size(NavBarDimens.ItemIconSize)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
            .drawBehind {
                val amount = (routeSelection * playerClosedFraction()).coerceIn(0f, 1f)
                val tint = ColorFilter.tint(lerp(secondary, accentTint.color, amount))
                if (activeIcon == inactiveIcon) {
                    with(activePainter) {
                        draw(size, colorFilter = tint)
                    }
                } else {
                    // Inactive: fades out as the selection arrives.
                    with(inactivePainter) {
                        draw(size, colorFilter = tint, alpha = 1f - amount)
                    }
                    // Active: fades in on top as the selection arrives.
                    with(activePainter) {
                        draw(size, colorFilter = tint, alpha = amount)
                    }
                }
            }
    )
}

/**
 * Clickable nav icon. The hit target fills its slot width (see [NavBarSlotRow]) and
 * is clipped to [NavItemHitShape] before the click so the ripple stays rounded.
 * [onLongClick] is optional (only used by the search item).
 * [tintTiming], [activeIcon] and [inactiveIcon] drive the icon's selection tint
 * and variant crossfade (see [NavItemVisual]).
 */
@Composable
private fun FoamNavItem(
    activeIcon: ImageVector,
    inactiveIcon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    tintTiming: ScreenSwitchTiming,
    playerClosedFraction: () -> Float,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    hitPadding: Dp = NavBarDimens.ItemHitTargetPadding,
    enabled: Boolean = true,
    bubbleMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(NavItemHitShape)
            .combinedClickable(
                interactionSource = interactionSource,
                // Bubble mode: the shared selection pill replaces the ripple.
                indication = if (bubbleMode) null else ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(hitPadding),
        contentAlignment = Alignment.Center
    ) {
        NavItemVisual(
            activeIcon = activeIcon,
            inactiveIcon = inactiveIcon,
            contentDescription = contentDescription,
            selected = selected,
            tintTiming = tintTiming,
            playerClosedFraction = playerClosedFraction,
            modifier = iconModifier
        )
    }
}

/** The main-bar screen an editable [NavBarItem] points to. */
private fun NavBarItem.targetScreen(): Screen = when (this) {
    NavBarItem.HOME -> Screen.Home
    NavBarItem.PLAYLISTS -> Screen.Playlists
    NavBarItem.SEARCH -> Screen.Search
    NavBarItem.STATS -> Screen.Stats
    NavBarItem.EQUALIZER -> Screen.Equalizer
    NavBarItem.SETTINGS -> Screen.Settings
    // Unreachable: MORE is the expand toggle and never navigates.
    NavBarItem.MORE -> Screen.Settings
}

/** The item's icon in [variant]; null = the theme's current (default) set. */
@Composable
private fun NavBarItem.icon(variant: FoamIconStyle?): ImageVector {
    val icons = FoamTheme.icons
    val set = if (variant == null) icons.current else icons.setFor(variant)
    return when (this) {
        NavBarItem.HOME -> set.home
        NavBarItem.PLAYLISTS -> set.playlists
        NavBarItem.SEARCH -> set.search
        NavBarItem.STATS -> set.stats
        NavBarItem.EQUALIZER -> set.equalizer
        NavBarItem.SETTINGS -> set.settings
        NavBarItem.MORE -> set.more
    }
}

/**
 * Whether an item pointing at [screen] is the screen currently shown. The
 * player's dehighlight is a separate, gesture-following factor (see
 * [NavItemVisual]), so opening/closing the player fades the accent with the
 * swipe instead of on its own timer.
 */
private fun isRouteSelected(currentRoute: String?, screen: Screen): Boolean =
    currentRoute == screen.route ||
            (screen == Screen.Settings && currentRoute?.startsWith("settings/") == true)

/** Per-bar holder for the tint timing of the last screen switch. */
private class SwitchTintHolder(initialRoute: String?) {
    var route: String? = initialRoute
    var timing: ScreenSwitchTiming = ScreenSwitchTiming.Rest
}

/**
 * Enter/exit durations of the selection-tint fade for the screen transition
 * between the previous and the current route (see [screenSwitchTiming]).
 * Recomputed only on route changes, so an in-flight fade keeps its spec until
 * the next switch.
 */
@Composable
private fun rememberSwitchTintTiming(currentRoute: String?): ScreenSwitchTiming {
    val holder = remember { SwitchTintHolder(currentRoute) }
    if (currentRoute != holder.route) {
        holder.timing = screenSwitchTiming(
            initialRoute = holder.route,
            targetRoute = currentRoute
        )
        holder.route = currentRoute
    }
    return holder.timing
}

/**
 * Row of icon slots shared by the main and extra rows.
 *
 * [order] is the full layout (enabled and disabled items); slots are spread over
 * the enabled ones. Every item stays composed so its animation state isn't lost:
 * - Enable: snaps to its slot, fades/scales in, others slide apart.
 * - Disable: stays put and fades/scales out, others slide closed.
 * - Reorder: items slide to their new slots.
 *
 * Positions are animated as a center fraction of the row width rather than a Dp,
 * so icons follow the pill's width exactly while it animates.
 *
 * With [stretchBetweenItems], each slot widens from [itemWidth] up to
 * [NavBarDimens.ItemHitTargetMaxWidth] so neighbouring targets meet halfway.
 */
@Composable
private fun NavBarSlotRow(
    order: List<NavBarItem>,
    enabledItems: Set<NavBarItem>,
    itemWidth: Dp,
    stretchBetweenItems: Boolean = false,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: NavBarItem, enabled: Boolean) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val enabledList = order.filter { it in enabledItems }
        val slotCount = enabledList.size

        // Read live (not animated) so it follows the pill's width.
        val slotWidth = navSlotWidth(maxWidth, slotCount, itemWidth, stretchBetweenItems)

        order.forEach { item ->
            key(item) {
                val isEnabled = item in enabledItems
                val slotIndex = enabledList.indexOf(item)

                val visibility by animateFloatAsState(
                    targetValue = if (isEnabled) 1f else 0f,
                    animationSpec = NavBarSlotAnimation,
                    label = "navSlotVisibility_${item.name}"
                )

                // Last position, kept for disabled items while they fade out.
                val lastFraction = remember { mutableStateOf(0f) }
                val centerFraction: Float = if (isEnabled && slotIndex >= 0) {
                    // key(isEnabled) resets the animation on enable, so the item
                    // appears in its slot instead of sliding in.
                    key(isEnabled) {
                        val animatedFraction by animateFloatAsState(
                            targetValue = (slotIndex + 0.5f) / slotCount,
                            animationSpec = NavBarSlotAnimation,
                            label = "navSlotFraction_${item.name}"
                        )
                        SideEffect { lastFraction.value = animatedFraction }
                        animatedFraction
                    }
                } else {
                    lastFraction.value
                }

                Box(
                    modifier = Modifier
                        // offset goes before graphicsLayer so scaling pivots on the icon's center
                        .offset(x = maxWidth * centerFraction - slotWidth / 2)
                        .graphicsLayer {
                            alpha = visibility
                            val scale = 0.4f + 0.6f * visibility
                            scaleX = scale
                            scaleY = scale
                        }
                        .width(slotWidth)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    itemContent(item, isEnabled)
                }
            }
        }
    }
}

/**
 * Main row of icons.
 * [items] is the configured layout, including the [NavBarItem.MORE] expand toggle.
 * The toggle is only enabled while [showExpandToggle] is true.
 */
@Composable
private fun FoamNavigationItems(
    currentRoute: String?,
    tintTiming: ScreenSwitchTiming,
    playerClosedFraction: () -> Float,
    onNavigate: (String) -> Unit,
    items: List<NavBarItem>,
    primaryEnabled: Set<NavBarItem>,
    dynamicIcons: Boolean,
    activeVariant: FoamIconStyle,
    inactiveVariant: FoamIconStyle,
    showExpandToggle: Boolean = false,
    expandFraction: Float = 0f,
    onToggleExpand: () -> Unit = {},
    bubbleMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val effectiveEnabled = if (showExpandToggle) primaryEnabled + NavBarItem.MORE else primaryEnabled
    val enabledList = items.filter { it in effectiveEnabled }
    val searchFocusRequest = LocalSearchFocusRequest.current

    // The selection bubble and its drag gesture live on the bar itself (see
    // [rememberNavBarBubble]): a single bubble travels between this row and the
    // extra row, so it can't be owned by either of them.
    Box(modifier = modifier) {
        NavBarSlotRow(
            order = items,
            enabledItems = effectiveEnabled,
            itemWidth = NavBarDimens.ItemHitTargetSize,
            stretchBetweenItems = true,
            modifier = Modifier.matchParentSize()
        ) { item, itemEnabled ->
            if (item == NavBarItem.MORE) {
                // The toggle is never selected: with dynamic icons it renders the
                // inactive variant.
                val moreIcon = item.icon(if (dynamicIcons) inactiveVariant else null)
                FoamNavItem(
                    activeIcon = moreIcon,
                    inactiveIcon = moreIcon,
                    contentDescription = stringResource(
                        if (expandFraction > 0.5f) R.string.collapse_navigation else R.string.expand_navigation
                    ),
                    selected = false,
                    tintTiming = tintTiming,
                    playerClosedFraction = playerClosedFraction,
                    enabled = itemEnabled,
                    bubbleMode = bubbleMode,
                    iconModifier = Modifier.rotate(expandFraction * 90f),
                    onClick = onToggleExpand
                )
            } else {
                val screen = item.targetScreen()
                val selected = isRouteSelected(currentRoute, screen)
                // Search: tap opens it focused, long press opens the browse cards.
                val isSearch = item == NavBarItem.SEARCH

                val activeIcon = item.icon(if (dynamicIcons) activeVariant else null)
                val inactiveIcon = if (dynamicIcons) item.icon(inactiveVariant) else activeIcon

                FoamNavItem(
                    activeIcon = activeIcon,
                    inactiveIcon = inactiveIcon,
                    contentDescription = screen.title,
                    selected = selected,
                    tintTiming = tintTiming,
                    playerClosedFraction = playerClosedFraction,
                    enabled = itemEnabled,
                    bubbleMode = bubbleMode,
                    onLongClick = {
                        if (isSearch)
                            searchFocusRequest.request()
                        onNavigate(screen.route)

                    },
                    onClick = {
                        onNavigate(screen.route)
                    }
                )
            }
        }
    }
}

/**
 * Secondary row revealed above the main row. [expandFraction] (0..1) drives its
 * height and alpha.
 *
 * Built from two stacked layers on the same slots: a clipped visual layer with the
 * icons, and an unclipped hit layer with the touch targets. They can't share one
 * layer because the row is clipped while it grows, and a clipping node doesn't
 * hit-test outside its bounds, so the part of a target overflowing the tight row
 * would be dead. The main row is composed after this one, so its targets win where
 * they overlap.
 */
@Composable
private fun FoamNavExtraRow(
    items: List<NavBarItem>,
    enabledItems: Set<NavBarItem>,
    currentRoute: String?,
    tintTiming: ScreenSwitchTiming,
    playerClosedFraction: () -> Float,
    onNavigate: (String) -> Unit,
    dynamicIcons: Boolean,
    activeVariant: FoamIconStyle,
    inactiveVariant: FoamIconStyle,
    expandFraction: Float,
    bubbleMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (expandFraction <= 0f) return

    val searchFocusRequest = LocalSearchFocusRequest.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NavBarDimens.ExtraRowHeight * expandFraction)
    ) {
        // Visual layer: icons only, no interaction
        NavBarSlotRow(
            order = items,
            enabledItems = enabledItems,
            itemWidth = NavBarDimens.ItemIconSize + NavBarDimens.ExtraRowItemHitPadding * 2,
            modifier = Modifier
                .matchParentSize()
                .clipToBounds()
                .alpha(expandFraction.coerceIn(0f, 1f))
                .padding(horizontal = NavBarDimens.RowEdgePadding)
        ) { item, _ ->
            val screen = item.targetScreen()
            val activeIcon = item.icon(if (dynamicIcons) activeVariant else null)
            val inactiveIcon = if (dynamicIcons) item.icon(inactiveVariant) else activeIcon
            NavItemVisual(
                activeIcon = activeIcon,
                inactiveIcon = inactiveIcon,
                selected = isRouteSelected(currentRoute, screen),
                tintTiming = tintTiming,
                playerClosedFraction = playerClosedFraction,
                modifier = Modifier.padding(NavBarDimens.ExtraRowItemHitPadding)
            )
        }

        // Hit layer: unclipped touch targets, none for disabled items
        NavBarSlotRow(
            order = items,
            enabledItems = enabledItems,
            itemWidth = NavBarDimens.ItemHitTargetSize,
            stretchBetweenItems = true,
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = NavBarDimens.RowEdgePadding)
        ) { item, itemEnabled ->
            if (itemEnabled) {
                val screen = item.targetScreen()
                val isSearch = item == NavBarItem.SEARCH
                Box(
                    modifier = Modifier
                        // Width follows the slot, only the height overflows the row
                        .fillMaxWidth()
                        .requiredHeight(NavBarDimens.ItemHitTargetSize)
                        .graphicsLayer {
                            val fraction = expandFraction.coerceIn(0f, 1f)
                            alpha = fraction
                            scaleX = fraction
                            scaleY = fraction
                        }
                        .clip(NavItemHitShape)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            // Bubble mode: the shared selection pill replaces the ripple.
                            indication = if (bubbleMode) null else ripple(color = FoamTheme.colors.textPrimary),
                            onLongClick = if (isSearch) {
                                { onNavigate(screen.route) }
                            } else {
                                null
                            },
                            onClick = {
                                if (isSearch) searchFocusRequest.request()
                                onNavigate(screen.route)
                            }
                        )
                        .semantics { contentDescription = screen.title }
                )
            }
        }
    }
}

/** The bar row the selection bubble can sit on. */
private enum class BubbleRow { Main, Extra }

/** Linear blend of two pixel values. */
private fun blendPx(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/**
 * State of the bubble mode's selection pill, shared by the main and the extra row.
 *
 * The bubble sits on ONE row at a time. Its position is described by:
 * - [xFraction]: horizontal center as a fraction of the row width (same
 *   convention as the icon slots, so it follows the icons when rows reflow);
 * - [rowBlend]: 0 = main row, 1 = extra row. Animating it moves the bubble
 *   vertically between the two rows; the actual Y is resolved at draw time from
 *   the live layout, so it also follows the extra row while it expands.
 *
 * Gestures:
 * - Selection change: slides to the selected item, switching row if needed.
 * - Drag (any direction, anywhere on the bar): the bubble follows the finger in X
 *   and, once the extra row is open, in Y too (clamped between the two row centers),
 *   so it glides between the rows instead of snapping to one of them.
 * - Hovering the "more" item while the extra row is closed opens it.
 * - Release: settles on the closest item of the closest row and navigates to it.
 */
private class NavBarBubbleController(
    private val scope: CoroutineScope,
    initialFraction: Float,
    initialRow: BubbleRow,
    initialVisible: Boolean
) {
    // ---- Geometry & callbacks, refreshed by rememberNavBarBubble on every recomposition ----
    var expandFraction: Float = 0f
    var edgePx = 0f
    var itemHeightPx = 0f
    var minSlotPx = 0f
    var maxSlotPx = 0f
    var mainRowHeightPx = 0f
    var extraRowHeightPx = 0f
    var extraOverlapPx = 0f

    /** Enabled slots per row (the main row includes the "more" toggle). */
    var mainCount = 0
    var extraCount = 0

    /** Slot index of the "more" toggle in the main row, or -1. */
    var mainMoreIndex = -1

    /** Where the selected item lives, or null when nothing is selected. */
    var selectedRow: BubbleRow? = null
    var selectedIndex = -1

    /** Opens the extra row (the bar's toggle; only called while it is closed). */
    var onAutoOpen: () -> Unit = {}

    /** Drop handler: (row, slot index, whether this drag already opened the extra row). */
    var onDrop: (BubbleRow, Int, Boolean) -> Unit = { _, _, _ -> }

    // ---- Animated state ----
    val xFraction = Animatable(initialFraction)
    val rowBlend = Animatable(if (initialRow == BubbleRow.Extra) 1f else 0f)
    val alphaAnim = Animatable(if (initialVisible) 1f else 0f)
    val scaleAnim = Animatable(1f)

    /** Bubble center X (px, row frame) while the finger holds it. */
    var dragCenterX by mutableFloatStateOf(0f)

    /** Vertical position while the finger holds the bubble: 0 = main row center, 1 = extra row center. */
    var dragBlend by mutableFloatStateOf(0f)

    /** True while the finger holds the bubble; drives the scale-up. */
    var dragging by mutableStateOf(false)

    // ---- Internal state ----
    private var columnWidthPx = 0f
    private var columnHeightPx = 0f
    private var rowTarget = initialRow
    private var holdPosition = false
    private var autoOpened = false
    private var settleJob: Job? = null

    private val rowWidthPx: Float get() = (columnWidthPx - 2 * edgePx).coerceAtLeast(0f)

    private fun rowValue(row: BubbleRow) = if (row == BubbleRow.Extra) 1f else 0f

    private fun countOf(row: BubbleRow) = if (row == BubbleRow.Main) mainCount else extraCount

    /** Same rule as [navSlotWidth] (both rows stretch their slots). */
    private fun slotWidthPx(count: Int, rowW: Float): Float =
        if (count > 0) (rowW / count).coerceIn(minSlotPx, maxSlotPx) else minSlotPx

    /** Center of slot [index], as a fraction of the row width. */
    private fun fractionOf(row: BubbleRow, index: Int): Float {
        val count = countOf(row)
        return if (count > 0) (index + 0.5f) / count else 0.5f
    }

    private fun closestIndex(x: Float, row: BubbleRow): Int {
        val count = countOf(row)
        val rowW = rowWidthPx
        return (0 until count).minByOrNull { slot ->
            abs((slot + 0.5f) / count * rowW - x)
        } ?: 0
    }

    private fun mainCenterY(height: Float) = height - mainRowHeightPx / 2

    private fun extraCenterY(fraction: Float) = extraOverlapPx + extraRowHeightPx * fraction / 2

    /**
     * Where the finger is between the two row centers (0 = main row, 1 = extra row),
     * clamped to the rows. The extra row only counts once it is mostly open.
     */
    private fun blendForY(y: Float): Float {
        if (extraCount == 0 || expandFraction <= 0.5f || columnHeightPx <= 0f) return 0f
        val main = mainCenterY(columnHeightPx)
        val extra = extraCenterY(expandFraction.coerceIn(0f, 1f))
        val span = main - extra
        if (span <= 0f) return 0f
        return ((main - y) / span).coerceIn(0f, 1f)
    }

    /** Slide to the selected item (possibly on the other row); suppressed while the finger holds the bubble. */
    fun retarget() {
        val row = selectedRow ?: return
        if (holdPosition) return
        settleJob?.cancel()
        rowTarget = row
        val target = fractionOf(row, selectedIndex)
        scope.launch { xFraction.animateTo(target, NavBarSlotAnimation) }
        scope.launch { rowBlend.animateTo(rowValue(row), NavBarSlotAnimation) }
    }

    private fun onDragStart(offset: Offset) {
        settleJob?.cancel()
        holdPosition = true
        autoOpened = false
        dragging = true
        followFinger(offset)
    }

    /** The bubble follows the finger exactly in X and Y (Y limited to the span between the rows). */
    private fun followFinger(position: Offset) {
        val blend = blendForY(position.y)
        dragBlend = blend
        // The row the bubble would settle on if released now
        val row = if (blend >= 0.5f) BubbleRow.Extra else BubbleRow.Main
        rowTarget = row

        val rowW = rowWidthPx
        val width = blendPx(
            slotWidthPx(mainCount, rowW),
            slotWidthPx(extraCount, rowW),
            blend
        )
        val half = width / 2
        val x = position.x - edgePx
        dragCenterX = if (rowW > width) x.coerceIn(half, rowW - half) else x

        // Bubble over the "more" item while the extra row is closed: open it.
        if (!autoOpened &&
            row == BubbleRow.Main &&
            mainMoreIndex >= 0 &&
            expandFraction < 0.5f &&
            closestIndex(dragCenterX, BubbleRow.Main) == mainMoreIndex
        ) {
            autoOpened = true
            onAutoOpen()
        }
    }

    /**
     * End of the drag. The bubble leaves the release position (X and Y) and slides to the
     * closest item of the closest row, scaling back down. Releasing on "more"
     * hands the bubble back to the selected item instead (more is never selected).
     */
    private fun finishDrag(navigate: Boolean) {
        if (!dragging) return
        val row = rowTarget
        val index = closestIndex(dragCenterX, row)
        val selected = selectedRow
        val settleRow: BubbleRow
        val settleIndex: Int
        if (row == BubbleRow.Main && index == mainMoreIndex && selected != null) {
            settleRow = selected
            settleIndex = selectedIndex
        } else {
            settleRow = row
            settleIndex = index
        }
        rowTarget = settleRow
        val target = fractionOf(settleRow, settleIndex)
        val release = if (rowWidthPx > 0f) dragCenterX / rowWidthPx else target
        val releaseBlend = dragBlend
        settleJob = scope.launch {
            // Hand the finger's position over to the animatables, then slide to the item
            rowBlend.snapTo(releaseBlend)
            xFraction.snapTo(release)
            dragging = false
            launch { rowBlend.animateTo(rowValue(settleRow), NavBarSlotAnimation) }
            xFraction.animateTo(target, NavBarSlotAnimation)
            holdPosition = false
        }
        if (navigate) onDrop(row, index, autoOpened)
    }

    /**
     * Drag detector for the whole bar (both rows), applied on the bar's surface —
     * an ancestor of every item clickable, so taps fall through to the icons and
     * drags past the touch slop grab the bubble.
     */
    fun gestureModifier(): Modifier = Modifier
        .onSizeChanged {
            columnWidthPx = it.width.toFloat()
            columnHeightPx = it.height.toFloat()
        }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset -> onDragStart(offset) },
                onDrag = { change, _ ->
                    change.consume()
                    followFinger(change.position)
                },
                onDragEnd = { finishDrag(navigate = true) },
                onDragCancel = { finishDrag(navigate = false) }
            )
        }

    /**
     * Draws the pill in the bar surface's coordinate space, behind the icons
     * (call it from the surface's drawBehind, after the surface itself). The
     * footprint is the hit target's: slot width × [NavBarDimens.ItemHitTargetSize].
     */
    fun DrawScope.drawBubble(color: Color) {
        val blend = (if (dragging) dragBlend else rowBlend.value).coerceIn(0f, 1f)
        val expand = expandFraction.coerceIn(0f, 1f)
        // The extra row's targets scale/fade with its expansion; so does the bubble on it.
        val presence = 1f + (expand - 1f) * blend
        val alpha = alphaAnim.value * presence
        if (alpha <= 0.001f) return

        val rowW = size.width - 2 * edgePx
        val width = blendPx(slotWidthPx(mainCount, rowW), slotWidthPx(extraCount, rowW), blend)
        val centerX = edgePx + if (dragging) dragCenterX else xFraction.value * rowW
        val centerY = blendPx(mainCenterY(size.height), extraCenterY(expand), blend)
        val scale = scaleAnim.value * presence
        val w = width * scale
        val h = itemHeightPx * scale

        drawRoundRect(
            color = color.copy(alpha = color.alpha * alpha),
            topLeft = Offset(centerX - w / 2, centerY - h / 2),
            size = Size(w, h),
            cornerRadius = CornerRadius(h / 2)
        )
    }
}

/**
 * The bar's shared selection bubble, or null when bubble mode is off. It also
 * wires the drop (navigation, or toggling the extra row) and keeps the bubble on
 * the selected item. The bar applies [NavBarBubbleController.gestureModifier] to
 * its surface and calls [NavBarBubbleController.drawBubble] from its drawBehind.
 */
@Composable
private fun rememberNavBarBubble(
    bubbleMode: Boolean,
    currentRoute: String?,
    primaryItems: List<NavBarItem>,
    primaryEnabled: Set<NavBarItem>,
    secondaryItems: List<NavBarItem>,
    secondaryEnabled: Set<NavBarItem>,
    mainRowHeight: Dp,
    expandFraction: Float,
    onNavigate: (String) -> Unit,
    onToggleExpand: () -> Unit
): NavBarBubbleController? {
    if (!bubbleMode) return null

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Same slot layout as FoamNavigationItems / FoamNavExtraRow.
    val effectivePrimaryEnabled =
        if (secondaryEnabled.isNotEmpty()) primaryEnabled + NavBarItem.MORE else primaryEnabled
    val mainList = primaryItems.filter { it in effectivePrimaryEnabled }
    val extraList = secondaryItems.filter { it in secondaryEnabled }

    val selectedMainIndex = mainList.indexOfFirst {
        it != NavBarItem.MORE && isRouteSelected(currentRoute, it.targetScreen())
    }
    val selectedExtraIndex = extraList.indexOfFirst {
        it != NavBarItem.MORE && isRouteSelected(currentRoute, it.targetScreen())
    }
    val selectedRow: BubbleRow? = when {
        selectedMainIndex >= 0 -> BubbleRow.Main
        selectedExtraIndex >= 0 -> BubbleRow.Extra
        else -> null
    }
    val selectedIndex = if (selectedRow == BubbleRow.Extra) selectedExtraIndex else selectedMainIndex

    val controller = remember {
        val count = if (selectedRow == BubbleRow.Extra) extraList.size else mainList.size
        NavBarBubbleController(
            scope = scope,
            initialFraction = if (selectedRow != null && count > 0) (selectedIndex + 0.5f) / count else 0.5f,
            initialRow = selectedRow ?: BubbleRow.Main,
            initialVisible = selectedRow != null
        )
    }

    with(density) {
        controller.expandFraction = expandFraction
        controller.edgePx = NavBarDimens.RowEdgePadding.toPx()
        controller.itemHeightPx = NavBarDimens.ItemHitTargetSize.toPx()
        controller.minSlotPx = NavBarDimens.ItemHitTargetSize.toPx()
        controller.maxSlotPx = NavBarDimens.ItemHitTargetMaxWidth.toPx()
        controller.mainRowHeightPx = mainRowHeight.toPx()
        controller.extraRowHeightPx = NavBarDimens.ExtraRowHeight.toPx()
        controller.extraOverlapPx = NavBarDimens.extraRowBottomOverlap(mainRowHeight).toPx()
    }
    controller.mainCount = mainList.size
    controller.extraCount = extraList.size
    controller.mainMoreIndex = mainList.indexOf(NavBarItem.MORE)
    controller.selectedRow = selectedRow
    controller.selectedIndex = selectedIndex
    controller.onAutoOpen = onToggleExpand
    controller.onDrop = { row, index, autoOpened ->
        val item = (if (row == BubbleRow.Main) mainList else extraList).getOrNull(index)
        if (item != null) {
            if (item == NavBarItem.MORE) {
                // A drag that already opened the extra row must not close it again.
                if (!autoOpened) onToggleExpand()
            } else {
                val screen = item.targetScreen()
                // Dropping on the already-selected item is a no-op:
                // don't push a duplicate entry on the back stack.
                if (!isRouteSelected(currentRoute, screen)) onNavigate(screen.route)
            }
        }
    }

    // Keep the bubble on the selected item, hopping rows when the selection does.
    LaunchedEffect(selectedRow, selectedIndex, mainList.size, extraList.size) {
        controller.retarget()
    }

    // Hides while nothing is selected; grows while dragged. Both tweened.
    val visible = selectedRow != null || controller.dragging
    LaunchedEffect(visible) {
        controller.alphaAnim.animateTo(if (visible) 1f else 0f, NavBarSlotAnimation)
    }
    val dragging = controller.dragging
    LaunchedEffect(dragging) {
        controller.scaleAnim.animateTo(
            if (dragging) NavBarBubbleDragScale else 1f,
            tween(FoamMotion.Duration.SNAP, easing = FoamMotion.Easings.STANDARD)
        )
    }
    return controller
}

/**
 * Classic docked navigation bar.
 *
 * [playerClosedFraction] (1 = player closed, 0 = open) fades the selection
 * tint with the player transition, so the accent follows the swipe in and out.
 * [dynamicIcons] with [activeVariant]/[inactiveVariant] swaps each item's icon
 * variant with its selection (see [NavItemVisual]).
 */
@Composable
fun FoamNavigationBar(
    currentRoute: String?,
    playerClosedFraction: () -> Float = { 1f },
    onNavigate: (String) -> Unit,
    primaryItems: List<NavBarItem>,
    primaryEnabled: Set<NavBarItem>,
    secondaryItems: List<NavBarItem>,
    secondaryEnabled: Set<NavBarItem>,
    dynamicIcons: Boolean = false,
    activeVariant: FoamIconStyle = FoamIconStyle.FLAT,
    inactiveVariant: FoamIconStyle = FoamIconStyle.OUTLINE,
    connectToMiniPlayer: Boolean = false,
    expandFraction: Float = 0f,
    onToggleExpand: () -> Unit = {},
    bubbleMode: Boolean = false
) {
    // Tint is resolved at draw time to keep accent frames draw-only
    val surfaceColor = FoamTheme.colors.surface
    val accentTint = LocalAccentTintState.current
    val density = LocalDensity.current
    val navBarBottomInsetPx = WindowInsets.navigationBars.getBottom(density)
    // The items' selection tint fades for as long as the screen transition
    // that caused the route change
    val tintTiming = rememberSwitchTintTiming(currentRoute)

    // One bubble for both rows (null when bubble mode is off)
    val bubble = rememberNavBarBubble(
        bubbleMode = bubbleMode,
        currentRoute = currentRoute,
        primaryItems = primaryItems,
        primaryEnabled = primaryEnabled,
        secondaryItems = secondaryItems,
        secondaryEnabled = secondaryEnabled,
        mainRowHeight = NavBarDimens.DockedBarHeight,
        expandFraction = expandFraction,
        onNavigate = onNavigate,
        onToggleExpand = onToggleExpand
    )
    val bubbleColor = FoamTheme.colors.textPrimary.copy(alpha = NavBarBubbleAlpha)

    // Fixed footprint: the extra row expands upwards as an overlay
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(NavBarDimens.DockedBarHeight + NavBarDimens.DockedBarBottomMargin)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .consumeTaps()
                .padding(bottom = NavBarDimens.DockedBarBottomMargin)
                .fillMaxWidth()
                .requiredHeight(
                    NavBarDimens.DockedBarHeight +
                            (NavBarDimens.ExtraRowHeight + NavBarDimens.ExtraRowExpandedSpacing) * expandFraction
                )
                // Bubble drag covers both rows
                .then(bubble?.gestureModifier() ?: Modifier)
                // clip stays off so the surface drawn below the bounds survives
                .shadow(elevation = 6.dp, shape = RectangleShape, clip = false)
                .drawBehind {
                    // Surface runs down through the bottom margin and system inset
                    drawRect(
                        color = accentTint.resolve(surfaceColor, SurfaceAccentTint),
                        size = Size(
                            width = size.width,
                            height = size.height +
                                    navBarBottomInsetPx +
                                    NavBarDimens.DockedBarBottomMargin.toPx()
                        )
                    )
                    // Selection bubble: above the surface, behind the icons
                    bubble?.let { with(it) { drawBubble(bubbleColor) } }
                },
            // Expansion grows the gap between the two rows
            verticalArrangement = Arrangement.spacedBy(
                NavBarDimens.ExtraRowExpandedSpacing * expandFraction
            )
        ) {
            FoamNavExtraRow(
                items = secondaryItems,
                enabledItems = secondaryEnabled,
                currentRoute = currentRoute,
                tintTiming = tintTiming,
                playerClosedFraction = playerClosedFraction,
                onNavigate = onNavigate,
                dynamicIcons = dynamicIcons,
                activeVariant = activeVariant,
                inactiveVariant = inactiveVariant,
                expandFraction = expandFraction,
                bubbleMode = bubbleMode,
                modifier = Modifier.offset(y = NavBarDimens.extraRowBottomOverlap(NavBarDimens.DockedBarHeight))
            )

            FoamNavigationItems(
                currentRoute = currentRoute,
                tintTiming = tintTiming,
                playerClosedFraction = playerClosedFraction,
                onNavigate = onNavigate,
                items = primaryItems,
                primaryEnabled = primaryEnabled,
                dynamicIcons = dynamicIcons,
                activeVariant = activeVariant,
                inactiveVariant = inactiveVariant,
                showExpandToggle = secondaryEnabled.isNotEmpty(),
                expandFraction = expandFraction,
                onToggleExpand = onToggleExpand,
                bubbleMode = bubbleMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NavBarDimens.DockedBarHeight)
                    .padding(horizontal = NavBarDimens.RowEdgePadding)
            )
        }
    }
}

/**
 * Floating navigation bar.
 *
 * Standalone, the pill wraps its items: one [NavBarDimens.ItemHitTargetMaxWidth]
 * slot per enabled item, compressed when the screen is too narrow. Its ends are
 * fully round. While the extra row expands, the width follows the larger of the
 * two rows.
 *
 * When [attachedToMiniPlayer], the pill stays full width: its surface extension
 * is the mini player card's container.
 */
@Composable
internal fun FloatingNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    primaryItems: List<NavBarItem>,
    primaryEnabled: Set<NavBarItem>,
    secondaryItems: List<NavBarItem>,
    secondaryEnabled: Set<NavBarItem>,
    dynamicIcons: Boolean = false,
    activeVariant: FoamIconStyle = FoamIconStyle.FLAT,
    inactiveVariant: FoamIconStyle = FoamIconStyle.OUTLINE,
    attachedToMiniPlayer: Boolean = false,
    connectToMiniPlayer: Boolean = false,
    miniPlayerTopPx: () -> Float? = { null },
    /**
     * How closed the player is (1 = closed, 0 = open), live with the swipe:
     * drives the pill's transparent-mode fade and the items' selection tint.
     */
    playerClosedFraction: () -> Float = { 1f },
    /**
     * Presence (0..1) of the mini player card, independent of the player
     * transition. Animates in on the first play.
     */
    cardPresenceFraction: () -> Float = { 1f },
    transitionAnimated: Boolean = true,
    navBarTransparentWhenPlayerOpen: Boolean = false,
    expandFraction: Float = 0f,
    onToggleExpand: () -> Unit = {},
    bubbleMode: Boolean = false
) {
    val miniImageRadius = (NavBarDimens.MiniPlayerArtSize / 2) * FoamTheme.shapes.miniPlayerRounding
    // Attached: match the card's radius. Standalone: fully round ends.
    val containerRadius = if (attachedToMiniPlayer) {
        miniImageRadius + NavBarDimens.FloatingCornerOverhang
    } else {
        NavBarDimens.ItemHitTargetSize / 2 + NavBarDimens.RowEdgePadding
    }

    // Tint is resolved at draw time to keep accent frames draw-only. The base
    // palette color is read here (not tintedSurfaceVariant): that one is
    // already resolved once, so resolving it again would double-tint the pill.
    val surfaceVariantColor = FoamTheme.colors.surfaceVariant
    val accentTint = LocalAccentTintState.current
    // The items' selection tint fades for as long as the screen transition
    // that caused the route change
    val tintTiming = rememberSwitchTintTiming(currentRoute)

    // One bubble for both rows (null when bubble mode is off)
    val bubble = rememberNavBarBubble(
        bubbleMode = bubbleMode,
        currentRoute = currentRoute,
        primaryItems = primaryItems,
        primaryEnabled = primaryEnabled,
        secondaryItems = secondaryItems,
        secondaryEnabled = secondaryEnabled,
        mainRowHeight = NavBarDimens.FloatingRowHeight,
        expandFraction = expandFraction,
        onNavigate = onNavigate,
        onToggleExpand = onToggleExpand
    )
    val bubbleColor = FoamTheme.colors.textPrimary.copy(alpha = NavBarBubbleAlpha)

    // Measured top of the pill, used with the card's top to size the attached extension
    var pillTopPx by remember { mutableStateOf(Float.NaN) }

    // Fixed footprint: the pill expands upwards as an overlay
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(NavBarDimens.FloatingRowHeight + NavBarDimens.FloatingBarMargin + NavBarDimens.FloatingBarBottomMargin)
    ) {
        // Widest the surface may get; denser rows compress their slots instead
        val maxSurfaceWidth = maxWidth - NavBarDimens.FloatingBarMargin * 2

        // Standalone pill hugs its items, sized for the larger of the two rows.
        // The width animates and follows expandFraction.
        val pillSurfaceWidth: Dp? = if (!attachedToMiniPlayer) {
            val effectivePrimaryEnabled =
                if (secondaryEnabled.isNotEmpty()) primaryEnabled + NavBarItem.MORE else primaryEnabled
            val primaryCount = primaryItems.count { it in effectivePrimaryEnabled }.coerceAtLeast(1)
            val secondaryCount = secondaryItems.count { it in secondaryEnabled }

            fun rowWidth(itemCount: Int): Dp =
                NavBarDimens.ItemHitTargetMaxWidth * itemCount +
                        NavBarDimens.RowEdgePadding * 2

            val collapsedWidth by animateDpAsState(
                targetValue = rowWidth(primaryCount),
                animationSpec = NavBarSlotDpAnimation,
                label = "NavBarPillCollapsedWidth"
            )
            val expandedWidth by animateDpAsState(
                targetValue = rowWidth(maxOf(primaryCount, secondaryCount)),
                animationSpec = NavBarSlotDpAnimation,
                label = "NavBarPillExpandedWidth"
            )
            (collapsedWidth + (expandedWidth - collapsedWidth) * expandFraction.coerceIn(0f, 1f))
                .coerceAtMost(maxSurfaceWidth)
        } else {
            null
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = NavBarDimens.FloatingBarMargin,
                    end = NavBarDimens.FloatingBarMargin,
                    bottom = NavBarDimens.FloatingBarBottomMargin
                )
                .then(
                    pillSurfaceWidth?.let { Modifier.width(it) } ?: Modifier
                )
                .requiredHeight(
                    NavBarDimens.FloatingRowHeight +
                            (NavBarDimens.ExtraRowHeight + NavBarDimens.ExtraRowExpandedSpacing) * expandFraction
                )
                .consumeTaps()
                // Bubble drag covers both rows
                .then(bubble?.gestureModifier() ?: Modifier)
                // clip stays off so the attached extension drawn outside the bounds survives
                .graphicsLayer {
                    shape = RoundedCornerShape(containerRadius)
                    shadowElevation = if (navBarTransparentWhenPlayerOpen) {
                        0f
                    } else {
                        NavBarShadowElevation.toPx()
                    }
                }
                // Translucent mode: drawn shadow instead of elevation. Its alpha follows
                // the surface fade, so there is no pop when switching.
                .then(
                    if (navBarTransparentWhenPlayerOpen) {
                        Modifier.dropShadow(shape = RoundedCornerShape(containerRadius)) {
                            radius = NavBarShadowRadius.toPx()
                            alpha = NavBarShadowStrength * playerClosedFraction()
                        }
                    } else Modifier
                )
                .onGloballyPositioned { pillTopPx = it.positionInRoot().y }
                .drawBehind {
                    val radiusPx = containerRadius.toPx()
                    val greyAlpha = if (navBarTransparentWhenPlayerOpen) {
                        playerClosedFraction()
                    } else 1f

                    val closed = playerClosedFraction()
                    val presence = cardPresenceFraction()
                    val extensionPx = if (attachedToMiniPlayer && connectToMiniPlayer) {
                        val cardTopPx = miniPlayerTopPx()
                        if (cardTopPx != null && !pillTopPx.isNaN()) {
                            // Measured: up to the card's visible top edge
                            (pillTopPx - cardTopPx -
                                    NavBarDimens.MiniPlayerCardVerticalPadding.toPx())
                                .coerceAtLeast(0f) * closed * presence
                        } else {
                            // Not measured yet: use the derived geometry
                            NavBarDimens.attachedExtensionHeight(expandFraction).toPx() * closed * presence
                        }
                    } else 0f

                    drawRoundRect(
                        color = accentTint.resolve(surfaceVariantColor, SurfaceVariantAccentTint),
                        topLeft = Offset(0f, -extensionPx),
                        size = Size(size.width, size.height + extensionPx),
                        cornerRadius = CornerRadius(radiusPx),
                        alpha = greyAlpha
                    )
                    // Selection bubble: above the surface, behind the icons
                    bubble?.let { with(it) { drawBubble(bubbleColor) } }
                },
            // Expansion grows the gap between the two rows
            verticalArrangement = Arrangement.spacedBy(
                NavBarDimens.ExtraRowExpandedSpacing * expandFraction
            )
        ) {
            FoamNavExtraRow(
                items = secondaryItems,
                enabledItems = secondaryEnabled,
                currentRoute = currentRoute,
                tintTiming = tintTiming,
                playerClosedFraction = playerClosedFraction,
                onNavigate = onNavigate,
                dynamicIcons = dynamicIcons,
                activeVariant = activeVariant,
                inactiveVariant = inactiveVariant,
                expandFraction = expandFraction,
                bubbleMode = bubbleMode,
                modifier = Modifier.offset(y = NavBarDimens.extraRowBottomOverlap(NavBarDimens.FloatingRowHeight))
            )

            FoamNavigationItems(
                currentRoute = currentRoute,
                tintTiming = tintTiming,
                playerClosedFraction = playerClosedFraction,
                onNavigate = onNavigate,
                items = primaryItems,
                primaryEnabled = primaryEnabled,
                dynamicIcons = dynamicIcons,
                activeVariant = activeVariant,
                inactiveVariant = inactiveVariant,
                showExpandToggle = secondaryEnabled.isNotEmpty(),
                expandFraction = expandFraction,
                onToggleExpand = onToggleExpand,
                bubbleMode = bubbleMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NavBarDimens.FloatingRowHeight)
                    .padding(horizontal = NavBarDimens.RowEdgePadding)
            )
        }
    }
}