package com.foamplayer.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.ui.theme.FoamTheme

/** Duration of every nav-bar slot animation */
private const val NavBarSlotAnimMillis = 250

/** Elevation shared by both bars' drop shadow. */
private val NavBarShadowElevation = 6.dp

/**
 * Radius and peak strength of the drawn translucent-when-player-open shadow in
 * [FloatingNavigationBar]: with that setting on, the RenderNode elevation shadow is
 * replaced by a [dropShadow] while the player transitions, because elevation shadows
 * only wrap the outline -- seen through the fading surface they read as a ring with
 * a hole under the bar. The drawn shadow fills under the surface as well.
 */
private val NavBarShadowRadius = 6.dp
private const val NavBarShadowStrength = 0.35f

/**
 * Slide animation used when nav-bar items change slot (reorder / add / remove
 * from the navigation-bar settings).
 */
private val NavBarSlotAnimation = tween<Dp>(durationMillis = NavBarSlotAnimMillis, easing = FastOutSlowInEasing)

/** Fade/scale animation played when a nav-bar item is enabled or disabled. */
private val NavBarSlotFadeAnimation = tween<Float>(durationMillis = NavBarSlotAnimMillis, easing = FastOutSlowInEasing)

/**
 * Bottom strip of the screen -- nav-bar zone plus floating mini player card, system
 * nav-bar inset included -- that edge-to-edge scrollable content should keep its
 * trailing rows clear of. Screens scroll BEHIND the floating bar and card (they are
 * occluded, not clipped), and add this value as a trailing spacer / bottom content
 * padding so the last rows can be scrolled up past them. Provided at the navigation
 * root; 0 when unconstrained.
 */
val LocalBottomBarClearance = compositionLocalOf { 0.dp }

/**
 * Identity of the collection item that was clicked to start the current
 * navigation, as "<kind>:<key>" (e.g. "album:Abbey Road", "playlist:12",
 * "artist:Radiohead"), or null when the last navigation did not start from a
 * collection row.
 *
 * Shared-element rows register their keys ONLY while they match this tag, so a
 * screen transition pairs exactly one source row with its destination hero --
 * other rows that happen to also exist on the destination (e.g. the artist's
 * albums already visible on the home page) never join the flight. The tag is
 * overwritten by every collection click, which also drops stale tags from
 * earlier flights.
 */
val LocalActiveSharedCollection = compositionLocalOf { null as String? }

/** Canonical tag of a clickable collection row for [LocalActiveSharedCollection]. */
fun sharedCollectionTag(kind: String, key: String): String = "$kind:$key"

/**
 * True when this row is the element that was clicked to start the current
 * transition (and therefore the only source that should register its
 * shared-element keys).
 */
@Composable
fun isActiveSharedCollection(kind: String, key: String): Boolean =
    LocalActiveSharedCollection.current == sharedCollectionTag(kind, key)

/**
 * Single source of truth for nav-bar / mini-player geometry.
 */
object NavBarDimens {

    // ---- Docked bar -----------------------------------------------------------
    /** The icon row itself. */
    val DockedRowHeight = 64.dp

    /** Connector that joins the bar to the mini player card above it. */
    val DockedConnector = 6.dp

    /**
     * The docked container's height in EVERY state: player open or closed, mini
     * player present or not. The connector is folded into the container instead of
     * being a separately-faded draw, so the visible height can never change anymore.
     */
    val DockedBarHeight = DockedRowHeight + DockedConnector

    /**
     * Slack kept above/below the docked bar's interactive row so the icons sit
     * centered within the surface's visible height, with a moderate amount of
     * breathing room to the bottom edge. The surface itself lands on the bottom
     * of the screen, extending down through the system nav-bar inset.
     */
    val DockedBarBottomMargin = 6.dp

    // ---- Floating / attached bar ----------------------------------------------
    val FloatingRowHeight = 56.dp

    /** Margin around the floating pill (horizontal and vertical). */
    val FloatingBarMargin = 12.dp

    /** Mini player artwork size the pill's corner radius hugs. Used by MiniPlayer.kt. */
    val MiniPlayerArtSize = 44.dp

