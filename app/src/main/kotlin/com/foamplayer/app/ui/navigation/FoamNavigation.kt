package com.foamplayer.app.ui.navigation

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseInOut
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable as navComposable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.foamplayer.app.BuildConfig
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.BackgroundMode
import com.foamplayer.app.data.settings.GradientStop
import com.foamplayer.app.ui.modals.CollectionFoamModal
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.CollectionKind
import com.foamplayer.app.ui.modals.LocalCollectionFoamModalState
import com.foamplayer.app.ui.modals.LocalOpenPlaylistPickerForPlaylist
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.modals.LocalTrackFoamModalState
import com.foamplayer.app.ui.components.MiniPlayer
import com.foamplayer.app.ui.components.ToastStack
import com.foamplayer.app.ui.modals.PlaylistPickerModal
import com.foamplayer.app.ui.modals.TrackFoamModal
import com.foamplayer.app.ui.modals.TrackRadioModal
import com.foamplayer.app.ui.modals.TrackRadioViewModel
import com.foamplayer.app.ui.modals.UpdateFoamModal
import com.foamplayer.app.ui.modals.rememberCollectionFoamModalState
import com.foamplayer.app.ui.modals.rememberTrackFoamModalState
import com.foamplayer.app.ui.screens.equalizer.EqualizerScreen
import com.foamplayer.app.ui.screens.browse.AlbumsBrowseScreen
import com.foamplayer.app.ui.screens.browse.AllTracksBrowseScreen
import com.foamplayer.app.ui.screens.browse.ArtistsBrowseScreen
import com.foamplayer.app.ui.screens.browse.CollectionAlbumsScreen
import com.foamplayer.app.ui.screens.browse.CollectionArtistsScreen
import com.foamplayer.app.ui.screens.browse.ComposersBrowseScreen
import com.foamplayer.app.ui.screens.browse.ComposerTracksScreen
import com.foamplayer.app.ui.screens.browse.FolderBrowseScreen
import com.foamplayer.app.ui.screens.browse.GenresBrowseScreen
import com.foamplayer.app.ui.screens.browse.GenreTracksScreen
import com.foamplayer.app.ui.screens.browse.YearTracksScreen
import com.foamplayer.app.ui.screens.browse.YearsBrowseScreen
import com.foamplayer.app.ui.screens.home.FavoriteArtistsSeeAllScreen
import com.foamplayer.app.ui.screens.home.FavoriteTracksSeeAllScreen
import com.foamplayer.app.ui.screens.home.HomeScreen
import com.foamplayer.app.ui.screens.home.HomeViewModel
import com.foamplayer.app.ui.screens.home.OldFavoriteAlbumsSeeAllScreen
import com.foamplayer.app.ui.screens.home.OldFavoritesSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecentTracksSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecentlyAddedAlbumsSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecentlyAddedArtistsSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecommendedAlbumsSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecommendedPlaylistsSeeAllScreen
import com.foamplayer.app.ui.screens.home.RecommendedTracksSeeAllScreen
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
import com.foamplayer.app.ui.screens.settings.AboutSettingsScreen
import com.foamplayer.app.update.UpdateManager
import com.foamplayer.app.ui.screens.settings.AudioSettingsScreen
import com.foamplayer.app.ui.screens.settings.AudioDevicesSettingsScreen
import com.foamplayer.app.ui.screens.settings.BackgroundSettingsScreen
import com.foamplayer.app.ui.screens.settings.CollectionsSettingsScreen
import com.foamplayer.app.ui.screens.settings.ColorsSettingsScreen
import com.foamplayer.app.ui.screens.settings.DiscordSettingsScreen
import com.foamplayer.app.ui.screens.settings.EqualizerSettingsScreen
import com.foamplayer.app.ui.screens.settings.HomeSettingsScreen
import com.foamplayer.app.ui.screens.settings.LibrarySettingsScreen
import com.foamplayer.app.ui.screens.settings.LyricsSettingsScreen
import com.foamplayer.app.ui.screens.settings.LastFmSettingsScreen
import com.foamplayer.app.ui.screens.settings.MiscSettingsScreen
import com.foamplayer.app.ui.screens.settings.NavBarSettingsScreen
import com.foamplayer.app.ui.screens.settings.NotificationSettingsScreen
import com.foamplayer.app.ui.screens.settings.PlaybackSettingsScreen
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
import com.foamplayer.app.ui.components.collectionHeroOverlayLayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/**
 * Peak alpha of the theme-aware scrim drawn behind the bottom chrome (app nav
 * bar, mini player card and the system navigation bar's inset). 0.5 at the very
 * bottom edge of the screen, fading to fully transparent at the top of the bar
 * zone (see the layer in [FoamNavHost]).
 */
private const val BottomChromeScrimMaxAlpha = 0.9f

/**
 * zIndex of the nav bar while lifted into the shared-element overlay (see
 * [FoamNavHost]'s bottom-chrome lift): above the flying artwork (0f), texts
 * (1f) and every screen-lifted chrome layer (0.5f/1f).
 */
private const val BottomChromeNavBarOverlayZIndex = 10f

