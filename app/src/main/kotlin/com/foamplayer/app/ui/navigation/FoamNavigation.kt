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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.settings.BackgroundMode
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.CollectionKind
import com.foamplayer.app.ui.modals.LocalCollectionFoamModalState
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
import com.foamplayer.app.ui.screens.settings.lyricsmanager.LyricsDetailsViewModel
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

const val SharedElementTransitionDurationMs = 350
val SharedElementEasing = FastOutSlowInEasing

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

    // Lyrics details sheet: a separate Dialog stacked on the open track sheet.
    val lyricsDetailsViewModel: LyricsDetailsViewModel = hiltViewModel()
    val lyricsTrackDetails by lyricsDetailsViewModel.trackDetails.collectAsState()
    val isLyricsDetailsLoading by lyricsDetailsViewModel.isDetailsLoading.collectAsState()

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

    // Collection row clicked to start the current navigation; drives shared-element pairing.
    var activeSharedCollection by remember { mutableStateOf<String?>(null) }

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

    /** Navigates while closing the player, staging the flight after the nav swap. */
    fun navigateClosingPlayer(route: String, pattern: String) {
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

    SharedTransitionLayout {
        val transition: Transition<Boolean> = rememberTransition(playerTransitionState, label = "PlayerTransition")
        val navTransitions = remember(playerTransitionState) {
            buildFoamNavTransitions(playerTransitionState)
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

        CompositionLocalProvider(
            LocalTrackFoamModalState provides trackModalState,
            LocalCollectionFoamModalState provides collectionModalState,
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
            LocalActiveSharedCollection provides activeSharedCollection,
            LocalSearchFocusRequest provides searchFocusRequest,
            LocalBackgroundSettingsPreview provides backgroundSettingsPreview
        ) {
            Box(modifier = modifier.fillMaxSize().background(FoamTheme.colors.background)) {

                var navZoneHeightPx by remember { mutableStateOf(0) }
                val onBackgroundTransition = AppBackdrop(
                    currentRoute = currentRoute,
                    backgroundMode = backgroundMode,
                    settings = playerSettings,
                    preview = backgroundSettingsPreview,
                    playerViewModel = playerViewModel,
                    playerTargetOpen = playerTransitionState.targetState
                )

                // Full-bleed backdrop for the detail list screens (album /
                // playlist / artist), drawn at the root so it covers the status
                // bar and nav zone.
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

                val isPlayerOpen = playerTransitionState.targetState

                val miniPlayerPresence = remember {
                    Animatable(if (playerViewModel.hasCurrentTrack.value) 1f else 0f)
                }
                val isOnboardingRoute = currentRoute == Screen.Onboarding.route
                LaunchedEffect(hasCurrentTrack, isOnboardingRoute) {
                    when {
                        isOnboardingRoute -> miniPlayerPresence.snapTo(0f)
                        !hasCurrentTrack -> miniPlayerPresence.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(
                                durationMillis = SharedElementTransitionDurationMs,
                                easing = SharedElementEasing
                            )
                        )
                        playerTransitionState.targetState -> miniPlayerPresence.snapTo(1f)
                        else -> miniPlayerPresence.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = SharedElementTransitionDurationMs,
                                easing = SharedElementEasing
                            )
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
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
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
                    activeSharedCollection = null
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

                Box(modifier = Modifier.fillMaxSize()) {
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
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val playerTransitionVisibilityScope = this
                            Box(modifier = Modifier.fillMaxSize()) {
                                val navGraphContext = FoamNavGraphContext(
                                    navController = navController,
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
                                    onActiveSharedCollectionChange = { activeSharedCollection = it }
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
                                    activeSharedCollection = sharedCollectionTag("album", track.album)
                                    navigateClosingPlayer(
                                        Screen.AlbumDetail.createRoute(track.album, track.albumArtPath, track.artist),
                                        Screen.AlbumDetail.route
                                    )
                                },
                                onOpenArtist = { track ->
                                    activeSharedCollection = sharedCollectionTag("artist", track.artist)
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

                FoamBottomChrome(
                    currentRoute = currentRoute,
                    navigationBarMode = navigationBarMode,
                    transition = transition,
                    playerTransitionState = playerTransitionState,
                    isPlayerOpen = isPlayerOpen,
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
                    bottomBarClearance = bottomBarClearance,
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

            FoamGlobalModals(
                modalState = modalState,
                navController = navController,
                playerViewModel = playerViewModel,
                playlistsViewModel = playlistsViewModel,
                trackRadioViewModel = trackRadioViewModel,
                lyricsDetailsViewModel = lyricsDetailsViewModel,
                updateManager = updateManager,
                updateStartupRelease = updateStartupRelease,
                updateDownloadState = updateDownloadState,
                updateInstallFile = updateInstallFile,
                trackModalState = trackModalState,
                collectionModalState = collectionModalState,
                trackRadioState = trackRadioState,
                lyricsTrackDetails = lyricsTrackDetails,
                isLyricsDetailsLoading = isLyricsDetailsLoading,
                playlists = playlists,
                playlistCovers = playlistCovers,
                playlistIdsWithTrack = playlistIdsWithTrack,
                playlistIdsWithBatch = playlistIdsWithBatch,
                addTrackInsertPosition = addTrackInsertPosition,
                settings = playerSettings,
                scoreOverrides = scoreOverrides,
                existingGenres = libraryGenres,
                onNavigateClosingPlayer = { route, pattern -> navigateClosingPlayer(route, pattern) },
                onActiveSharedCollectionChange = { activeSharedCollection = it },
                onOpenArtist = { artist ->
                    activeSharedCollection = sharedCollectionTag("artist", artist)
                    navController.navigate(Screen.ArtistDetail.createRoute(artist))
                },
                onOpenAlbum = { album, albumArtPath, artist ->
                    activeSharedCollection = sharedCollectionTag("album", album)
                    navController.navigate(Screen.AlbumDetail.createRoute(album, albumArtPath, artist))
                }
            )
        }
    }
}
