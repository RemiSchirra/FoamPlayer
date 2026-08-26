package com.foamplayer.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.playlists.PlaylistDetailScreen
import com.foamplayer.app.ui.screens.playlists.PlaylistsScreen
import com.foamplayer.app.ui.screens.search.SearchScreen
import com.foamplayer.app.ui.screens.settings.AudioSettingsScreen
import com.foamplayer.app.ui.screens.settings.LibrarySettingsScreen
import com.foamplayer.app.ui.screens.settings.SettingsScreen
import com.foamplayer.app.ui.screens.settings.UISettingsScreen
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String, val title: String) {
    object Library : Screen("library", "Library")
    object Playlists : Screen("playlists", "Playlists")
    object Search : Screen("search", "Search")
    object Equalizer : Screen("equalizer", "Equalizer")
    object Settings : Screen("settings", "Settings")
    object SettingsAudio : Screen("settings/audio", "Audio Settings")
    object SettingsUI : Screen("settings/ui", "UI Settings")
    object SettingsLibrary : Screen("settings/library", "Library Settings")

    object ArtistDetail : Screen("artist/{artistName}", "Artist") {
        fun createRoute(artistName: String) = "artist/${URLEncoder.encode(artistName, StandardCharsets.UTF_8.toString())}"
    }
    object AlbumDetail : Screen("album/{albumName}", "Album") {
        fun createRoute(albumName: String) = "album/${URLEncoder.encode(albumName, StandardCharsets.UTF_8.toString())}"
    }
    object PlaylistDetail : Screen("playlist/{playlistId}", "Playlist") {
        fun createRoute(playlistId: Long) = "playlist/$playlistId"
    }
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

    val playerTransitionState = remember { SeekableTransitionState(false) }
    val scope = rememberCoroutineScope()
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

        val dynamicDuration = (800 * remainingFraction).toInt().coerceAtLeast(1)

        tween(durationMillis = dynamicDuration, easing = EaseOut)
    }

    SharedTransitionLayout {
        val transition: Transition<Boolean> = rememberTransition(playerTransitionState, label = "PlayerTransition")

        Column(modifier = modifier.fillMaxSize().background(FoamTheme.colors.background)) {
            Box(modifier = Modifier.weight(1f)) {
                // Content Area
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Library.route,
                            enterTransition = {
                                if (playerTransitionState.currentState) {
                                    EnterTransition.None
                                } else {
                                    fadeIn(animationSpec = tween(400))
                                }
                            },
                            exitTransition = {
                                if (playerTransitionState.currentState) {
                                    ExitTransition.None
                                } else {
                                    fadeOut(animationSpec = tween(400))
                                }
                            },
                            popEnterTransition = {
                                if (playerTransitionState.currentState) {
                                    EnterTransition.None
                                } else {
                                    fadeIn(animationSpec = tween(400))
                                }
                            },
                            popExitTransition = {
                                if (playerTransitionState.currentState) {
                                    ExitTransition.None
                                } else {
                                    fadeOut(animationSpec = tween(400))
                                }
                            }
                        ) {
                            composable(Screen.Library.route) {
                                LibraryScreen(
                                    viewModel = libraryViewModel,
                                    onTrackClick = { tracks, index ->
                                        playerViewModel.playQueue(tracks, index)
                                    }
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
                                        navController.navigate(Screen.AlbumDetail.createRoute(albumResult.album))
                                    },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this
                                )
                            }
                            composable(Screen.Equalizer.route) { EqualizerScreen() }
                            composable(Screen.Settings.route) {
                                SettingsScreen(
                                    onNavigateToAudio = { navController.navigate(Screen.SettingsAudio.route) },
                                    onNavigateToUI = { navController.navigate(Screen.SettingsUI.route) },
                                    onNavigateToLibrary = { navController.navigate(Screen.SettingsLibrary.route) }
                                )
                            }
                            composable(Screen.SettingsAudio.route) {
                                AudioSettingsScreen(onBack = { navController.popBackStack() })
                            }
                            composable(Screen.SettingsUI.route) {
                                UISettingsScreen(onBack = { navController.popBackStack() })
                            }
                            composable(Screen.SettingsLibrary.route) {
                                LibrarySettingsScreen(onBack = { navController.popBackStack() })
                            }
                            composable(
                                Screen.ArtistDetail.route,
                                arguments = listOf(navArgument("artistName") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val artistName = URLDecoder.decode(backStackEntry.arguments?.getString("artistName") ?: "", StandardCharsets.UTF_8.toString())
                                ArtistDetailScreen(
                                    artistName = artistName,
                                    onAlbumClick = { album ->
                                        navController.navigate(Screen.AlbumDetail.createRoute(album))
                                    },
                                    onBack = { navController.popBackStack() },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this
                                )
                            }
                            composable(
                                Screen.AlbumDetail.route,
                                arguments = listOf(navArgument("albumName") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val albumName = URLDecoder.decode(backStackEntry.arguments?.getString("albumName") ?: "", StandardCharsets.UTF_8.toString())
                                AlbumDetailScreen(
                                    albumName = albumName,
                                    onTrackClick = { tracks, index ->
                                        playerViewModel.playQueue(tracks, index)
                                    },
                                    onBack = { navController.popBackStack() },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this
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
                                        playerViewModel.playQueue(tracks, index)
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                    // Spacer for MiniPlayer
                    Box(modifier = Modifier.fillMaxWidth().height(80.dp))
                }

                // MiniPlayer - Positioned at the bottom of the main content area, above the Nav Bar
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    transition.AnimatedVisibility(
                        visible = { isFull -> !isFull },
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        if (playbackState.currentTrack != null) {
                            MiniPlayer(
                                playbackState = playbackState,
                                onPlayPauseClick = {
                                    if (playbackState.isPlaying) playerViewModel.pause() else playerViewModel.play()
                                },
                                onSeek = { progress ->
                                    playerViewModel.seekTo((progress * playbackState.durationMs).toLong())
                                },
                                onClick = {
                                    scope.launch { playerTransitionState.animateTo(true, getPlayerAnimationSpec(true)) }
                                },
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this@AnimatedVisibility
                            )
                        }
                    }
                }

                // FullPlayerScreen - Slides up over the content but stays above the Navigation Bar
                transition.AnimatedVisibility(
                    visible = { isFull -> isFull },
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    FullPlayerScreen(
                        viewModel = playerViewModel,
                        onDismiss = {
                            scope.launch { playerTransitionState.animateTo(false, getPlayerAnimationSpec(false)) }
                        },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedVisibility,
                        playerTransitionState = playerTransitionState
                    )
                }
            }

            // Navigation Bar - Always Visible
            FoamNavigationBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    scope.launch {
                        if (playerTransitionState.currentState || playerTransitionState.targetState) {
                            playerTransitionState.animateTo(false, getPlayerAnimationSpec(false))
                        }
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
                        else -> FoamTheme.icons.current.settings // Fallback
                    } as ImageVector,
                    contentDescription = screen.title,
                    modifier = Modifier.size(24.dp),
                    tint = if (selected) FoamTheme.colors.primaryAccent else FoamTheme.colors.textSecondary
                )
            }
        }
    }
}