/**
 * zIndex of the mini player card while lifted: above the lifted nav bar, so
 * the card keeps its at-rest position on top of the bar (they touch in
 * ATTACHED mode, where the card sinks into the bar's surface extension).
 */
private const val BottomChromeMiniPlayerOverlayZIndex = 11f

/**
 * Gap between the mini player's top and the bottom of the toast stack sitting
 * above it.
 */
private val ToastsAboveMiniPlayerGap = 12.dp

/**
 * The stored background-blur percentage rendered as an actual Gaussian radius:
 * the slider value is perceptual (see [AppSettings.backgroundBlurRadiusDp]), so
 * every backdrop layer converts it through that mapping instead of treating
 * percent as dp.
 */
private fun backgroundBlurRadius(blurPercent: Int): Dp =
    AppSettings.backgroundBlurRadiusDp(blurPercent).dp

/**
 * Live-preview overrides pushed by the background settings screen while it is
 * open. Every field is null = "use the persisted setting"; the app-wide backdrop
 * layers ([rememberAnimatedBackground], [StaticBackgroundLayers]) read them
 * inside their own recompose scopes, so per-frame edits (gradient dots mid-drag,
 * blur / intensity sliders mid-drag) redraw only the backdrop and never the
 * navigation tree — the settings themselves stay commit-on-release.
 *
 * Cleared when the settings screen leaves composition, handing control back to
 * the persisted values.
 */
@Stable
class BackgroundSettingsPreviewState {
    var mode by mutableStateOf<BackgroundMode?>(null)
    var gradientStops by mutableStateOf<List<GradientStop>?>(null)
    var backgroundBlur by mutableStateOf<Int?>(null)
    var appBackgroundIntensity by mutableStateOf<Int?>(null)

    /** Drops every override; the persisted settings drive the layers again. */
    fun clear() {
        mode = null
        gradientStops = null
        backgroundBlur = null
        appBackgroundIntensity = null
    }
}

/** Provided by [FoamNavHost]; the background settings screen pushes into it. */
val LocalBackgroundSettingsPreview = staticCompositionLocalOf { BackgroundSettingsPreviewState() }

/**
 * Destination wrapper that applies the status-bar inset INSIDE each destination.
 *
 * The inset used to be applied once, around the whole NavHost. The NavHost's
 * enter/exit fades (graphicsLayer alpha < 1) render each destination into an
 * offscreen layer sized to the destination's bounds, so anything a screen draws
 * above its top edge — the immersive hero artwork bleeding under the status bar —
 * was cropped for the duration of every fade (album <-> artist, etc.) and the
 * dimmed root backdrop showed through instead. With the padding moved inside,
 * the fading layer spans the full screen and the bleed survives the transition.
 * Layout of the screens is unchanged: they still start below the inset.
 */
private fun NavGraphBuilder.composable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
    // Settings pages are opaque and sit on the plain theme background (the root
    // art/custom/gradient layers are hidden on settings routes). Painting that
    // background BEFORE the status-bar padding makes the page cover the status
    // bar zone too, so it slides/fades as one surface instead of leaving the
    // root backdrop visible behind the status bar during the transition.
    // The layer is also flattened offscreen so the NavHost fade applies to the
    // background and the items as ONE bitmap; otherwise each is blended at the
    // fade alpha on its own and the items read as fading in after the background.
    //
    // EXCEPTION — the background settings page: it is the one place where the
    // backdrop layers must stay visible (they ARE the live preview of the
    // parameters being edited), so it gets no opaque surface; its content
    // composes straight over whatever the root backdrop draws.
    val isSettings = isSettingsRoute(route)
    val paintsOpaqueSurface = isSettings && !isBackgroundSettingsRoute(route)
    navComposable(route = route, arguments = arguments) { entry ->
        val contentScope = this
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (paintsOpaqueSurface) {
                        Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .background(FoamTheme.colors.background)
                    } else {
                        Modifier
                    }
                )
                .statusBarsPadding()
        ) {
            contentScope.content(entry)
        }
    }
}

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

/** The one settings page that previews the backdrop layers live. */
private fun isBackgroundSettingsRoute(route: String?): Boolean =
    route == Screen.SettingsBackground.route

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
 *
 * Blur and app intensity come from the persisted settings, overridden per frame
 * by [preview] while the background settings screen edits them — the reads
 * happen inside this function's own recompose scope, so slider drags never
 * invalidate [FoamNavHost]'s body.
 */
