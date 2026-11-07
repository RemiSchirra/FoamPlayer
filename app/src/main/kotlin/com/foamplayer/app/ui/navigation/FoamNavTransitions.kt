package com.foamplayer.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.navigation.NavBackStackEntry
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.ui.theme.FoamMotion
import kotlin.math.roundToInt

/**
 * Enter/exit specs for the screen [NavHost], kept out of the host composable.
 *
 * The rules depend on the route pair: navigation-bar switches slide from the side
 * or row the new tab lives on, settings/stats/browse stacks slide, album -> artist
 * uses the same stack slide (it has no shared artwork), opening/closing a
 * collection (album/playlist/artist), or an album from its artist page, zooms
 * around the tapped item (see [CollectionZoomOrigins]), other detail screens crossfade over the
 * shared-element duration, and everything else fades.
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
    secondaryNavBarItems: List<NavBarItem>,
    zoomOrigins: CollectionZoomOrigins
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

    // Album -> artist has no shared artwork to fly (the album's artist line is
    // plain text), so it would otherwise crossfade. Give it the same subtle
    // horizontal slide as the settings/stats/browse stacks instead.
    fun AnimatedContentTransitionScope<NavBackStackEntry>.isAlbumToArtistPair() =
        isAlbumRoute(initialState.destination.route) && isArtistRoute(targetState.destination.route)

    fun AnimatedContentTransitionScope<NavBackStackEntry>.isArtistToAlbumPair() =
        isArtistRoute(initialState.destination.route) && isAlbumRoute(targetState.destination.route)

    // Opening a collection from a list/grid, or an album from its artist page
    // (push, used by enter/exit), and closing it back (pop, used by
    // popEnter/popExit). Album -> artist pushes and artist -> album pops keep
    // their stack slide, so only the opposite directions zoom.
    fun AnimatedContentTransitionScope<NavBackStackEntry>.isCollectionOpenPair() =
        (isCollectionDetailRoute(targetState.destination.route) &&
                !isCollectionDetailRoute(initialState.destination.route)) ||
                isArtistToAlbumPair()

    fun AnimatedContentTransitionScope<NavBackStackEntry>.isCollectionClosePair() =
        (isCollectionDetailRoute(initialState.destination.route) &&
                !isCollectionDetailRoute(targetState.destination.route)) ||
                isAlbumToArtistPair()

    // Both screens of the push share the origin computed for the collection entry.
    fun AnimatedContentTransitionScope<NavBackStackEntry>.openOrigin(): TransformOrigin =
        zoomOrigins.originForOpen(entryId = targetState.id, sourceEntryId = initialState.id)

    // On close the list screen is composed again at the position it was left
    // at, so the origin remembered at open is where the item will be at the
    // end of the animation.
    fun AnimatedContentTransitionScope<NavBackStackEntry>.closeOrigin(): TransformOrigin =
        zoomOrigins.originForClose(entryId = initialState.id, targetEntryId = targetState.id)

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
                isSettingsPair() || isStatsPair() || isAlbumToArtistPair() ->
                    slideInHorizontally(
                        initialOffsetX = { (it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeIn(animationSpec = FoamMotion.Spec.screenFadeIn)
                isBrowseRoute(targetState.destination.route) ->
                    slideInHorizontally(
                        initialOffsetX = { (it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeIn(animationSpec = FoamMotion.Spec.screenFadeIn)
                // Collection opens: the incoming detail content scales up from the tapped item.
                isCollectionOpenPair() ->
                    scaleIn(
                        animationSpec = FoamMotion.Spec.sharedElementFlight,
                        initialScale = CollectionZoomMinScale,
                        transformOrigin = openOrigin()
                    ) + fadeIn(FoamMotion.Spec.sharedElementFlight)
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
                isSettingsPair() || isStatsPair() || isAlbumToArtistPair() ->
                    slideOutHorizontally(
                        targetOffsetX = { (-it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
                isBrowsePair() ->
                    slideOutHorizontally(
                        targetOffsetX = { (-it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
                // Collection opens: the outgoing list shrinks around the tapped item.
                // The artwork flies in the shared-transition overlay, so it is
                // not scaled by this layer.
                isCollectionOpenPair() ->
                    scaleOut(
                        animationSpec = FoamMotion.Spec.sharedElementFlight,
                        targetScale = CollectionZoomMinScale,
                        transformOrigin = openOrigin()
                    ) + fadeOut(FoamMotion.Spec.sharedElementFlight)
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
                isSettingsPair() || isStatsPair() || isArtistToAlbumPair() ->
                    slideInHorizontally(
                        initialOffsetX = { (-it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeIn(animationSpec = FoamMotion.Spec.screenFadeIn)
                isBrowsePair() ->
                    slideInHorizontally(
                        initialOffsetX = { (-it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeIn(animationSpec = FoamMotion.Spec.screenFadeIn)
                // Collection closes: the list scales back up around the item's
                // final position.
                isCollectionClosePair() ->
                    scaleIn(
                        animationSpec = FoamMotion.Spec.sharedElementFlight,
                        initialScale = CollectionZoomMinScale,
                        transformOrigin = closeOrigin()
                    ) + fadeIn(FoamMotion.Spec.sharedElementFlight)
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
                isSettingsPair() || isStatsPair() || isArtistToAlbumPair() ->
                    slideOutHorizontally(
                        targetOffsetX = { (it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
                isBrowseRoute(initialState.destination.route) ->
                    slideOutHorizontally(
                        targetOffsetX = { (it * SubScreenSlideFraction).roundToInt() },
                        animationSpec = tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.STANDARD)
                    ) + fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
                // Collection closes: the outgoing detail content shrinks toward
                // the item's final position.
                isCollectionClosePair() ->
                    scaleOut(
                        animationSpec = FoamMotion.Spec.sharedElementFlight,
                        targetScale = CollectionZoomMinScale,
                        transformOrigin = closeOrigin()
                    ) + fadeOut(FoamMotion.Spec.sharedElementFlight)
                isSharedElementPair() ->
                    fadeOut(FoamMotion.Spec.sharedElementFlight)
                else -> fadeOut(animationSpec = FoamMotion.Spec.screenFadeOut)
            }
        },
    )
}

/**
 * Scale the shrunken side of the collection open/close zoom starts/ends at.
 * Keep it in step with the shrink [com.foamplayer.app.ui.theme.foamBackdropScale]
 * applies behind modals so both read as the same "push back" gesture.
 */
