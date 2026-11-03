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
import com.foamplayer.app.ui.theme.FoamMotion
import kotlin.math.roundToInt

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
            when {
                playerTransitionState.currentState -> ExitTransition.None
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
            when {
                playerTransitionState.currentState -> EnterTransition.None
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
            when {
                playerTransitionState.currentState -> ExitTransition.None
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