@Composable
private fun rememberAnimatedBackground(
    enabled: Boolean,
    initialFrontArtPath: () -> String?,
    persistedBlur: Int,
    persistedAppIntensity: Int,
    preview: BackgroundSettingsPreviewState,
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
            blur = backgroundBlurRadius(preview.backgroundBlur ?: persistedBlur),
            intensity = (preview.appBackgroundIntensity ?: persistedAppIntensity) / 100f,
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
 * The user-configured static backdrop layers (custom blurred image / gradient)
 * for the non-dynamic modes, with the background settings page's live overrides
 * applied. Reads [preview] INSIDE this scope so per-frame edits (gradient dots,
 * sliders) recompose only this layer, never the navigation tree.
 */
@Composable
private fun StaticBackgroundLayers(
    mode: BackgroundMode,
    customBackgroundUri: String?,
    persistedGradientStops: List<GradientStop>,
    persistedBackgroundBlur: Int,
    persistedAppIntensity: Int,
    preview: BackgroundSettingsPreviewState,
    modifier: Modifier = Modifier
) {
    when (mode) {
        BackgroundMode.CUSTOM -> CustomImageBackground(
            imageUri = customBackgroundUri,
            blur = backgroundBlurRadius(preview.backgroundBlur ?: persistedBackgroundBlur),
            intensity = (preview.appBackgroundIntensity ?: persistedAppIntensity) / 100f,
            modifier = modifier
        )
        BackgroundMode.GRADIENT -> GradientBackground(
            stops = preview.gradientStops ?: persistedGradientStops,
            intensity = (preview.appBackgroundIntensity ?: persistedAppIntensity) / 100f,
            modifier = modifier
        )
        BackgroundMode.DYNAMIC -> Unit
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
    var playlistPickerTrack by remember { mutableStateOf<TrackEntity?>(null) }

    var pickerBatchCollection by remember { mutableStateOf<CollectionInfo?>(null) }
    var pickerBatchTracks by remember { mutableStateOf<List<TrackEntity>?>(null) }

    // In playlist -> add to playlist, the source playlist itself is hidden from the
    // picker.
    var pickerExcludedPlaylistId by remember { mutableStateOf<Long?>(null) }

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

    // Song radio sheet: the similar list is built per seed track on open.
    val trackRadioViewModel: TrackRadioViewModel = hiltViewModel()
    val trackRadioState by trackRadioViewModel.state.collectAsState()

    val pickerTrackId = playlistPickerTrack?.id ?: trackModalState.track?.id
    val playlistIdsWithTrack by remember(pickerTrackId) {
        playlistsViewModel.playlistIdsContainingTrack(pickerTrackId)
    }.collectAsState(initial = emptySet())

    val batchTrackIds = pickerBatchTracks?.map { it.id } ?: emptyList()
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

    // Cold start straight into the player: restore animated transitions once the first
    // frames are composed (see animatePlayerTransition for the warm-start equivalent).
    LaunchedEffect(Unit) {
        if (initialOpenRequested) {
            withFrameNanos { }
            withFrameNanos { }
            isTransitionAnimated = true
        }
    }

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
                    playerTransitionState.snapTo(open)
                    if (transitionJobHolder.generation == myGeneration) {
                        isTransitionAnimated = true
                    }
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
                            (playerTransitionState.targetState && trackListTransitionTrackId != null))
        }

        CompositionLocalProvider(
            LocalTrackFoamModalState provides trackModalState,
            LocalCollectionFoamModalState provides collectionModalState,
            LocalOpenPlaylistPickerForPlaylist provides { source ->
                pickerExcludedPlaylistId = source.playlistId
                scope.launch {
                    val tracks = playlistsViewModel.playlistTracks(source.playlistId)
                    if (tracks.isEmpty()) return@launch
                    pickerBatchCollection = CollectionInfo(
                        title = source.name,
                        subtitle = null,
                        imageUrl = null,
                        imageIsEmbeddedArt = false,
                        kind = CollectionKind.Playlist,
                        tracks = tracks
                    )
                    pickerBatchTracks = tracks
                }
            },
            LocalBottomBarClearance provides bottomBarClearance,
            LocalCollectionHeroLayoutLookup provides heroLayoutFor,
            LocalActiveSharedCollection provides activeSharedCollection,
            LocalSearchFocusRequest provides searchFocusRequest,
            LocalBackgroundSettingsPreview provides backgroundSettingsPreview
        ) {
            Box(modifier = modifier.fillMaxSize().background(FoamTheme.colors.background)) {

                // Settings pages hide the backdrop layers (they sit on the plain
                // theme background) — after the nav fade, so the outgoing screen
                // dissolves over the still-drawn backdrop. The background settings
                // page is the exception: the layers ARE its live preview.
                var settingsBackdropHidden by remember { mutableStateOf(isSettingsRoute(currentRoute)) }
                LaunchedEffect(currentRoute) {
                    if (isSettingsRoute(currentRoute) && !isBackgroundSettingsRoute(currentRoute)) {
                        delay(NavScreenFadeDurationMs.toLong())
                        settingsBackdropHidden = true
                    } else {
                        settingsBackdropHidden = false
                    }
                }
                val backgroundEnabled = backgroundMode == BackgroundMode.DYNAMIC && !settingsBackdropHidden
                val onBackgroundTransition = if (backgroundEnabled) {
                    rememberAnimatedBackground(
                        enabled = true,
                        initialFrontArtPath = { playerViewModel.playbackState.value.currentTrack?.albumArtPath },
                        persistedBlur = playerSettings.backgroundBlur,
                        persistedAppIntensity = playerSettings.appBackgroundIntensity,
                        preview = backgroundSettingsPreview,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    remember { { _: String?, _: String?, _: Float -> } }
                }

                // While the background settings page previews the art backdrop, the
                // current track's art must keep feeding it even with the player
                // closed — normally only the open player drives this collector and
                // the visible mini player covers the rest. rememberUpdatedState
                // keeps the read inside the collector fresh across route changes.
                val feedArtToBackground by rememberUpdatedState(
                    playerTransitionState.targetState || isBackgroundSettingsRoute(currentRoute)
                )
                LaunchedEffect(onBackgroundTransition) {
                    playerViewModel.currentAlbumArtPath.collect { path ->
                        if (path != null && feedArtToBackground) {
                            onBackgroundTransition(path, null, 0f)
                        }
                    }
                }

                // The user-configured static layers (custom blurred image / gradient):
                // same dim as the dynamic art, hidden on settings pages like the art —
                // EXCEPT the background settings page, where they are the live
                // preview of the parameters being edited.
                // They are never suppressed on detail pages: the collection backdrop
                // below paints over them once opaque, and keeping them composed
                // underneath makes every fade crossfade over the custom image /
                // gradient instead of dropping to the bare background color.
                if (!settingsBackdropHidden && backgroundMode != BackgroundMode.DYNAMIC) {
                    StaticBackgroundLayers(
                        mode = backgroundMode,
                        customBackgroundUri = playerSettings.customBackgroundUri,
                        persistedGradientStops = playerSettings.gradientStops,
                        persistedBackgroundBlur = playerSettings.backgroundBlur,
                        persistedAppIntensity = playerSettings.appBackgroundIntensity,
                        preview = backgroundSettingsPreview,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Full-bleed backdrop for the detail list screens (album/playlist/artist),
                // drawn at the root so it covers the status bar and nav zone. Its alpha
                // tween runs in lock-step with the screens' fade (same duration rule as
                // the NavHost's fades for the route PAIR, and the same easing); between
                // two detail routes the alpha never leaves 1f and the art switch is a
                // front/back crossfade instead. Art-driven: it applies in DYNAMIC mode,
                // and in any mode while "force dynamic background in collections" is on.
                // The static layers composed above stay underneath it, so its fades
                // crossfade over the custom image / gradient instead of the bare
                // background color.
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
                    // Keyed on the ROUTE as well as the art: the art is only cleared from a
                    // LaunchedEffect (a frame or more after the route flips), so keying on
                    // art alone started this fade late while the screens were already
                    // fading, and the status-bar strip (painted only by this backdrop)
                    // held full alpha then dropped out of step.
                    targetValue = if (listBackdropArt != null && isDetailListRoute(currentRoute)) 1f else 0f,
                    animationSpec = tween(
                        durationMillis = if (isSharedElementRoute(currentRoute) ||
                            isSharedElementRoute(lastRoute)
                        ) {
                            SharedElementTransitionDurationMs
                        } else {
                            NavScreenFadeDurationMs
                        },
                        // Lock-step with the NavHost's screen fades (FastOutSlowIn):
                        // animateFloatAsState's tween defaults to LINEAR, which made the
                        // backdrop — and with it the status-bar strip it alone paints —
                        // linger half-strength over the already-faded content while a
                        // collection closed.
                        easing = SharedElementEasing
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
                        blur = backgroundBlurRadius(playerSettings.backgroundBlur),
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
                    val progress = playerTransitionState.progress().coerceIn(0f, 1f)
                    if (playerTransitionState.targetState) {
                        EaseOutCubic.transform(progress)
                    } else {
                        EaseInCubic.transform(progress)
                    }
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
                        backgroundBlur = backgroundBlurRadius(playerSettings.backgroundBlur),
                        intensity = playerSettings.playerBackgroundIntensity / 100f,
                        bottomFadeInset = playerBackdropFadeInset,
                        forceDynamic = forceDynamicBackgroundInPlayer,
                        canvasUrl = fullPlayerBackdropState.canvasUrl,
                        canvasPlaying = fullPlayerBackdropState.canvasPlaying,
                        canvasAnchor = fullPlayerBackdropState.canvasAnchor,
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
                    activeSharedCollection = null
                    // Only the search icon's tap raises the focused-search request just
                    // before landing here; any other destination cancels a stale one.
                    if (route != Screen.Search.route) searchFocusRequest.consume()
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
                            Box(modifier = Modifier.fillMaxSize()) {
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
                                    navComposable(Screen.Onboarding.route) {
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
                                                val cover = playlistCovers[playlist.playlistId]
                                                navController.navigate(
                                                    Screen.PlaylistDetail.createRoute(
                                                        playlist.playlistId,
                                                        playlist.name,
                                                        cover?.imageUrl ?: playlist.imageUrl,
                                                        cover?.isEmbeddedArt ?: false
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
                                            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(
                                                    Screen.ArtistDetail.createRoute(artist, image, imageIsEmbeddedArt)
                                                )
                                            },
                                            onTrackClick = { track -> playTrackFromList(listOf(track), 0) },
                                            onSeeAllAlbums = { navController.navigate(Screen.HomeSeeAllAlbums.route) },
                                            onSeeAllRecent = { navController.navigate(Screen.HomeSeeAllRecentTracks.route) },
                                            onSeeAllFavorites = { navController.navigate(Screen.HomeSeeAllTopTracks.route) },
                                            onSeeAllArtists = { navController.navigate(Screen.HomeSeeAllArtists.route) },
                                            onSeeAllFavoriteTracks = { navController.navigate(Screen.HomeSeeAllFavoriteTracks.route) },
                                            onSeeAllRecommendedTracks = { navController.navigate(Screen.HomeSeeAllRecommendedTracks.route) },
                                            onSeeAllOldFavoriteAlbums = { navController.navigate(Screen.HomeSeeAllOldFavoriteAlbums.route) },
                                            onSeeAllRecentlyAddedAlbums = { navController.navigate(Screen.HomeSeeAllRecentlyAddedAlbums.route) },
                                            onSeeAllRecentlyAddedArtists = { navController.navigate(Screen.HomeSeeAllRecentlyAddedArtists.route) },
                                            onOpenHomeSettings = { navController.navigate(Screen.SettingsUIHome.route) },
                                            onOpenLibrarySettings = { navController.navigate(Screen.SettingsLibrary.route) },
                                            sections = playerSettings.homeSections,
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
                                                val cover = playlistCovers[playlist.playlistId]
                                                navController.navigate(
                                                    Screen.PlaylistDetail.createRoute(
                                                        playlist.playlistId,
                                                        playlist.name,
                                                        cover?.imageUrl ?: playlist.imageUrl,
                                                        cover?.isEmbeddedArt ?: false
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
                                        FavoriteTracksSeeAllScreen(
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
                                    composable(Screen.HomeSeeAllRecommendedTracks.route) {
                                        RecommendedTracksSeeAllScreen(
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index)
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeTrackId = activeTrackId,
                                            density = densityFor(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS),
                                            onDensityChange = { setDensity(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS, it) },
                                            sortMode = sortModeFor(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS),
                                            onSortChange = { setSortMode(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.HOME_SEE_ALL_RECOMMENDED_TRACKS, it) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Default),
                                            viewModel = homeViewModel
                                        )
                                    }
                                    composable(Screen.HomeSeeAllTopTracks.route) {
                                        OldFavoritesSeeAllScreen(
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index)
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeTrackId = activeTrackId,
                                            density = densityFor(MediaListView.HOME_SEE_ALL_TOP_TRACKS),
                                            onDensityChange = { setDensity(MediaListView.HOME_SEE_ALL_TOP_TRACKS, it) },
                                            sortMode = sortModeFor(MediaListView.HOME_SEE_ALL_TOP_TRACKS),
                                            onSortChange = { setSortMode(MediaListView.HOME_SEE_ALL_TOP_TRACKS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.HOME_SEE_ALL_TOP_TRACKS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.HOME_SEE_ALL_TOP_TRACKS, it) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Default),
                                            viewModel = homeViewModel
                                        )
                                    }
                                    composable(Screen.HomeSeeAllOldFavoriteAlbums.route) {
                                        OldFavoriteAlbumsSeeAllScreen(
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
                                            density = densityFor(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS),
                                            onDensityChange = { setDensity(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS, it) },
                                            sortMode = sortModeFor(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS),
                                            onSortChange = { setSortMode(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.HOME_SEE_ALL_OLD_FAVORITE_ALBUMS, it) },
                                            viewModel = homeViewModel
                                        )
                                    }
                                    composable(Screen.HomeSeeAllRecentlyAddedAlbums.route) {
                                        RecentlyAddedAlbumsSeeAllScreen(
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
                                            density = densityFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS),
                                            onDensityChange = { setDensity(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS, it) },
                                            sortMode = sortModeFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS),
                                            onSortChange = { setSortMode(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ALBUMS, it) },
                                            viewModel = homeViewModel
                                        )
                                    }
                                    composable(Screen.HomeSeeAllArtists.route) {
                                        FavoriteArtistsSeeAllScreen(
                                            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(
                                                    Screen.ArtistDetail.createRoute(artist, image, imageIsEmbeddedArt)
                                                )
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
                                    composable(Screen.HomeSeeAllRecentlyAddedArtists.route) {
                                        RecentlyAddedArtistsSeeAllScreen(
                                            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(
                                                    Screen.ArtistDetail.createRoute(artist, image, imageIsEmbeddedArt)
                                                )
                                            },
                                            onBack = { navController.popBackStack() },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS),
                                            onDensityChange = { setDensity(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS, it) },
                                            sortMode = sortModeFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS),
                                            onSortChange = { setSortMode(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.HOME_SEE_ALL_RECENTLY_ADDED_ARTISTS, it) },
                                            viewModel = homeViewModel
                                        )
                                    }
                                    composable(Screen.Playlists.route) {
                                        PlaylistsScreen(
                                            onPlaylistClick = { playlist ->
                                                activeSharedCollection = sharedCollectionTag("playlist", "${playlist.playlistId}")
                                                val cover = playlistCovers[playlist.playlistId]
                                                navController.navigate(
                                                    Screen.PlaylistDetail.createRoute(
                                                        playlist.playlistId,
                                                        playlist.name,
                                                        cover?.imageUrl ?: playlist.imageUrl,
                                                        cover?.isEmbeddedArt ?: false
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
                                            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(
                                                    Screen.ArtistDetail.createRoute(artist, image, imageIsEmbeddedArt)
                                                )
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
                                            onBrowseComposers = { navController.navigate(Screen.BrowseComposers.route) },
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
                                            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(
                                                    Screen.ArtistDetail.createRoute(artist, image, imageIsEmbeddedArt)
                                                )
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
                                    composable(Screen.BrowseComposers.route) {
                                        ComposersBrowseScreen(
                                            onComposerClick = { composer ->
                                                navController.navigate(Screen.BrowseComposerDetail.createRoute(composer))
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeCollectionId = activeCollectionId,
                                            density = densityFor(MediaListView.BROWSE_COMPOSERS),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_COMPOSERS, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_COMPOSERS),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_COMPOSERS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_COMPOSERS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_COMPOSERS, it) }
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
                                            isPlaybackActive = isPlaybackActive,
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
                                            isPlaybackActive = isPlaybackActive,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Default),
                                            immersiveMode = playerSettings.immersiveModeEnabled
                                        )
                                    }
                                    composable(
                                        Screen.BrowseComposerDetail.route,
                                        arguments = listOf(navArgument("composer") { type = NavType.StringType })
                                    ) { backStackEntry ->
                                        val composer = backStackEntry.arguments?.getString("composer") ?: ""
                                        ComposerTracksScreen(
                                            composer = composer,
                                            onTrackClick = { tracks, index ->
                                                playTrackFromList(tracks, index, CollectionId.composer(composer))
                                            },
                                            onBack = { navController.popBackStack() },
                                            activeTrackId = activeTrackId,
                                            density = densityFor(MediaListView.BROWSE_COMPOSER_TRACKS),
                                            onDensityChange = { setDensity(MediaListView.BROWSE_COMPOSER_TRACKS, it) },
                                            sortMode = sortModeFor(MediaListView.BROWSE_COMPOSER_TRACKS),
                                            onSortChange = { setSortMode(MediaListView.BROWSE_COMPOSER_TRACKS, it) },
                                            sortInverted = sortInvertedFor(MediaListView.BROWSE_COMPOSER_TRACKS),
                                            onSortInvertedChange = { setSortInverted(MediaListView.BROWSE_COMPOSER_TRACKS, it) },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            centerTrackRequest = centerTrackRequest,
                                            isPlaybackActive = isPlaybackActive,
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
                                            onArtistClick = { artist, image, imageIsEmbeddedArt ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(
                                                    Screen.ArtistDetail.createRoute(artist, image, imageIsEmbeddedArt)
                                                )
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
                                            onArtistClick = { artistName, image, imageIsEmbeddedArt ->
                                                activeSharedCollection = sharedCollectionTag("artist", artistName)
                                                navController.navigate(
                                                    Screen.ArtistDetail.createRoute(artistName, image, imageIsEmbeddedArt)
                                                )
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
                                            onArtistClick = { artistName, image, imageIsEmbeddedArt ->
                                                activeSharedCollection = sharedCollectionTag("artist", artistName)
                                                navController.navigate(
                                                    Screen.ArtistDetail.createRoute(artistName, image, imageIsEmbeddedArt)
                                                )
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
                                            onNavigateToAbout = { navController.navigate(Screen.SettingsAbout.route) },
                                            // Compiled in only with devMode=true; the entry
                                            // disappears from Settings when the flag is off.
                                            onNavigateToTest = if (BuildConfig.DEV_MODE) {
                                                { navController.navigate(Screen.SettingsTest.route) }
                                            } else {
                                                null
                                            }
                                        )
                                    }
                                    composable(Screen.SettingsAudio.route) {
                                        AudioSettingsScreen(
                                            onBack = { navController.popBackStack() },
                                            onNavigateToPlayback = { navController.navigate(Screen.SettingsAudioPlayback.route) },
                                            onNavigateToDevices = { navController.navigate(Screen.SettingsAudioDevices.route) },
                                            onNavigateToEqualizer = { navController.navigate(Screen.SettingsAudioEqualizer.route) }
                                        )
                                    }
                                    composable(Screen.SettingsAudioPlayback.route) {
                                        PlaybackSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsAudioDevices.route) {
                                        AudioDevicesSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsAudioEqualizer.route) {
                                        EqualizerSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsUI.route) {
                                        UISettingsScreen(
                                            onBack = { navController.popBackStack() },
                                            onNavigateToColors = { navController.navigate(Screen.SettingsColors.route) },
                                            onNavigateToPlayer = { navController.navigate(Screen.SettingsPlayer.route) },
                                            onNavigateToNavBar = { navController.navigate(Screen.SettingsNavBar.route) },
                                            onNavigateToBackground = { navController.navigate(Screen.SettingsBackground.route) },
                                            onNavigateToCollections = { navController.navigate(Screen.SettingsUICollections.route) },
                                            onNavigateToHome = { navController.navigate(Screen.SettingsUIHome.route) }
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
                                    composable(Screen.SettingsUIHome.route) {
                                        HomeSettingsScreen(onBack = { navController.popBackStack() })
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
                                        MiscSettingsScreen(
                                            onBack = { navController.popBackStack() },
                                            onNavigateToNotification = {
                                                navController.navigate(Screen.SettingsNotification.route)
                                            },
                                            onNavigateToLastFm = {
                                                navController.navigate(Screen.SettingsLastFm.route)
                                            },
                                            onNavigateToDiscord = {
                                                navController.navigate(Screen.SettingsDiscord.route)
                                            }
                                        )
                                    }
                                    composable(Screen.SettingsNotification.route) {
                                        NotificationSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsLastFm.route) {
                                        LastFmSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsDiscord.route) {
                                        DiscordSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    composable(Screen.SettingsAbout.route) {
                                        AboutSettingsScreen(onBack = { navController.popBackStack() })
                                    }
                                    // Developer test screen: registered only with devMode=true.
                                    if (BuildConfig.DEV_MODE) {
                                        composable(Screen.SettingsTest.route) {
                                            TestSettingsScreen(
                                                onBack = { navController.popBackStack() },
                                                onOnboardingReset = {
                                                    // Re-run the first-run flow from a clean stack.
                                                    navController.navigate(Screen.Onboarding.route) {
                                                        popUpTo(0) { inclusive = true }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    composable(
                                        Screen.ArtistDetail.route,
                                        arguments = listOf(
                                            navArgument("artistName") { type = NavType.StringType },
                                            navArgument("image") { type = NavType.StringType; defaultValue = "" },
                                            navArgument("embedded") { type = NavType.StringType; defaultValue = "false" }
                                        )
                                    ) { backStackEntry ->
                                        val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
                                        val seedImageUrl = backStackEntry.arguments?.getString("image")
                                            ?.takeIf { it.isNotBlank() }
                                        val seedImageIsEmbeddedArt = backStackEntry.arguments
                                            ?.getString("embedded")?.toBooleanStrictOrNull() ?: false
                                        ArtistDetailScreen(
                                            artistName = artistName,
                                            seedImageUrl = seedImageUrl,
                                            seedImageIsEmbeddedArt = seedImageIsEmbeddedArt,
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
                                            isPlaybackActive = isPlaybackActive,
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
                                            onArtistClick = { artist ->
                                                activeSharedCollection = sharedCollectionTag("artist", artist)
                                                navController.navigate(
                                                    Screen.ArtistDetail.createRoute(artist)
                                                )
                                            },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this,
                                            playerTransitionVisibilityScope = playerTransitionVisibilityScope,
                                            sharedTrackId = trackListTransitionTrackId,
                                            activeTrackId = activeTrackId,
                                            onListBackdropArt = onListBackdropArt,
                                            centerTrackRequest = centerTrackRequest,
                                            isPlaybackActive = isPlaybackActive,
                                            heroLayout = heroLayoutFor(CollectionHeroTarget.Album),
                                            immersiveMode = playerSettings.immersiveModeEnabled
                                        )
                                    }
                                    composable(
                                        Screen.PlaylistDetail.route,
                                        arguments = listOf(
                                            navArgument("playlistId") { type = NavType.LongType },
                                            navArgument("name") { type = NavType.StringType; defaultValue = "" },
                                            navArgument("imageUrl") { type = NavType.StringType; defaultValue = "" },
                                            navArgument("embedded") { type = NavType.StringType; defaultValue = "false" }
                                        )
                                    ) { backStackEntry ->
                                        val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: -1L
                                        val initialName = backStackEntry.arguments?.getString("name").orEmpty()
                                        val initialImageUrl = backStackEntry.arguments?.getString("imageUrl")?.ifEmpty { null }
                                        val initialImageIsEmbeddedArt = backStackEntry.arguments
                                            ?.getString("embedded")?.toBooleanStrictOrNull() ?: false
                                        PlaylistDetailScreen(
                                            playlistId = playlistId,
                                            initialName = initialName,
                                            initialImageUrl = initialImageUrl,
                                            initialImageIsEmbeddedArt = initialImageIsEmbeddedArt,
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
                                            isPlaybackActive = isPlaybackActive,
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

                if (currentRoute != Screen.Onboarding.route) {
                    val bottomScrimColor = backdropScrimColor()
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(bottomBarClearance)
                            .background(
                                Brush.verticalGradient(
                                    0f to bottomScrimColor.copy(alpha = 0f),
                                    1f to bottomScrimColor.copy(alpha = BottomChromeScrimMaxAlpha)
                                )
                            )
                    )
                }

                // Nav bar + mini player overlay, hidden on onboarding.
                if (currentRoute != Screen.Onboarding.route) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onSizeChanged { navZoneHeightPx = it.height }
                            .collectionHeroOverlayLayer(
                                sharedTransitionScope = this@SharedTransitionLayout,
                                zIndexInOverlay = BottomChromeNavBarOverlayZIndex,
                                renderInOverlay = isBottomChromeLifted
                            )
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
                        .collectionHeroOverlayLayer(
                            sharedTransitionScope = this@SharedTransitionLayout,
                            zIndexInOverlay = BottomChromeMiniPlayerOverlayZIndex,
                            renderInOverlay = isBottomChromeLifted
                        )
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

                // In-app toasts notifications
                ToastStack(
                    toasts = toastViewModel.toastCenter.toasts,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = miniPlayerBottomPadding + miniPlayerBoxHeight + ToastsAboveMiniPlayerGap)
                )
            }

            // In-app update sheets: startup "new update available" (auto-check)
            // and the "install" prompt raised whenever a download completes.
            // Dismissing a sheet never stops the download; only the explicit
            // "Cancel download" button does.
            UpdateFoamModal(
                startupRelease = updateStartupRelease,
                downloadState = updateDownloadState,
                installFile = updateInstallFile,
                onDismissStartup = updateManager::dismissStartupRelease,
                onCancelDownload = updateManager::cancelDownload,
                onDownload = updateManager::downloadAndInstall,
                onInstall = updateManager::installApk,
                onDismissInstall = updateManager::dismissInstallPrompt
            )

            // Long-press track actions (plain Dialogs hosted at the root).
            TrackFoamModal(
                state = trackModalState,                onPlayNext = playerViewModel::playNext,
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
                onSeeRadio = { track -> trackRadioViewModel.openRadioFor(track) },
                onOpenLyrics = { track ->
                    lyricsModalTrack = track
                    lyricsDetailsViewModel.loadTrackDetails(track)
                },
                onResetPlayStats = { track ->
                    playerViewModel.resetPlayStats(track.id)
                    trackModalState.updateCurrentTrack { it.copy(playCount = 0, lastPlayedAt = null) }
                },
                onApplyMetadata = { track, edit ->
                    playerViewModel.updateTrackMetadata(track, edit)
                    trackModalState.updateCurrentTrack {
                        it.copy(
                            title = edit.title,
                            artist = edit.artist,
                            album = edit.album,
                            albumArtist = edit.albumArtist,
                            genre = edit.genre.ifEmpty { null },
                            composer = edit.composer,
                            year = edit.year,
                            trackNumber = edit.trackNumber,
                            discNumber = edit.discNumber
                        )
                    }
                },
                onSetInstrumental = { track, instrumental ->
                    playerViewModel.setTrackInstrumental(track.id, instrumental)
                    trackModalState.updateCurrentTrack { it.copy(instrumental = instrumental) }
                },
                scoreOverrides = scoreOverrides,
                existingGenres = libraryGenres,
                onSetScore = playerViewModel::setTrackScore,
                useAlbumArtAccent = playerSettings.automaticColorEnabled
            )

            PlaylistPickerModal(
                track = playlistPickerTrack,
                playlists = pickerExcludedPlaylistId
                    ?.let { excluded -> playlists.filterNot { it.playlistId == excluded } }
                    ?: playlists,
                playlistCovers = playlistCovers,
                playlistIdsWithTrack = if (pickerBatchCollection != null) {
                    playlistIdsWithBatch
                } else {
                    playlistIdsWithTrack
                },
                insertPosition = addTrackInsertPosition,
                onToggle = { playlist, checked ->
                    val batchTrackList = pickerBatchTracks
                    if (pickerBatchCollection != null && batchTrackList != null) {
                        if (checked) {
                            playlistsViewModel.addTracksToPlaylist(playlist.playlistId, batchTrackList.map { it.id })
                        } else {
                            playlistsViewModel.removeTracksFromPlaylist(playlist.playlistId, batchTrackList.map { it.id })
                        }
                    } else {
                        playlistPickerTrack?.let { track ->
                            if (checked) {
                                playlistsViewModel.addTrackToPlaylist(playlist.playlistId, track.id)
                            } else {
                                playlistsViewModel.removeTrackFromPlaylist(playlist.playlistId, track.id)
                            }
                        }
                    }
                },
                onInsertPositionChange = playlistsViewModel::setAddTrackInsertPosition,
                onCreatePlaylist = { name -> playlistsViewModel.createPlaylist(name) },
                onDismiss = {
                    playlistPickerTrack = null
                    pickerBatchCollection = null
                    pickerBatchTracks = null
                    pickerExcludedPlaylistId = null
                },
                collection = pickerBatchCollection,
                collectionTracks = pickerBatchTracks ?: emptyList()
            )

            TrackRadioModal(
                visible = trackRadioState.sourceTrack != null,
                sourceTrack = trackRadioState.sourceTrack,
                tracks = trackRadioState.tracks,
                isLoading = trackRadioState.isLoading,
                useAlbumArtAccent = playerSettings.automaticColorEnabled,
                onPlay = { startIndex ->
                    val radioTracks = trackRadioState.tracks
                    if (radioTracks.isNotEmpty()) {
                        playerViewModel.playQueueReplacingUpcoming(radioTracks, startIndex)
                    }
                    trackRadioViewModel.dismissRadio()
                },
                onDismiss = trackRadioViewModel::dismissRadio
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
                onAddToPlaylist = { collection ->
                    // Nothing to add for a track-less collection
                    if (collection.tracks.isNotEmpty()) {
                        pickerBatchCollection = collection
                        pickerBatchTracks = collection.tracks
                    }
                },
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
