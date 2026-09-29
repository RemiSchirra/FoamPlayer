package com.foamplayer.app.ui.navigation

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.settings.BackgroundMode
import com.foamplayer.app.ui.components.CollectionFoamModal
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.components.LocalCollectionFoamModalState
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.components.LocalTrackFoamModalState
import com.foamplayer.app.ui.components.MiniPlayer
import com.foamplayer.app.ui.components.PlaylistPickerModal
import com.foamplayer.app.ui.components.TrackFoamModal
import com.foamplayer.app.ui.components.rememberCollectionFoamModalState
import com.foamplayer.app.ui.components.rememberTrackFoamModalState
import com.foamplayer.app.ui.screens.equalizer.EqualizerScreen
import com.foamplayer.app.ui.screens.browse.AlbumsBrowseScreen
import com.foamplayer.app.ui.screens.browse.AllTracksBrowseScreen
import com.foamplayer.app.ui.screens.browse.ArtistsBrowseScreen
import com.foamplayer.app.ui.screens.browse.CollectionAlbumsScreen
import com.foamplayer.app.ui.screens.browse.CollectionArtistsScreen
import com.foamplayer.app.ui.screens.browse.FolderBrowseScreen
import com.foamplayer.app.ui.screens.browse.GenresBrowseScreen
import com.foamplayer.app.ui.screens.browse.GenreTracksScreen
import com.foamplayer.app.ui.screens.browse.YearTracksScreen
import com.foamplayer.app.ui.screens.browse.YearsBrowseScreen
import com.foamplayer.app.ui.screens.home.FavoriteArtistsSeeAllScreen
import com.foamplayer.app.ui.screens.home.HomeScreen
import com.foamplayer.app.ui.screens.home.HomeViewModel
import com.foamplayer.app.ui.screens.home.OldFavoritesSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecentTracksSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecommendedAlbumsSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecommendedPlaylistsSeeAllScreen
import com.foamplayer.app.ui.screens.onboarding.OnboardingScreen
import com.foamplayer.app.ui.screens.library.AlbumDetailScreen
import com.foamplayer.app.ui.screens.library.ArtistDetailScreen
import com.foamplayer.app.ui.screens.library.ArtistSongsScreen
import com.foamplayer.app.ui.screens.player.AlbumArtBackdrop
import com.foamplayer.app.ui.screens.player.CustomImageBackground
import com.foamplayer.app.ui.screens.player.DownSwipeCommitVelocityPxPerSec
import com.foamplayer.app.ui.screens.player.FullPlayerBackdropState
import com.foamplayer.app.ui.screens.player.FullPlayerScreen
import com.foamplayer.app.ui.screens.player.DynamicAlbumArtBackground
import com.foamplayer.app.ui.screens.player.GradientBackground
import com.foamplayer.app.ui.screens.player.MiniPlayerSwipeController
import com.foamplayer.app.ui.screens.player.PlayerDimens
import com.foamplayer.app.ui.screens.player.PlayerFlightGeometryState
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.player.backdropScrimColor
import com.foamplayer.app.ui.screens.player.progress
import com.foamplayer.app.ui.screens.playlists.PlaylistDetailScreen
import com.foamplayer.app.ui.screens.playlists.PlaylistsScreen
import com.foamplayer.app.ui.screens.playlists.PlaylistsViewModel
import com.foamplayer.app.ui.screens.stats.StatsScreen
import com.foamplayer.app.ui.screens.stats.StatsSeeAllScreen
import com.foamplayer.app.ui.screens.stats.StatsTopKind
import com.foamplayer.app.ui.screens.search.SearchScreen
import com.foamplayer.app.ui.screens.settings.AudioSettingsScreen
import com.foamplayer.app.ui.screens.settings.AudioDevicesSettingsScreen
import com.foamplayer.app.ui.screens.settings.BackgroundSettingsScreen
import com.foamplayer.app.ui.screens.settings.CollectionsSettingsScreen
import com.foamplayer.app.ui.screens.settings.ColorsSettingsScreen
import com.foamplayer.app.ui.screens.settings.LibrarySettingsScreen
import com.foamplayer.app.ui.screens.settings.LyricsSettingsScreen
import com.foamplayer.app.ui.screens.settings.MiscSettingsScreen
import com.foamplayer.app.ui.screens.settings.NavBarSettingsScreen
import com.foamplayer.app.ui.screens.settings.PlayerSettingsScreen
import com.foamplayer.app.ui.screens.settings.SettingsScreen
import com.foamplayer.app.ui.screens.settings.TestSettingsScreen
import com.foamplayer.app.ui.screens.settings.UISettingsScreen
import com.foamplayer.app.ui.screens.settings.lyricsmanager.LyricsDetailsViewModel
import com.foamplayer.app.ui.screens.settings.lyricsmanager.LyricsManagerScreen
import com.foamplayer.app.ui.screens.settings.lyricsmanager.LyricsSearchResultsScreen
import com.foamplayer.app.ui.screens.settings.lyricsmanager.LyricsTrackDetailsModal
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.components.TrackCenterRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

const val SharedElementTransitionDurationMs = 350
val SharedElementEasing = FastOutSlowInEasing

/** Longest a programmatic mini<->full player transition may take. */
private const val PlayerTransitionMaxDurationMs = 500

/**
 * Duration budget for a programmatic close flight (no finger on screen),
 * longer than a gesture close so it never reads as an instant snap.
 */
private const val AutoCloseFlightDurationMs = 700

/** Shortest a flick-committed close flight may take. */
private const val MinGestureCloseFlightMs = 240

/** Fade duration for non-shared-element screen-to-screen navigation. */
private const val NavScreenFadeDurationMs = 400

/** Settings and browse subscreens slide by a fraction of their width when pushed/popped. */
private const val SubScreenSlideDivisor = 3

private fun isSharedElementRoute(route: String?): Boolean {
    if (route == null) return false
    return route == Screen.Home.route ||
            route == Screen.Search.route ||
            route == Screen.Playlists.route ||
            route.startsWith("artist/") ||
            route.startsWith("album/") ||
            route.startsWith("playlist/")
}

private fun isSettingsRoute(route: String?): Boolean {
    return route != null && (route == Screen.Settings.route || route.startsWith("settings/"))
}

/** Stats overview and its see-all pages slide like the settings stack. */
private fun isStatsRoute(route: String?): Boolean =
    route != null && (route == Screen.Stats.route || route.startsWith("stats/"))

/** Browse list pages (plus their sub-pages) slide over the screen they leave. */
private fun isBrowseRoute(route: String?): Boolean = route != null && route.startsWith("browse/")

/** Detail screens whose list background is drawn full-bleed at the root. */
private fun isDetailListRoute(route: String?): Boolean =
    route == Screen.AlbumDetail.route ||
            route == Screen.PlaylistDetail.route ||
            route == Screen.ArtistDetail.route ||
            route == Screen.ArtistSongs.route

/**
 * True when the page on top of the back stack IS the collection the queue was
 * started from — the only case where a player dismiss can fly the artwork into
 * that page's playing row instead of the mini player.
 */
private fun isOriginCollectionPage(
    route: String?,
    arguments: Bundle?,
    activeCollectionId: String?
): Boolean {
    if (activeCollectionId == null || route == null) return false
    val routeCollectionId = when {
        route.startsWith("playlist/") -> CollectionId.playlist(arguments?.getLong("playlistId") ?: -1L)
        route.startsWith("browse/genre/") -> CollectionId.genre(arguments?.getString("genre").orEmpty())
        route.startsWith("browse/year/") -> CollectionId.year(arguments?.getInt("year") ?: 0)
        else -> null
    }
    return routeCollectionId == activeCollectionId
}