    /**
     * The mini player card's own vertical padding around its content; its *visible*
     * edge sits this far inside the box that [miniPlayerBottomPadding] positions.
     * Used by MiniPlayer.kt.
     */
    val MiniPlayerCardVerticalPadding = 6.dp

    /** The mini player card's inner padding around its content. Used by MiniPlayer.kt. */
    val MiniPlayerContainerPadding = 8.dp

    /** Vertical gap between the mini player's artwork row and its seek slider. */
    val MiniPlayerContentSpacing = 6.dp

    /** Height of the strip the mini player's seek slider occupies. */
    val MiniPlayerSliderHeight = 12.dp

    /**
     * The mini player card's full visible height: artwork row + spacing + slider,
     * plus the container's inner padding on both sides.
     */
    val MiniPlayerCardHeight = MiniPlayerArtSize +
            MiniPlayerContentSpacing +
            MiniPlayerSliderHeight +
            MiniPlayerContainerPadding * 2

    /**
     * How far the pill's corner radius grows past the artwork radius. Derived from
     * the card's container padding so the pill's radius always matches the card
     * container's -- required for the attached extension to blend into the card's
     * top corners.
     */
    val FloatingCornerOverhang = MiniPlayerContainerPadding

    // ---- Shared item metrics ---------------------------------------------------
    val ItemIconSize = 28.dp

    /** Hit padding for the extra row's items, whose row hugs them tightly. */
    val ItemHitAreaPadding = 6.dp

    /**
     * Square touch target for main-row items (Material's 48dp minimum). Fits
     * inside both bars' rows, so the bars' sizes never change. The extra row's
     * items use the same target size even though their row is only [ExtraRowHeight]
     * tall: their targets live in an unclipped overlay that overflows the row
     * (see FoamNavExtraRow).
     */
    val ItemHitTargetSize = 48.dp

    /** Padding around [ItemIconSize] that produces [ItemHitTargetSize]. */
    val ItemHitTargetPadding = (ItemHitTargetSize - ItemIconSize) / 2

    val RowHorizontalPadding = 8.dp

    // ---- Expandable extra row ---------------------------------------------------
    /** Tighter hit padding for the extra row's icons so the row can hug the main row. */
    val ExtraRowItemHitPadding = 2.dp

    /**
     * Height of the secondary row revealed above the main row when expanded.
     * Hugs its tighter items exactly, so no dead slack opens up inside the row.
     * This is the row's visual footprint only: its touch targets are full
     * [ItemHitTargetSize] squares in an unclipped overlay that overflows the row
     * above and below (see FoamNavExtraRow).
     */
    val ExtraRowHeight = ItemIconSize + ExtraRowItemHitPadding * 2

    /**
     * Edge-to-edge gap to keep between the extra row's icons and the main row's
     * icons. The extra row dips into the main row's empty upper hit padding to get
     * this close without clipping anything. The floating/attached bar trades part
     * of this gap away to keep the extra row's ripple circles inside the container
     * (see [extraRowBottomOverlap]).
     */
    val ExtraRowIconGap = 12.dp

    /**
     * Breathing room the extra row's ripple circles keep below the bar surface's
     * top edge once [extraRowBottomOverlap] clamps the row's dip: the same margin
     * the main row's ripples keep above the floating pill's bottom edge
     * (([FloatingRowHeight] - [ItemHitTargetSize]) / 2), so neither row's ripple
     * hugs its container edge.
     */
    val ExtraRowRippleMargin = (FloatingRowHeight - ItemHitTargetSize) / 2

    /**
     * Extra thickness the bar surface gains while the extra row is expanded,
     * inserted BETWEEN the two icon rows: the extra row stays glued to the
     * surface's top edge and the main row to its bottom edge (both keeping their
     * ripple margins), so the whole growth lands in the gap between the rows and
     * the top/bottom padding never changes. [miniPlayerBottomPadding] and
     * [attachedExtensionHeight] derive from the same constant, so the mini player
     * card and the attached surface extension track the taller bar in lock-step.
     */
    val ExtraRowExpandedSpacing = 8.dp

