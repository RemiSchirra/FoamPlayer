package com.foamplayer.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.LocalTrackFoamModalState
import com.foamplayer.app.ui.components.MiniPlayer
import com.foamplayer.app.ui.components.PlaylistPickerModal
import com.foamplayer.app.ui.components.TrackFoamModal
import com.foamplayer.app.ui.components.rememberTrackFoamModalState
import com.foamplayer.app.ui.screens.equalizer.EqualizerScreen
import com.foamplayer.app.ui.screens.library.AlbumDetailScreen
import com.foamplayer.app.ui.screens.library.ArtistDetailScreen
import com.foamplayer.app.ui.screens.library.LibraryScreen
import com.foamplayer.app.ui.screens.library.LibraryViewModel
import com.foamplayer.app.ui.screens.player.FullPlayerScreen
import com.foamplayer.app.ui.screens.player.DynamicAlbumArtBackground
import com.foamplayer.app.ui.screens.player.MiniPlayerSwipeController
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.playlists.PlaylistDetailScreen
import com.foamplayer.app.ui.screens.playlists.PlaylistsScreen
import com.foamplayer.app.ui.screens.playlists.PlaylistsViewModel
import com.foamplayer.app.ui.screens.search.SearchScreen
import com.foamplayer.app.ui.screens.settings.AudioSettingsScreen
import com.foamplayer.app.ui.screens.settings.BackgroundSettingsScreen
import com.foamplayer.app.ui.screens.settings.ColorsSettingsScreen
import com.foamplayer.app.ui.screens.settings.LibrarySettingsScreen
import com.foamplayer.app.ui.screens.settings.LyricsProviderSettingsScreen
import com.foamplayer.app.ui.screens.settings.LyricsSettingsScreen
import com.foamplayer.app.ui.screens.settings.NavBarSettingsScreen
import com.foamplayer.app.ui.screens.settings.PlayerSettingsScreen
import com.foamplayer.app.ui.screens.settings.SettingsScreen
import com.foamplayer.app.ui.screens.settings.TestSettingsScreen
import com.foamplayer.app.ui.screens.settings.UISettingsScreen
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val SharedElementTransitionDurationMs = 350
val SharedElementEasing = FastOutSlowInEasing

/** Longest a programmatic mini<->full player transition may take (scales with remaining distance). */
private const val PlayerTransitionMaxDurationMs = 500

/** Fade duration for non-shared-element screen-to-screen navigation. */
private const val NavScreenFadeDurationMs = 400

/** Settings subscreens slide by a fraction of their width when pushed/popped. */
private const val SettingsSlideDivisor = 3

private fun isSharedElementRoute(route: String?): Boolean {
    if (route == null) return false
    return route == Screen.Library.route ||
            route == Screen.Search.route ||
            route.startsWith("artist/") ||
            route.startsWith("album/")
}

private fun isSettingsRoute(route: String?): Boolean {
    return route != null && (route == Screen.Settings.route || route.startsWith("settings/"))
}

/** Detail screens whose list background is drawn full-bleed at the root. */
private fun isDetailListRoute(route: String?): Boolean =
    route == Screen.AlbumDetail.route || route == Screen.PlaylistDetail.route

