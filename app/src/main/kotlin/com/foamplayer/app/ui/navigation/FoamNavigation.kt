package com.foamplayer.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.CollectionKind
import com.foamplayer.app.ui.modals.LocalCollectionFoamModalState
import com.foamplayer.app.ui.modals.LocalDspAssignments
import com.foamplayer.app.ui.modals.LocalOpenPlaylistPickerForPlaylist
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.modals.LocalTrackFoamModalState
import com.foamplayer.app.ui.modals.TrackRadioViewModel
import com.foamplayer.app.ui.modals.rememberCollectionFoamModalState
import com.foamplayer.app.ui.modals.rememberTrackFoamModalState
import com.foamplayer.app.ui.screens.home.HomeViewModel
import com.foamplayer.app.ui.screens.player.FullPlayerBackdropState
import com.foamplayer.app.ui.screens.player.FullPlayerScreen
import com.foamplayer.app.ui.screens.player.MiniPlayerSwipeController
import com.foamplayer.app.ui.screens.player.PlayerFlightGeometryState
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.player.progress
import com.foamplayer.app.ui.screens.playlists.PlaylistsViewModel
import com.foamplayer.app.update.UpdateManager
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.foamBackdropScale
import com.foamplayer.app.ui.theme.foamPlayerOpenScale
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Process-wide latch for the launch gate: once the gate has opened, later
 * activity recreations (rotation, theme changes) must not hide the already-warm
 * UI for a frame. A fresh process still starts with the gate closed.
 */
private var startupGateOpened = false

/**
 * Root navigation host: every screen of the app, the mini/full player
 * transition (a [SeekableTransitionState] driven by both taps and drags),
 * shared-element artwork flights, the app-wide backdrop, the nav bar and the
 * global action modals.
 */