private const val CollectionZoomMinScale = 0.92f

/** A tap older than this is not what triggered the navigation (modal, deep link...). */
private const val TouchFreshnessNanos = 2_500_000_000L
private const val MaxRememberedOrigins = 48

/**
 * Where the collection zoom is pivoted: the position of the item the user tapped
 * to open a collection, as a fraction of the screen-content container.
 *
 * The last pointer-down is tracked by [foamZoomOriginTracker]. When a collection
 * entry is pushed, that position is frozen for the entry, so the push and the
 * later pop (close) pivot around the same spot, which is where the item sits
 * again once the list is back (its scroll position is restored). Without a
 * fresh tap (opened from a modal, the player, a restored stack) or when the pop
 * does not return to the list the collection was opened from, the pivot falls
 * back to the container center.
 */
@Stable
internal class CollectionZoomOrigins {
    private class Remembered(val sourceEntryId: String, val origin: TransformOrigin)

    private var lastTouch: Offset = Offset.Unspecified
    private var lastTouchContainer: IntSize = IntSize.Zero
    private var lastTouchNanos: Long = 0L
    private val byEntry = LinkedHashMap<String, Remembered>()

    fun onPointerDown(position: Offset, containerSize: IntSize) {
        lastTouch = position
        lastTouchContainer = containerSize
        lastTouchNanos = System.nanoTime()
    }

    /** Navigations not started by tapping an item (modals, player) must not reuse a past tap. */
    fun discardTouch() {
        lastTouch = Offset.Unspecified
    }

    fun originForOpen(entryId: String, sourceEntryId: String): TransformOrigin {
        byEntry[entryId]?.let { return it.origin }
        val origin = touchOrigin() ?: TransformOrigin.Center
        byEntry[entryId] = Remembered(sourceEntryId, origin)
        if (byEntry.size > MaxRememberedOrigins) {
            byEntry.remove(byEntry.keys.first())
        }
        return origin
    }

    fun originForClose(entryId: String, targetEntryId: String): TransformOrigin {
        val remembered = byEntry[entryId] ?: return TransformOrigin.Center
        return if (remembered.sourceEntryId == targetEntryId) {
            remembered.origin
        } else {
            TransformOrigin.Center
        }
    }

    private fun touchOrigin(): TransformOrigin? {
        if (lastTouch.isUnspecified) return null
        if (System.nanoTime() - lastTouchNanos > TouchFreshnessNanos) return null
        val width = lastTouchContainer.width
        val height = lastTouchContainer.height
        if (width <= 0 || height <= 0) return null
        return TransformOrigin(
            pivotFractionX = (lastTouch.x / width).coerceIn(0f, 1f),
            pivotFractionY = (lastTouch.y / height).coerceIn(0f, 1f)
        )
    }
}

/**
 * Records where the screen content was last touched, without consuming anything
 * (Initial pass). Apply it to the box that hosts the NavHost, before any scale
 * modifier, so positions stay in the container's own coordinates.
 */
internal fun Modifier.foamZoomOriginTracker(origins: CollectionZoomOrigins): Modifier =
    pointerInput(origins) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                if (down != null) origins.onPointerDown(down.position, size)
            }
        }
    }

/** Which row of the navigation bar a screen belongs to. */
private enum class NavBarTier { PRIMARY, SECONDARY }

/** The row and slot index of a screen inside the configured navigation bar. */
private data class NavBarPosition(val tier: NavBarTier, val index: Int)

/** The edge an incoming navbar screen slides in from. */
private enum class NavBarSlide { LEFT, RIGHT, TOP, BOTTOM }

/**
 * Opacity changes finish well before the 300ms slide travel. Keeping them at
 * the travel's duration left both screens (and, through the incoming Settings
 * surface, the backdrop image) half-transparent for most of the switch, which
 * reads as a ghost/flicker. The fade only needs to get the layers out of each
 * other's way; the slide carries the motion.
 */
private val NavBarSlideFadeInSpec = tween<Float>(
    durationMillis = FoamMotion.Duration.SNAP,
    easing = FoamMotion.Easings.STANDARD
)

/** Front-loaded (most of the drop in the first frames) and short. */
private val NavBarSlideFadeOutSpec = tween<Float>(
    durationMillis = FoamMotion.Duration.SNAP,
    easing = FoamMotion.Easings.EXIT
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
    }) + fadeIn(animationSpec = NavBarSlideFadeInSpec)

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
    }) + fadeOut(animationSpec = NavBarSlideFadeOutSpec)