@Composable
private fun rememberAnimatedBackground(
    enabled: Boolean,
    initialFrontArtPath: String?,
    blur: Dp,
    intensity: Float,
    modifier: Modifier = Modifier
): (frontArtPath: String?, backArtPath: String?, fraction: Float) -> Unit {
    var frontArtPath by remember { mutableStateOf(initialFrontArtPath) }
    var backArtPath by remember { mutableStateOf<String?>(null) }
    var crossfadeFraction by remember { mutableStateOf(0f) }

    if (enabled) {
        DynamicAlbumArtBackground(
            frontArtPath = frontArtPath,
            backArtPath = backArtPath,
            crossfadeFraction = crossfadeFraction,
            blur = blur,
            intensity = intensity,
            modifier = modifier,
        )
    }

    return remember {
        { front, back, fraction ->
            frontArtPath = front
            backArtPath = back
            crossfadeFraction = fraction
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FoamNavHost(
    modifier: Modifier = Modifier,
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val playlistsViewModel: PlaylistsViewModel = hiltViewModel()
    val playlists by playlistsViewModel.playlists.collectAsState()

    // Long-press track actions: the modal is hosted at the root and reached by
    // every TrackListItem through LocalTrackFoamModalState. The playlist button
    // opens the picker, which holds the pending track until a playlist is picked.
    val trackModalState = rememberTrackFoamModalState()
    var playlistPickerTrack by remember { mutableStateOf<TrackEntity?>(null) }

    val playbackState by playerViewModel.playbackState.collectAsState()
    val playerSettings by playerViewModel.settings.collectAsState()
    val isAnimateSeekBarEnabled = playerSettings.animateSeekBarEnabled
    val isAutoOpenPlayerEnabled = playerSettings.autoOpenPlayerEnabled
    val isDynamicBackgroundEnabled = playerSettings.dynamicBackgroundEnabled
    val navigationBarMode = playerSettings.navigationBarMode
    // Still honored by FLOATING/ATTACHED; the docked bar ignores it (always solid).
    val navBarTransparentWhenPlayerOpen = playerSettings.navBarTransparentWhenPlayerOpen

    // Initialize the transition state based on whether the player should be open immediately
    val initialOpenRequested = remember { playerViewModel.openPlayerRequested.value }
    val playerTransitionState = remember { SeekableTransitionState(initialOpenRequested) }

    LaunchedEffect(Unit) {
        if (initialOpenRequested) {
            playerViewModel.consumeOpenPlayerRequest()
        }
    }

    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    var trackListTransitionTrackId by remember { mutableStateOf<Long?>(null) }
    var skipMiniPlayerTrackAnimation by remember { mutableStateOf(false) }

    // Track whether the current or next transition should use animation.
    // If we start opened, we disable animation for the initial frame.
    var isTransitionAnimated by remember { mutableStateOf(!initialOpenRequested) }

    val getPlayerAnimationSpec: (Boolean) -> androidx.compose.animation.core.TweenSpec<Float> = { targetIsFull ->
        val isOpenProgress = if (playerTransitionState.currentState == playerTransitionState.targetState) {
            if (playerTransitionState.currentState) 1f else 0f
        } else {
            if (playerTransitionState.targetState) {
                playerTransitionState.fraction
            } else {
                1f - playerTransitionState.fraction
            }
        }
        val remainingFraction = (if (targetIsFull) 1f - isOpenProgress else isOpenProgress).coerceIn(0f, 1f)
        val dynamicDuration = (PlayerTransitionMaxDurationMs * remainingFraction).toInt().coerceAtLeast(1)
        tween(durationMillis = dynamicDuration, easing = EaseInOut)
    }

    class TransitionJobHolder {
        var job: Job? = null
        var generation: Int = 0
    }
    val transitionJobHolder = remember { TransitionJobHolder() }

    fun animatePlayerTransition(
        open: Boolean,
        sourceTrackId: Long? = null,
        precompose: Boolean = false,
        animated: Boolean = true
    ) {
        transitionJobHolder.job?.cancel()
        isTransitionAnimated = animated

        if (open) {
            trackListTransitionTrackId = sourceTrackId
        }
        val myGeneration = ++transitionJobHolder.generation
        transitionJobHolder.job = scope.launch {
            try {
                if (animated) {
                    if (precompose) {
                        withFrameNanos { }
                        playerTransitionState.seekTo(fraction = 0f, targetState = open)
                        withFrameNanos { }
                    }
                    playerTransitionState.animateTo(open, getPlayerAnimationSpec(open))
                } else {
                    playerTransitionState.seekTo(if (open) 1f else 0f, targetState = open)
                }
            } finally {
                if (open && transitionJobHolder.generation == myGeneration) {
                    trackListTransitionTrackId = null
                    skipMiniPlayerTrackAnimation = false
                }
            }
        }
    }

    val openPlayerRequested by playerViewModel.openPlayerRequested.collectAsState()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            animatePlayerTransition(open = true, animated = false)
            playerViewModel.consumeOpenPlayerRequest()
        }
    }

    val miniPlayerSwipeController = remember(playerTransitionState, screenHeightPx) {
        MiniPlayerSwipeController(
            playerTransitionState = playerTransitionState,
            scope = scope,
            dragThresholdPx = screenHeightPx.coerceAtLeast(1f),
            onOpen = {
                animatePlayerTransition(open = true)
            }
        )
    }

    SharedTransitionLayout {
        val transition: Transition<Boolean> = rememberTransition(playerTransitionState, label = "PlayerTransition")

        // Every TrackListItem in the graph opens the track actions modal through
        // this local, so no screen has to thread the state down itself.
        CompositionLocalProvider(LocalTrackFoamModalState provides trackModalState) {
            Box(modifier = modifier.fillMaxSize().background(FoamTheme.colors.background)) {

                // App-wide dynamic album-art backdrop
                val backgroundEnabled = isDynamicBackgroundEnabled && !isSettingsRoute(currentRoute)
                val onBackgroundTransition = if (backgroundEnabled) {
                    rememberAnimatedBackground(
                        enabled = true,
                        initialFrontArtPath = playbackState.currentTrack?.albumArtPath,
                        blur = playerSettings.backgroundBlur.dp,
                        intensity = playerSettings.dynamicBackgroundIntensity / 100f,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    remember { { _: String?, _: String?, _: Float -> } }
                }

                // Full-bleed backdrop for the detail list screens (album/playlist). Drawn here
                // at the root, so it covers the status bar and the nav zone by construction --
                // no overflow tricks that transition layers could clip. Its alpha tween runs in
                // lock-step with the NavHost's screen fade (shared-element routes fade in 350ms,
                // plain routes in 400ms), so the background fades with the list opening/closing.
                // [lastListBackdropArtPath] keeps the art alive while the alpha fades back out.
                var listBackdropArtPath by remember { mutableStateOf<String?>(null) }
                var lastListBackdropArtPath by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(currentRoute) {
                    if (!isDetailListRoute(currentRoute)) listBackdropArtPath = null
                }
                val onListBackdropArt = { path: String? ->
                    if (path != null) lastListBackdropArtPath = path
                    listBackdropArtPath = path
                }
                val listBackdropAlpha by animateFloatAsState(
                    targetValue = if (listBackdropArtPath != null) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (currentRoute == Screen.AlbumDetail.route) {
                            SharedElementTransitionDurationMs
                        } else {
                            NavScreenFadeDurationMs
                        }
                    ),
                    label = "listBackdropAlpha"
                )
                val listBackdropDisplayPath = listBackdropArtPath ?: lastListBackdropArtPath
                if (listBackdropDisplayPath != null && listBackdropAlpha > 0f) {
                    DynamicAlbumArtBackground(
                        frontArtPath = listBackdropDisplayPath,
                        backArtPath = null,
                        crossfadeFraction = 0f,
                        blur = playerSettings.backgroundBlur.dp,
                        intensity = playerSettings.dynamicBackgroundIntensity / 100f,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = listBackdropAlpha }
                    )
                }

                val isPlayerOpen = playerTransitionState.targetState

                // ---- Expandable nav-bar second row ------------------------------------------
                var isNavBarExpanded by remember { mutableStateOf(false) }
                val navBarExpandFraction by animateFloatAsState(
                    targetValue = if (isNavBarExpanded) 1f else 0f,
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                    label = "NavBarExpandFraction"
                )
                // The extra row is a browsing utility: fold it away whenever the full player opens.
                LaunchedEffect(isPlayerOpen) {
                    if (isPlayerOpen) isNavBarExpanded = false
                }
                // The second bar disappeared (user disabled its last item): fold the row away
                // and drop the "more" toggle, which is hidden while the second bar is empty.
                LaunchedEffect(playerSettings.secondaryNavBarItems) {
                    if (playerSettings.secondaryNavBarItems.isEmpty()) isNavBarExpanded = false
                }
                // Back button folds the extra row away before leaving the app.
                BackHandler(enabled = isNavBarExpanded) { isNavBarExpanded = false }

                // Navbar surface driver: a child of the same transition the mini player's
                // enter/exit fade runs on, with the identical fade spring. The SeekableTransitionState
                // seeks every child at the same playtime (fraction x total duration), so this value is
                // always equal to the mini player's fade alpha -- during finger drags and tweened
                // settles alike. Deferred as a lambda: reading it only invalidates the DRAW phase.
                val playerClosedFractionState = transition.animateFloat(
                    transitionSpec = { spring(stiffness = Spring.StiffnessMediumLow) },
                    label = "PlayerClosedFraction"
                ) { isFull -> if (isFull) 0f else 1f }
                val playerClosedFraction = { playerClosedFractionState.value }

                // Extends the full player background fade into the bottom nav zone. All sizes
                // come from NavBarDimens -- the single source of truth for nav-bar geometry --
                // so this can never drift from the heights the bars themselves use.
                val navigationBarsBottomInsetPx = WindowInsets.navigationBars.getBottom(density)
                val playerBackdropBottomExtension = with(density) {
                    NavBarDimens.playerBackdropBottomExtension(
                        mode = navigationBarMode,
                        bottomInset = navigationBarsBottomInsetPx.toDp()
                    )
                }

                val handleNavBarNavigation: (String) -> Unit = { route ->
                    // Navigating from the bar always folds the extra row away.
                    isNavBarExpanded = false
                    if (playerTransitionState.currentState || playerTransitionState.targetState) {
                        animatePlayerTransition(open = false)
                    }
                    if (currentRoute != route) {
                        // Animate back if we're a subscreen of [route],
                        // otherwise just open the desired screen.
                        if (!navController.popBackStack(route, inclusive = false)) {
                            navController.navigate(route) {
                                popUpTo(navController.graph.startDestinationId)
                                launchSingleTop = true
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    fun playTrackFromList(tracks: List<com.foamplayer.app.data.db.entities.TrackEntity>, index: Int) {
                        if (isAutoOpenPlayerEnabled) {
                            skipMiniPlayerTrackAnimation = true
                        }
                        playerViewModel.playQueue(tracks, index)
                        if (isAutoOpenPlayerEnabled) {
                            animatePlayerTransition(
                                open = true,
                                sourceTrackId = tracks.getOrNull(index)?.id,
                                precompose = true
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        transition.AnimatedVisibility(
                            visible = { isFull -> !isFull },
                            enter = if (isTransitionAnimated) fadeIn() else EnterTransition.None,
                            exit = if (isTransitionAnimated) fadeOut() else ExitTransition.None,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val playerTransitionVisibilityScope = this
                            // Screens keep their status-bar padding; the backdrop behind them
                            // (drawn at the root now) extends up under the status bar.
                            Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                                NavHost(
                                    modifier = Modifier.fillMaxSize(),
                                    navController = navController,
                                    startDestination = Screen.Library.route,
                                    enterTransition = {
                                        when {
                                            playerTransitionState.currentState -> EnterTransition.None
                                            isSettingsRoute(initialState.destination.route) &&
                                                    isSettingsRoute(targetState.destination.route) ->
                                                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween()) + fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                                            isSharedElementRoute(initialState.destination.route) ||
                                                    isSharedElementRoute(targetState.destination.route) ->
                                                fadeIn(animationSpec = tween(SharedElementTransitionDurationMs, easing = SharedElementEasing))
                                            else -> fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                                        }
                                    },
                                    exitTransition = {
                                        when {
                                            playerTransitionState.currentState -> ExitTransition.None
                                            isSettingsRoute(initialState.destination.route) &&
                                                    isSettingsRoute(targetState.destination.route) ->
                                                slideOutHorizontally(targetOffsetX = { -it / SettingsSlideDivisor }, animationSpec = tween()) + fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                                            isSharedElementRoute(initialState.destination.route) ||
                                                    isSharedElementRoute(targetState.destination.route) ->
                                                fadeOut(animationSpec = tween(SharedElementTransitionDurationMs, easing = SharedElementEasing))
                                            else -> fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                                        }
                                    },
                                    popEnterTransition = {
                                        when {
                                            playerTransitionState.currentState -> EnterTransition.None
                                            isSettingsRoute(initialState.destination.route) &&
                                                    isSettingsRoute(targetState.destination.route) ->
                                                slideInHorizontally(initialOffsetX = { -it / SettingsSlideDivisor }, animationSpec = tween()) + fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                                            isSharedElementRoute(initialState.destination.route) ||
                                                    isSharedElementRoute(targetState.destination.route) ->
                                                fadeIn(animationSpec = tween(SharedElementTransitionDurationMs, easing = SharedElementEasing))
                                            else -> fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                                        }
                                    },
                                    popExitTransition = {
                                        when {
                                            playerTransitionState.currentState -> ExitTransition.None
                                            isSettingsRoute(initialState.destination.route) &&
                                                    isSettingsRoute(targetState.destination.route) ->
                                                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween()) + fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                                            isSharedElementRoute(initialState.destination.route) ||
                                                    isSharedElementRoute(targetState.destination.route) ->
                                                fadeOut(animationSpec = tween(SharedElementTransitionDurationMs, easing = SharedElementEasing))
                                            else -> fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                                        }
                                    },
                                    sizeTransform = { SizeTransform(clip = false) }
                                ) {
                                    composable(Screen.Library.route) {
                                        LibraryScreen(
                                            viewModel = libraryViewModel,
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index)
                                            },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            activeTrackId = playbackState.currentTrack?.id
                                        )
                                    }
                                    composable(Screen.Playlists.route) {
                                        PlaylistsScreen(
                                            onPlaylistClick = { playlist ->
                                                navController.navigate(Screen.PlaylistDetail.createRoute(playlist.playlistId))
                                            }
                                        )
                                    }
                                    composable(Screen.Search.route) {
                                        SearchScreen(
                                            onArtistClick = { artist ->
                                                navController.navigate(Screen.ArtistDetail.createRoute(artist))
                                            },
                                            onAlbumClick = { albumResult ->
                                                navController.navigate(
                                                    Screen.AlbumDetail.createRoute(
                                                        albumResult.album,
                                                        albumResult.albumArtPath,
                                                        albumResult.artist
                                                    )
                                                )
                                            },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            activeTrackId = playbackState.currentTrack?.id,
                                            onTrackClick = { track -> playTrackFromList(listOf(track), 0) }
                                        )
                                    }
                                    composable(Screen.Equalizer.route) { EqualizerScreen() }
                                    composable(Screen.Settings.route) {
                                        SettingsScreen(
                                            onNavigateToAudio = { navController.navigate(Screen.SettingsAudio.route) },
                                            onNavigateToUI = { navController.navigate(Screen.SettingsUI.route) },
                                            onNavigateToLibrary = { navController.navigate(Screen.SettingsLibrary.route) },
                                            onNavigateToLyrics = { navController.navigate(Screen.SettingsLyrics.route) },
                                            onNavigateToTest = { navController.navigate(Screen.SettingsTest.route) }
                                        )
                                    }
                                    composable(Screen.SettingsAudio.route) {
                                        AudioSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsUI.route) {
                                        UISettingsScreen(
                                            onBack = { navController.popBackStack() },
                                            onNavigateToColors = { navController.navigate(Screen.SettingsColors.route) },
                                            onNavigateToPlayer = { navController.navigate(Screen.SettingsPlayer.route) },
                                            onNavigateToNavBar = { navController.navigate(Screen.SettingsNavBar.route) },
                                            onNavigateToBackground = { navController.navigate(Screen.SettingsBackground.route) },
                                        )
                                    }
                                    composable(Screen.SettingsColors.route) {
                                        ColorsSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsPlayer.route) {
                                        PlayerSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsNavBar.route) {
                                        NavBarSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsBackground.route) {
                                        BackgroundSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsLibrary.route) {
                                        LibrarySettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsLyrics.route) {
                                        LyricsSettingsScreen(
                                            onBack = { navController.popBackStack() },
                                            onNavigateToProviders = { navController.navigate(Screen.SettingsLyricsProvider.route) }
                                        )
                                    }
                                    composable(Screen.SettingsLyricsProvider.route) {
                                        LyricsProviderSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsTest.route) {
                                        TestSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(
                                        Screen.ArtistDetail.route,
                                        arguments = listOf(navArgument("artistName") { type = NavType.StringType })
                                    ) { backStackEntry ->
                                        val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
                                        ArtistDetailScreen(
                                            artistName = artistName,
                                            onAlbumClick = { album ->
                                                navController.navigate(
                                                    Screen.AlbumDetail.createRoute(
                                                        album.album,
                                                        album.albumArtPath,
                                                        album.artist
                                                    )
                                                )
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this
                                        )
                                    }
                                    composable(
                                        Screen.AlbumDetail.route,
                                        arguments = listOf(
                                            navArgument("albumName") { type = NavType.StringType },
                                            navArgument("artUrl") { type = NavType.StringType; defaultValue = "" },
                                            navArgument("artist") { type = NavType.StringType; defaultValue = "" }
                                        )
                                    ) { backStackEntry ->
                                        val albumName = backStackEntry.arguments?.getString("albumName") ?: ""
                                        val artUrl = (backStackEntry.arguments?.getString("artUrl") ?: "").ifEmpty { null }
                                        val artist = (backStackEntry.arguments?.getString("artist") ?: "").ifEmpty { null }
                                        AlbumDetailScreen(
                                            albumName = albumName,
                                            initialImageUrl = artUrl,
                                            initialArtistName = artist,
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index)
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            activeTrackId = playbackState.currentTrack?.id,
                                            onListBackdropArt = onListBackdropArt
                                        )
                                    }
                                    composable(
                                        Screen.PlaylistDetail.route,
                                        arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
                                    ) { backStackEntry ->
                                        val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: -1L
                                        PlaylistDetailScreen(
                                            playlistId = playlistId,
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index)
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            activeTrackId = playbackState.currentTrack?.id,
                                            onListBackdropArt = onListBackdropArt
                                        )
                                    }
                                }
                            }
                        }

                        transition.AnimatedVisibility(
                            visible = { isFull -> isFull },
                            enter = EnterTransition.None,
                            exit = ExitTransition.None
                        ) {
                            BackHandler { animatePlayerTransition(open = false) }
                            FullPlayerScreen(
                                viewModel = playerViewModel,
                                onDismiss = { animatePlayerTransition(open = false) },
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this@AnimatedVisibility,
                                playerTransitionState = playerTransitionState,
                                // Without this the backdrop's bottom extension silently stays 0.dp.
                                backdropBottomExtension = playerBackdropBottomExtension,
                                isAnimateSeekBarEnabled = isAnimateSeekBarEnabled
                            )
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        when (navigationBarMode) {
                            NavigationBarMode.DOCKED -> Box(
                                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                            ) {
                                FoamNavigationBar(
                                    currentRoute = currentRoute,
                                    isPlayerOpen = isPlayerOpen,
                                    onNavigate = handleNavBarNavigation,
                                    primaryItems = playerSettings.navBarPrimaryOrder,
                                    primaryEnabled = playerSettings.navBarPrimaryEnabled,
                                    secondaryItems = playerSettings.navBarSecondaryOrder,
                                    secondaryEnabled = playerSettings.navBarSecondaryEnabled,
                                    connectToMiniPlayer = playbackState.currentTrack != null,
                                    expandFraction = navBarExpandFraction,
                                    onToggleExpand = { isNavBarExpanded = !isNavBarExpanded }
                                )
                            }
                            NavigationBarMode.FLOATING -> Box(
                                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                            ) {
                                FloatingNavigationBar(
                                    currentRoute = currentRoute,
                                    isPlayerOpen = isPlayerOpen,
                                    onNavigate = handleNavBarNavigation,
                                    primaryItems = playerSettings.navBarPrimaryOrder,
                                    primaryEnabled = playerSettings.navBarPrimaryEnabled,
                                    secondaryItems = playerSettings.navBarSecondaryOrder,
                                    secondaryEnabled = playerSettings.navBarSecondaryEnabled,
                                    attachedToMiniPlayer = false,
                                    connectToMiniPlayer = playbackState.currentTrack != null,
                                    playerClosedFraction = playerClosedFraction,
                                    transitionAnimated = isTransitionAnimated,
                                    navBarTransparentWhenPlayerOpen = navBarTransparentWhenPlayerOpen,
                                    expandFraction = navBarExpandFraction,
                                    onToggleExpand = { isNavBarExpanded = !isNavBarExpanded }
                                )
                            }
                            NavigationBarMode.ATTACHED -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                            ) {
                                FloatingNavigationBar(
                                    currentRoute = currentRoute,
                                    isPlayerOpen = isPlayerOpen,
                                    onNavigate = handleNavBarNavigation,
                                    primaryItems = playerSettings.navBarPrimaryOrder,
                                    primaryEnabled = playerSettings.navBarPrimaryEnabled,
                                    secondaryItems = playerSettings.navBarSecondaryOrder,
                                    secondaryEnabled = playerSettings.navBarSecondaryEnabled,
                                    attachedToMiniPlayer = true,
                                    connectToMiniPlayer = playbackState.currentTrack != null,
                                    playerClosedFraction = playerClosedFraction,
                                    navBarTransparentWhenPlayerOpen = navBarTransparentWhenPlayerOpen,
                                    expandFraction = navBarExpandFraction,
                                    onToggleExpand = { isNavBarExpanded = !isNavBarExpanded }
                                )
                            }
                        }
                    }
                }

                // Mini player placement derives from NavBarDimens
                val miniPlayerBottomPadding = NavBarDimens.miniPlayerBottomPadding(
                    mode = navigationBarMode,
                    expandFraction = navBarExpandFraction
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = miniPlayerBottomPadding)
                ) {
                    transition.AnimatedVisibility(
                        visible = { isFull -> !isFull },
                        enter = if (isTransitionAnimated) fadeIn() else EnterTransition.None,
                        exit = if (isTransitionAnimated) fadeOut() else ExitTransition.None
                    ) {
                        val miniPlayerVisibilityScope = this
                        if (playbackState.currentTrack != null) {
                            MiniPlayer(
                                playbackState = playbackState,
                                positionMsFlow = playerViewModel.positionMs,
                                onPlayPauseClick = {
                                    if (playbackState.isPlaying) playerViewModel.pause() else playerViewModel.play()
                                },
                                onSeek = { progress ->
                                    playerViewModel.seekTo((progress * playbackState.durationMs).toLong())
                                },
                                onSkipToTrack = { index ->
                                    playerViewModel.skipToIndex(index)
                                },
                                onColorChange = { color ->
                                    playerViewModel.setDynamicAccentColor(color)
                                },
                                onBackgroundTransition = onBackgroundTransition,
                                onClick = {
                                    animatePlayerTransition(open = true, precompose = true)
                                },
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = miniPlayerVisibilityScope,
                                useSharedElements = trackListTransitionTrackId == null,
                                isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                                skipTrackChangeAnimation = skipMiniPlayerTrackAnimation,
                                modifier = Modifier.draggable(
                                    state = rememberDraggableState { delta -> miniPlayerSwipeController.onDrag(delta) },
                                    orientation = Orientation.Vertical,
                                    onDragStopped = { velocity -> miniPlayerSwipeController.onDragStopped(velocity) }
                                )
                            )
                        }
                    }
                }
            }

            // Long-press track actions. The modal and its playlist picker are
            // plain Dialogs, so their placement in the tree doesn't matter.
            TrackFoamModal(
                state = trackModalState,
                onPlayNext = playerViewModel::playNext,
                onAddToQueue = playerViewModel::addToQueue,
                onAddToPlaylist = { track -> playlistPickerTrack = track },
                onSeeArtist = { track ->
                    navController.navigate(Screen.ArtistDetail.createRoute(track.artist))
                },
                onSeeAlbum = { track ->
                    navController.navigate(
                        Screen.AlbumDetail.createRoute(track.album, track.albumArtPath, track.artist)
                    )
                },
                onDetails = { /* TODO: dedicated details sheet */ }
            )

            PlaylistPickerModal(
                track = playlistPickerTrack,
                playlists = playlists,
                onPick = { playlist ->
                    playlistPickerTrack?.let { track ->
                        playlistsViewModel.addTrackToPlaylist(playlist.playlistId, track.id)
                    }
                    playlistPickerTrack = null
                },
                onDismiss = { playlistPickerTrack = null }
            )
        }
    }
}
