package com.foamplayer.app.ui.navigation

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
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
import com.foamplayer.app.ui.components.CollectionFoamModal
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.components.LocalCollectionFoamModalState
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
import com.foamplayer.app.ui.screens.library.AlbumDetailScreen
import com.foamplayer.app.ui.screens.library.ArtistDetailScreen
import com.foamplayer.app.ui.screens.library.ArtistSongsScreen
import com.foamplayer.app.ui.screens.player.AlbumArtBackdrop
import com.foamplayer.app.ui.screens.player.DownSwipeCommitVelocityPxPerSec
import com.foamplayer.app.ui.screens.player.FullPlayerBackdropState
import com.foamplayer.app.ui.screens.player.FullPlayerScreen
import com.foamplayer.app.ui.screens.player.DynamicAlbumArtBackground
import com.foamplayer.app.ui.screens.player.MiniPlayerSwipeController
import com.foamplayer.app.ui.screens.player.PlayerDimens
import com.foamplayer.app.ui.screens.player.PlayerFlightGeometryState
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.player.progress
import com.foamplayer.app.ui.screens.playlists.PlaylistDetailScreen
import com.foamplayer.app.ui.screens.playlists.PlaylistsScreen
import com.foamplayer.app.ui.screens.playlists.PlaylistsViewModel
import com.foamplayer.app.ui.screens.search.SearchScreen
import com.foamplayer.app.ui.screens.settings.AudioSettingsScreen
import com.foamplayer.app.ui.screens.settings.BackgroundSettingsScreen
import com.foamplayer.app.ui.screens.settings.CollectionsSettingsScreen
import com.foamplayer.app.ui.screens.settings.ColorsSettingsScreen
import com.foamplayer.app.ui.screens.settings.LibrarySettingsScreen
import com.foamplayer.app.ui.screens.settings.LyricsSettingsScreen
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

/** Longest a programmatic mini<->full player transition may take (scales with remaining distance). */
private const val PlayerTransitionMaxDurationMs = 500

/**
 * Shortest a gesture-committed close flight may take when the commit was a flick:
 * the drag's rate cap keeps the in-gesture fraction low, but the flight must always
 * read as an animation instead of collapsing to an instant transition.
 */
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

/**
 * Browse list pages (opened from the search section's browse cards, plus their
 * sub-pages: genre/year tracks, folders, artists-in, albums-in). They slide over
 * the screen they leave: in from the right when pushed, back out to the right
 * when popped.
 */
private fun isBrowseRoute(route: String?): Boolean = route != null && route.startsWith("browse/")

/** Detail screens whose list background is drawn full-bleed at the root. */
private fun isDetailListRoute(route: String?): Boolean =
    route == Screen.AlbumDetail.route ||
            route == Screen.PlaylistDetail.route ||
            route == Screen.ArtistDetail.route ||
            route == Screen.ArtistSongs.route

/**
 * True when the page currently on top of the back stack IS the collection the queue
 * was started from ([activeCollectionId], the playing track's origin-collection tag)
 * AND that page's rows render an artwork the flight can morph into (playlist / genre
 * / year details -- all [com.foamplayer.app.ui.components.TrackListScreen]-based with
 * artwork rows). Album detail rows show a track NUMBER instead of artwork and folder
 * pages aren't built on the shared-element track list at all, so both keep the plain
 * mini-player dismissal.
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

/** Art feeding the detail-list backdrop: the path plus how Coil must load it (embedded audio art vs plain image). */
private data class ListBackdropArt(val path: String, val isEmbeddedArt: Boolean)

