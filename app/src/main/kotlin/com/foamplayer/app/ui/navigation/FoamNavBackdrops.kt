package com.foamplayer.app.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.BackgroundMode
import com.foamplayer.app.ui.screens.player.AlbumArtBackdrop
import com.foamplayer.app.ui.screens.player.DynamicAlbumArtBackground
import com.foamplayer.app.ui.screens.player.FullPlayerBackdropState
import com.foamplayer.app.ui.screens.player.PlayerDimens
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.player.backdropScrimColor
import com.foamplayer.app.ui.screens.player.progress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App-wide animated album-art backdrop, plus the static custom-image/gradient
 * layers used outside [BackgroundMode.DYNAMIC].
 *
 * Blur / intensity are read through [preview] inside this composable's scope, so
 * slider drags on the background settings page only redraw the backdrop. Returns
 * the setter the mini player uses to feed new artwork.
 */
@Composable
internal fun AppBackdrop(
    currentRoute: String?,
    backgroundMode: BackgroundMode,
    settings: AppSettings,
    preview: BackgroundSettingsPreviewState,
    playerViewModel: PlayerViewModel,
    playerTargetOpen: Boolean,
): (frontArtPath: String?, backArtPath: String?, fraction: Float) -> Unit {
    // Settings pages hide the backdrop layers (they sit on the plain theme
    // background) — after the nav fade, so the outgoing screen dissolves over the
    // still-drawn backdrop. The background settings page is the exception: the
    // layers ARE its live preview.
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
            persistedBlur = settings.backgroundBlur,
            persistedAppIntensity = settings.appBackgroundIntensity,
            preview = preview,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        remember { { _: String?, _: String?, _: Float -> } }
    }

    // While the background settings page previews the art backdrop, the current
    // track's art must keep feeding it even with the player closed — normally
    // only the open player drives this collector and the visible mini player
    // covers the rest. rememberUpdatedState keeps the read inside the collector
    // fresh across route changes.
    val feedArtToBackground by rememberUpdatedState(
        playerTargetOpen || isBackgroundSettingsRoute(currentRoute)
    )
    LaunchedEffect(onBackgroundTransition) {
        playerViewModel.currentAlbumArtPath.collect { path ->
            if (path != null && feedArtToBackground) {
                onBackgroundTransition(path, null, 0f)
            }
        }
    }

    // The user-configured static layers (custom blurred image / gradient): same
    // dim as the dynamic art, hidden on settings pages like the art — EXCEPT the
    // background settings page, where they are the live preview of the
    // parameters being edited. They are never suppressed on detail pages: the
    // collection backdrop paints over them once opaque, and keeping them
    // composed underneath makes every fade crossfade over the custom image /
    // gradient instead of dropping to the bare background color.
    if (!settingsBackdropHidden && backgroundMode != BackgroundMode.DYNAMIC) {
        StaticBackgroundLayers(
            mode = backgroundMode,
            customBackgroundUri = settings.customBackgroundUri,
            persistedGradientStops = settings.gradientStops,
            persistedBackgroundBlur = settings.backgroundBlur,
            persistedAppIntensity = settings.appBackgroundIntensity,
            preview = preview,
            modifier = Modifier.fillMaxSize()
        )
    }

    return onBackgroundTransition
}

/**
 * State for the full-bleed detail-list backdrop: the art currently shown, the
 * last art (kept while fading out) and the front/back crossfade used when one
 * detail page replaces another.
 */
@Stable
internal class DetailListBackdropState(private val scope: CoroutineScope) {
    var art by mutableStateOf<ListBackdropArt?>(null)
        private set

    var lastArt by mutableStateOf<ListBackdropArt?>(null)
        private set

    var crossfadeFrom by mutableStateOf<ListBackdropArt?>(null)
        private set

    var crossfadeTo by mutableStateOf<ListBackdropArt?>(null)
        private set

    val crossfade = Animatable(1f)
    private var crossfadeGeneration = 0

    /** Receives a detail page's hero art. */
    fun onArt(path: String?, isEmbeddedArt: Boolean) {
        val next = path?.let { ListBackdropArt(it, isEmbeddedArt) }
        if (next != null) lastArt = next
        val previous = art
        art = next
        if (next != null && previous != null && previous != next) {
            // Detail -> detail navigation: fade the new art in on the back layer.
            val generation = ++crossfadeGeneration
            scope.launch {
                crossfadeFrom = previous
                crossfadeTo = next
                try {
                    crossfade.snapTo(0f)
                    crossfade.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = SharedElementTransitionDurationMs,
                            easing = SharedElementEasing
                        )
                    )
                } finally {
                    if (generation == crossfadeGeneration) {
                        crossfadeFrom = null
                        crossfadeTo = null
                    }
                }
            }
        } else {
            crossfadeGeneration++
            crossfadeFrom = null
            crossfadeTo = null
        }
    }

    /** Drops the art as soon as the route stops being a detail list page. */
    fun onRouteChanged(route: String?) {
        if (!isDetailListRoute(route)) art = null
    }
}