    /**
     * How far the extra row visually dips into the main row below it. The dipped
     * strip only contains the main row's own hit-area padding, so nothing visibly
     * collides -- but the two icon rows end up [ExtraRowIconGap] apart.
     *
     * Floor: the row's touch targets are [ItemHitTargetSize] squares centered on
     * the icons, so each one overflows the tight row by half that much past its
     * top edge, and [ExtraRowRippleMargin] on top keeps the ripple clear of the
     * surface's top edge -- mirroring the main row's ripple margin above the
     * pill's bottom edge. The floating/attached bar's natural dip (4dp, from the
     * [ExtraRowIconGap] arithmetic below) is therefore clamped up to 12dp and the
     * items end up only 4dp (edge-to-edge) above the main row's icons instead of
     * 12dp; the docked bar's natural dip (11dp) is 1dp short of the floor and is
     * clamped up to it. While the bar is expanded, [ExtraRowExpandedSpacing] pushes
     * the main row (and only the main row) down, widening the edge-to-edge gap by
     * that much again.
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

    // ---- Mini player card placement --------------------------------------------
    /** Gap between the card's bottom edge and the top of the docked bar. */
    val DockedMiniPlayerGap = 2.dp

    /** Gap between the card's bottom edge and the top of the floating pill. */
    val FloatingMiniPlayerGap = 4.dp

    /** How far the card is lowered into the bar in ATTACHED mode. */
    val AttachedCardDrop = 24.dp

    /**
     * Gap between the mini player card's visible bottom edge and the extra row's
     * icon hit-areas in ATTACHED mode once the row is fully expanded. Slightly
     * negative: the card's edge sinks into the icons' hit padding and lands right
     * on the glyphs' top edge, matching how deep the card sinks into the pill
     * when the row is closed.
     */
    val AttachedExpandedIconGap = (-2).dp