@Composable
private fun rememberAnimatedBackground(
    enabled: Boolean,
    initialFrontArtPath: () -> String?,
    blur: Dp,
    intensity: Float,
    modifier: Modifier = Modifier
): (frontArtPath: String?, backArtPath: String?, fraction: Float) -> Unit {
    // The initial path is evaluated lazily (once, when the state is created) so the
    // caller can hand over a playbackState read without subscribing this scope to the
    // whole state flow.
    var frontArtPath by remember { mutableStateOf(initialFrontArtPath()) }
    var backArtPath by remember { mutableStateOf<String?>(null) }
    // The crossfade fraction changes on every frame of a mini-player swipe; keeping it
    // in a State that DynamicAlbumArtBackground reads inside its graphicsLayer makes
    // those frames draw-only instead of recomposing the backdrop's composition.
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FoamNavHost(
    modifier: Modifier = Modifier,
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val homeViewModel: HomeViewModel = hiltViewModel()
    val playlistsViewModel: PlaylistsViewModel = hiltViewModel()
    val playlists by playlistsViewModel.playlists.collectAsState()

    // Long-press track actions: the modal is hosted at the root and reached by
    // every TrackListItem through LocalTrackFoamModalState. The playlist button
    // opens the picker, which holds the pending track until a playlist is picked.
    val trackModalState = rememberTrackFoamModalState()
    var playlistPickerTrack by remember { mutableStateOf<TrackEntity?>(null) }

    // Lyrics details sheet, opened from the track modal's details page. It is a
    // separate Dialog window stacked on top of the open track sheet, so
    // dismissing it returns to the details page beneath.
    var lyricsModalTrack by remember { mutableStateOf<TrackEntity?>(null) }
    // Keep the last shown track through the sheet's exit animation (same
    // pattern as the other modals), so it slides away with content instead of
    // collapsing to empty.
    var lastLyricsModalTrack by remember { mutableStateOf<TrackEntity?>(null) }
    SideEffect {
        if (lyricsModalTrack != null) lastLyricsModalTrack = lyricsModalTrack
    }
    val lyricsDisplayTrack = lyricsModalTrack ?: lastLyricsModalTrack
    val lyricsDetailsViewModel: LyricsDetailsViewModel = hiltViewModel()
    val lyricsTrackDetails by lyricsDetailsViewModel.trackDetails.collectAsState()
    val isLyricsDetailsLoading by lyricsDetailsViewModel.isDetailsLoading.collectAsState()

    // Playlists that already contain the track being picked for: drives the
    // picker's checkbox states, live from Room, so each toggle is reflected as
    // soon as it persists. Keyed on the track modal's track too, so the query
    // has already settled by the time the picker opens (the track modal
    // dismisses and the picker opens in the same recomposition).
    val pickerTrackId = playlistPickerTrack?.id ?: trackModalState.track?.id
    val playlistIdsWithTrack by remember(pickerTrackId) {
        playlistsViewModel.playlistIdsContainingTrack(pickerTrackId)
    }.collectAsState(initial = emptySet())

    // Long-press collection actions (albums/artists/genres/years/folders),
    // reached by every CollectionListItem through LocalCollectionFoamModalState.
    val collectionModalState = rememberCollectionFoamModalState()

    // Narrow projections instead of the whole PlaybackState: collecting the full
    // state at the nav root re-ran this entire body (backdrop logic, every
    // composable() destination lambda, nav-bar branches) on every playback emission
    // -- including play/pause toggles and queue refills that don't affect any of
    // this scope's actual decisions.
    val activeCollectionId by playerViewModel.activeCollectionId.collectAsState()
    val hasCurrentTrack by playerViewModel.hasCurrentTrack.collectAsState()
    // Highlighting "the playing row" only needs the id: screens recompose when the
    // track actually changes, not on every unrelated playback emission.
    val activeTrackId by playerViewModel.activeTrackId.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val playerSettings by playerViewModel.settings.collectAsState()
    val scoreOverrides by playerViewModel.scoreOverrides.collectAsState()
    val isAnimateSeekBarEnabled = playerSettings.animateSeekBarEnabled
    val isAutoOpenPlayerEnabled = playerSettings.autoOpenPlayerEnabled
    val isDynamicBackgroundEnabled = playerSettings.dynamicBackgroundEnabled
    val navigationBarMode = playerSettings.navigationBarMode
    // Still honored by FLOATING/ATTACHED; the docked bar ignores it (always solid).
    val navBarTransparentWhenPlayerOpen = playerSettings.navBarTransparentWhenPlayerOpen

    // Per-view media list density (organisation modal). Every view remembers its own.
    val densityFor: (MediaListView) -> MediaDensity = { view -> playerSettings.mediaDensity(view) }
    val setDensity: (MediaListView, MediaDensity) -> Unit = { view, value ->
        playerViewModel.setMediaDensity(view, value)
    }

    // Per-view media list sort (organisation modal): the mode and its "Inverse" toggle.
    val sortModeFor: (MediaListView) -> CollectionSortMode = { view -> playerSettings.mediaSortMode(view) }
    val setSortMode: (MediaListView, CollectionSortMode) -> Unit = { view, value ->
        playerViewModel.setMediaSortMode(view, value)
    }
    val sortInvertedFor: (MediaListView) -> Boolean = { view -> playerSettings.mediaSortIsInverted(view) }
    val setSortInverted: (MediaListView, Boolean) -> Unit = { view, value ->
        playerViewModel.setMediaSortInverted(view, value)
    }

    // Per-collection hero layout. Playlists are identified by their detail route
    // (CollectionKind has no Playlist member), albums/artists by their detail
    // screens; every other collection resolves to the Default value.
    val heroLayoutFor: (CollectionHeroTarget) -> CollectionHeroLayout = { target ->
        playerSettings.collectionHeroLayout(target)
    }

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

    // Dismiss-to-origin-collection session: when a swipe-down on the full player starts
    // over the playing track's origin collection page, that page's playing row becomes
    // the shared-element endpoint of the artwork flight (trackListTransitionTrackId --
    // which also keeps the mini player untagged for the whole transition), and the page
    // is asked to scroll the row to the vertical middle of the screen before the first
    // visible frame of the flight.
    var dismissToRowSessionActive by remember { mutableStateOf(false) }
    var centerTrackRequest by remember { mutableStateOf<TrackCenterRequest?>(null) }
    var centerRequestSeq by remember { mutableStateOf(0) }

    // Measured top edge (root coordinates, px) of the mini player's box. The attached
    // nav bar's surface extension is drawn to land exactly on the card's visible top
    // edge computed from it, so the surface and the card can never drift apart --
    // whatever the real layout does (font scale, text metrics, second-bar lift).
    var miniPlayerTopPx by remember { mutableStateOf(Float.NaN) }

    // Measured height (px) of the mini player's box, outer padding included. Font
    // scale can grow the card past its derived dp metrics, so the content clearance
    // below prefers this measurement and only falls back to the derived height
    // before the first layout has reported.
    var miniPlayerBoxHeightPx by remember { mutableStateOf(0) }

    // ---- Bottom-bar zone geometry for content layering ---------------------------
    // The nav bar and the floating mini player card are overlays: every screen
    // scrolls BEHIND them (occluded, not clipped) and keeps its trailing rows
    // reachable via [LocalBottomBarClearance] spacers.
    //
    // The card's height prefers the live measurement over the derived metrics.
    val miniPlayerBoxHeight = with(density) {
        miniPlayerBoxHeightPx.takeIf { it > 0 }?.toDp()
            ?: (NavBarDimens.MiniPlayerCardHeight + NavBarDimens.MiniPlayerCardVerticalPadding * 2)
    }
    // Trailing clearance for edge-to-edge lists: everything from the screen's
    // bottom edge (system inset included) up past the floating card, or up past
    // the bar while no track is loaded.
    val bottomBarClearance = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                (if (hasCurrentTrack) {
                    NavBarDimens.miniPlayerBottomPadding(navigationBarMode, 0f) + miniPlayerBoxHeight
                } else {
                    NavBarDimens.navBarZoneHeight(navigationBarMode)
                }) +
                16.dp
    }
    // The full player keeps its classic footprint: it ends at the nav zone's top
    // edge, whose black backdrop fade lands exactly on the bar (DOCKED).
    val playerBottomPadding = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                NavBarDimens.navBarZoneHeight(navigationBarMode)
    }

    // Hero artwork center (root coordinates), reported by the full player's shared
    // element, paired with the current dismiss target (mini player art or the origin
    // collection's centered row) to size the swipe to the artwork's travel distance.
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

    // Track whether the current or next transition should use animation.
    // If we start opened, we disable animation for the initial frame.
    var isTransitionAnimated by remember { mutableStateOf(!initialOpenRequested) }

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
        val dynamicDuration = (PlayerTransitionMaxDurationMs * remainingFraction).toInt().coerceAtLeast(1)
        val duration = if (!gestureVelocityPxPerSec.isNaN() &&
            gestureVelocityPxPerSec >= DownSwipeCommitVelocityPxPerSec
        ) {
            // Flick commit: force a real tween so the close never reads as instant.
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
            // A fresh open always supersedes any pending dismiss-to-row session; its
            // own finally below re-clears the tag when the flight lands. Dropping the
            // center request is what re-arms the row tag for open flights (a stale
            // request would keep rows untagged on the freshly composed page).
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
                        // Close flight finished: hand the shared-element key back to
                        // the mini player. Only the dismiss-to-row session ever holds
                        // the tag across a close, so this is a no-op otherwise.
                        trackListTransitionTrackId = null
                        dismissToRowSessionActive = false
                        centerTrackRequest = null
                    }
                }
            }
        }
    }

    /**
     * PRESS PRELOAD (finger down on the full player): precomposes the returning page
     * at zero visual progress (fraction 0 against targetState=false is visually
     * identical to full rest) and asks it to scroll the playing row to the vertical
     * middle of the screen, so everything is in place before the finger moves.
     * Deliberately does NOT tag the row: a rest-state pairing would keep the shared
     * element "active" while nothing moves, hijacking the hero artwork and breaking
     * pager swipes. Returns without touching anything when the current page is not
     * the origin collection.
     */
    suspend fun prepareDismissToRow() {
        val trackId = activeTrackId ?: return
        if (!isOriginCollectionPage(currentRoute, navBackStackEntry?.arguments, activeCollectionId)) {
            return
        }

        val request = TrackCenterRequest(trackId, ++centerRequestSeq)
        // The center request is written in the same snapshot batch as the seek, so
        // the page's very first composition already sees it and can scroll while it
        // lays out.
        centerTrackRequest = request
        playerTransitionState.seekTo(fraction = 0f, targetState = false)

        // Deterministic hand-off: wait for the list to scroll the row into place, then
        // give its layout a frame to settle. The timeout only guards a pathological
        // unconsumed request -- an unbounded await here would brick the dismiss gesture.
        withTimeoutOrNull(250) { request.scrolledIntoPlace.await() }
        withFrameNanos { }
        withFrameNanos { }
    }

    /**
     * DRAG ACTIVATION (first delta of a down-stroke): tags the playing row so the
     * shared element pairs, then lets one recomposition + layout pass land before the
     * drag moves, so the flight's first visible frame already has its target at its
     * final position. Runs after the press preload (which it falls back to when the
     * preload was skipped, e.g. the finger landed mid-flight).
     */
    suspend fun activateDismissToRow() {
        val trackId = activeTrackId ?: return
        if (!isOriginCollectionPage(currentRoute, navBackStackEntry?.arguments, activeCollectionId)) {
            return
        }

        val request = centerTrackRequest
        if (request == null || request.trackId != trackId) {
            // No usable preload: compose and scroll now (the common path never lands
            // here, the press preload already did the work).
            prepareDismissToRow()
        }

        dismissToRowSessionActive = true
        trackListTransitionTrackId = trackId
        // One frame for the recomposition that attaches the row's shared-element tag,
        // one for the match configuration that follows in that frame's layout pass.
        withFrameNanos { }
        withFrameNanos { }
    }

    /** Swipe-down settled back without committing: drop the row tag (if we set it). */
    fun clearDismissToRowSession() {
        if (dismissToRowSessionActive) {
            dismissToRowSessionActive = false
            trackListTransitionTrackId = null
        }
        // The press preload creates the request without a session; clear it whenever
        // a dismiss gesture ends without committing so it can never suppress a later
        // open flight's row tag.
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

    SharedTransitionLayout {
        val transition: Transition<Boolean> = rememberTransition(playerTransitionState, label = "PlayerTransition")

        // Every TrackListItem / CollectionListItem in the graph opens its
        // actions modal through these locals, so no screen has to thread the
        // states down itself. [LocalBottomBarClearance] tells edge-to-edge
        // screens how much trailing space keeps their last rows clear of the
        // floating nav bar + mini player card.
        CompositionLocalProvider(
            LocalTrackFoamModalState provides trackModalState,
            LocalCollectionFoamModalState provides collectionModalState,
            LocalBottomBarClearance provides bottomBarClearance
        ) {
            Box(modifier = modifier.fillMaxSize().background(FoamTheme.colors.background)) {

                // App-wide dynamic album-art backdrop
                val backgroundEnabled = isDynamicBackgroundEnabled && !isSettingsRoute(currentRoute)
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

                // Full-bleed backdrop for the detail list screens (album/playlist). Drawn here
                // at the root, so it covers the status bar and the nav zone by construction --
                // no overflow tricks that transition layers could clip. Its alpha tween runs in
                // lock-step with the NavHost's screen fade (shared-element routes fade in 350ms,
                // plain routes in 400ms), so the background fades with the list opening/closing.
                // [lastListBackdropArt] keeps the art alive while the alpha fades back out.
                var listBackdropArt by remember { mutableStateOf<ListBackdropArt?>(null) }
                var lastListBackdropArt by remember { mutableStateOf<ListBackdropArt?>(null) }
                LaunchedEffect(currentRoute) {
                    if (!isDetailListRoute(currentRoute)) listBackdropArt = null
                }
                val onListBackdropArt = { path: String?, isEmbeddedArt: Boolean ->
                    val art = path?.let { ListBackdropArt(it, isEmbeddedArt) }
                    if (art != null) lastListBackdropArt = art
                    listBackdropArt = art
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
                // Boolean gate via derivedStateOf: reading the animated alpha directly in
                // this if recomposed the whole nav root on every frame of the 350-400ms
                // backdrop fade. The derived value only flips at the fade's endpoints.
                val showListBackdrop by remember {
                    derivedStateOf {
                        (listBackdropArt ?: lastListBackdropArt) != null && listBackdropAlpha > 0f
                    }
                }
                if (showListBackdrop) {
                    DynamicAlbumArtBackground(
                        frontArtPath = listBackdropDisplay?.path,
                        frontArtIsEmbedded = listBackdropDisplay?.isEmbeddedArt ?: true,
                        backArtPath = null,
                        crossfadeFraction = { 0f },
                        blur = playerSettings.backgroundBlur.dp,
                        intensity = playerSettings.appBackgroundIntensity / 100f,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = listBackdropAlpha }
                    )
                }

                // ---- Full player backdrop (root-hosted) --------------------------------------
                // Height of the nav-bar zone, measured from the bar container below. In DOCKED
                // mode the solid bar is painted above the backdrop, so the backdrop's black
                // fade must land exactly on the bar's top edge; in FLOATING/ATTACHED the
                // backdrop stays visible down to the physical bottom, where the fade ends.
                var navZoneHeightPx by remember { mutableStateOf(0) }
                val playerBackdropFadeInset = if (navigationBarMode == NavigationBarMode.DOCKED) {
                    with(density) { navZoneHeightPx.toDp() }
                } else {
                    0.dp
                }

                // The full player's blurred backdrop is hosted here at the very root, inside
                // the exact same fillMaxSize box as the app-wide backdrop above. Both crop the
                // same artwork at the same size with the same blur and dim, so the open/close
                // crossfade between them is seamless, and the blurred art + black fade reach
                // the physical bottom of the screen by construction -- no overflow tricks an
                // intermediate container can cut off. FullPlayerScreen feeds the pager-driven
                // state through [fullPlayerBackdropState].
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
                        dynamicBackgroundEnabled = isDynamicBackgroundEnabled,
                        backgroundBlur = playerSettings.backgroundBlur.dp,
                        intensity = playerSettings.playerBackgroundIntensity / 100f,
                        bottomFadeInset = playerBackdropFadeInset,
                        canvasUrl = fullPlayerBackdropState.canvasUrl,
                        canvasPlaying = fullPlayerBackdropState.canvasPlaying,
                        canvasSwipeFraction = fullPlayerBackdropState.canvasSwipeFraction,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = playerBackdropAlpha() }
                    )

                    // Subview scrim, formerly drawn by the player over its own backdrop.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = PlayerDimens.scrimMaxAlpha *
                                        fullPlayerBackdropState.subViewBlurProgress() *
                                        playerBackdropAlpha()
                            }
                            .background(Color.Black)
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

                val playerClosedFraction = {
                    val progress = playerTransitionState.progress().coerceIn(0f, 1f)
                    if (playerTransitionState.targetState) {
                        // Opening: retract/fade out as soon as the transition starts.
                        1f - EaseOutCubic.transform(progress)
                    } else {
                        // Closing: back as soon as the transition starts.
                        1f - EaseInCubic.transform(progress)
                    }
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
                                popUpTo(navController.graph.startDestinationId) {
                                    // Preserve the tab's back stack and UI state so
                                    // switching tabs doesn't tear down and rebuild
                                    // the destination (full re-composition, Coil
                                    // re-requests, lazy list state reset).
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // [collectionId] marks the queue as started from a specific
                    // collection (album/playlist/genre/year/folder screen) so
                    // its row can be highlighted as the active one; bare track
                    // lists (library, search) pass null and highlight nothing.
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
                            // Screens keep their status-bar padding; the backdrop behind
                            // them (drawn at the root now) extends up under the status
                            // bar. They run edge-to-edge to the screen's bottom edge,
                            // scrolling behind the nav bar and mini player card.
                            Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                                NavHost(
                                    modifier = Modifier.fillMaxSize(),
                                    navController = navController,
                                    startDestination = Screen.Home.route,
                                    enterTransition = {
                                        when {
                                            playerTransitionState.currentState -> EnterTransition.None
                                            isSettingsRoute(initialState.destination.route) &&
                                                    isSettingsRoute(targetState.destination.route) ->
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
                                    composable(Screen.Home.route) {
                                        HomeScreen(
                                            viewModel = homeViewModel,
                                            onPlaylistClick = { playlist ->
                                                navController.navigate(
                                                    Screen.PlaylistDetail.createRoute(
                                                        playlist.playlistId,
                                                        playlist.name,
                                                        playlist.imageUrl
                                                    )
                                                )
                                            },
                                            onAlbumClick = { album ->
                                                navController.navigate(
                                                    Screen.AlbumDetail.createRoute(
                                                        album.album,
                                                        album.albumArtPath,
                                                        album.artist
                                                    )
                                                )
                                            },
                                            onArtistClick = { artist ->
                                                navController.navigate(Screen.ArtistDetail.createRoute(artist))
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
                                            onArtistClick = { artist ->
                                                navController.navigate(Screen.ArtistDetail.createRoute(artist))
                                            },
                                            onBack = { navController.popBackStack() },
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
                                            onArtistClick = { artist ->
                                                navController.navigate(Screen.ArtistDetail.createRoute(artist))
                                            },
                                            onBack = { navController.popBackStack() },
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
                                            onArtistClick = { artist ->
                                                navController.navigate(Screen.ArtistDetail.createRoute(artist))
                                            },
                                            onBack = { navController.popBackStack() },
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
                                            onAllSongsClick = {
                                                navController.navigate(Screen.ArtistSongs.createRoute(artistName))
                                            },
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index, CollectionId.artist(artistName))
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
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
                            // The player keeps its classic footprint: it ends at the
                            // nav zone's top edge (the bar renders below it and the
                            // backdrop's black fade lands on the bar, DOCKED).
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
                                isAnimateSeekBarEnabled = isAnimateSeekBarEnabled
                            )
                        }
                    }
                }

                // The nav bar floats over the edge-to-edge content as an overlay at
                // the screen's bottom; the mini player card is layered above it.
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
                                    navBarTransparentWhenPlayerOpen = navBarTransparentWhenPlayerOpen,
                                    expandFraction = navBarExpandFraction,
                                     onToggleExpand = { isNavBarExpanded = !isNavBarExpanded }
                                 )
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
                        enter = if (isTransitionAnimated) {
                            if (trackListTransitionTrackId != null) {
                                // The full player's artwork is morphing into the origin
                                // collection's row instead of the mini player: the mini
                                // is untagged for that flight, so it announces itself
                                // with a fade + slide up from the bottom edge instead
                                // of merely fading in. Both are transition animations,
                                // so they follow the seeked flight fraction exactly
                                // (progressive under the finger, reversed on cancel).
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
                        if (hasCurrentTrack) {
                            // MiniPlayer collects playbackState internally; the lambdas below
                            // read the flow's current value at invocation time (event
                            // handlers, not composition), so this scope stays unsubscribed
                            // from the whole state.
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
                                // Untagged while a row is the flight endpoint (dismiss
                                // session) AND while a press preload is pending: the
                                // preload's zero-fraction seek composes this branch, and
                                // a rest-state pairing here would pin the player's art
                                // and texts to the matched bounds -- breaking pager
                                // swipes -- until the track (and key) changed.
                                useSharedElements = trackListTransitionTrackId == null &&
                                        centerTrackRequest == null,
                                sharedArtFlightAlpha = {
                                    if (playerSettings.immersiveModeEnabled) {
                                        1f - playerTransitionState.progress()
                                    } else {
                                        1f
                                    }
                                },
                                isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                                marqueeMode = playerSettings.marqueeMode,
                                skipTrackChangeAnimation = skipMiniPlayerTrackAnimation,
                                showContainer = navigationBarMode != NavigationBarMode.ATTACHED,
                                modifier = Modifier
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

            // Long-press track actions. The modal and its playlist picker are
            // plain Dialogs, so their placement in the tree doesn't matter.
            // Details is an internal page of the same sheet, sharing its header.
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
                onOpenLyrics = { track ->
                    lyricsModalTrack = track
                    lyricsDetailsViewModel.loadTrackDetails(track)
                },
                onResetPlayStats = { track ->
                    playerViewModel.resetPlayStats(track.id)
                    // Reflect the reset in the open sheet immediately.
                    trackModalState.updateCurrentTrack { it.copy(playCount = 0, lastPlayedAt = null) }
                },
                onSetInstrumental = { track, instrumental ->
                    playerViewModel.setTrackInstrumental(track.id, instrumental)
                    // Reflect the toggle in the open sheet immediately.
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
                    // Jumping to the lyrics search results replaces this flow
                    // entirely, so neither sheet may stay up behind it.
                    val trackId = lyricsModalTrack?.id
                    lyricsModalTrack = null
                    trackModalState.dismiss()
                    trackId?.let {
                        navController.navigate(Screen.SettingsLyricsManagerResults.createRoute(it))
                    }
                }
            )

            // Long-press collection actions. The modal resolves which artist
            // name / album to navigate to; a folder, genre or year spanning
            // several artists/albums opens the matching list screen instead.
            CollectionFoamModal(
                state = collectionModalState,
                onPlayNext = { collection -> playerViewModel.playNext(collection.tracks) },
                onAddToQueue = { collection -> playerViewModel.addToQueue(collection.tracks) },
                onSeeArtist = { artist ->
                    navController.navigate(Screen.ArtistDetail.createRoute(artist))
                },
                onSeeAlbum = { album, albumArtPath, artist ->
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