/**
 * Full-bleed backdrop for album/playlist/artist pages, drawn at the root so it
 * covers the status bar and nav zone. Its alpha tween runs in lock-step with the
 * NavHost fades; between two detail routes the alpha stays at 1f and only the
 * art crossfades.
 */
@Composable
internal fun DetailListBackdrop(
    state: DetailListBackdropState,
    currentRoute: String?,
    lastRoute: String?,
    backgroundMode: BackgroundMode,
    forceDynamic: Boolean,
    blur: Dp,
    intensity: Float,
) {
    val listBackdropArt = state.art
    val lastListBackdropArt = state.lastArt
    val listBackdropAlpha by animateFloatAsState(
        // Keyed on the ROUTE as well as the art: the art is only cleared from a
        // LaunchedEffect (a frame or more after the route flips), so keying on
        // art alone started this fade late while the screens were already
        // fading, and the status-bar strip (painted only by this backdrop) held
        // full alpha then dropped out of step.
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
            // linger half-strength over the already-faded content.
            easing = SharedElementEasing
        ),
        label = "listBackdropAlpha"
    )
    val display = listBackdropArt ?: lastListBackdropArt
    val show by remember(backgroundMode, forceDynamic) {
        derivedStateOf {
            (backgroundMode == BackgroundMode.DYNAMIC || forceDynamic) &&
                (listBackdropArt ?: lastListBackdropArt) != null && listBackdropAlpha > 0f
        }
    }
    if (show) {
        DynamicAlbumArtBackground(
            frontArtPath = (state.crossfadeFrom ?: display)?.path,
            frontArtIsEmbedded = (state.crossfadeFrom ?: display)?.isEmbeddedArt ?: true,
            backArtPath = state.crossfadeTo?.path,
            backArtIsEmbedded = state.crossfadeTo?.isEmbeddedArt ?: true,
            crossfadeFraction = { state.crossfade.value },
            blur = blur,
            intensity = intensity,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = listBackdropAlpha }
        )
    }
}

/**
 * Full player backdrop hosted at the very root, cropping the same art with the
 * same blur and dim as the app-wide backdrop so the open/close crossfade is
 * seamless. [forceDynamic] overrides CUSTOM/GRADIENT with the classic art
 * treatment.
 */
@Composable
internal fun PlayerBackdrop(
    transitionState: SeekableTransitionState<Boolean>,
    state: FullPlayerBackdropState,
    backgroundMode: BackgroundMode,
    settings: AppSettings,
    navigationBarMode: NavigationBarMode,
    navZoneHeightPx: Int,
    forceDynamic: Boolean,
) {
    // In DOCKED mode the backdrop's black fade must land on the bar's top edge.
    val playerBackdropFadeInset = if (navigationBarMode == NavigationBarMode.DOCKED) {
        with(androidx.compose.ui.platform.LocalDensity.current) { navZoneHeightPx.toDp() }
    } else {
        0.dp
    }
    val playerBackdropAlpha = {
        val progress = transitionState.progress().coerceIn(0f, 1f)
        if (transitionState.targetState) {
            EaseOutCubic.transform(progress)
        } else {
            EaseInCubic.transform(progress)
        }
    }
    if (transitionState.currentState || transitionState.targetState) {
        AlbumArtBackdrop(
            frontArtPath = state.frontArtPath,
            backArtPath = state.backArtPath,
            crossfadeFraction = state.crossfadeFraction,
            immersive = state.immersive,
            subViewProgress = state.subViewBlurProgress,
            backgroundMode = backgroundMode,
            customBackgroundUri = settings.customBackgroundUri,
            gradientStops = settings.gradientStops,
            backgroundBlur = backgroundBlurRadius(settings.backgroundBlur),
            intensity = settings.playerBackgroundIntensity / 100f,
            bottomFadeInset = playerBackdropFadeInset,
            forceDynamic = forceDynamic,
            canvasUrl = state.canvasUrl,
            canvasPlaying = state.canvasPlaying,
            canvasAnchor = state.canvasAnchor,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = playerBackdropAlpha() }
        )

        // Subview scrim, drawn by the player's container. Theme-aware: lightens
        // toward the background color in light mode.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = PlayerDimens.scrimMaxAlpha *
                        state.subViewBlurProgress() *
                        playerBackdropAlpha()
                }
                .background(backdropScrimColor())
        )
    }
}
