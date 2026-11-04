package com.foamplayer.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.ui.theme.FoamMotion
import kotlin.math.roundToInt

/**
 * Enter/exit specs for the screen [NavHost], kept out of the host composable.
 *
 * The rules depend on the route pair: navigation-bar switches slide from the side
 * or row the new tab lives on, settings/stats/browse stacks slide, detail screens
 * crossfade over the shared-element duration, and everything else fades.
 * While the full player is on screen the nav host is already hidden, so its own
 * transitions are disabled to avoid overlapping flights on close.
 */
internal class FoamNavTransitions(
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition,
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition,
)

internal fun buildFoamNavTransitions(
    playerTransitionState: SeekableTransitionState<Boolean>,
    primaryNavBarItems: List<NavBarItem>,
    secondaryNavBarItems: List<NavBarItem>
): FoamNavTransitions {
    fun AnimatedContentTransitionScope<NavBackStackEntry>.isSettingsPair() =
        isSettingsRoute(initialState.destination.route) && isSettingsRoute(targetState.destination.route)

    fun AnimatedContentTransitionScope<NavBackStackEntry>.isStatsPair() =
        isStatsRoute(initialState.destination.route) && isStatsRoute(targetState.destination.route)

    fun AnimatedContentTransitionScope<NavBackStackEntry>.isBrowsePair() =
        isBrowseRoute(initialState.destination.route) && isBrowseRoute(targetState.destination.route)

    fun AnimatedContentTransitionScope<NavBackStackEntry>.isSharedElementPair() =
        isSharedElementRoute(initialState.destination.route) ||
            isSharedElementRoute(targetState.destination.route)

    fun AnimatedContentTransitionScope<NavBackStackEntry>.navBarSlide() =
        navBarSlide(
            initialRoute = initialState.destination.route,
            targetRoute = targetState.destination.route,
            primaryItems = primaryNavBarItems,
            secondaryItems = secondaryNavBarItems
        )

    return FoamNavTransitions(
        enter = {
            val navBarSlide = navBarSlide()
            when {
                playerTransitionState.currentState -> EnterTransition.None
                navBarSlide != null -> navBarEnter(navBarSlide)
                isSettingsPair() || isStatsPair() ->
                    slideInHorizontally(
                        initialOffsetX = { (it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeIn(animationSpec = FoamMotion.Spec.screenFadeIn)
                isBrowseRoute(targetState.destination.route) ->
                    slideInHorizontally(
                        initialOffsetX = { (it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeIn(animationSpec = FoamMotion.Spec.screenFadeIn)
                isSharedElementPair() ->
                    fadeIn(FoamMotion.Spec.sharedElementFlight)
                else -> fadeIn(animationSpec = FoamMotion.Spec.screenFadeIn)
            }
        },
        exit = {
            val navBarSlide = navBarSlide()
            when {
                playerTransitionState.currentState -> ExitTransition.None
                navBarSlide != null -> navBarExit(navBarSlide)
                isSettingsPair() || isStatsPair() ->
                    slideOutHorizontally(
                        targetOffsetX = { (-it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
                isBrowsePair() ->
                    slideOutHorizontally(
                        targetOffsetX = { (-it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
                isSharedElementPair() ->
                    fadeOut(FoamMotion.Spec.sharedElementFlight)
                else -> fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
            }
        },
        popEnter = {
            val navBarSlide = navBarSlide()
            when {
                playerTransitionState.currentState -> EnterTransition.None
                // A tab switch can be a pop (returning to a tab already on the
                // back stack); the incoming screen still slides in from the side
                // of the navbar icon it is opened from.
                navBarSlide != null -> navBarEnter(navBarSlide)
                isSettingsPair() || isStatsPair() ->
                    slideInHorizontally(
                        initialOffsetX = { (-it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeIn(animationSpec = FoamMotion.Spec.screenFadeIn)
                isBrowsePair() ->
                    slideInHorizontally(
                        initialOffsetX = { (-it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeIn(animationSpec = FoamMotion.Spec.screenFadeIn)
                isSharedElementPair() ->
                    fadeIn(FoamMotion.Spec.sharedElementFlight)
                else -> fadeIn(animationSpec = FoamMotion.Spec.screenFadeIn)
            }
        },
        popExit = {
            val navBarSlide = navBarSlide()
            when {
                playerTransitionState.currentState -> ExitTransition.None
                navBarSlide != null -> navBarExit(navBarSlide)
                isSettingsPair() || isStatsPair() ->
                    slideOutHorizontally(
                        targetOffsetX = { (it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
                isBrowseRoute(initialState.destination.route) ->
                    slideOutHorizontally(
                        targetOffsetX = { (it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
                isSharedElementPair() ->
                    fadeOut(FoamMotion.Spec.sharedElementFlight)
                else -> fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
            }
        },
    )
}

/** Which row of the navigation bar a screen belongs to. */
private enum class NavBarTier { PRIMARY, SECONDARY }

/** The row and slot index of a screen inside the configured navigation bar. */
private data class NavBarPosition(val tier: NavBarTier, val index: Int)

/** The edge an incoming navbar screen slides in from. */
private enum class NavBarSlide { LEFT, RIGHT, TOP, BOTTOM }

/**
 * Opacity and translation share one duration for navbar switches, so an opaque
 * destination (Settings) fades to/from the backdrop image on exactly the same
 * frames as its slide. The generic screen fades (400ms in / 200ms out) are too
 * long/short for the 300ms slide and made the background visibly outlive it.
 */
private val NavBarSlideFadeSpec = tween<Float>(
    durationMillis = FoamMotion.Duration.SMOOTH,
    easing = FoamMotion.Easings.STANDARD
)

/**
 * The navbar item behind [route]. Settings and stats sub-pages resolve to their
 * parent tab, since that is the icon highlighted in the bar while they are open;
 * a switch between two sub-pages of the same tab is not a navbar switch and
 * returns null (they keep their stack's own slide).
 */
private fun navBarItemForRoute(route: String?): NavBarItem? = when {
    route == null -> null
    route == Screen.Home.route -> NavBarItem.HOME
    route == Screen.Playlists.route -> NavBarItem.PLAYLISTS
    route == Screen.Search.route -> NavBarItem.SEARCH
    route == Screen.Stats.route || route.startsWith("stats/") -> NavBarItem.STATS
    route == Screen.Equalizer.route -> NavBarItem.EQUALIZER
    route == Screen.Settings.route || route.startsWith("settings/") -> NavBarItem.SETTINGS
    else -> null
}

private fun navBarPosition(
    item: NavBarItem,
    primaryItems: List<NavBarItem>,
    secondaryItems: List<NavBarItem>
): NavBarPosition? {
    // MORE is the expand toggle, not a screen, and takes no slot of its own
    // among the screen icons.
    val primaryIndex = primaryItems.indexOfFirst { it == item && it != NavBarItem.MORE }
    if (primaryIndex >= 0) return NavBarPosition(NavBarTier.PRIMARY, primaryIndex)

    val secondaryIndex = secondaryItems.indexOf(item)
    if (secondaryIndex >= 0) return NavBarPosition(NavBarTier.SECONDARY, secondaryIndex)

    return null
}

/**
 * Direction for a screen opened from the navigation bar: horizontally toward the
 * new icon's side when both screens share a row, and vertically when the switch
 * crosses between the main row and the second row (down from the second row,
 * up into it).
 */
private fun navBarSlide(
    initialRoute: String?,
    targetRoute: String?,
    primaryItems: List<NavBarItem>,
    secondaryItems: List<NavBarItem>
): NavBarSlide? {
    val initialItem = navBarItemForRoute(initialRoute) ?: return null
    val targetItem = navBarItemForRoute(targetRoute) ?: return null
    if (initialItem == targetItem) return null

    val initialPosition = navBarPosition(initialItem, primaryItems, secondaryItems) ?: return null
    val targetPosition = navBarPosition(targetItem, primaryItems, secondaryItems) ?: return null

    return when {
        targetPosition.tier != initialPosition.tier ->
            if (targetPosition.tier == NavBarTier.SECONDARY) NavBarSlide.TOP else NavBarSlide.BOTTOM
        targetPosition.index > initialPosition.index -> NavBarSlide.RIGHT
        targetPosition.index < initialPosition.index -> NavBarSlide.LEFT
        else -> null
    }
}

private fun navBarEnter(slide: NavBarSlide): EnterTransition =
    (when (slide) {
        NavBarSlide.LEFT -> slideInHorizontally(
            initialOffsetX = { (-it * SubScreenSlideFraction).roundToInt() },
            animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
        )
        NavBarSlide.RIGHT -> slideInHorizontally(
            initialOffsetX = { (it * SubScreenSlideFraction).roundToInt() },
            animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
        )
        NavBarSlide.TOP -> slideInVertically(
            initialOffsetY = { (-it * SubScreenSlideFraction).roundToInt() },
            animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
        )
        NavBarSlide.BOTTOM -> slideInVertically(
            initialOffsetY = { (it * SubScreenSlideFraction).roundToInt() },
            animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
        )
    }) + fadeIn(animationSpec = NavBarSlideFadeSpec)

private fun navBarExit(slide: NavBarSlide): ExitTransition =
    (when (slide) {
        NavBarSlide.LEFT -> slideOutHorizontally(
            targetOffsetX = { (it * SubScreenSlideFraction).roundToInt() },
            animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
        )
        NavBarSlide.RIGHT -> slideOutHorizontally(
            targetOffsetX = { (-it * SubScreenSlideFraction).roundToInt() },
            animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
        )
        NavBarSlide.TOP -> slideOutVertically(
            targetOffsetY = { (it * SubScreenSlideFraction).roundToInt() },
            animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
        )
        NavBarSlide.BOTTOM -> slideOutVertically(
            targetOffsetY = { (-it * SubScreenSlideFraction).roundToInt() },
            animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
        )
    }) + fadeOut(animationSpec = NavBarSlideFadeSpec)
