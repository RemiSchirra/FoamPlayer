package com.foamplayer.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.ui.theme.FoamTheme

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

    /**
     * Mini player artwork size the pill's corner radius hugs.
     * Keep in sync with MiniPlayer.kt (and optionally reuse it there).
     */
    val MiniPlayerArtSize = 44.dp

    /**
     * The mini player card's own vertical padding around its content; its *visible*
     * edge sits this far inside the box that [miniPlayerBottomPadding] positions.
     * Keep in sync with MiniPlayer.kt.
     */
    val MiniPlayerCardVerticalPadding = 6.dp

    /** How far the pill's corner radius grows past the artwork radius. */
    val FloatingCornerOverhang = 8.dp

    /** How far the attached pill stretches up toward the mini player when closed. */
    const val AttachedConnectorGrowFactor = 3.5f

    // ---- Shared item metrics ---------------------------------------------------
    val ItemIconSize = 24.dp
    val ItemHitAreaPadding = 8.dp
    val RowHorizontalPadding = 8.dp

    // ---- Expandable extra row ---------------------------------------------------
    /** Tighter hit padding for the extra row's icons so the row can hug the main row. */
    val ExtraRowItemHitPadding = 4.dp

    /**
     * Height of the secondary row revealed above the main row when expanded.
     * Hugs its tighter items exactly, so no dead slack opens up inside the row.
     */
    val ExtraRowHeight = ItemIconSize + ExtraRowItemHitPadding * 2

    /**
     * Edge-to-edge gap to keep between the extra row's icons and the main row's
     * icons. The extra row dips into the main row's empty upper hit padding to get
     * this close without clipping anything.
     */
    val ExtraRowIconGap = 12.dp

    /**
     * How far the extra row visually dips into the main row below it. The dipped
     * strip only contains the main row's own hit-area padding, so nothing visibly
     * collides -- but the two icon rows end up [ExtraRowIconGap] apart.
     */
    fun extraRowBottomOverlap(mainRowHeight: Dp): Dp =
        ExtraRowItemHitPadding +
                (mainRowHeight - (ItemIconSize + ItemHitAreaPadding * 2)) / 2 +
                ItemHitAreaPadding -
                ExtraRowIconGap

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
     * Small slack so a one-frame desync between the inset read here and the bar's own
     * inset read can never under-fill the fade during a drag.
     */
    val BackdropSyncBuffer = 4.dp

    /**
     * FLOATING/ATTACHED pills only cover part of the bottom zone, so the player
     * backdrop gets a generously oversized buffer (far bigger than the real bars'
     * height) and can never run out of fade mid-drag.
     */
    const val FloatingBackdropBufferFactor = 3f

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
        val base = when (mode) {
            NavigationBarMode.ATTACHED -> {
                // Where the extra row's icons end up once the bar is fully open:
                // pill top, minus the row's dip into the main row, minus the icons'
                // own hit padding.
                val pillTop = FloatingBarMargin + FloatingRowHeight + ExtraRowHeight
                val iconsTop = pillTop -
                        extraRowBottomOverlap(FloatingRowHeight) -
                        ExtraRowItemHitPadding
                // Lift that leaves the card's visible edge just above those icons.
                val fullLift = iconsTop + AttachedExpandedIconGap -
                        MiniPlayerCardVerticalPadding - attachedBase - ExtraRowHeight
                attachedBase + fullLift * expandFraction
            }
            NavigationBarMode.FLOATING -> FloatingRowHeight + FloatingBarMargin + FloatingMiniPlayerGap
            else -> DockedBarHeight + DockedMiniPlayerGap + DockedBarBottomMargin
        }
        val ride = when (mode) {
            NavigationBarMode.ATTACHED -> ExtraRowHeight
            NavigationBarMode.FLOATING -> ExtraRowHeight - extraRowBottomOverlap(FloatingRowHeight)
            else -> ExtraRowHeight - extraRowBottomOverlap(DockedBarHeight)
        }
        return base + ride * expandFraction
    }

    /**
     * How far the full player's backdrop fade extends below the player content, into
     * the nav zone.
     *
     * DOCKED: the bar is solid and paints above the backdrop (see FoamNavigationBar),
     * so the fade only needs to cover the system inset plus a frame-sync buffer.
     * FLOATING/ATTACHED: a large buffer keeps the fade continuous around the pills.
     */
    fun playerBackdropBottomExtension(mode: NavigationBarMode, bottomInset: Dp): Dp = when (mode) {
        NavigationBarMode.DOCKED -> bottomInset + BackdropSyncBuffer
        else -> bottomInset + (FloatingRowHeight + FloatingBarMargin * 2) * FloatingBackdropBufferFactor
    }
}

/**
 * A single clickable nav-bar icon with an animated selected/unselected tint.
 */
@Composable
private fun FoamNavItem(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    hitPadding: Dp = NavBarDimens.ItemHitAreaPadding,
    onClick: () -> Unit
) {
    val animatedTint by animateColorAsState(
        targetValue = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
        label = "NavIconTint"
    )

    Box(
        modifier = modifier
            .clickable { onClick() }
            .padding(hitPadding),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = iconModifier.size(NavBarDimens.ItemIconSize),
            tint = animatedTint
        )
    }
}

