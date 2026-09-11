package com.foamplayer.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.ui.components.MiniPlayer
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

/**
 * Single source of truth for nav-bar / mini-player geometry.
 *
 * Replaces the scattered literals this file used to have (a 72dp fake "content height",
 * a hardcoded `64.dp + 24.dp` docked backdrop extension, a `6f.dp` strip, 56dp/12dp/
 * 44dp/3.5f in the floating bar, and the TODO'd `navBarContentHeight - 12.dp - 12.dp`
 * mini-player offset). Values that depend on each other are derived here, so they can
 * never drift apart again.
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

            val isPlayerOpen = playerTransitionState.targetState

            // 0f..1f "how open is the player", read STRAIGHT off the SeekableTransitionState --
            // the same value the open/close animation and the finger drag run on.
            // No independent spring, so it follows the finger 1:1 and matches the tween when
            // opened programmatically. Deferred as a lambda: reading it only invalidates the
            // DRAW phase, never this scope.
            val playerOpenFraction = remember(playerTransitionState) {
                {
                    val openProgress =
                        if (playerTransitionState.currentState == playerTransitionState.targetState) {
                            if (playerTransitionState.currentState) 1f else 0f
                        } else {
                            if (playerTransitionState.targetState) playerTransitionState.fraction
                            else 1f - playerTransitionState.fraction
                        }
                    EaseInOutCubic.transform(openProgress.coerceIn(0f, 1f))
                }
            }

            // Extends the full player background fade into the bottom nav zone. All sizes
            // come from NavBarDimens -- the single source of truth for nav-bar geometry --
            // so this can never drift from the heights the bars themselves use.
            //
            // History: DOCKED used to hand the player a hardcoded inset + 64dp + 24dp so its
            // fade could reach behind a TRANSPARENT docked bar. Docked bars are solid now
            // (see FoamNavigationBar) and paint above the backdrop, so the fade only needs
            // the system inset plus a small frame-sync buffer; FLOATING/ATTACHED keep a
            // generous buffer because their pills cover only part of the zone.
            val navigationBarsBottomInsetPx = WindowInsets.navigationBars.getBottom(density)
            val playerBackdropBottomExtension = with(density) {
                NavBarDimens.playerBackdropBottomExtension(
                    mode = navigationBarMode,
                    bottomInset = navigationBarsBottomInsetPx.toDp()
                )
            }

            val handleNavBarNavigation: (String) -> Unit = { route ->
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
                                        activeTrackId = playbackState.currentTrack?.id
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
                                        activeTrackId = playbackState.currentTrack?.id
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
                            // Docked bars are always solid: they ignore
                            // navBarTransparentWhenPlayerOpen and never fade with the
                            // player, so no playerOpenFraction is passed at all. The bar
                            // draws ABOVE the player backdrop, so the fade can't cover it.
                            FoamNavigationBar(
                                currentRoute = currentRoute,
                                isPlayerOpen = isPlayerOpen,
                                onNavigate = handleNavBarNavigation,
                                connectToMiniPlayer = playbackState.currentTrack != null
                            )
                        }
                        NavigationBarMode.FLOATING -> Box(
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        ) {
                            FloatingNavigationBar(
                                currentRoute = currentRoute,
                                isPlayerOpen = isPlayerOpen,
                                onNavigate = handleNavBarNavigation,
                                attachedToMiniPlayer = false,
                                connectToMiniPlayer = playbackState.currentTrack != null,
                                playerOpenFraction = playerOpenFraction,
                                transitionAnimated = isTransitionAnimated,
                                navBarTransparentWhenPlayerOpen = navBarTransparentWhenPlayerOpen
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
                                attachedToMiniPlayer = true,
                                connectToMiniPlayer = playbackState.currentTrack != null,
                                playerOpenFraction = playerOpenFraction,
                                navBarTransparentWhenPlayerOpen = navBarTransparentWhenPlayerOpen
                            )
                        }
                    }
                }
            }

            // Mini player placement derives from NavBarDimens so it can never drift from
            // the bar geometry it sits on (this replaces the old TODO'd pile of literals;
            // values are unchanged: 72dp docked/floating, 48dp attached).
            val miniPlayerBottomPadding = NavBarDimens.miniPlayerBottomPadding(navigationBarMode)
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
    // The system's own bottom button/gesture bar sits in the inset directly below this
    // container -- the caller applies that inset as invisible navigationBarsPadding
    // outside this node (see FoamNavHost). Reading it here lets the fill extend all the
    // way through it, so the OS bar reads as part of this docked container instead of
    // showing whatever is drawn behind the whole screen (the album-art backdrop).
    val navBarBottomInsetPx = WindowInsets.navigationBars.getBottom(density)

    FoamNavigationItems(
        currentRoute = currentRoute,
        isPlayerOpen = isPlayerOpen,
        onNavigate = onNavigate,
        modifier = Modifier
            .fillMaxWidth()
            // Height locked BEFORE drawBehind (and before any insets are applied) so the
            // visible container is exactly DockedBarHeight no matter what the system-bar
            // insets or the player transition are doing. Insets are applied by the caller
            // as invisible padding outside this node (see FoamNavHost).
            .height(NavBarDimens.DockedBarHeight)
            // One opaque fill from the container's top down through the system inset.
            .drawBehind {
                drawRect(
                    color = surfaceColor,
                    size = androidx.compose.ui.geometry.Size(
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
private fun FloatingNavigationBar(
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

                    // Attached: the pill stretches upward to wrap the mini player card
                    // while the player is closed, following the drag 1:1; the stretch
                    // collapses to 0 by the time the player is fully open.
                    val extensionPx = if (attachedToMiniPlayer && connectToMiniPlayer) {
                        NavBarDimens.AttachedConnectorGrowFactor * radiusPx * (1f - playerOpenFraction())
                    } else 0f

                    drawRoundRect(
                        color = surfaceVariantColor,
                        topLeft = Offset(0f, -extensionPx),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height + extensionPx),
                        cornerRadius = CornerRadius(radiusPx),
                        alpha = greyAlpha
                    )
                }
                .padding(horizontal = NavBarDimens.RowHorizontalPadding)
        )
    }
}