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
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import com.foamplayer.app.ui.screens.settings.PlayerSettingsScreen
import com.foamplayer.app.ui.screens.settings.SettingsScreen
import com.foamplayer.app.ui.screens.settings.TestSettingsScreen
import com.foamplayer.app.ui.screens.settings.UISettingsScreen
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Search <-> ArtistDetail <-> AlbumDetail all hand off shared elements (album art,
// title, artist) between each other. The non-shared parts of the screen (background,
// buttons, list rows) still need their own fade-in/out so the transition doesn't feel
// abrupt — but that fade must run on the exact same duration/easing as the shared
// element's bounds animation (see AlbumArtBoundsTransform in TrackListScreen.kt), or the
// two race each other at different speeds and the mismatch reads as a flicker.
const val SharedElementTransitionDurationMs = 350
val SharedElementEasing = FastOutSlowInEasing

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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FoamNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val playerViewModel: PlayerViewModel = hiltViewModel()
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val playbackState by playerViewModel.playbackState.collectAsState()
    val playerSettings by playerViewModel.settings.collectAsState()
    val isAnimateSeekBarEnabled = playerSettings.animateSeekBarEnabled
    val isAutoOpenPlayerEnabled = playerSettings.autoOpenPlayerEnabled
    val isDynamicBackgroundEnabled = playerSettings.dynamicBackgroundEnabled

    val playerTransitionState = remember { SeekableTransitionState(false) }
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    var trackListTransitionTrackId by remember { mutableStateOf<Long?>(null) }
    var skipMiniPlayerTrackAnimation by remember { mutableStateOf(false) }
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

        val dynamicDuration = (500 * remainingFraction).toInt().coerceAtLeast(1)

        tween(durationMillis = dynamicDuration, easing = EaseInOut)
    }
    var backgroundFrontArtPath by remember { mutableStateOf(playbackState.currentTrack?.albumArtPath) }
    var backgroundBackArtPath by remember { mutableStateOf<String?>(null) }
    var backgroundCrossfadeFraction by remember { mutableStateOf(0f) }

    class TransitionJobHolder {
        var job: Job? = null
        var generation: Int = 0
    }
    val transitionJobHolder = remember { TransitionJobHolder() }

    fun animatePlayerTransition(open: Boolean, sourceTrackId: Long? = null, precompose: Boolean = false) {
        transitionJobHolder.job?.cancel()
        if (open) {
            trackListTransitionTrackId = sourceTrackId
        }
        val myGeneration = ++transitionJobHolder.generation
        transitionJobHolder.job = scope.launch {
            try {
                if (precompose) {
                    withFrameNanos { }
                    playerTransitionState.seekTo(fraction = 0f, targetState = open)
                    withFrameNanos { }
                }
                playerTransitionState.animateTo(open, getPlayerAnimationSpec(open))
            } finally {
                if (open && transitionJobHolder.generation == myGeneration) {
                    trackListTransitionTrackId = null
                    skipMiniPlayerTrackAnimation = false
                }
            }
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

        Column(modifier = modifier.fillMaxSize().background(FoamTheme.colors.background)) {
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
                if (isDynamicBackgroundEnabled && !isSettingsRoute(currentRoute)) {
                    DynamicAlbumArtBackground(
                        frontArtPath = backgroundFrontArtPath,
                        backArtPath = backgroundBackArtPath,
                        crossfadeFraction = backgroundCrossfadeFraction,
                        blur = playerSettings.backgroundBlur.dp,
                        intensity = playerSettings.dynamicBackgroundIntensity / 100f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                transition.AnimatedVisibility(
                    visible = { isFull -> !isFull },
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val playerTransitionVisibilityScope = this
                    Box(modifier = Modifier.fillMaxSize()) {
                        NavHost(
                            modifier = Modifier.fillMaxSize(),
                            navController = navController,
                            startDestination = Screen.Library.route,
                            enterTransition = {
                                when {
                                    playerTransitionState.currentState -> EnterTransition.None
                                    isSettingsRoute(initialState.destination.route) &&
                                            isSettingsRoute(targetState.destination.route) ->
                                        slideInHorizontally(initialOffsetX = { it }, animationSpec = tween()) + fadeIn(animationSpec = tween(400))
                                    isSharedElementRoute(initialState.destination.route) ||
                                            isSharedElementRoute(targetState.destination.route) ->
                                        fadeIn(animationSpec = tween(SharedElementTransitionDurationMs, easing = SharedElementEasing))
                                    else -> fadeIn(animationSpec = tween(400))
                                }
                            },
                            exitTransition = {
                                when {
                                    playerTransitionState.currentState -> ExitTransition.None
                                    isSettingsRoute(initialState.destination.route) &&
                                            isSettingsRoute(targetState.destination.route) ->
                                        slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween()) + fadeOut(animationSpec = tween(400))
                                    isSharedElementRoute(initialState.destination.route) ||
                                            isSharedElementRoute(targetState.destination.route) ->
                                        fadeOut(animationSpec = tween(SharedElementTransitionDurationMs, easing = SharedElementEasing))
                                    else -> fadeOut(animationSpec = tween(400))
                                }
                            },
                            popEnterTransition = {
                                when {
                                    playerTransitionState.currentState -> EnterTransition.None
                                    isSettingsRoute(initialState.destination.route) &&
                                            isSettingsRoute(targetState.destination.route) ->
                                        slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween()) + fadeIn(animationSpec = tween(400))
                                    isSharedElementRoute(initialState.destination.route) ||
                                            isSharedElementRoute(targetState.destination.route) ->
                                        fadeIn(animationSpec = tween(SharedElementTransitionDurationMs, easing = SharedElementEasing))
                                    else -> fadeIn(animationSpec = tween(400))
                                }
                            },
                            popExitTransition = {
                                when {
                                    playerTransitionState.currentState -> ExitTransition.None
                                    isSettingsRoute(initialState.destination.route) &&
                                            isSettingsRoute(targetState.destination.route) ->
                                        slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween()) + fadeOut(animationSpec = tween(400))
                                    isSharedElementRoute(initialState.destination.route) ||
                                            isSharedElementRoute(targetState.destination.route) ->
                                        fadeOut(animationSpec = tween(SharedElementTransitionDurationMs, easing = SharedElementEasing))
                                    else -> fadeOut(animationSpec = tween(400))
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
                                    onNavigateToBackground = { navController.navigate(Screen.SettingsBackground.route) },
                                )
                            }
                            composable(Screen.SettingsColors.route) {
                                ColorsSettingsScreen(onBack = { navController.popBackStack() })
                            }
                            composable(Screen.SettingsPlayer.route) {
                                PlayerSettingsScreen(onBack = { navController.popBackStack() })
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

                        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                            transition.AnimatedVisibility(
                                visible = { isFull -> !isFull },
                                enter = fadeIn(),
                                exit = fadeOut()
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
                                        onBackgroundTransition = { frontArtPath, backArtPath, fraction ->
                                            backgroundFrontArtPath = frontArtPath
                                            backgroundBackArtPath = backArtPath
                                            backgroundCrossfadeFraction = fraction
                                        },
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

                transition.AnimatedVisibility(
                    visible = { isFull -> isFull },
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    BackHandler {
                        animatePlayerTransition(open = false)
                    }
                    FullPlayerScreen(
                        viewModel = playerViewModel,
                        onDismiss = {
                            animatePlayerTransition(open = false)
                        },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedVisibility,
                        playerTransitionState = playerTransitionState,
                        isAnimateSeekBarEnabled = isAnimateSeekBarEnabled
                    )
                }

            }

            FoamNavigationBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    if (playerTransitionState.currentState || playerTransitionState.targetState) {
                        animatePlayerTransition(open = false)
                    }
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

@Composable
fun FoamNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    val items = listOf(
        Screen.Library,
        Screen.Playlists,
        Screen.Search,
        Screen.Equalizer,
        Screen.Settings
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(FoamTheme.colors.surface)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { screen ->
            val selected = currentRoute == screen.route || (screen == Screen.Settings && currentRoute?.startsWith("settings/") == true)
            Box(
                modifier = Modifier
                    .clickable { onNavigate(screen.route) }
                    .padding(8.dp),
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
                    modifier = Modifier.size(24.dp),
                    tint = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary
                )
            }
        }
    }
}
