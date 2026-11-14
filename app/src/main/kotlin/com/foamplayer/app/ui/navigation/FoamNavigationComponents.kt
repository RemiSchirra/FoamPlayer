package com.foamplayer.app.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onGloballyPositioned
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

    /** How far the card sinks into the bar in ATTACHED mode. */
    val AttachedCardDrop = 24.dp

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
                indication = ripple(color = FoamTheme.colors.textPrimary),
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
        val slotWidth = if (stretchBetweenItems && slotCount > 0) {
            (maxWidth / slotCount).coerceIn(itemWidth, NavBarDimens.ItemHitTargetMaxWidth)
        } else {
            itemWidth
        }

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
    modifier: Modifier = Modifier
) {
    val effectiveEnabled = if (showExpandToggle) primaryEnabled + NavBarItem.MORE else primaryEnabled
    val searchFocusRequest = LocalSearchFocusRequest.current

    NavBarSlotRow(
        order = items,
        enabledItems = effectiveEnabled,
        itemWidth = NavBarDimens.ItemHitTargetSize,
        stretchBetweenItems = true,
        modifier = modifier
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
                        .clip(NavItemHitShape)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary),
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
    onToggleExpand: () -> Unit = {}
) {
    // Tint is resolved at draw time to keep accent frames draw-only
    val surfaceColor = FoamTheme.colors.surface
    val accentTint = LocalAccentTintState.current
    val density = LocalDensity.current
    val navBarBottomInsetPx = WindowInsets.navigationBars.getBottom(density)
    // The items' selection tint fades for as long as the screen transition
    // that caused the route change
    val tintTiming = rememberSwitchTintTiming(currentRoute)

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
    onToggleExpand: () -> Unit = {}
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NavBarDimens.FloatingRowHeight)
                    .padding(horizontal = NavBarDimens.RowEdgePadding)
            )
        }
    }
}