/** Art feeding the detail-list backdrop: the path plus how Coil must load it. */
private data class ListBackdropArt(val path: String, val isEmbeddedArt: Boolean)

/**
 * App-wide animated album-art backdrop. Returns a setter
 * (front, back, fraction) whose frames draw without recomposing the caller.
 */
@Composable
private fun rememberAnimatedBackground(
    enabled: Boolean,
    initialFrontArtPath: () -> String?,
    blur: Dp,
    intensity: Float,
    modifier: Modifier = Modifier
): (frontArtPath: String?, backArtPath: String?, fraction: Float) -> Unit {
    var frontArtPath by remember { mutableStateOf(initialFrontArtPath()) }
    var backArtPath by remember { mutableStateOf<String?>(null) }
    val crossfadeFraction = remember { mutableStateOf(0f) }

    if (enabled) {
        DynamicAlbumArtBackground(
            frontArtPath = frontArtPath,
            backArtPath = backArtPath,
            crossfadeFraction = { crossfadeFraction.value },
            blur = blur,
            intensity = intensity,
            modifier = modifier,
        )
    }

    return remember {
        { front, back, fraction ->
            frontArtPath = front
            backArtPath = back
            crossfadeFraction.value = fraction
        }
    }
}

/**
 * Root navigation host: every screen of the app, the mini/full player
 * transition (a [SeekableTransitionState] driven by both taps and drags),
 * shared-element artwork flights, the app-wide backdrop, the nav bar and the
 * global action modals.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FoamNavHost(
    modifier: Modifier = Modifier,
    startOnOnboarding: Boolean = false,
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val homeViewModel: HomeViewModel = hiltViewModel()
    val playlistsViewModel: PlaylistsViewModel = hiltViewModel()
    val playlists by playlistsViewModel.playlists.collectAsState()

    val trackModalState = rememberTrackFoamModalState()
    var playlistPickerTrack by remember { mutableStateOf<TrackEntity?>(null) }

    // Lyrics details sheet: a separate Dialog stacked on the open track sheet.
    var lyricsModalTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var lastLyricsModalTrack by remember { mutableStateOf<TrackEntity?>(null) }
    SideEffect {
        if (lyricsModalTrack != null) lastLyricsModalTrack = lyricsModalTrack
    }
    val lyricsDisplayTrack = lyricsModalTrack ?: lastLyricsModalTrack
    val lyricsDetailsViewModel: LyricsDetailsViewModel = hiltViewModel()
    val lyricsTrackDetails by lyricsDetailsViewModel.trackDetails.collectAsState()
    val isLyricsDetailsLoading by lyricsDetailsViewModel.isDetailsLoading.collectAsState()

    val pickerTrackId = playlistPickerTrack?.id ?: trackModalState.track?.id
    val playlistIdsWithTrack by remember(pickerTrackId) {
        playlistsViewModel.playlistIdsContainingTrack(pickerTrackId)
    }.collectAsState(initial = emptySet())

    val collectionModalState = rememberCollectionFoamModalState()

    // Narrow projections instead of the whole PlaybackState: collecting the full
    // state here would re-run this body on every playback emission.
    val activeCollectionId by playerViewModel.activeCollectionId.collectAsState()
    val hasCurrentTrack by playerViewModel.hasCurrentTrack.collectAsState()
    val activeTrackId by playerViewModel.activeTrackId.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val playerSettings by playerViewModel.settings.collectAsState()
    val scoreOverrides by playerViewModel.scoreOverrides.collectAsState()
    val isAnimateSeekBarEnabled = playerSettings.animateSeekBarEnabled
    val isAutoOpenPlayerEnabled = playerSettings.autoOpenPlayerEnabled
    val backgroundMode = playerSettings.backgroundMode
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

    // Collection row clicked to start the current navigation; drives shared-element pairing.
    var activeSharedCollection by remember { mutableStateOf<String?>(null) }

    // Dismiss-to-origin-collection session: a swipe-down over the playing track's
    // origin page flies the artwork into that page's playing row.
    var dismissToRowSessionActive by remember { mutableStateOf(false) }
    var centerTrackRequest by remember { mutableStateOf<TrackCenterRequest?>(null) }
    var centerRequestSeq by remember { mutableStateOf(0) }

    var miniPlayerTopPx by remember { mutableStateOf(Float.NaN) }
    var miniPlayerBoxHeightPx by remember { mutableStateOf(0) }

    // Bottom-bar zone geometry: content scrolls behind the bar and the card.
    val miniPlayerBoxHeight = with(density) {
        miniPlayerBoxHeightPx.takeIf { it > 0 }?.toDp()
            ?: (NavBarDimens.MiniPlayerCardHeight + NavBarDimens.MiniPlayerCardVerticalPadding * 2)
    }
    val bottomBarClearance = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                (if (hasCurrentTrack) {
                    NavBarDimens.miniPlayerBottomPadding(navigationBarMode, 0f) + miniPlayerBoxHeight
                } else {
                    NavBarDimens.navBarZoneHeight(navigationBarMode)
                }) +
                16.dp
    }
    val playerBottomPadding = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                NavBarDimens.navBarZoneHeight(navigationBarMode)
    }

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
        val targetY = centerTrackRequest?.rowArtCenterYPx?.takeUnless { it.isNaN() }
            ?: miniPlayerTopPx.takeUnless { it.isNaN() }?.let { it + miniArtCenterOffsetPx }
        val distance = if (heroY.isNaN() || targetY == null) {
            Float.NaN
        } else {
            abs(targetY - heroY)
        }
        if (distance.isNaN()) screenHeightPx else distance.coerceAtLeast(1f)
    }

    var isTransitionAnimated by remember { mutableStateOf(!initialOpenRequested) }

    /** Duration scaled to the transition's remaining distance; flicks get a floor. */
    val getPlayerAnimationSpec: (Boolean, Float) -> androidx.compose.animation.core.TweenSpec<Float> = { targetIsFull, gestureVelocityPxPerSec ->
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
        val maxDuration = if (!targetIsFull && gestureVelocityPxPerSec.isNaN()) {
            AutoCloseFlightDurationMs
        } else {
            PlayerTransitionMaxDurationMs
        }
        val dynamicDuration = (maxDuration * remainingFraction).toInt().coerceAtLeast(1)
        val duration = if (!gestureVelocityPxPerSec.isNaN() &&
            gestureVelocityPxPerSec >= DownSwipeCommitVelocityPxPerSec
        ) {
            dynamicDuration.coerceAtLeast(MinGestureCloseFlightMs)
        } else {
            dynamicDuration
        }
        tween(durationMillis = duration, easing = EaseInOut)
    }

    class TransitionJobHolder {
        var job: Job? = null
        var generation: Int = 0
    }
    val transitionJobHolder = remember { TransitionJobHolder() }

    /** Animates (or snaps) the player transition; superseding flights cancel prior ones. */
    fun animatePlayerTransition(
        open: Boolean,
        sourceTrackId: Long? = null,
        precompose: Boolean = false,
        animated: Boolean = true,
        gestureVelocityPxPerSec: Float = Float.NaN
    ) {
        transitionJobHolder.job?.cancel()
        isTransitionAnimated = animated

        if (open) {
            trackListTransitionTrackId = sourceTrackId
            dismissToRowSessionActive = false
            centerTrackRequest = null
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
                    playerTransitionState.animateTo(open, getPlayerAnimationSpec(open, gestureVelocityPxPerSec))
                } else {
                    playerTransitionState.seekTo(if (open) 1f else 0f, targetState = open)
                }
            } finally {
                if (transitionJobHolder.generation == myGeneration) {
                    if (open) {
                        trackListTransitionTrackId = null
                        skipMiniPlayerTrackAnimation = false
                    } else {
                        trackListTransitionTrackId = null
                        dismissToRowSessionActive = false
                        centerTrackRequest = null
                    }
                }
            }
        }
    }

    /**
     * PRESS PRELOAD: precomposes the returning page and scrolls the playing row
     * to the screen's middle before the finger moves. Deliberately does NOT tag
     * the row (a rest-state pairing would hijack the hero artwork).
     */
    suspend fun prepareDismissToRow() {
        val trackId = activeTrackId ?: return
        if (!isOriginCollectionPage(currentRoute, navBackStackEntry?.arguments, activeCollectionId)) {
            return
        }

        val request = TrackCenterRequest(trackId, ++centerRequestSeq)
        centerTrackRequest = request
        playerTransitionState.seekTo(fraction = 0f, targetState = false)

        withTimeoutOrNull(250) { request.scrolledIntoPlace.await() }
        withFrameNanos { }
        withFrameNanos { }
    }

    /**
     * DRAG ACTIVATION: tags the playing row's artwork so the shared element pairs, letting
     * a frame land before the drag moves so the flight's first frame is correct.
     */
    suspend fun activateDismissToRow() {
        val trackId = activeTrackId ?: return
        if (!isOriginCollectionPage(currentRoute, navBackStackEntry?.arguments, activeCollectionId)) {
            return
        }

        val request = centerTrackRequest
        if (request == null || request.trackId != trackId) {
            prepareDismissToRow()
        }

        dismissToRowSessionActive = true
        trackListTransitionTrackId = trackId
        withFrameNanos { }
        withFrameNanos { }
    }

    /** Swipe-down settled without committing: drop the row tag. */
    fun clearDismissToRowSession() {
        if (dismissToRowSessionActive) {
            dismissToRowSessionActive = false
            trackListTransitionTrackId = null
        }
        centerTrackRequest = null
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
            animatePlayerTransition(open = false, precompose = true)
        }
    }

    SharedTransitionLayout {
        val transition: Transition<Boolean> = rememberTransition(playerTransitionState, label = "PlayerTransition")

        CompositionLocalProvider(
            LocalTrackFoamModalState provides trackModalState,
            LocalCollectionFoamModalState provides collectionModalState,
            LocalBottomBarClearance provides bottomBarClearance,
            LocalCollectionHeroLayoutLookup provides heroLayoutFor,
            LocalActiveSharedCollection provides activeSharedCollection
        ) {
            Box(modifier = modifier.fillMaxSize().background(FoamTheme.colors.background)) {

                // App-wide dynamic album-art backdrop
                val backgroundEnabled = backgroundMode == BackgroundMode.DYNAMIC && !isSettingsRoute(currentRoute)
                val onBackgroundTransition = if (backgroundEnabled) {
                    rememberAnimatedBackground(
                        enabled = true,
                        initialFrontArtPath = { playerViewModel.playbackState.value.currentTrack?.albumArtPath },
                        blur = playerSettings.backgroundBlur.dp,
                        intensity = playerSettings.appBackgroundIntensity / 100f,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    remember { { _: String?, _: String?, _: Float -> } }
                }

                // The user-configured static layers (custom blurred image / gradient):
                // same dim as the dynamic art, hidden on settings routes like the art.
                // They are never suppressed on detail pages: the collection backdrop
                // below paints over them once opaque, and keeping them composed
                // underneath makes every fade crossfade over the custom image /
                // gradient instead of dropping to the bare background color.
                if (!isSettingsRoute(currentRoute) && backgroundMode != BackgroundMode.DYNAMIC) {
                    when (backgroundMode) {
                        BackgroundMode.CUSTOM -> CustomImageBackground(
                            imageUri = playerSettings.customBackgroundUri,
                            blur = playerSettings.backgroundBlur.dp,
                            intensity = playerSettings.appBackgroundIntensity / 100f,
                            modifier = Modifier.fillMaxSize()
                        )
                        BackgroundMode.GRADIENT -> GradientBackground(
                            stops = playerSettings.gradientStops,
                            intensity = playerSettings.appBackgroundIntensity / 100f,
                            modifier = Modifier.fillMaxSize()
                        )
                        BackgroundMode.DYNAMIC -> Unit
                    }
                }

                // Full-bleed backdrop for the detail list screens (album/playlist/artist),
                // drawn at the root so it covers the status bar and nav zone. Its alpha
                // tween runs in lock-step with the screens' fade; between two detail
                // routes the alpha never leaves 1f and the art switch is a front/back
                // crossfade instead. Art-driven: it applies in DYNAMIC mode, and in any
                // mode while "force dynamic background in collections" is on. The static
                // layers composed above stay underneath it, so its fades crossfade over
                // the custom image / gradient instead of the bare background color.
                var listBackdropArt by remember { mutableStateOf<ListBackdropArt?>(null) }
                var lastListBackdropArt by remember { mutableStateOf<ListBackdropArt?>(null) }
                LaunchedEffect(currentRoute) {
                    if (!isDetailListRoute(currentRoute)) listBackdropArt = null
                }
                var listCrossfadeFrom by remember { mutableStateOf<ListBackdropArt?>(null) }
                var listCrossfadeTo by remember { mutableStateOf<ListBackdropArt?>(null) }
                val listCrossfade = remember { Animatable(1f) }
                var listCrossfadeGeneration by remember { mutableStateOf(0) }
                val onListBackdropArt: (String?, Boolean) -> Unit = { path: String?, isEmbeddedArt: Boolean ->
                    val art = path?.let { ListBackdropArt(it, isEmbeddedArt) }
                    if (art != null) lastListBackdropArt = art
                    val previous = listBackdropArt
                    listBackdropArt = art
                    if (art != null && previous != null && previous != art) {
                        // Detail -> detail navigation: fade the new art in on the back layer.
                        val generation = ++listCrossfadeGeneration
                        scope.launch {
                            listCrossfadeFrom = previous
                            listCrossfadeTo = art
                            try {
                                listCrossfade.snapTo(0f)
                                listCrossfade.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = SharedElementTransitionDurationMs,
                                        easing = SharedElementEasing
                                    )
                                )
                            } finally {
                                if (generation == listCrossfadeGeneration) {
                                    listCrossfadeFrom = null
                                    listCrossfadeTo = null
                                }
                            }
                        }
                    } else {
                        listCrossfadeGeneration++
                        listCrossfadeFrom = null
                        listCrossfadeTo = null
                    }
                }
                val listBackdropAlpha by animateFloatAsState(
                    targetValue = if (listBackdropArt != null) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (isSharedElementRoute(currentRoute)) {
                            SharedElementTransitionDurationMs
                        } else {
                            NavScreenFadeDurationMs
                        }
                    ),
                    label = "listBackdropAlpha"
                )
                val listBackdropDisplay = listBackdropArt ?: lastListBackdropArt
                val showListBackdrop by remember(backgroundMode, forceDynamicBackgroundInCollections) {
                    derivedStateOf {
                        (backgroundMode == BackgroundMode.DYNAMIC || forceDynamicBackgroundInCollections) &&
                                (listBackdropArt ?: lastListBackdropArt) != null && listBackdropAlpha > 0f
                    }
                }
                if (showListBackdrop) {
                    DynamicAlbumArtBackground(
                        frontArtPath = (listCrossfadeFrom ?: listBackdropDisplay)?.path,
                        frontArtIsEmbedded = (listCrossfadeFrom ?: listBackdropDisplay)?.isEmbeddedArt ?: true,
                        backArtPath = listCrossfadeTo?.path,
                        backArtIsEmbedded = listCrossfadeTo?.isEmbeddedArt ?: true,
                        crossfadeFraction = { listCrossfade.value },
                        blur = playerSettings.backgroundBlur.dp,
                        intensity = playerSettings.appBackgroundIntensity / 100f,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = listBackdropAlpha }
                    )
                }

                // ---- Full player backdrop (root-hosted) --------------------------------------
                // In DOCKED mode the backdrop's black fade must land on the bar's top edge.
                var navZoneHeightPx by remember { mutableStateOf(0) }
                val playerBackdropFadeInset = if (navigationBarMode == NavigationBarMode.DOCKED) {
                    with(density) { navZoneHeightPx.toDp() }
                } else {
                    0.dp
                }

                // Hosted at the very root, cropping the same art with the same blur and
                // dim as the app-wide backdrop so the open/close crossfade is seamless.
                // forceDynamicBackgroundInPlayer overrides the CUSTOM/GRADIENT layers with
                // the classic art treatment ("force dynamic background in player").
                val fullPlayerBackdropState = remember { FullPlayerBackdropState() }
                val playerBackdropAlpha = {
                    EaseInOutCubic.transform(playerTransitionState.progress().coerceIn(0f, 1f))
                }
                if (playerTransitionState.currentState ||
                    playerTransitionState.targetState
                ) {
                    AlbumArtBackdrop(
                        frontArtPath = fullPlayerBackdropState.frontArtPath,
                        backArtPath = fullPlayerBackdropState.backArtPath,
                        crossfadeFraction = fullPlayerBackdropState.crossfadeFraction,
                        immersive = fullPlayerBackdropState.immersive,
                        subViewProgress = fullPlayerBackdropState.subViewBlurProgress,
                        backgroundMode = backgroundMode,
                        customBackgroundUri = playerSettings.customBackgroundUri,
                        gradientStops = playerSettings.gradientStops,
                        backgroundBlur = playerSettings.backgroundBlur.dp,
                        intensity = playerSettings.playerBackgroundIntensity / 100f,
                        bottomFadeInset = playerBackdropFadeInset,
                        forceDynamic = forceDynamicBackgroundInPlayer,
                        canvasUrl = fullPlayerBackdropState.canvasUrl,
                        canvasPlaying = fullPlayerBackdropState.canvasPlaying,
                        canvasSwipeFraction = fullPlayerBackdropState.canvasSwipeFraction,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = playerBackdropAlpha() }
                    )

                    // Subview scrim, drawn by the player's container. Theme-aware:
                    // lightens toward the background color in light mode.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = PlayerDimens.scrimMaxAlpha *
                                        fullPlayerBackdropState.subViewBlurProgress() *
                                        playerBackdropAlpha()
                            }
                            .background(backdropScrimColor())
                    )
                }

                val isPlayerOpen = playerTransitionState.targetState

                // ---- Mini player first-appearance animation ----------------------------------
                // The card's AnimatedVisibility enter only fires on a close flight; the
                // very first play lands at rest, so this presence fraction fades it in
                // (and grows the attached bar's surface extension) instead of popping.
                val miniPlayerPresence = remember {
                    Animatable(if (playerViewModel.hasCurrentTrack.value) 1f else 0f)
                }
                val isOnboardingRoute = currentRoute == Screen.Onboarding.route
                LaunchedEffect(hasCurrentTrack, isOnboardingRoute) {
                    when {
                        isOnboardingRoute || !hasCurrentTrack -> miniPlayerPresence.snapTo(0f)
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

                // ---- Expandable nav-bar second row ------------------------------------------
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
                    if (playerTransitionState.currentState || playerTransitionState.targetState) {
                        animatePlayerTransition(open = false)
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
                        tracks: List<com.foamplayer.app.data.db.entities.TrackEntity>,
                        index: Int,
                        collectionId: String? = null
                    ) {
                        if (isAutoOpenPlayerEnabled) {
                            skipMiniPlayerTrackAnimation = true
                        }
                        playerViewModel.playQueueReplacingUpcoming(tracks, index, collectionId)
                        if (isAutoOpenPlayerEnabled) {
                            animatePlayerTransition(
                                open = true,
                                sourceTrackId = tracks.getOrNull(index)?.id,
                                precompose = true
                            )
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        transition.AnimatedVisibility(
                            visible = { isFull -> !isFull },
                            enter = if (isTransitionAnimated) fadeIn() else EnterTransition.None,
                            exit = if (isTransitionAnimated) fadeOut() else ExitTransition.None,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val playerTransitionVisibilityScope = this
                            Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                                NavHost(
                                    modifier = Modifier.fillMaxSize(),
                                    navController = navController,
                                    startDestination = if (startOnOnboarding) {
                                        Screen.Onboarding.route
                                    } else {
                                        Screen.Home.route
                                    },
                                    enterTransition = {
                                        when {
                                            playerTransitionState.currentState -> EnterTransition.None
                                            isSettingsRoute(initialState.destination.route) &&
                                                    isSettingsRoute(targetState.destination.route) ->
                                                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween()) + fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                                            isStatsRoute(initialState.destination.route) &&
                                                    isStatsRoute(targetState.destination.route) ->
                                                slideInHorizontally(initialOffsetX = { it }, animationSpec = tween()) + fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                                            isBrowseRoute(targetState.destination.route) ->
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
                                                slideOutHorizontally(targetOffsetX = { -it / SubScreenSlideDivisor }, animationSpec = tween()) + fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                                            isStatsRoute(initialState.destination.route) &&
                                                    isStatsRoute(targetState.destination.route) ->
                                                slideOutHorizontally(targetOffsetX = { -it / SubScreenSlideDivisor }, animationSpec = tween()) + fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                                            isBrowseRoute(initialState.destination.route) &&
                                                    isBrowseRoute(targetState.destination.route) ->
                                                slideOutHorizontally(targetOffsetX = { -it / SubScreenSlideDivisor }, animationSpec = tween()) + fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
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
                                                slideInHorizontally(initialOffsetX = { -it / SubScreenSlideDivisor }, animationSpec = tween()) + fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                                            isStatsRoute(initialState.destination.route) &&
                                                    isStatsRoute(targetState.destination.route) ->
                                                slideInHorizontally(initialOffsetX = { -it / SubScreenSlideDivisor }, animationSpec = tween()) + fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
                                            isBrowseRoute(initialState.destination.route) &&
                                                    isBrowseRoute(targetState.destination.route) ->
                                                slideInHorizontally(initialOffsetX = { -it / SubScreenSlideDivisor }, animationSpec = tween()) + fadeIn(animationSpec = tween(NavScreenFadeDurationMs))
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
                                            isStatsRoute(initialState.destination.route) &&
                                                    isStatsRoute(targetState.destination.route) ->
                                                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween()) + fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                                            isBrowseRoute(initialState.destination.route) ->
                                                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween()) + fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                                            isSharedElementRoute(initialState.destination.route) ||
                                                    isSharedElementRoute(targetState.destination.route) ->
                                                fadeOut(animationSpec = tween(SharedElementTransitionDurationMs, easing = SharedElementEasing))
                                            else -> fadeOut(animationSpec = tween(NavScreenFadeDurationMs))
                                        }
                                    },
                                    sizeTransform = { SizeTransform(clip = false) }
                                ) {
                                    composable(Screen.Onboarding.route) {
                                        OnboardingScreen(
                                            onFinish = {
                                                navController.navigate(Screen.Home.route) {
                                                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                                                }
                                            }
                                        )
                                    }
                                    composable(Screen.Home.route) {
                                        HomeScreen(
                                            viewModel = homeViewModel,
                                            onPlaylistClick = { playlist ->
                                                activeSharedCollection = sharedCollectionTag("playlist", "${playlist.playlistId}")
                                                navController.navigate(
                                                    Screen.PlaylistDetail.createRoute(
                                                        playlist.playlistId,
                                                        playlist.name,
                                                        playlist.imageUrl
                                                    )
                                                )
                                            },
                                            onAlbumClick = { album ->
                                                activeSharedCollection = sharedCollectionTag("album", album.album)
                                                navController.navigate(
                                                    Screen.AlbumDetail.createRoute(
                                                        album.album,
                                                        album.albumArtPath,
                                                        album.artist
                                                    )
                                                )
                                            },
                                            onArtistClick = { artist, image ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(Screen.ArtistDetail.createRoute(artist, image))
                                            },
                                            onTrackClick = { track -> playTrackFromList(listOf(track), 0) },
                                            onSeeAllAlbums = { navController.navigate(Screen.HomeSeeAllAlbums.route) },
                                            onSeeAllRecent = { navController.navigate(Screen.HomeSeeAllRecentTracks.route) },
                                            onSeeAllFavorites = { navController.navigate(Screen.HomeSeeAllFavoriteTracks.route) },
                                            onSeeAllArtists = { navController.navigate(Screen.HomeSeeAllArtists.route) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId
                                        )
                                    }
                                    composable(Screen.HomeSeeAllPlaylists.route) {
                                        RecommendedPlaylistsSeeAllScreen(
                                            onPlaylistClick = { playlist ->
                                                activeSharedCollection = sharedCollectionTag("playlist", "${playlist.playlistId}")
                                                navController.navigate(
                                                    Screen.PlaylistDetail.createRoute(
                                                        playlist.playlistId,
                                                        playlist.name,
                                                        playlist.imageUrl
                                                    )
                                                )
                                            },
                                            onBack = { navController.popBackStack() },
                                            density = densityFor(MediaListView.HOME_SEE_ALL_PLAYLISTS),
                                            onDensityChange = { setDensity(MediaListView.HOME_SEE_ALL_PLAYLISTS, it) },
                                            sortMode = sortModeFor(MediaListView.HOME_SEE_ALL_PLAYLISTS),
                                            onSortChange = { setSortMode(MediaListView.HOME_SEE_ALL_PLAYLISTS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.HOME_SEE_ALL_PLAYLISTS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.HOME_SEE_ALL_PLAYLISTS, it) },
                                            viewModel = homeViewModel
                                        )
                                    }
                                    composable(Screen.HomeSeeAllAlbums.route) {
                                        RecommendedAlbumsSeeAllScreen(
                                            onAlbumClick = { album ->
                                                activeSharedCollection = sharedCollectionTag("album", album.album)
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
                                            animatedVisibilityScope = this,
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.HOME_SEE_ALL_ALBUMS),
                                            onDensityChange = { setDensity(MediaListView.HOME_SEE_ALL_ALBUMS, it) },
                                            sortMode = sortModeFor(MediaListView.HOME_SEE_ALL_ALBUMS),
                                            onSortChange = { setSortMode(MediaListView.HOME_SEE_ALL_ALBUMS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.HOME_SEE_ALL_ALBUMS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.HOME_SEE_ALL_ALBUMS, it) },
                                            viewModel = homeViewModel
                                        )
                                    }
                                    composable(Screen.HomeSeeAllRecentTracks.route) {
                                        RecentTracksSeeAllScreen(
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index)
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeTrackId = activeTrackId,
                                            density = densityFor(MediaListView.HOME_SEE_ALL_RECENT_TRACKS),
                                            onDensityChange = { setDensity(MediaListView.HOME_SEE_ALL_RECENT_TRACKS, it) },
                                            sortMode = sortModeFor(MediaListView.HOME_SEE_ALL_RECENT_TRACKS),
                                            onSortChange = { setSortMode(MediaListView.HOME_SEE_ALL_RECENT_TRACKS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.HOME_SEE_ALL_RECENT_TRACKS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.HOME_SEE_ALL_RECENT_TRACKS, it) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Default),
                                            viewModel = homeViewModel
                                        )
                                    }
                                    composable(Screen.HomeSeeAllFavoriteTracks.route) {
                                        OldFavoritesSeeAllScreen(
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index)
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeTrackId = activeTrackId,
                                            density = densityFor(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS),
                                            onDensityChange = { setDensity(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS, it) },
                                            sortMode = sortModeFor(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS),
                                            onSortChange = { setSortMode(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.HOME_SEE_ALL_FAVORITE_TRACKS, it) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Default),
                                            viewModel = homeViewModel
                                        )
                                    }
                                    composable(Screen.HomeSeeAllArtists.route) {
                                        FavoriteArtistsSeeAllScreen(
                                            onArtistClick = { artist, image ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(Screen.ArtistDetail.createRoute(artist, image))
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.HOME_SEE_ALL_ARTISTS),
                                            onDensityChange = { setDensity(MediaListView.HOME_SEE_ALL_ARTISTS, it) },
                                            sortMode = sortModeFor(MediaListView.HOME_SEE_ALL_ARTISTS),
                                            onSortChange = { setSortMode(MediaListView.HOME_SEE_ALL_ARTISTS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.HOME_SEE_ALL_ARTISTS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.HOME_SEE_ALL_ARTISTS, it) },
                                            viewModel = homeViewModel
                                        )
                                    }
                                    composable(Screen.Playlists.route) {
                                        PlaylistsScreen(
                                            onPlaylistClick = { playlist ->
                                                activeSharedCollection = sharedCollectionTag("playlist", "${playlist.playlistId}")
                                                navController.navigate(
                                                    Screen.PlaylistDetail.createRoute(
                                                        playlist.playlistId,
                                                        playlist.name,
                                                        playlist.imageUrl
                                                    )
                                                )
                                            },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.PLAYLISTS),
                                            onDensityChange = { setDensity(MediaListView.PLAYLISTS, it) },
                                            sortMode = sortModeFor(MediaListView.PLAYLISTS),
                                            onSortChange = { setSortMode(MediaListView.PLAYLISTS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.PLAYLISTS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.PLAYLISTS, it) }
                                        )
                                    }
                                    composable(Screen.Search.route) {
                                        SearchScreen(
                                            onArtistClick = { artist, image ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(Screen.ArtistDetail.createRoute(artist, image))
                                            },
                                            onAlbumClick = { albumResult ->
                                                activeSharedCollection = sharedCollectionTag("album", albumResult.album)
                                                navController.navigate(
                                                    Screen.AlbumDetail.createRoute(
                                                        albumResult.album,
                                                        albumResult.albumArtPath,
                                                        albumResult.artist
                                                    )
                                                )
                                            },
                                            onBrowseAllTracks = { navController.navigate(Screen.BrowseAllTracks.route) },
                                            onBrowseAlbums = { navController.navigate(Screen.BrowseAlbums.route) },
                                            onBrowseFolders = { navController.navigate(Screen.BrowseFolders.createRoute()) },
                                            onBrowseGenres = { navController.navigate(Screen.BrowseGenres.route) },
                                            onBrowseArtists = { navController.navigate(Screen.BrowseArtists.route) },
                                            onBrowseYears = { navController.navigate(Screen.BrowseYears.route) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            activeTrackId = activeTrackId,
                                            onTrackClick = { track -> playTrackFromList(listOf(track), 0) }
                                        )
                                    }
                                    composable(Screen.BrowseAlbums.route) {
                                        AlbumsBrowseScreen(
                                            onAlbumClick = { album ->
                                                activeSharedCollection = sharedCollectionTag("album", album.album)
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
                                            animatedVisibilityScope = this,
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.BROWSE_ALBUMS),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_ALBUMS, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_ALBUMS),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_ALBUMS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_ALBUMS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_ALBUMS, it) }
                                        )
                                    }
                                    composable(Screen.BrowseAllTracks.route) {
                                        AllTracksBrowseScreen(
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index)
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeTrackId = activeTrackId,
                                            density = densityFor(MediaListView.BROWSE_ALL_TRACKS),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_ALL_TRACKS, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_ALL_TRACKS),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_ALL_TRACKS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_ALL_TRACKS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_ALL_TRACKS, it) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Default)
                                        )
                                    }
                                    composable(Screen.BrowseArtists.route) {
                                        ArtistsBrowseScreen(
                                            onArtistClick = { artist, image ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(Screen.ArtistDetail.createRoute(artist, image))
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.BROWSE_ARTISTS),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_ARTISTS, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_ARTISTS),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_ARTISTS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_ARTISTS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_ARTISTS, it) }
                                        )
                                    }
                                    composable(Screen.BrowseGenres.route) {
                                        GenresBrowseScreen(
                                            onGenreClick = { genre ->
                                                navController.navigate(Screen.BrowseGenreDetail.createRoute(genre))
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.BROWSE_GENRES),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_GENRES, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_GENRES),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_GENRES, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_GENRES),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_GENRES, it) }
                                        )
                                    }
                                    composable(Screen.BrowseYears.route) {
                                        YearsBrowseScreen(
                                            onYearClick = { year ->
                                                navController.navigate(Screen.BrowseYearDetail.createRoute(year))
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.BROWSE_YEARS),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_YEARS, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_YEARS),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_YEARS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_YEARS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_YEARS, it) }
                                        )
                                    }
                                    composable(
                                        Screen.BrowseGenreDetail.route,
                                        arguments = listOf(navArgument("genre") { type = NavType.StringType })
                                    ) { backStackEntry ->
                                        val genre = backStackEntry.arguments?.getString("genre") ?: ""
                                        GenreTracksScreen(
                                            genre = genre,
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index, CollectionId.genre(genre))
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeTrackId = activeTrackId,
                                            density = densityFor(MediaListView.BROWSE_GENRE_TRACKS),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_GENRE_TRACKS, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_GENRE_TRACKS),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_GENRE_TRACKS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_GENRE_TRACKS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_GENRE_TRACKS, it) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            centerTrackRequest = centerTrackRequest,
                                            isPlaying = isPlaying,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Default),
                                            immersiveMode = playerSettings.immersiveModeEnabled
                                        )
                                    }
                                    composable(
                                        Screen.BrowseYearDetail.route,
                                        arguments = listOf(navArgument("year") { type = NavType.IntType })
                                    ) { backStackEntry ->
                                        val year = backStackEntry.arguments?.getInt("year") ?: 0
                                        YearTracksScreen(
                                            year = year,
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index, CollectionId.year(year))
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeTrackId = activeTrackId,
                                            density = densityFor(MediaListView.BROWSE_YEAR_TRACKS),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_YEAR_TRACKS, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_YEAR_TRACKS),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_YEAR_TRACKS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_YEAR_TRACKS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_YEAR_TRACKS, it) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            centerTrackRequest = centerTrackRequest,
                                            isPlaying = isPlaying,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Default),
                                            immersiveMode = playerSettings.immersiveModeEnabled
                                        )
                                    }
                                    composable(
                                        Screen.BrowseArtistsIn.route,
                                        arguments = listOf(
                                            navArgument("kind") { type = NavType.StringType },
                                            navArgument("key") { type = NavType.StringType }
                                        )
                                    ) { backStackEntry ->
                                        CollectionArtistsScreen(
                                            kind = backStackEntry.arguments?.getString("kind").orEmpty(),
                                            key = backStackEntry.arguments?.getString("key").orEmpty(),
                                            onArtistClick = { artist, image ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(Screen.ArtistDetail.createRoute(artist, image))
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.BROWSE_ARTISTS_IN),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_ARTISTS_IN, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_ARTISTS_IN),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_ARTISTS_IN, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_ARTISTS_IN),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_ARTISTS_IN, it) }
                                        )
                                    }
                                    composable(
                                        Screen.BrowseAlbumsIn.route,
                                        arguments = listOf(
                                            navArgument("kind") { type = NavType.StringType },
                                            navArgument("key") { type = NavType.StringType }
                                        )
                                    ) { backStackEntry ->
                                        CollectionAlbumsScreen(
                                            kind = backStackEntry.arguments?.getString("kind").orEmpty(),
                                            key = backStackEntry.arguments?.getString("key").orEmpty(),
                                            onAlbumClick = { entry ->
                                                activeSharedCollection = sharedCollectionTag("album", entry.album)
                                                navController.navigate(
                                                    Screen.AlbumDetail.createRoute(
                                                        entry.album,
                                                        entry.albumArtPath,
                                                        entry.artist
                                                    )
                                                )
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.BROWSE_ALBUMS_IN),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_ALBUMS_IN, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_ALBUMS_IN),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_ALBUMS_IN, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_ALBUMS_IN),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_ALBUMS_IN, it) }
                                        )
                                    }
                                    composable(
                                        Screen.BrowseFolders.route,
                                        arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" })
                                    ) { backStackEntry ->
                                        val path = (backStackEntry.arguments?.getString("path") ?: "").ifEmpty { null }
                                        FolderBrowseScreen(
                                            path = path,
                                            onFolderClick = { folderPath ->
                                                navController.navigate(Screen.BrowseFolders.createRoute(folderPath))
                                            },
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index, path?.let { CollectionId.folder(it) })
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeTrackId = activeTrackId,
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.BROWSE_FOLDERS),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_FOLDERS, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_FOLDERS),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_FOLDERS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_FOLDERS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_FOLDERS, it) }
                                        )
                                    }
                                    composable(Screen.Equalizer.route) {
                                        EqualizerScreen()
                                    }
                                    composable(Screen.Stats.route) {
                                        StatsScreen(
                                            onArtistClick = { artistName, image ->
                                                activeSharedCollection = sharedCollectionTag("artist", artistName)
                                                navController.navigate(Screen.ArtistDetail.createRoute(artistName, image))
                                            },
                                            onAlbumClick = { album, artUrl, artist ->
                                                activeSharedCollection = sharedCollectionTag("album", album)
                                                navController.navigate(
                                                    Screen.AlbumDetail.createRoute(album, artUrl, artist)
                                                )
                                            },
                                            onSeeAll = { kind, period ->
                                                navController.navigate(
                                                    Screen.StatsSeeAll.createRoute(kind.name, period.name)
                                                )
                                            },
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index)
                                            }
                                        )
                                    }
                                    composable(Screen.StatsSeeAll.route) { backStackEntry ->
                                        val kind = backStackEntry.arguments?.getString("kind")
                                            ?.let { name -> StatsTopKind.entries.find { it.name == name } }
                                            ?: StatsTopKind.TRACKS
                                        val view = when (kind) {
                                            StatsTopKind.TRACKS -> MediaListView.STATS_SEE_ALL_TRACKS
                                            StatsTopKind.ARTISTS -> MediaListView.STATS_SEE_ALL_ARTISTS
                                            StatsTopKind.ALBUMS -> MediaListView.STATS_SEE_ALL_ALBUMS
                                        }
                                        StatsSeeAllScreen(
                                            onBack = { navController.popBackStack() },
                                            onArtistClick = { artistName, image ->
                                                activeSharedCollection = sharedCollectionTag("artist", artistName)
                                                navController.navigate(Screen.ArtistDetail.createRoute(artistName, image))
                                            },
                                            onAlbumClick = { album, artUrl, artist ->
                                                activeSharedCollection = sharedCollectionTag("album", album)
                                                navController.navigate(
                                                    Screen.AlbumDetail.createRoute(album, artUrl, artist)
                                                )
                                            },
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index)
                                            },
                                            activeTrackId = activeTrackId,
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(view),
                                            onDensityChange = { setDensity(view, it) },
                                            sortMode = sortModeFor(view),
                                            onSortChange = { setSortMode(view, it) },
                                            sortInverted = sortInvertedFor(view),
                                            onSortInvertedChange = { setSortInverted(view, it) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Default)
                                        )
                                    }
                                    composable(Screen.Settings.route) {
                                        SettingsScreen(
                                            onNavigateToAudio = { navController.navigate(Screen.SettingsAudio.route) },
                                            onNavigateToUI = { navController.navigate(Screen.SettingsUI.route) },
                                            onNavigateToLibrary = { navController.navigate(Screen.SettingsLibrary.route) },
                                            onNavigateToLyrics = { navController.navigate(Screen.SettingsLyrics.route) },
                                            onNavigateToMisc = { navController.navigate(Screen.SettingsMisc.route) },
                                            onNavigateToTest = { navController.navigate(Screen.SettingsTest.route) }
                                        )
                                    }
                                    composable(Screen.SettingsAudio.route) {
                                        AudioSettingsScreen(
                                            onBack = { navController.popBackStack() },
                                            onNavigateToDevices = { navController.navigate(Screen.SettingsAudioDevices.route) }
                                        )
                                    }
                                    composable(Screen.SettingsAudioDevices.route) {
                                        AudioDevicesSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsUI.route) {
                                        UISettingsScreen(
                                            onBack = { navController.popBackStack() },
                                            onNavigateToColors = { navController.navigate(Screen.SettingsColors.route) },
                                            onNavigateToPlayer = { navController.navigate(Screen.SettingsPlayer.route) },
                                            onNavigateToNavBar = { navController.navigate(Screen.SettingsNavBar.route) },
                                            onNavigateToBackground = { navController.navigate(Screen.SettingsBackground.route) },
                                            onNavigateToCollections = { navController.navigate(Screen.SettingsUICollections.route) },
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
                                    composable(Screen.SettingsUICollections.route) {
                                        CollectionsSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsLyricsManager.route) {
                                        LyricsManagerScreen(
                                            onBack = { navController.popBackStack() },
                                            onOpenResults = { trackId ->
                                                navController.navigate(Screen.SettingsLyricsManagerResults.createRoute(trackId))
                                            }
                                        )
                                    }
                                    composable(
                                        Screen.SettingsLyricsManagerResults.route,
                                        arguments = listOf(navArgument("trackId") { type = NavType.LongType })
                                    ) { backStackEntry ->
                                        val trackId = backStackEntry.arguments?.getLong("trackId") ?: -1L
                                        LyricsSearchResultsScreen(
                                            trackId = trackId,
                                            onBack = { navController.popBackStack() }
                                        )
                                    }
                                    composable(Screen.SettingsLibrary.route) {
                                        LibrarySettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsLyrics.route) {
                                        LyricsSettingsScreen(
                                            onBack = { navController.popBackStack() },
                                            onNavigateToLyricsManager = {
                                                navController.navigate(Screen.SettingsLyricsManager.route)
                                            }
                                        )
                                    }
                                    composable(Screen.SettingsMisc.route) {
                                        MiscSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsTest.route) {
                                        TestSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(
                                        Screen.ArtistDetail.route,
                                        arguments = listOf(
                                            navArgument("artistName") { type = NavType.StringType },
                                            navArgument("image") { type = NavType.StringType; defaultValue = "" }
                                        )
                                    ) { backStackEntry ->
                                        val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
                                        val seedImageUrl = backStackEntry.arguments?.getString("image")
                                            ?.takeIf { it.isNotBlank() }
                                        ArtistDetailScreen(
                                            artistName = artistName,
                                            seedImageUrl = seedImageUrl,
                                            onAlbumClick = { album ->
                                                activeSharedCollection = sharedCollectionTag("album", album.album)
                                                navController.navigate(
                                                    Screen.AlbumDetail.createRoute(
                                                        album.album,
                                                        album.albumArtPath,
                                                        album.artist
                                                    )
                                                )
                                            },
                                            onAllSongsClick = {
                                                navController.navigate(Screen.ArtistSongs.createRoute(artistName))
                                            },
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index, CollectionId.artist(artistName))
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            activeCollectionId = activeCollectionId,
                                            activeTrackId = activeTrackId,
                                            onListBackdropArt = onListBackdropArt,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Artist)
                                        )
                                    }
                                    composable(
                                        Screen.ArtistSongs.route,
                                        arguments = listOf(navArgument("artistName") { type = NavType.StringType })
                                    ) { backStackEntry ->
                                        val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
                                        ArtistSongsScreen(
                                            artistName = artistName,
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index, CollectionId.artist(artistName))
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            activeTrackId = activeTrackId,
                                            onListBackdropArt = onListBackdropArt,
                                            centerTrackRequest = centerTrackRequest,
                                            isPlaying = isPlaying,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Artist),
                                            immersiveMode = playerSettings.immersiveModeEnabled
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
                                                playTrackFromList(tracks, index, CollectionId.album(albumName))
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            activeTrackId = activeTrackId,
                                            onListBackdropArt = onListBackdropArt,
                                            centerTrackRequest = centerTrackRequest,
                                            isPlaying = isPlaying,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Album),
                                            immersiveMode = playerSettings.immersiveModeEnabled
                                        )
                                    }
                                    composable(
                                        Screen.PlaylistDetail.route,
                                        arguments = listOf(
                                            navArgument("playlistId") { type = NavType.LongType },
                                            navArgument("name") { type = NavType.StringType; defaultValue = "" },
                                            navArgument("imageUrl") { type = NavType.StringType; defaultValue = "" }
                                        )
                                    ) { backStackEntry ->
                                        val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: -1L
                                        val initialName = backStackEntry.arguments?.getString("name").orEmpty()
                                        val initialImageUrl = backStackEntry.arguments?.getString("imageUrl")?.ifEmpty { null }
                                        PlaylistDetailScreen(
                                            playlistId = playlistId,
                                            initialName = initialName,
                                            initialImageUrl = initialImageUrl,
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index, CollectionId.playlist(playlistId))
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            activeTrackId = activeTrackId,
                                            onListBackdropArt = onListBackdropArt,
                                            density = densityFor(MediaListView.PLAYLIST_DETAIL),
                                            onDensityChange = { setDensity(MediaListView.PLAYLIST_DETAIL, it) },
                                            sortMode = sortModeFor(MediaListView.PLAYLIST_DETAIL),
                                            onSortChange = { setSortMode(MediaListView.PLAYLIST_DETAIL, it) },
                                            sortInverted = sortInvertedFor(MediaListView.PLAYLIST_DETAIL),
                                            onSortInvertedChange = { setSortInverted(MediaListView.PLAYLIST_DETAIL, it) },
                                            centerTrackRequest = centerTrackRequest,
                                            isPlaying = isPlaying,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Playlist),
                                            immersiveMode = playerSettings.immersiveModeEnabled
                                        )
                                    }
                                }
                            }
                        }

                        transition.AnimatedVisibility(
                            visible = { isFull -> isFull },
                            enter = EnterTransition.None,
                            exit = ExitTransition.None,
                            modifier = Modifier.fillMaxSize().padding(bottom = playerBottomPadding)
                        ) {
                            BackHandler { animatePlayerTransition(open = false) }
                            FullPlayerScreen(
                                viewModel = playerViewModel,
                                onDismiss = { velocity ->
                                    animatePlayerTransition(
                                        open = false,
                                        gestureVelocityPxPerSec = velocity
                                    )
                                },
                                onDismissGestureStart = { prepareDismissToRow() },
                                onDismissGestureActivate = { activateDismissToRow() },
                                onDismissGestureCancelled = { clearDismissToRowSession() },
                                dismissSwipeDistancePx = dismissSwipeDistancePx,
                                flightGeometry = flightGeometry,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = this@AnimatedVisibility,
                                playerTransitionState = playerTransitionState,
                                backdropState = fullPlayerBackdropState,
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
                                }
                            )
                        }
                    }
                }

                // Nav bar + mini player overlay, hidden on onboarding.
                if (currentRoute != Screen.Onboarding.route) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onSizeChanged { navZoneHeightPx = it.height }
                    ) {
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
                                    connectToMiniPlayer = hasCurrentTrack,
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
                                    connectToMiniPlayer = hasCurrentTrack,
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
                                    connectToMiniPlayer = hasCurrentTrack,
                                    miniPlayerTopPx = { miniPlayerTopPx.takeUnless { it.isNaN() } },
                                    playerClosedFraction = playerClosedFraction,
                                    cardPresenceFraction = { miniPlayerPresence.value },
                                    navBarTransparentWhenPlayerOpen = navBarTransparentWhenPlayerOpen,
                                    expandFraction = navBarExpandFraction,
                                    onToggleExpand = { isNavBarExpanded = !isNavBarExpanded }
                                )
                            }
                        }
                    }
                }

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
                        enter = if (isTransitionAnimated) {
                            if (trackListTransitionTrackId != null) {
                                // The artwork is morphing into a collection row instead
                                // of the mini player: fade + slide up from the bottom.
                                fadeIn(tween(SharedElementTransitionDurationMs, easing = SharedElementEasing)) +
                                        slideInVertically(tween(SharedElementTransitionDurationMs, easing = SharedElementEasing)) { it / 2 }
                            } else {
                                fadeIn()
                            }
                        } else {
                            EnterTransition.None
                        },
                        exit = if (isTransitionAnimated) fadeOut() else ExitTransition.None
                    ) {
                        val miniPlayerVisibilityScope = this
                        if (hasCurrentTrack && currentRoute != Screen.Onboarding.route) {
                            MiniPlayer(
                                playbackStateFlow = playerViewModel.playbackState,
                                positionMsFlow = playerViewModel.positionMs,
                                onPlayPauseClick = {
                                    val state = playerViewModel.playbackState.value
                                    if (state.isPlaying) playerViewModel.pause() else playerViewModel.play()
                                },
                                onSeek = { progress ->
                                    playerViewModel.seekTo(
                                        (progress * playerViewModel.playbackState.value.durationMs).toLong()
                                    )
                                },
                                onSkipToTrack = { index, forward ->
                                    playerViewModel.skipToIndex(index, forward)
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
                                // Untagged while a row is the flight endpoint or a press
                                // preload is pending (a rest-state pairing would pin the
                                // shared elements and break pager swipes).
                                useSharedElements = trackListTransitionTrackId == null &&
                                        centerTrackRequest == null,
                                sharedArtFlightAlpha = {
                                    if (playerSettings.immersiveModeEnabled) {
                                        1f - playerTransitionState.progress()
                                    } else {
                                        1f
                                    }
                                },
                                isImmersiveEnabled = playerSettings.immersiveModeEnabled,
                                isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                                marqueeMode = playerSettings.marqueeMode,
                                skipTrackChangeAnimation = skipMiniPlayerTrackAnimation,
                                showContainer = navigationBarMode != NavigationBarMode.ATTACHED,
                                modifier = Modifier
                                    .graphicsLayer { alpha = miniPlayerPresence.value }
                                    .onGloballyPositioned { miniPlayerTopPx = it.positionInRoot().y }
                                    .onSizeChanged { miniPlayerBoxHeightPx = it.height }
                                    .draggable(
                                        state = rememberDraggableState { delta -> miniPlayerSwipeController.onDrag(delta) },
                                        orientation = Orientation.Vertical,
                                        onDragStopped = { velocity -> miniPlayerSwipeController.onDragStopped(velocity) }
                                    )
                            )
                        }
                    }
                }
            }

            // Long-press track actions (plain Dialogs hosted at the root).
            TrackFoamModal(
                state = trackModalState,
                onPlayNext = playerViewModel::playNext,
                onAddToQueue = playerViewModel::addToQueue,
                onAddToPlaylist = { track -> playlistPickerTrack = track },
                onSeeArtist = { track ->
                    activeSharedCollection = sharedCollectionTag("artist", track.artist)
                    navigateClosingPlayer(
                        Screen.ArtistDetail.createRoute(track.artist),
                        Screen.ArtistDetail.route
                    )
                },
                onSeeAlbum = { track ->
                    activeSharedCollection = sharedCollectionTag("album", track.album)
                    navigateClosingPlayer(
                        Screen.AlbumDetail.createRoute(track.album, track.albumArtPath, track.artist),
                        Screen.AlbumDetail.route
                    )
                },
                onOpenLyrics = { track ->
                    lyricsModalTrack = track
                    lyricsDetailsViewModel.loadTrackDetails(track)
                },
                onResetPlayStats = { track ->
                    playerViewModel.resetPlayStats(track.id)
                    trackModalState.updateCurrentTrack { it.copy(playCount = 0, lastPlayedAt = null) }
                },
                onSetInstrumental = { track, instrumental ->
                    playerViewModel.setTrackInstrumental(track.id, instrumental)
                    trackModalState.updateCurrentTrack { it.copy(instrumental = instrumental) }
                },
                scoreOverrides = scoreOverrides,
                onSetScore = playerViewModel::setTrackScore,
                useAlbumArtAccent = playerSettings.automaticColorEnabled
            )

            PlaylistPickerModal(
                track = playlistPickerTrack,
                playlists = playlists,
                playlistIdsWithTrack = playlistIdsWithTrack,
                onToggle = { playlist, checked ->
                    playlistPickerTrack?.let { track ->
                        if (checked) {
                            playlistsViewModel.addTrackToPlaylist(playlist.playlistId, track.id)
                        } else {
                            playlistsViewModel.removeTrackFromPlaylist(playlist.playlistId, track.id)
                        }
                    }
                },
                onDismiss = { playlistPickerTrack = null }
            )

            LyricsTrackDetailsModal(
                visible = lyricsModalTrack != null,
                track = lyricsDisplayTrack,
                details = lyricsTrackDetails?.takeIf { it.track.id == lyricsDisplayTrack?.id },
                isLoading = isLyricsDetailsLoading,
                onDismiss = { lyricsModalTrack = null },
                onDelete = { lyricsModalTrack?.let { lyricsDetailsViewModel.deleteLyrics(it) } },
                onSearch = {
                    val trackId = lyricsModalTrack?.id
                    lyricsModalTrack = null
                    trackModalState.dismiss()
                    trackId?.let {
                        navController.navigate(Screen.SettingsLyricsManagerResults.createRoute(it))
                    }
                }
            )

            // Long-press collection actions; a folder/genre/year spanning several
            // artists/albums opens the matching list screen instead.
            CollectionFoamModal(
                state = collectionModalState,
                onPlayNext = { collection -> playerViewModel.playNext(collection.tracks) },
                onAddToQueue = { collection -> playerViewModel.addToQueue(collection.tracks) },
                onSeeArtist = { artist ->
                    activeSharedCollection = sharedCollectionTag("artist", artist)
                    navController.navigate(Screen.ArtistDetail.createRoute(artist))
                },
                onSeeAlbum = { album, albumArtPath, artist ->
                    activeSharedCollection = sharedCollectionTag("album", album)
                    navController.navigate(Screen.AlbumDetail.createRoute(album, albumArtPath, artist))
                },
                onSeeArtistList = { sourceKind, sourceKey ->
                    navController.navigate(Screen.BrowseArtistsIn.createRoute(sourceKind.name, sourceKey))
                },
                onSeeAlbumList = { sourceKind, sourceKey ->
                    navController.navigate(Screen.BrowseAlbumsIn.createRoute(sourceKind.name, sourceKey))
                },
                useAlbumArtAccent = playerSettings.automaticColorEnabled
            )
        }
    }
}