@Composable
fun FoamNavHost(
    modifier: Modifier = Modifier,
    startOnOnboarding: Boolean = false,
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val routePair = remember { object { var previous: String? = currentRoute; var current: String? = currentRoute } }
    if (routePair.current != currentRoute) {
        routePair.previous = routePair.current
        routePair.current = currentRoute
    }
    val lastRoute = routePair.previous

    val homeViewModel: HomeViewModel = hiltViewModel()
    val playlistsViewModel: PlaylistsViewModel = hiltViewModel()
    val playlists by playlistsViewModel.playlists.collectAsState()
    val playlistCovers by playlistsViewModel.playlistCovers.collectAsState()
    val addTrackInsertPosition by playlistsViewModel.addTrackInsertPosition.collectAsState()

    val trackModalState = rememberTrackFoamModalState()

    // Playlist picker / lyrics sheet state is shared with the destination graph
    // (the collection modal) and the global dialogs below.
    val modalState = remember { FoamNavModalState() }

    // Song radio sheet: the similar list is built per seed track on open.
    val trackRadioViewModel: TrackRadioViewModel = hiltViewModel()
    val trackRadioState by trackRadioViewModel.state.collectAsState()

    val pickerTrackId = modalState.playlistPickerTrack?.id ?: trackModalState.track?.id
    val playlistIdsWithTrack by remember(pickerTrackId) {
        playlistsViewModel.playlistIdsContainingTrack(pickerTrackId)
    }.collectAsState(initial = emptySet())

    val batchTrackIds = modalState.pickerBatchTracks?.map { it.id } ?: emptyList()
    val playlistIdsWithBatch by remember(batchTrackIds) {
        playlistsViewModel.playlistIdsContainingAllTracks(batchTrackIds)
    }.collectAsState(initial = emptySet())

    val collectionModalState = rememberCollectionFoamModalState()

    val updateManager: UpdateManager = hiltViewModel<UpdateViewModel>().updateManager
    val updateStartupRelease by updateManager.startupRelease.collectAsState()
    val updateDownloadState by updateManager.downloadState.collectAsState()
    val updateInstallFile by updateManager.installPrompt.collectAsState()

    val toastViewModel: ToastViewModel = hiltViewModel()

    val searchFocusRequest = remember { SearchFocusRequest() }

    val backgroundSettingsPreview = remember { BackgroundSettingsPreviewState() }

    val activeCollectionId by playerViewModel.activeCollectionId.collectAsState()
    val hasCurrentTrack by playerViewModel.hasCurrentTrack.collectAsState()
    val activeTrackId by playerViewModel.activeTrackId.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val isPlaybackRequested by playerViewModel.isPlaybackRequested.collectAsState()
    val isPlaybackActive = isPlaying || isPlaybackRequested
    val playerSettings by playerViewModel.settings.collectAsState()
    val scoreOverrides by playerViewModel.scoreOverrides.collectAsState()
    val libraryGenres by playerViewModel.genres.collectAsState()
    val isAnimateSeekBarEnabled = playerSettings.animateSeekBarEnabled
    val isAutoOpenPlayerEnabled = playerSettings.autoOpenPlayerEnabled
    val backgroundMode = backgroundSettingsPreview.mode ?: playerSettings.backgroundMode
    val forceDynamicBackgroundInCollections = playerSettings.forceDynamicBackgroundInCollections
    val forceDynamicBackgroundInPlayer = playerSettings.forceDynamicBackgroundInPlayer
    val navigationBarMode = playerSettings.navigationBarMode
    val navBarTransparentWhenPlayerOpen = playerSettings.navBarTransparentWhenPlayerOpen
    val primaryNavBarItems = playerSettings.primaryNavBarItems
    val secondaryNavBarItems = playerSettings.secondaryNavBarItems

    // ---------------------------------------------------------------------
    // Launch gate
    //
    // The backdrop layers below are composed from the very first frame (so
    // the restored artwork can load and warm up), but the screens, the mini
    // player and the nav bar only enter once everything they show is known:
    // the saved playback session, the Home query snapshots and the
    // album-art accent. That makes the app appear as one finished frame
    // instead of fading, reordering and animating its parts into place.
    // ---------------------------------------------------------------------
    val startupContext = LocalContext.current
    val sessionRestoreSettled by playerViewModel.sessionRestoreSettled.collectAsState()
    val homeContentReady by homeViewModel.contentReady.collectAsState()
    val currentAlbumArtPath by playerViewModel.currentAlbumArtPath.collectAsState()

    var startupAccentReady by remember { mutableStateOf(false) }
    LaunchedEffect(
        playerSettings.automaticColorEnabled,
        sessionRestoreSettled,
        currentAlbumArtPath
    ) {
        if (startupAccentReady) return@LaunchedEffect
        if (!playerSettings.automaticColorEnabled) {
            startupAccentReady = true
        } else if (sessionRestoreSettled) {
            val path = currentAlbumArtPath
            if (path != null) {
                AlbumArtPaletteCache.ensure(
                    scope = this,
                    context = startupContext,
                    path = path
                )
                AlbumArtPaletteCache.awaitSettled(path)
            }
            startupAccentReady = true
        }
    }

    var startupReady by remember { mutableStateOf(startupGateOpened) }
    LaunchedEffect(sessionRestoreSettled, homeContentReady, startupAccentReady) {
        if (startupReady) return@LaunchedEffect
        if (sessionRestoreSettled && homeContentReady && startupAccentReady) {
            // Let the session/row state flows land on their loaded values for
            // one frame before the UI is composed for the first time.
            withFrameNanos { }
            startupGateOpened = true
            startupReady = true
        }
    }

    // Per-view list density / sort / inversion setters (organisation modal).
    val densityFor: (MediaListView) -> MediaDensity = { view -> playerSettings.mediaDensity(view) }
    val setDensity: (MediaListView, MediaDensity) -> Unit = { view, value ->
        playerViewModel.setMediaDensity(view, value)
    }
    val sortModeFor: (MediaListView) -> CollectionSortMode = { view -> playerSettings.mediaSortMode(view) }
    val setSortMode: (MediaListView, CollectionSortMode) -> Unit = { view, value ->
        playerViewModel.setMediaSortMode(view, value)
    }
    val sortInvertedFor: (MediaListView) -> Boolean = { view -> playerSettings.mediaSortIsInverted(view) }
    val setSortInverted: (MediaListView, Boolean) -> Unit = { view, value ->
        playerViewModel.setMediaSortInverted(view, value)
    }

    val heroLayoutFor: (CollectionHeroTarget) -> CollectionHeroLayout = { target ->
        playerSettings.collectionHeroLayout(target)
    }

    // Player open/close state; open only on an explicit request (e.g. notification tap).
    val initialOpenRequested = remember { playerViewModel.openPlayerRequested.value }

    LaunchedEffect(Unit) {
        if (initialOpenRequested) {
            playerViewModel.consumeOpenPlayerRequest()
        }
    }

    val scope = rememberCoroutineScope()
    val playerTransition = remember(scope, initialOpenRequested) {
        PlayerTransitionController(
            state = SeekableTransitionState(initialOpenRequested),
            scope = scope,
            initialOpenRequested = initialOpenRequested
        )
    }
    val playerTransitionState = playerTransition.state
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    val activeSharedCollectionTag = remember { mutableStateOf<String?>(null) }

    // Pivot of the collection open/close zoom (position of the tapped item).
    val zoomOrigins = remember { CollectionZoomOrigins() }

    DisposableEffect(navController) {
        var lastEntry: NavBackStackEntry? = navController.currentBackStackEntry
        val seenEntryIds = navController.currentBackStack.value.mapTo(mutableSetOf()) { it.id }
        val listener = NavController.OnDestinationChangedListener { controller, destination, _ ->
            val entry = controller.currentBackStackEntry
            val outgoing = lastEntry
            val returnedToExisting = entry != null && entry.id in seenEntryIds
            lastEntry = entry
            entry?.let { seenEntryIds += it.id }

            // A null active tag means the navigation explicitly opted out (e.g.
            // a nav-bar switch), so leave it alone then.
            if (returnedToExisting &&
                outgoing != null &&
                outgoing.id != entry?.id &&
                activeSharedCollectionTag.value != null &&
                isCollectionDetailRoute(outgoing.destination.route)
            ) {
                outgoing.savedStateHandle.get<String>(SharedCollectionTagKey)?.let {
                    activeSharedCollectionTag.value = it
                }
            }

            if (!isCollectionDetailRoute(destination.route)) return@OnDestinationChangedListener
            val currentEntry = entry ?: return@OnDestinationChangedListener
            val savedState = currentEntry.savedStateHandle
            val savedTag = savedState.get<String>(SharedCollectionTagKey)
            if (savedTag == null) {
                activeSharedCollectionTag.value?.let { savedState[SharedCollectionTagKey] = it }
            } else if (activeSharedCollectionTag.value == null) {
                // Reaching a detail page with no click tag means it was restored
                // (saved tab stack / process death). Give it its own tag back so
                // its later close still pairs with its original source row.
                activeSharedCollectionTag.value = savedTag
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    var miniPlayerTopPx by remember { mutableStateOf(Float.NaN) }
    var miniPlayerBoxHeightPx by remember { mutableStateOf(0) }

    // Bottom-bar zone geometry: content scrolls behind the bar and the card.
    val miniPlayerBoxHeight = with(density) {
        miniPlayerBoxHeightPx.takeIf { it > 0 }?.toDp()
            ?: (NavBarDimens.MiniPlayerCardHeight + NavBarDimens.MiniPlayerCardVerticalPadding * 2)
    }
    val bottomBarClearance = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            (if (hasCurrentTrack) {
                NavBarDimens.miniPlayerBottomPadding(navigationBarMode, 0f) + miniPlayerBoxHeight
            } else {
                NavBarDimens.navBarZoneHeight(navigationBarMode)
            }) +
            16.dp
    val playerBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            NavBarDimens.navBarZoneHeight(navigationBarMode)

    // Artwork center of the full player's hero, paired with the dismiss target
    // (mini player art or origin row) to size the swipe distance.
    val flightGeometry = remember { PlayerFlightGeometryState() }
    val miniArtCenterOffsetPx = with(density) {
        (NavBarDimens.MiniPlayerCardVerticalPadding +
                NavBarDimens.MiniPlayerContainerPadding +
                NavBarDimens.MiniPlayerArtSize / 2).toPx()
    }
    val dismissSwipeDistancePx = {
        val heroY = flightGeometry.heroArtCenterY
        val targetY = playerTransition.centerTrackRequest?.rowArtCenterYPx?.takeUnless { it.isNaN() }
            ?: miniPlayerTopPx.takeUnless { it.isNaN() }?.let { it + miniArtCenterOffsetPx }
        val distance = if (heroY.isNaN() || targetY == null) {
            Float.NaN
        } else {
            abs(targetY - heroY)
        }
        if (distance.isNaN()) screenHeightPx else distance.coerceAtLeast(1f)
    }

    // Cold start straight into the player: restore animated transitions once the
    // first frames are composed (see PlayerTransitionController.animate for the
    // warm-start equivalent).
    LaunchedEffect(Unit) {
        if (initialOpenRequested) playerTransition.enableAnimationAfterColdStart()
    }

    val openPlayerRequested by playerViewModel.openPlayerRequested.collectAsState()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            playerTransition.animate(open = true, animated = false)
            playerViewModel.consumeOpenPlayerRequest()
        }
    }

    val miniPlayerSwipeController = remember(playerTransitionState, screenHeightPx) {
        MiniPlayerSwipeController(
            playerTransitionState = playerTransitionState,
            scope = scope,
            dragThresholdPx = screenHeightPx.coerceAtLeast(1f),
            onOpen = {
                playerTransition.animate(open = true)
            }
        )
    }

    // The screen last opened from the player: a follow-up player navigation
    // replaces it instead of stacking beneath it.
    var playerOpenedRoute by remember { mutableStateOf<String?>(null) }
    var playerOpenedPattern by remember { mutableStateOf<String?>(null) }

    /**
     * Navigates while closing the player, staging the flight after the nav
     * swap. The modal shrink scale lives on the SharedTransitionLayout, so the
     * artwork flight scales with the world and starts together with a
     * dismissing modal — no pop, no staging.
     */
    fun navigateClosingPlayer(route: String, pattern: String) {
        zoomOrigins.discardTouch()
        val topId = navController.currentDestination?.id
        val topPattern = navController.currentDestination?.route
        val replacesPrevious = playerOpenedPattern != null &&
                topPattern == playerOpenedPattern &&
                route != playerOpenedRoute
        fun navOptions(): NavOptionsBuilder.() -> Unit = {
            launchSingleTop = true
            if (replacesPrevious && topId != null) {
                popUpTo(topId) { inclusive = true }
            }
        }
        if (!playerTransitionState.targetState) {
            navController.navigate(route, navOptions())
            playerOpenedRoute = route
            playerOpenedPattern = pattern
            return
        }
        scope.launch {
            navController.navigate(route, navOptions())
            playerOpenedRoute = route
            playerOpenedPattern = pattern
            playerTransition.animate(open = false, precompose = true)
        }
    }

    // Backdrop layers: full-bleed root-level siblings, composed OUTSIDE the
    // shared transition below, so the modal shrink never scales the background.
    var navZoneHeightPx by remember { mutableStateOf(0) }
    val onBackgroundTransition = AppBackdrop(
        currentRoute = currentRoute,
        backgroundMode = backgroundMode,
        settings = playerSettings,
        preview = backgroundSettingsPreview,
        playerViewModel = playerViewModel,
        playerTargetOpen = playerTransitionState.targetState
    )

    val listBackdropState = remember(scope) { DetailListBackdropState(scope) }
    LaunchedEffect(currentRoute) { listBackdropState.onRouteChanged(currentRoute) }
    DetailListBackdrop(
        state = listBackdropState,
        currentRoute = currentRoute,
        lastRoute = lastRoute,
        backgroundMode = backgroundMode,
        forceDynamic = forceDynamicBackgroundInCollections,
        blur = backgroundBlurRadius(playerSettings.backgroundBlur),
        intensity = playerSettings.appBackgroundIntensity / 100f
    )
    val onListBackdropArt: (String?, Boolean) -> Unit = listBackdropState::onArt

    // Full player backdrop (root-hosted)
    val fullPlayerBackdropState = remember { FullPlayerBackdropState() }
    PlayerBackdrop(
        transitionState = playerTransitionState,
        state = fullPlayerBackdropState,
        backgroundMode = backgroundMode,
        settings = playerSettings,
        navigationBarMode = navigationBarMode,
        navZoneHeightPx = navZoneHeightPx,
        forceDynamic = forceDynamicBackgroundInPlayer
    )

    // The modal shrink scale is applied to the SharedTransitionLayout itself,
    // so the shared-element overlay scales with the world: the artwork flight
    // departs exactly at the on-screen hero's bounds, and a close can start
    // together with a dismissing modal — no pop, no staging. No base background
    // here: it would paint over the backdrop layers behind this layout — the
    // base comes from FoamBackdropHost instead.
    SharedTransitionLayout(
        modifier = modifier
            .fillMaxSize()
            .foamBackdropScale()
    ) {
        val transition: Transition<Boolean> = rememberTransition(playerTransitionState, label = "PlayerTransition")
        val navTransitions = remember(
            playerTransitionState,
            primaryNavBarItems,
            secondaryNavBarItems,
            zoomOrigins
        ) {
            buildFoamNavTransitions(
                playerTransitionState = playerTransitionState,
                primaryNavBarItems = primaryNavBarItems,
                secondaryNavBarItems = secondaryNavBarItems,
                zoomOrigins = zoomOrigins
            )
        }

        val isBottomChromeLiftAllowed = remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            snapshotFlow {
                Pair(
                    playerTransitionState.currentState == playerTransitionState.targetState,
                    isTransitionActive
                )
            }.collect { (statesEqual, sharedTransitionActive) ->
                when {
                    !statesEqual -> isBottomChromeLiftAllowed.value = false
                    !sharedTransitionActive -> isBottomChromeLiftAllowed.value = true
                }
            }
        }
        val isBottomChromeLifted: () -> Boolean = {
            isTransitionActive &&
                    (isBottomChromeLiftAllowed.value ||
                            (playerTransitionState.targetState && playerTransition.trackListTransitionTrackId != null))
        }

        val dspAssignments by playerViewModel.dspAssignments.collectAsState()

        CompositionLocalProvider(
            LocalTrackFoamModalState provides trackModalState,
            LocalCollectionFoamModalState provides collectionModalState,
            LocalDspAssignments provides dspAssignments,
            LocalOpenPlaylistPickerForPlaylist provides { source ->
                modalState.pickerExcludedPlaylistId = source.playlistId
                scope.launch {
                    val tracks = playlistsViewModel.playlistTracks(source.playlistId)
                    if (tracks.isEmpty()) return@launch
                    modalState.pickerBatchCollection = CollectionInfo(
                        title = source.name,
                        subtitle = null,
                        imageUrl = null,
                        imageIsEmbeddedArt = false,
                        kind = CollectionKind.Playlist,
                        tracks = tracks
                    )
                    modalState.pickerBatchTracks = tracks
                }
            },
            LocalBottomBarClearance provides bottomBarClearance,
            LocalCollectionHeroLayoutLookup provides heroLayoutFor,
            LocalActiveSharedCollection provides activeSharedCollectionTag.value,
            LocalActiveSharedCollectionTagState provides activeSharedCollectionTag,
            LocalSearchFocusRequest provides searchFocusRequest,
            LocalBackgroundSettingsPreview provides backgroundSettingsPreview,
            LocalPlayerTransitionState provides playerTransitionState
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                val isPlayerOpen = playerTransitionState.targetState
                val scalesWholeScreenForPlayer = !isSettingsRoute(currentRoute)

                val isOnboardingRoute = currentRoute == Screen.Onboarding.route
                // Created only once the gate opens, seeded with the restored
                // session, so the first revealed frame already has the mini
                // player in its final position: no snap/animation visible.
                val miniPlayerPresence = remember(startupReady) {
                    Animatable(if (startupReady && hasCurrentTrack && !isOnboardingRoute) 1f else 0f)
                }
                LaunchedEffect(hasCurrentTrack, isOnboardingRoute, startupReady) {
                    if (!startupReady) return@LaunchedEffect
                    when {
                        isOnboardingRoute -> miniPlayerPresence.snapTo(0f)
                        !hasCurrentTrack -> miniPlayerPresence.animateTo(
                            targetValue = 0f,
                            animationSpec = FoamMotion.Spec.sharedElementFlight
                        )
                        playerTransitionState.targetState -> miniPlayerPresence.snapTo(1f)
                        else -> miniPlayerPresence.animateTo(
                            targetValue = 1f,
                            animationSpec = FoamMotion.Spec.sharedElementFlight
                        )
                    }
                }
                LaunchedEffect(isPlayerOpen) {
                    if (isPlayerOpen && hasCurrentTrack) miniPlayerPresence.snapTo(1f)
                }
                var hadCurrentTrack by remember { mutableStateOf(false) }
                LaunchedEffect(hasCurrentTrack) {
                    if (hasCurrentTrack) {
                        hadCurrentTrack = true
                    } else if (hadCurrentTrack &&
                        (playerTransitionState.currentState || playerTransitionState.targetState)
                    ) {
                        playerTransition.animate(open = false)
                    }
                }

                // Expandable nav-bar second row
                var isNavBarExpanded by remember { mutableStateOf(false) }
                val navBarExpandFraction by animateFloatAsState(
                    targetValue = if (isNavBarExpanded) 1f else 0f,
                    animationSpec = tween(FoamMotion.Duration.STANDARD, easing = FoamMotion.Easings.STANDARD),
                    label = "NavBarExpandFraction"
                )
                LaunchedEffect(isPlayerOpen) {
                    if (isPlayerOpen) isNavBarExpanded = false
                }
                LaunchedEffect(playerSettings.secondaryNavBarItems) {
                    if (playerSettings.secondaryNavBarItems.isEmpty()) isNavBarExpanded = false
                }
                BackHandler(enabled = isNavBarExpanded) { isNavBarExpanded = false }

                val playerClosedFraction = {
                    val progress = playerTransitionState.progress().coerceIn(0f, 1f)
                    if (playerTransitionState.targetState) {
                        1f - EaseOutCubic.transform(progress)
                    } else {
                        1f - EaseInCubic.transform(progress)
                    }
                }

                val handleNavBarNavigation: (String) -> Unit = { route ->
                    isNavBarExpanded = false
                    activeSharedCollectionTag.value = null
                    // Only the search icon's tap raises the focused-search request just
                    // before landing here; any other destination cancels a stale one.
                    if (route != Screen.Search.route) searchFocusRequest.consume()
                    if (playerTransitionState.currentState || playerTransitionState.targetState) {
                        playerTransition.animate(open = false)
                    }
                    if (currentRoute != route) {
                        // Animate back if we're a subscreen of [route],
                        // otherwise just open the desired screen.
                        if (!navController.popBackStack(route, inclusive = false)) {
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    // Preserve the tab's back stack and UI state.
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                }

                if (startupReady) Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .foamZoomOriginTracker(zoomOrigins)
                ) {
                    fun playTrackFromList(
                        tracks: List<TrackEntity>,
                        index: Int,
                        collectionId: String? = null
                    ) {
                        if (isAutoOpenPlayerEnabled) {
                            playerTransition.skipMiniPlayerTrackAnimation = true
                        }
                        playerViewModel.playQueueReplacingUpcoming(tracks, index, collectionId)
                        if (isAutoOpenPlayerEnabled) {
                            playerTransition.animate(
                                open = true,
                                sourceTrackId = tracks.getOrNull(index)?.id,
                                precompose = true
                            )
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        transition.AnimatedVisibility(
                            visible = { isFull -> !isFull },
                            enter = if (playerTransition.isTransitionAnimated) fadeIn() else EnterTransition.None,
                            exit = if (playerTransition.isTransitionAnimated) fadeOut() else ExitTransition.None,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (scalesWholeScreenForPlayer) {
                                        Modifier.foamPlayerOpenScale(
                                            playerOpen = playerTransitionState.targetState,
                                            playerProgress = { playerTransitionState.progress() }
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            val playerTransitionVisibilityScope = this
                            Box(modifier = Modifier.fillMaxSize()) {
                                val navGraphContext = FoamNavGraphContext(
                                    navController = navController,
                                    context = LocalContext.current,
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                    homeViewModel = homeViewModel,
                                    playlistsViewModel = playlistsViewModel,
                                    playlistCovers = playlistCovers,
                                    activeCollectionId = activeCollectionId,
                                    activeTrackId = activeTrackId,
                                    isPlaybackActive = isPlaybackActive,
                                    settings = playerSettings,
                                    heroLayoutFor = heroLayoutFor,
                                    densityFor = densityFor,
                                    setDensity = setDensity,
                                    sortModeFor = sortModeFor,
                                    setSortMode = setSortMode,
                                    sortInvertedFor = sortInvertedFor,
                                    setSortInverted = setSortInverted,
                                    sharedTrackId = playerTransition.trackListTransitionTrackId,
                                    centerTrackRequest = playerTransition.centerTrackRequest,
                                    onListBackdropArt = onListBackdropArt,
                                    onPlayTracks = { tracks, index, collectionId ->
                                        playTrackFromList(tracks, index, collectionId)
                                    },
                                    onActiveSharedCollectionChange = { activeSharedCollectionTag.value = it }
                                )
                                NavHost(
                                    modifier = Modifier.fillMaxSize(),
                                    navController = navController,
                                    startDestination = if (startOnOnboarding) {
                                        Screen.Onboarding.route
                                    } else {
                                        Screen.Home.route
                                    },
                                    enterTransition = navTransitions.enter,
                                    exitTransition = navTransitions.exit,
                                    popEnterTransition = navTransitions.popEnter,
                                    popExitTransition = navTransitions.popExit,
                                    sizeTransform = { SizeTransform(clip = false) }
                                ) {
                                    foamDestinationGraph(navGraphContext)
                                }
                            }
                        }

                        transition.AnimatedVisibility(
                            visible = { isFull -> isFull },
                            enter = EnterTransition.None,
                            exit = ExitTransition.None,
                            modifier = Modifier.fillMaxSize().padding(bottom = playerBottomPadding)
                        ) {
                            BackHandler { playerTransition.animate(open = false) }
                            FullPlayerScreen(
                                viewModel = playerViewModel,
                                onDismiss = { velocity ->
                                    playerTransition.animate(
                                        open = false,
                                        gestureVelocityPxPerSec = velocity
                                    )
                                },
                                onDismissGestureStart = { playerTransition.prepareDismissToRow(currentRoute, navBackStackEntry?.arguments, activeTrackId, activeCollectionId) },
                                onDismissGestureActivate = { playerTransition.activateDismissToRow(currentRoute, navBackStackEntry?.arguments, activeTrackId, activeCollectionId) },
                                onDismissGestureCancelled = { playerTransition.clearDismissToRowSession() },
                                dismissSwipeDistancePx = dismissSwipeDistancePx,
                                flightGeometry = flightGeometry,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this@AnimatedVisibility,
                                playerTransitionState = playerTransitionState,
                                backdropState = fullPlayerBackdropState,
                                navbarExpandHeight = {
                                    with(density) {
                                        NavBarDimens.extraRowVisibleRide(
                                            mode = navigationBarMode,
                                            expandFraction = navBarExpandFraction
                                        ).toPx()
                                    }
                                },
                                isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                                onOpenAlbum = { track ->
                                    activeSharedCollectionTag.value = sharedCollectionTag("album", track.album)
                                    navigateClosingPlayer(
                                        Screen.AlbumDetail.createRoute(track.album, track.albumArtPath, track.artist),
                                        Screen.AlbumDetail.route
                                    )
                                },
                                onOpenArtist = { track ->
                                    activeSharedCollectionTag.value = sharedCollectionTag("artist", track.artist)
                                    navigateClosingPlayer(
                                        Screen.ArtistDetail.createRoute(track.artist),
                                        Screen.ArtistDetail.route
                                    )
                                },
                                onOpenRadio = { track -> trackRadioViewModel.openRadioFor(track) }
                            )
                        }
                    }
                }

                if (startupReady) {
                    FoamBottomScrim(
                        currentRoute = currentRoute,
                        bottomBarClearance = bottomBarClearance
                    )
                }

                // Bottom chrome (mini player + nav bar + toasts) shrinks with
                // the screen content, in the shared-transition scale above.
                if (startupReady) Box(modifier = Modifier.fillMaxSize()) {
                    FoamBottomChrome(
                        currentRoute = currentRoute,
                        navigationBarMode = navigationBarMode,
                        transition = transition,
                        playerTransitionState = playerTransitionState,
                        isTransitionAnimated = playerTransition.isTransitionAnimated,
                        navBarExpandFraction = navBarExpandFraction,
                        playerClosedFraction = playerClosedFraction,
                        isBottomChromeLifted = isBottomChromeLifted,
                        onToggleNavBarExpand = { isNavBarExpanded = !isNavBarExpanded },
                        onNavigate = handleNavBarNavigation,
                        settings = playerSettings,
                        hasCurrentTrack = hasCurrentTrack,
                        cardPresenceFraction = { miniPlayerPresence.value },
                        miniPlayerSwipeController = miniPlayerSwipeController,
                        miniPlayerTopPx = { miniPlayerTopPx.takeUnless { it.isNaN() } },
                        miniPlayerBoxHeight = miniPlayerBoxHeight,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        playerViewModel = playerViewModel,
                        onBackgroundTransition = onBackgroundTransition,
                        onOpenPlayer = { playerTransition.animate(open = true, precompose = true) },
                        trackListTransitionTrackId = playerTransition.trackListTransitionTrackId,
                        centerTrackRequest = playerTransition.centerTrackRequest,
                        skipMiniPlayerTrackAnimation = playerTransition.skipMiniPlayerTrackAnimation,
                        isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                        navBarTransparentWhenPlayerOpen = navBarTransparentWhenPlayerOpen,
                        toastViewModel = toastViewModel,
                        onNavZoneHeightChange = { navZoneHeightPx = it },
                        onMiniPlayerTopChange = { miniPlayerTopPx = it },
                        onMiniPlayerHeightChange = { miniPlayerBoxHeightPx = it }
                    )
                }
            }

            FoamGlobalModals(
                modalState = modalState,
                navController = navController,
                playerViewModel = playerViewModel,
                playlistsViewModel = playlistsViewModel,
                trackRadioViewModel = trackRadioViewModel,
                updateManager = updateManager,
                updateStartupRelease = updateStartupRelease,
                updateDownloadState = updateDownloadState,
                updateInstallFile = updateInstallFile,
                trackModalState = trackModalState,
                collectionModalState = collectionModalState,
                trackRadioState = trackRadioState,
                playlists = playlists,
                playlistCovers = playlistCovers,
                playlistIdsWithTrack = playlistIdsWithTrack,
                playlistIdsWithBatch = playlistIdsWithBatch,
                addTrackInsertPosition = addTrackInsertPosition,
                settings = playerSettings,
                scoreOverrides = scoreOverrides,
                existingGenres = libraryGenres,
                onNavigateClosingPlayer = { route, pattern -> navigateClosingPlayer(route, pattern) },
                onActiveSharedCollectionChange = { activeSharedCollectionTag.value = it },
                onOpenArtist = { artist ->
                    zoomOrigins.discardTouch()
                    activeSharedCollectionTag.value = sharedCollectionTag("artist", artist)
                    navController.navigate(Screen.ArtistDetail.createRoute(artist))
                },
                onOpenAlbum = { album, albumArtPath, artist ->
                    zoomOrigins.discardTouch()
                    activeSharedCollectionTag.value = sharedCollectionTag("album", album)
                    navController.navigate(Screen.AlbumDetail.createRoute(album, albumArtPath, artist))
                }
            )
        }
    }
}