    // ---- Player backdrop reach into the nav zone -------------------------------
    /**
     * Distance from the bottom of the screen (above the system nav-bar inset) to the
     * mini player card's box bottom edge, per mode.
     *
     * [expandFraction] (0f..1f) is the *animated* fraction of the expanded secondary
     * row, so the card rides up in lock-step with the bar expanding.
     *
     * DOCKED/FLOATING: the card only rides up by the extra row's *visible* growth --
     * the row's top dips into the main row below it, so riding the full height would
     * open up a bigger gap to the navbar top than when the row is closed.
     *
     * ATTACHED: the card instead un-drops just enough that its visible edge ends up
     * hovering right on top of the extra row's icons (gap = [AttachedExpandedIconGap])
     * instead of floating a full [AttachedCardDrop] away from the bar.
     */
    fun miniPlayerBottomPadding(mode: NavigationBarMode, expandFraction: Float = 0f): Dp {
        val attachedBase = DockedBarHeight + DockedMiniPlayerGap - AttachedCardDrop
        // How much taller the bar surface is at full expansion: the extra row plus
        // the inter-row spacing that only exists while expanded.
        val expandedGrowth = ExtraRowHeight + ExtraRowExpandedSpacing
        val base = when (mode) {
            NavigationBarMode.ATTACHED -> {
                // Where the extra row's icons end up once the bar is fully open:
                // pill top, minus the row's dip into the main row, minus the icons'
                // own hit padding.
                val pillTop = FloatingBarMargin + FloatingRowHeight + expandedGrowth
                val iconsTop = pillTop -
                        extraRowBottomOverlap(FloatingRowHeight) -
                        ExtraRowItemHitPadding
                // Lift that leaves the card's visible edge just above those icons.
                val fullLift = iconsTop + AttachedExpandedIconGap -
                        MiniPlayerCardVerticalPadding - attachedBase - expandedGrowth
                attachedBase + fullLift * expandFraction
            }
            NavigationBarMode.FLOATING -> FloatingRowHeight + FloatingBarMargin + FloatingMiniPlayerGap
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
     * Fixed collapsed footprint (excluding the system nav-bar inset) of the nav-zone
     * container in the nav Column: the docked bar's surface height, or the floating
     * pill's height including its margins. The expandable extra row grows UPWARDS as
     * an overlay inside this fixed footprint, so the zone's height never changes.
     */
    fun navBarZoneHeight(mode: NavigationBarMode): Dp = when (mode) {
        NavigationBarMode.DOCKED -> DockedBarHeight + DockedBarBottomMargin
        else -> FloatingRowHeight + FloatingBarMargin * 2
    }

    /** Fade length of the elevation scrim drawn at a bar surface's top edge. */
    val BarElevationScrimHeight = 16.dp

    /**
     * Exact distance the attached pill's grey surface must stretch above its own top
     * edge to land flush on the mini player card's top edge. In ATTACHED mode the
     * card draws no container of its own -- the pill's extension IS the card's
     * container -- so an overshoot pokes out above the card and a shortfall clips
     * its top corners.
     *
     * [miniPlayerBoxHeight] is the mini player's MEASURED box height (its own outer
     * padding included), so the surface reaches the card's real top edge whatever its
     * content actually measures (font scale, text metrics, future edits) instead of
     * trusting the derived [MiniPlayerCardHeight]. The default keeps that derived
     * height for callers without a measurement.
     *
     * Derived from [miniPlayerBottomPadding] (which positions the card's box), so the
     * two can never drift apart: while the extra row expands, the card lifts exactly
     * as much as the pill grows and the extension stays constant.
     */
    fun attachedExtensionHeight(
        expandFraction: Float = 0f,
        miniPlayerBoxHeight: Dp = MiniPlayerCardHeight + MiniPlayerCardVerticalPadding * 2
    ): Dp {
        val cardTop = miniPlayerBottomPadding(NavigationBarMode.ATTACHED, expandFraction) +
                miniPlayerBoxHeight -
                MiniPlayerCardVerticalPadding
        val pillTop = FloatingBarMargin + FloatingRowHeight +
                (ExtraRowHeight + ExtraRowExpandedSpacing) * expandFraction
        return (cardTop - pillTop).coerceAtLeast(0.dp)
    }
}

/**
 * The icon half of a nav-bar item: glyph with the animated selected/unselected tint,
 * no interaction of its own. Split from [FoamNavItem] so [FoamNavExtraRow] can draw
 * its icons inside the row's clipped visual layer while their touch targets live in
 * an unclipped hit layer (a clipping node stops hit-testing outside its bounds, so
 * full-size targets cannot live inside the row).
 */
@Composable
private fun NavItemVisual(
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val animatedTint by animateColorAsState(
        targetValue = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
        label = "NavIconTint"
    )
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(NavBarDimens.ItemIconSize),
        tint = animatedTint
    )
}

/**
 * A single clickable nav-bar icon with an animated selected/unselected tint.
 *
 * The touch target is a fixed [NavBarDimens.ItemHitTargetSize] square, centered
 * inside its slot, so it grows past the glyph without ever extending the bar.
 * The node is clipped to a [CircleShape] *before* becoming clickable: the ripple
 * is bounded by that circle and stays rounded.
 */
@Composable
private fun FoamNavItem(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    hitPadding: Dp = NavBarDimens.ItemHitTargetPadding,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled
            ) { onClick() }
            .padding(hitPadding),
        contentAlignment = Alignment.Center
    ) {
        NavItemVisual(
            icon = icon,
            contentDescription = contentDescription,
            selected = selected,
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
    // Unreachable: MORE is rendered as the expand toggle and never navigates.
    NavBarItem.MORE -> Screen.Settings
}

@Composable
private fun NavBarItem.icon(): ImageVector = when (this) {
    NavBarItem.HOME -> FoamTheme.icons.current.home
    NavBarItem.PLAYLISTS -> FoamTheme.icons.current.playlists
    NavBarItem.SEARCH -> FoamTheme.icons.current.search
    NavBarItem.STATS -> FoamTheme.icons.current.stats
    NavBarItem.EQUALIZER -> FoamTheme.icons.current.equalizer
    NavBarItem.SETTINGS -> FoamTheme.icons.current.settings
    NavBarItem.MORE -> FoamTheme.icons.current.more
}

/** Whether an item pointing at [screen] should render as selected. */
private fun isScreenSelected(currentRoute: String?, isPlayerOpen: Boolean, screen: Screen): Boolean =
    !isPlayerOpen && (
            currentRoute == screen.route ||
                    (screen == Screen.Settings && currentRoute?.startsWith("settings/") == true)
            )

/**
 * Row of nav-bar icon slots shared by the main row and the second-bar row.
 *
 * [order] is the full configured layout (enabled AND disabled items); slots are
 * distributed across the enabled items. Every item is always composed, so its
 * visibility animation state can never be lost:
 * - **Enable**: the item snaps to its slot while still invisible (keyed position
 *   state restarts) and fades/scales in in place; the other items slide to open
 *   the gap.
 * - **Disable**: the item holds its last position and fades/scales out in place
 *   while the other items slide closed.
 * - **Reorder**: enabled items slide to their new slots.
 *
 * Icons are placed at an animated *center* offset with a fixed-width box, so a
 * slot-count change re-targets the centers smoothly instead of resizing the boxes
 * (which would teleport every icon by half the slot-width delta).
 * Disabled items are not clickable.
 */
@Composable
private fun NavBarSlotRow(
    order: List<NavBarItem>,
    enabledItems: Set<NavBarItem>,
    itemWidth: Dp,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: NavBarItem, enabled: Boolean) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val enabledList = order.filter { it in enabledItems }
        val slotWidth = if (enabledList.isEmpty()) maxWidth else maxWidth / enabledList.size

        order.forEach { item ->
            key(item) {
                val isEnabled = item in enabledItems
                val slotIndex = enabledList.indexOf(item)

                // Always composed and never keyed on the toggle, so every item's
                // enter/exit fade+scale runs the same way.
                val visibility by animateFloatAsState(
                    targetValue = if (isEnabled) 1f else 0f,
                    animationSpec = NavBarSlotFadeAnimation,
                    label = "navSlotVisibility_${item.name}"
                )

                // Frozen position for disabled items (invisible while they fade out).
                val lastX = remember { mutableStateOf(0.dp) }
                val x: Dp = if (isEnabled && slotIndex >= 0) {
                    // key(isEnabled) restarts the position state on (re-)enter, so the
                    // item appears at its slot instead of sliding in from an old spot;
                    // while it stays enabled, layout changes slide it around.
                    key(isEnabled) {
                        val animatedX by animateDpAsState(
                            targetValue = slotWidth * (slotIndex + 0.5f) - itemWidth / 2,
                            animationSpec = NavBarSlotAnimation,
                            label = "navSlotX_${item.name}"
                        )
                        SideEffect { lastX.value = animatedX }
                        animatedX
                    }
                } else {
                    lastX.value
                }

                Box(
                    modifier = Modifier
                        // offset OUTSIDE graphicsLayer: the layer's transform origin
                        // (its center) must be the icon's own center, otherwise every
                        // item but the first scales around the row's left edge.
                        .offset(x = x)
                        .graphicsLayer {
                            alpha = visibility
                            val scale = 0.4f + 0.6f * visibility
                            scaleX = scale
                            scaleY = scale
                        }
                        .width(itemWidth)
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
 * Main row of clickable icons for the nav bar.
 * [items] is the user-configured main-bar layout, in display order, including the
 * [NavBarItem.MORE] marker which renders the expand toggle at its configured slot.
 * [primaryEnabled] are the switchable items the user turned on; the toggle itself
 * is only "enabled" while [showExpandToggle] is true (second bar non-empty).
 */
@Composable
private fun FoamNavigationItems(
    currentRoute: String?,
    isPlayerOpen: Boolean,
    onNavigate: (String) -> Unit,
    items: List<NavBarItem>,
    primaryEnabled: Set<NavBarItem>,
    showExpandToggle: Boolean = false,
    expandFraction: Float = 0f,
    onToggleExpand: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val effectiveEnabled = if (showExpandToggle) primaryEnabled + NavBarItem.MORE else primaryEnabled

    NavBarSlotRow(
        order = items,
        enabledItems = effectiveEnabled,
        itemWidth = NavBarDimens.ItemHitTargetSize,
        modifier = modifier
    ) { item, itemEnabled ->
        if (item == NavBarItem.MORE) {
            FoamNavItem(
                icon = FoamTheme.icons.current.more,
                contentDescription = if (expandFraction > 0.5f) "Collapse navigation" else "Expand navigation",
                selected = false,
                enabled = itemEnabled,
                iconModifier = Modifier.rotate(expandFraction * 90f),
                onClick = onToggleExpand
            )
        } else {
            val screen = item.targetScreen()
            val selected = isScreenSelected(currentRoute, isPlayerOpen, screen)

            FoamNavItem(
                icon = item.icon(),
                contentDescription = screen.title,
                selected = selected,
                enabled = itemEnabled,
                onClick = { onNavigate(screen.route) }
            )
        }
    }
}

/**
 * Secondary row revealed above the main row while the bar is expanded.
 * [items] is the full configured second-bar layout; [enabledItems] are the user's
 * active entries. [expandFraction] (0f..1f) drives both the height and the content
 * alpha so the row smoothly grows out of the bar.
 *
 * Visuals and touch targets are split into two stacked layers with identical slot
 * geometry. The row's visible footprint stays tight -- [NavBarDimens.ExtraRowHeight]
 * hugs the icons so the row can dip into the main row without opening a visible gap
 * -- but the touch targets must be the same [NavBarDimens.ItemHitTargetSize] squares
 * as the main row's. They cannot simply be grown inside the row: the row is clipped
 * while it grows out of the bar ([clipToBounds]), and a clipping node also stops
 * hit-testing anything outside its bounds, so the overflowing half of a bigger
 * target would be dead. The icons therefore render in the clipped visual layer with
 * no interaction, while an unclipped hit layer of transparent
 * [NavBarDimens.ItemHitTargetSize] squares on the same slots carries the click,
 * ripple and semantics, overflowing the row above and below without changing its
 * layout. The main row is composed after this one, so its own hit targets keep
 * priority in the band where the two rows' targets overlap.
 */
@Composable
private fun FoamNavExtraRow(
    items: List<NavBarItem>,
    enabledItems: Set<NavBarItem>,
    currentRoute: String?,
    isPlayerOpen: Boolean,
    onNavigate: (String) -> Unit,
    expandFraction: Float,
    modifier: Modifier = Modifier
) {
    if (expandFraction <= 0f) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NavBarDimens.ExtraRowHeight * expandFraction)
    ) {
        // Visual layer: the icons only, clipped to the row while it grows out of
        // the bar. No interaction -- the hit layer below owns the touch targets.
        NavBarSlotRow(
            order = items,
            enabledItems = enabledItems,
            itemWidth = NavBarDimens.ItemIconSize + NavBarDimens.ExtraRowItemHitPadding * 2,
            modifier = Modifier
                .matchParentSize()
                .clipToBounds()
                .alpha(expandFraction.coerceIn(0f, 1f))
                .padding(horizontal = NavBarDimens.RowHorizontalPadding)
        ) { item, _ ->
            val screen = item.targetScreen()
            NavItemVisual(
                icon = item.icon(),
                selected = isScreenSelected(currentRoute, isPlayerOpen, screen),
                modifier = Modifier.padding(NavBarDimens.ExtraRowItemHitPadding)
            )
        }

        // Hit layer: full-size touch targets on the same slots, unclipped so the
        // overflow above/below the tight row stays touchable. Disabled items get
        // no target, matching their non-clickable visuals.
        NavBarSlotRow(
            order = items,
            enabledItems = enabledItems,
            itemWidth = NavBarDimens.ItemHitTargetSize,
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = NavBarDimens.RowHorizontalPadding)
        ) { item, itemEnabled ->
            if (itemEnabled) {
                val screen = item.targetScreen()
                Box(
                    modifier = Modifier
                        // requiredSize, not size: the slot box is only as tall as the
                        // row, and the target must overflow it to reach 48dp.
                        .requiredSize(NavBarDimens.ItemHitTargetSize)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary)
                        ) { onNavigate(screen.route) }
                        .semantics { contentDescription = screen.title }
                )
            }
        }
    }
}

