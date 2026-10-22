package com.foamplayer.app.ui.navigation
import android.os.Bundle
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable as navComposable
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.BackgroundMode
import com.foamplayer.app.data.settings.GradientStop
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.screens.player.CustomImageBackground
import com.foamplayer.app.ui.screens.player.DynamicAlbumArtBackground
import com.foamplayer.app.ui.screens.player.GradientBackground
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Top-level support for [FoamNavHost]: transition timing, the background-layer subsystem and the
 * route classifiers that decide which chrome a destination shows.
 *
 * Split out of `FoamNavigation.kt` so the host composable holds only the navigation wiring.
 */
/** Longest a programmatic mini<->full player transition may take. */
internal const val PlayerTransitionMaxDurationMs = 500

/**
 * Duration budget for a programmatic close flight (no finger on screen),
 * longer than a gesture close so it never reads as an instant snap.
 */
internal const val AutoCloseFlightDurationMs = 700

/** Shortest a flick-committed close flight may take. */
internal const val MinGestureCloseFlightMs = 240

/** Fade duration for non-shared-element screen-to-screen navigation. */
internal const val NavScreenFadeDurationMs = 400

/** Settings and browse subscreens slide by a fraction of their width when pushed/popped. */
internal const val SubScreenSlideDivisor = 3

/**
 * Peak alpha of the theme-aware scrim drawn behind the bottom chrome (app nav
 * bar, mini player card and the system navigation bar's inset). 0.5 at the very
 * bottom edge of the screen, fading to fully transparent at the top of the bar
 * zone (see the layer in [FoamNavHost]).
 */
internal const val BottomChromeScrimMaxAlpha = 0.9f

/**
 * zIndex of the nav bar while lifted into the shared-element overlay (see
 * [FoamNavHost]'s bottom-chrome lift): above the flying artwork (0f), texts
 * (1f) and every screen-lifted chrome layer (0.5f/1f).
 */
internal const val BottomChromeNavBarOverlayZIndex = 10f

/**
 * zIndex of the mini player card while lifted: above the lifted nav bar, so
 * the card keeps its at-rest position on top of the bar (they touch in
 * ATTACHED mode, where the card sinks into the bar's surface extension).
 */
internal const val BottomChromeMiniPlayerOverlayZIndex = 11f

/**
 * Gap between the mini player's top and the bottom of the toast stack sitting
 * above it.
 */
internal val ToastsAboveMiniPlayerGap = 12.dp

/**
 * The stored background-blur percentage rendered as an actual Gaussian radius:
 * the slider value is perceptual (see [AppSettings.backgroundBlurRadiusDp]), so
 * every backdrop layer converts it through that mapping instead of treating
 * percent as dp.
 */
internal fun backgroundBlurRadius(blurPercent: Int): Dp =
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
internal fun NavGraphBuilder.composable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
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

internal fun isSharedElementRoute(route: String?): Boolean {
    if (route == null) return false
    return route == Screen.Home.route ||
            route == Screen.Search.route ||
            route == Screen.Playlists.route ||
            route.startsWith("artist/") ||
            route.startsWith("album/") ||
            route.startsWith("playlist/")
}

internal fun isSettingsRoute(route: String?): Boolean {
    return route != null && (route == Screen.Settings.route || route.startsWith("settings/"))
}

/** The one settings page that previews the backdrop layers live. */
internal fun isBackgroundSettingsRoute(route: String?): Boolean =
    route == Screen.SettingsBackground.route

/** Stats overview and its see-all pages slide like the settings stack. */
internal fun isStatsRoute(route: String?): Boolean =
    route != null && (route == Screen.Stats.route || route.startsWith("stats/"))

/** Browse list pages (plus their sub-pages) slide over the screen they leave. */
internal fun isBrowseRoute(route: String?): Boolean = route != null && route.startsWith("browse/")

/** Detail screens whose list background is drawn full-bleed at the root. */
internal fun isDetailListRoute(route: String?): Boolean =
    route == Screen.AlbumDetail.route ||
            route == Screen.PlaylistDetail.route ||
            route == Screen.ArtistDetail.route ||
            route == Screen.ArtistSongs.route

/**
 * True when the page on top of the back stack IS the collection the queue was
 * started from — the only case where a player dismiss can fly the artwork into
 * that page's playing row instead of the mini player.
 */
internal fun isOriginCollectionPage(
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
internal data class ListBackdropArt(val path: String, val isEmbeddedArt: Boolean)

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
internal fun rememberAnimatedBackground(
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
internal fun StaticBackgroundLayers(
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