/**
 * Row of clickable icons for the nav bar.
 * [showExpandToggle] appends the menu button that expands/collapses the extra row.
 */
@Composable
private fun FoamNavigationItems(
    currentRoute: String?,
    isPlayerOpen: Boolean,
    onNavigate: (String) -> Unit,
    showExpandToggle: Boolean = false,
    expandFraction: Float = 0f,
    onToggleExpand: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val items = listOf(
        Screen.Library,
        Screen.Playlists,
        Screen.Search,
        Screen.Equalizer,
        Screen.Settings
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { screen ->
            val selected = !isPlayerOpen && (
                    currentRoute == screen.route ||
                            (screen == Screen.Settings && currentRoute?.startsWith("settings/") == true)
                    )

            val icon = when (screen) {
                Screen.Library -> FoamTheme.icons.current.library
                Screen.Playlists -> FoamTheme.icons.current.playlists
                Screen.Search -> FoamTheme.icons.current.search
                Screen.Equalizer -> FoamTheme.icons.current.equalizer
                Screen.Settings -> FoamTheme.icons.current.settings
                else -> FoamTheme.icons.current.settings
            }

            FoamNavItem(
                icon = icon,
                contentDescription = screen.title,
                selected = selected,
                onClick = { onNavigate(screen.route) }
            )
        }

        if (showExpandToggle) {
            FoamNavItem(
                icon = FoamTheme.icons.current.more,
                contentDescription = if (expandFraction > 0.5f) "Collapse navigation" else "Expand navigation",
                selected = false,
                iconModifier = Modifier.rotate(expandFraction * 90f),
                onClick = onToggleExpand
            )
        }
    }
}

/**
 * Secondary row revealed above the main row while the bar is expanded.
 * [expandFraction] (0f..1f) drives both the height and the content alpha so the
 * row smoothly grows out of the bar.
 */
@Composable
private fun FoamNavExtraRow(
    expandFraction: Float,
    modifier: Modifier = Modifier
) {
    if (expandFraction <= 0f) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(NavBarDimens.ExtraRowHeight * expandFraction)
            .clipToBounds()
            .alpha(expandFraction.coerceIn(0f, 1f))
            .padding(horizontal = NavBarDimens.RowHorizontalPadding),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // TODO: replace these placeholders with real actions/screens.
        val placeholderItems = listOf(
            FoamTheme.icons.current.search to "Placeholder action 1",
            FoamTheme.icons.current.library to "Placeholder action 2",
            FoamTheme.icons.current.equalizer to "Placeholder action 3"
        )
        placeholderItems.forEach { (icon, description) ->
            FoamNavItem(
                icon = icon,
                contentDescription = description,
                selected = false,
                hitPadding = NavBarDimens.ExtraRowItemHitPadding,
                onClick = { /* TODO: wire up placeholder action */ }
            )
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
                .requiredHeight(NavBarDimens.DockedBarHeight + NavBarDimens.ExtraRowHeight * expandFraction)
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
                }
        ) {
            FoamNavExtraRow(
                expandFraction = expandFraction,
                modifier = Modifier.offset(y = NavBarDimens.extraRowBottomOverlap(NavBarDimens.DockedBarHeight))
            )

            FoamNavigationItems(
                currentRoute = currentRoute,
                isPlayerOpen = isPlayerOpen,
                onNavigate = onNavigate,
                showExpandToggle = true,
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
    attachedToMiniPlayer: Boolean = false,
    connectToMiniPlayer: Boolean = false,
    playerOpenFraction: () -> Float = { 0f },
    transitionAnimated: Boolean = true,
    navBarTransparentWhenPlayerOpen: Boolean = false,
    expandFraction: Float = 0f,
    onToggleExpand: () -> Unit = {}
) {
    val miniImageRadius = (NavBarDimens.MiniPlayerArtSize / 2) * FoamTheme.shapes.miniPlayerRounding
    val containerRadius = miniImageRadius + NavBarDimens.FloatingCornerOverhang

    val surfaceVariantColor = FoamTheme.colors.surfaceVariant

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
                .requiredHeight(NavBarDimens.FloatingRowHeight + NavBarDimens.ExtraRowHeight * expandFraction)
                .drawBehind {
                    val radiusPx = containerRadius.toPx()
                    val greyAlpha = if (isPlayerOpen && navBarTransparentWhenPlayerOpen) {
                        1f - playerOpenFraction()
                    } else 1f

                    val extensionPx = if (attachedToMiniPlayer && connectToMiniPlayer) {
                        NavBarDimens.AttachedConnectorGrowFactor * radiusPx * (1f - playerOpenFraction())
                    } else 0f

                    drawRoundRect(
                        color = surfaceVariantColor,
                        topLeft = Offset(0f, -extensionPx),
                        size = Size(size.width, size.height + extensionPx),
                        cornerRadius = CornerRadius(radiusPx),
                        alpha = greyAlpha
                    )
                }
        ) {
            FoamNavExtraRow(
                expandFraction = expandFraction,
                modifier = Modifier.offset(y = NavBarDimens.extraRowBottomOverlap(NavBarDimens.FloatingRowHeight))
            )

            FoamNavigationItems(
                currentRoute = currentRoute,
                isPlayerOpen = isPlayerOpen,
                onNavigate = onNavigate,
                showExpandToggle = true,
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