/**
 * Classic docked navigation bar
 */
@Composable
fun FoamNavigationBar(
    currentRoute: String?,
    isPlayerOpen: Boolean,
    onNavigate: (String) -> Unit,
    primaryItems: List<NavBarItem>,
    primaryEnabled: Set<NavBarItem>,
    secondaryItems: List<NavBarItem>,
    secondaryEnabled: Set<NavBarItem>,
    connectToMiniPlayer: Boolean = false,
    expandFraction: Float = 0f,
    onToggleExpand: () -> Unit = {}
) {
    val surfaceColor = FoamTheme.colors.surface
    val density = LocalDensity.current
    val navBarBottomInsetPx = WindowInsets.navigationBars.getBottom(density)

    // Fixed collapsed footprint: the content above never resizes when the extra
    // row folds. The row expands upwards as an overlay instead of taking space.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(NavBarDimens.DockedBarHeight + NavBarDimens.DockedBarBottomMargin)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = NavBarDimens.DockedBarBottomMargin)
                .fillMaxWidth()
                .requiredHeight(
                    NavBarDimens.DockedBarHeight +
                            (NavBarDimens.ExtraRowHeight + NavBarDimens.ExtraRowExpandedSpacing) * expandFraction
                )
                // Elevation over the scrolling content: the shadow wraps the surface's
                // own bounds; the part below them is painted over by the surface
                // extension that reaches down through the bottom margin and inset.
                // clip stays off so that extension (drawn outside the bounds) survives.
                .shadow(elevation = 6.dp, shape = RectangleShape, clip = false)
                .drawBehind {
                    // Solid surface behind the extra row + main row, landing on the
                    // bottom of the screen: it extends down through the bottom
                    // margin and the system nav-bar inset.
                    drawRect(
                        color = surfaceColor,
                        size = Size(
                            width = size.width,
                            height = size.height +
                                    navBarBottomInsetPx +
                                    NavBarDimens.DockedBarBottomMargin.toPx()
                        )
                    )
                },
            // The expanded spacing thickens the surface BETWEEN the two rows: the
            // extra row stays glued to the surface's top edge and the main row to
            // its bottom edge, so both keep their ripple margins to the top/bottom
            // edges and the whole growth lands in the gap between the rows.
            verticalArrangement = Arrangement.spacedBy(
                NavBarDimens.ExtraRowExpandedSpacing * expandFraction
            )
        ) {
            FoamNavExtraRow(
                items = secondaryItems,
                enabledItems = secondaryEnabled,
                currentRoute = currentRoute,
                isPlayerOpen = isPlayerOpen,
                onNavigate = onNavigate,
                expandFraction = expandFraction,
                modifier = Modifier.offset(y = NavBarDimens.extraRowBottomOverlap(NavBarDimens.DockedBarHeight))
            )

            FoamNavigationItems(
                currentRoute = currentRoute,
                isPlayerOpen = isPlayerOpen,
                onNavigate = onNavigate,
                items = primaryItems,
                primaryEnabled = primaryEnabled,
                showExpandToggle = secondaryEnabled.isNotEmpty(),
                expandFraction = expandFraction,
                onToggleExpand = onToggleExpand,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NavBarDimens.DockedBarHeight)
                    .padding(horizontal = NavBarDimens.RowHorizontalPadding)
            )
        }
    }
}

