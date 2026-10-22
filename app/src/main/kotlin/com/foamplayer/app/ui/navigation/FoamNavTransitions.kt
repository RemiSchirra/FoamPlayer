package com.foamplayer.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/**
 * Enter/exit specs for the screen [NavHost], kept out of the host composable.
 *
 * The rules depend on the route pair: settings/stats/browse stacks slide, detail
 * screens crossfade over the shared-element duration, and everything else fades.
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
    playerTransitionState: SeekableTransitionState<Boolean>
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

    return FoamNavTransitions(
        enter = {
            when {
                playerTransitionState.currentState -> EnterTransition.None
                isSettingsPair() || isStatsPair() ->
                    slideInHorizontally(initialOffsetX = { it }, animationSpec = tween()) +
                        fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                isBrowseRoute(targetState.destination.route) ->
                    slideInHorizontally(initialOffsetX = { it }, animationSpec = tween()) +
                        fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                isSharedElementPair() ->
                    fadeIn(
                        animationSpec = tween(
                            SharedElementTransitionDurationMs,
                            easing = SharedElementEasing
                        )
                    )
                else -> fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
            }
        },
        exit = {
            when {
                playerTransitionState.currentState -> ExitTransition.None
                isSettingsPair() || isStatsPair() ->
                    slideOutHorizontally(
                        targetOffsetX = { -it / SubScreenSlideDivisor },
                        animationSpec = tween()
                    ) + fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                isBrowsePair() ->
                    slideOutHorizontally(
                        targetOffsetX = { -it / SubScreenSlideDivisor },
                        animationSpec = tween()
                    ) + fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                isSharedElementPair() ->
                    fadeOut(
                        animationSpec = tween(
                            SharedElementTransitionDurationMs,
                            easing = SharedElementEasing
                        )
                    )
                else -> fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
            }
        },
        popEnter = {
            when {
                playerTransitionState.currentState -> EnterTransition.None
                isSettingsPair() || isStatsPair() ->
                    slideInHorizontally(
                        initialOffsetX = { -it / SubScreenSlideDivisor },
                        animationSpec = tween()
                    ) + fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                isBrowsePair() ->
                    slideInHorizontally(
                        initialOffsetX = { -it / SubScreenSlideDivisor },
                        animationSpec = tween()
                    ) + fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                isSharedElementPair() ->
                    fadeIn(
                        animationSpec = tween(
                            SharedElementTransitionDurationMs,
                            easing = SharedElementEasing
                        )
                    )
                else -> fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
            }
        },
        popExit = {
            when {
                playerTransitionState.currentState -> ExitTransition.None
                isSettingsPair() || isStatsPair() ->
                    slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween()) +
                        fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                isBrowseRoute(initialState.destination.route) ->
                    slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween()) +
                        fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                isSharedElementPair() ->
                    fadeOut(
                        animationSpec = tween(
                            SharedElementTransitionDurationMs,
                            easing = SharedElementEasing
                        )
                    )
                else -> fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
            }
        },
    )
}
