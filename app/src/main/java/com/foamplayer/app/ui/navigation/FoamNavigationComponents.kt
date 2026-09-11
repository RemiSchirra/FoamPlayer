package com.foamplayer.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

    // ---- Floating / attached bar ----------------------------------------------
    val FloatingRowHeight = 56.dp

    /** Margin around the floating pill (horizontal and vertical). */
    val FloatingBarMargin = 12.dp

    /**
     * Mini player artwork size the pill's corner radius hugs.
     * Keep in sync with MiniPlayer.kt (and optionally reuse it there).
     */
    val MiniPlayerArtSize = 44.dp

    /** How far the pill's corner radius grows past the artwork radius. */
    val FloatingCornerOverhang = 8.dp

    /** How far the attached pill stretches up toward the mini player when closed. */
    const val AttachedConnectorGrowFactor = 3.5f

    // ---- Mini player card placement --------------------------------------------
    /** Gap between the card's bottom edge and the top of the docked bar. */
    val DockedMiniPlayerGap = 2.dp

    /** Gap between the card's bottom edge and the top of the floating pill. */
    val FloatingMiniPlayerGap = 4.dp

    /** How far the card is lowered into the bar in ATTACHED mode. */
    val AttachedCardDrop = 24.dp

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

    // ---- Shared item metrics ---------------------------------------------------
    val ItemIconSize = 24.dp
    val ItemHitAreaPadding = 8.dp
    val RowHorizontalPadding = 8.dp

    /**
     * Distance from the bottom of the screen (above the system nav-bar inset) to the
     * mini player card's bottom edge, per mode. Resolves to the same values as the old
     * literals (72dp / 72dp / 48dp) but is now derived from the bar geometry.
     */
    fun miniPlayerBottomPadding(mode: NavigationBarMode): Dp = when (mode) {
        NavigationBarMode.ATTACHED -> DockedBarHeight + DockedMiniPlayerGap - AttachedCardDrop
        NavigationBarMode.FLOATING -> FloatingRowHeight + FloatingBarMargin + FloatingMiniPlayerGap
        else -> DockedBarHeight + DockedMiniPlayerGap
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
 * Row of clickable icons for the nav bar
 */
@Composable
private fun FoamNavigationItems(
    currentRoute: String?,
    isPlayerOpen: Boolean,
    onNavigate: (String) -> Unit,
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

            val animatedTint by animateColorAsState(
                targetValue = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
                label = "NavIconTint"
            )

            Box(
                modifier = Modifier
                    .clickable { onNavigate(screen.route) }
                    .padding(NavBarDimens.ItemHitAreaPadding),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when(screen) {
                        Screen.Library -> FoamTheme.icons.current.library
                        Screen.Playlists -> FoamTheme.icons.current.playlists
                        Screen.Search -> FoamTheme.icons.current.search
                        Screen.Equalizer -> FoamTheme.icons.current.equalizer
                        Screen.Settings -> FoamTheme.icons.current.settings
                        else -> FoamTheme.icons.current.settings
                    },
                    contentDescription = screen.title,
                    modifier = Modifier.size(NavBarDimens.ItemIconSize),
                    tint = animatedTint
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
    connectToMiniPlayer: Boolean = false
) {
    val surfaceColor = FoamTheme.colors.surface
    val density = LocalDensity.current
    val navBarBottomInsetPx = WindowInsets.navigationBars.getBottom(density)

    FoamNavigationItems(
        currentRoute = currentRoute,
        isPlayerOpen = isPlayerOpen,
        onNavigate = onNavigate,
        modifier = Modifier
            .fillMaxWidth()
            .height(NavBarDimens.DockedBarHeight)
            .drawBehind {
                drawRect(
                    color = surfaceColor,
                    size = Size(
                        width = size.width,
                        height = size.height + navBarBottomInsetPx
                    )
                )
            }
            .padding(horizontal = NavBarDimens.RowHorizontalPadding)
    )
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
    navBarTransparentWhenPlayerOpen: Boolean = false
) {
    val miniImageRadius = (NavBarDimens.MiniPlayerArtSize / 2) * FoamTheme.shapes.miniPlayerRounding
    val containerRadius = miniImageRadius + NavBarDimens.FloatingCornerOverhang

    val surfaceVariantColor = FoamTheme.colors.surfaceVariant

    val outerModifier = Modifier
        .fillMaxWidth()
        .padding(
            horizontal = NavBarDimens.FloatingBarMargin,
            vertical = NavBarDimens.FloatingBarMargin
        )

    Box(modifier = outerModifier) {
        FoamNavigationItems(
            currentRoute = currentRoute,
            isPlayerOpen = isPlayerOpen,
            onNavigate = onNavigate,
            modifier = Modifier
                .fillMaxWidth()
                .height(NavBarDimens.FloatingRowHeight)
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
                .padding(horizontal = NavBarDimens.RowHorizontalPadding)
        )
    }
}