/**
 * Floating navigation bar
 */
@Composable
internal fun FloatingNavigationBar(
    currentRoute: String?,
    isPlayerOpen: Boolean,
    onNavigate: (String) -> Unit,
    primaryItems: List<NavBarItem>,
    primaryEnabled: Set<NavBarItem>,
    secondaryItems: List<NavBarItem>,
    secondaryEnabled: Set<NavBarItem>,
    attachedToMiniPlayer: Boolean = false,
    connectToMiniPlayer: Boolean = false,
    miniPlayerTopPx: () -> Float? = { null },
    playerClosedFraction: () -> Float = { 0f },
    /**
     * 0..1 presence of the mini player card itself, independent of the player
     * transition: pinned at 1 through every open/close flight (whose own
     * enter/exit owns the motion), animating 0->1 on the very first play --
     * which lands with the transition at rest, so the attached surface
     * extension must grow out of the pill on its own instead of riding a
     * close flight's [playerClosedFraction].
     */
    cardPresenceFraction: () -> Float = { 1f },
    transitionAnimated: Boolean = true,
    navBarTransparentWhenPlayerOpen: Boolean = false,
    expandFraction: Float = 0f,
    onToggleExpand: () -> Unit = {}
) {
    val miniImageRadius = (NavBarDimens.MiniPlayerArtSize / 2) * FoamTheme.shapes.miniPlayerRounding
    val containerRadius = miniImageRadius + NavBarDimens.FloatingCornerOverhang

    val surfaceVariantColor = FoamTheme.colors.surfaceVariant

    // Measured top edge (root coordinates) of the surface column. Paired with the
    // card's measured top, it defines the attached extension directly: the surface is
    // drawn to the literal distance between the two, with no geometry model to fall
    // out of sync with the real layout.
    var pillTopPx by remember { mutableStateOf(Float.NaN) }

    // Fixed collapsed footprint (pill + its margins): the content above never
    // resizes when the extra row folds. The pill grows upwards as an overlay.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(NavBarDimens.FloatingRowHeight + NavBarDimens.FloatingBarMargin * 2)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = NavBarDimens.FloatingBarMargin,
                    end = NavBarDimens.FloatingBarMargin,
                    bottom = NavBarDimens.FloatingBarMargin
                )
                .requiredHeight(
                    NavBarDimens.FloatingRowHeight +
                            (NavBarDimens.ExtraRowHeight + NavBarDimens.ExtraRowExpandedSpacing) * expandFraction
                )
                // Elevation over the scrolling content. In ATTACHED mode the shadow's
                // top edge is hidden behind the card-connected surface extension; the
                // extension's own top edge gets its depth from the scrim drawn below.
                // clip stays off so the extension drawing outside the bounds survives.
                .graphicsLayer {
                    shape = RoundedCornerShape(containerRadius)
                    shadowElevation = if (navBarTransparentWhenPlayerOpen) {
                        0f
                    } else {
                        NavBarShadowElevation.toPx()
                    }
                }
                // With translucent-when-player-open the RenderNode shadow is replaced
                // by a drawn [dropShadow] for the bar's whole lifecycle: elevation
                // shadows only wrap the outline, so seen through the fading surface
                // they read as a ring with a hole under the bar, while the drawn one
                // fills under it too. Its alpha tracks the same closed fraction as the
                // surface fade -- full behind the opaque resting surface, gone when the
                // player rests open -- so there is no handover pop, and the block
                // re-runs at draw phase as the fraction moves (drag-scrub included).
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
                            // Measured: the surface's top lands exactly on the card's
                            // visible top edge (its box top + the card's outer vertical
                            // padding). The pill sits below the card, so its top edge is
                            // the larger root-y of the two.
                            (pillTopPx - cardTopPx -
                                    NavBarDimens.MiniPlayerCardVerticalPadding.toPx())
                                .coerceAtLeast(0f) * closed * presence
                        } else {
                            // Layout not measured yet: fall back to the derived geometry.
                            NavBarDimens.attachedExtensionHeight(expandFraction).toPx() * closed * presence
                        }
                    } else 0f

                    drawRoundRect(
                        color = surfaceVariantColor,
                        topLeft = Offset(0f, -extensionPx),
                        size = Size(size.width, size.height + extensionPx),
                        cornerRadius = CornerRadius(radiusPx),
                        alpha = greyAlpha
                    )
                },
            // The expanded spacing thickens the pill BETWEEN the two rows: the extra
            // row stays glued to the pill's top edge and the main row to its bottom
            // edge, so both keep their ripple margins to the top/bottom edges and the
            // whole growth lands in the gap between the rows.
            verticalArrangement = Arrangement.spacedBy(
                NavBarDimens.ExtraRowExpandedSpacing * expandFraction
            )
        ) {
            FoamNavExtraRow(
                items = secondaryItems,
                enabledItems = secondaryEnabled,
                currentRoute = currentRoute,
                isPlayerOpen = isPlayerOpen,
                onNavigate = onNavigate,
                expandFraction = expandFraction,
                modifier = Modifier.offset(y = NavBarDimens.extraRowBottomOverlap(NavBarDimens.FloatingRowHeight))
            )

            FoamNavigationItems(
                currentRoute = currentRoute,
                isPlayerOpen = isPlayerOpen,
                onNavigate = onNavigate,
                items = primaryItems,
                primaryEnabled = primaryEnabled,
                showExpandToggle = secondaryEnabled.isNotEmpty(),
                expandFraction = expandFraction,
                onToggleExpand = onToggleExpand,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NavBarDimens.FloatingRowHeight)
                    .padding(horizontal = NavBarDimens.RowHorizontalPadding)
            )
        }
    }
}
