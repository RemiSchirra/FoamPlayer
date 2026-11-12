package com.foamplayer.app.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.foamplayer.app.ui.theme.FoamMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App-wide animated album-art backdrop, plus the static custom-image/gradient
 * layers used outside [BackgroundMode.DYNAMIC].
 *
 * Blur / intensity are read through [preview] inside this composable's scope, so
 * slider drags on the background settings page only redraw the backdrop. Returns
 * the setter the mini player uses to feed new artwork.
 *
 * The returned setter is wrapped in a [BackdropCrossfadeDriver]: continuous
 * input (the mini player scrubbing the pager offset) passes straight through,
 * while a DISCONTINUOUS change of artwork (list pick, queue replacement, player
 * skip, no pager motion behind it) is turned into a timed crossfade here instead
 * of snapping. That way the backdrop never depends on every caller animating.
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
    var settingsBackdropHidden by remember { mutableStateOf(isSettingsRoute(currentRoute)) }
    LaunchedEffect(currentRoute) {
        if (isSettingsRoute(currentRoute) && !isBackgroundSettingsRoute(currentRoute)) {
            delay(FoamMotion.Duration.FLIGHT.toLong())
            settingsBackdropHidden = true
        } else {
            settingsBackdropHidden = false
        }
    }
    // The delayed flag only hides after ENTERING settings. Leaving settings must
    // reveal the layers immediately, without waiting for the effect above, so
    // the outgoing opaque settings page fades onto an already-drawn backdrop.
    val backdropAlpha = if (
        settingsBackdropHidden &&
        isSettingsRoute(currentRoute) &&
        !isBackgroundSettingsRoute(currentRoute)
    ) {
        0f
    } else {
        1f
    }
    val backdropModifier = Modifier
        .fillMaxSize()
        .graphicsLayer { alpha = backdropAlpha }

    val onBackgroundTransition = rememberAnimatedBackground(
        enabled = backgroundMode == BackgroundMode.DYNAMIC,
        initialFrontArtPath = { playerViewModel.playbackState.value.currentTrack?.albumArtPath },
        persistedBlur = settings.backgroundBlur,
        persistedAppIntensity = settings.appBackgroundIntensity,
        preview = preview,
        modifier = backdropModifier
    )

    // Every caller (mini player, player collector below) goes through this
    // driver, so any hard cut in the artwork becomes a crossfade.
    val latestSink by rememberUpdatedState(onBackgroundTransition)
    val driverScope = rememberCoroutineScope()
    val driver = remember(driverScope) {
        BackdropCrossfadeDriver(driverScope) { front, back, fraction ->
            latestSink(front, back, fraction)
        }
    }
    val drivenTransition = remember(driver) {
        { front: String?, back: String?, fraction: Float -> driver.submit(front, back, fraction) }
    }

    val feedArtToBackground by rememberUpdatedState(
        playerTargetOpen || isBackgroundSettingsRoute(currentRoute)
    )
    LaunchedEffect(drivenTransition) {
        playerViewModel.currentAlbumArtPath.collect { path ->
            if (path != null && feedArtToBackground) {
                drivenTransition(path, null, 0f)
            }
        }
    }

    if (backgroundMode != BackgroundMode.DYNAMIC) {
        StaticBackgroundLayers(
            mode = backgroundMode,
            customBackgroundUri = settings.customBackgroundUri,
            persistedGradientStops = settings.gradientStops,
            persistedBackgroundBlur = settings.backgroundBlur,
            persistedAppIntensity = settings.appBackgroundIntensity,
            preview = preview,
            modifier = backdropModifier
        )
    }

    return drivenTransition
}

/**
 * Turns hard cuts in the backdrop artwork into timed crossfades.
 *
 * Input is a stream of `(front, back, fraction)` frames: the displayed image is
 * `front` at 0f, `back` at 1f, and a blend in between. While the pager is being
 * dragged or flung the frames are continuous, so they are forwarded untouched.
 * When a frame is visually far from the previous one (a different front art
 * with nothing scrubbing towards it) the driver plays its own 0 -> 1 fade from
 * the art that was on screen to the new one, then hands control back.
 */
private class BackdropCrossfadeDriver(
    private val scope: CoroutineScope,
    private val sink: (frontArtPath: String?, backArtPath: String?, fraction: Float) -> Unit,
) {
    private class Frame(val front: String?, val back: String?, val fraction: Float) {
        /** The art that dominates what is on screen. */
        val dominant: String? get() = if (fraction >= 0.5f) back else front
    }

    private val progress = Animatable(0f)
    private var job: Job? = null
    private var shown: Frame? = null
    private var latest: Frame? = null
    private var animatingTo: String? = null

    fun submit(front: String?, back: String?, fraction: Float) {
        val frame = Frame(front, back, fraction.coerceIn(0f, 1f))
        latest = frame

        if (job?.isActive == true) {
            // A fade is running. Same destination: it will land on `latest`.
            // New destination: retarget from whatever is mostly visible now.
            if (frame.dominant != animatingTo) startFade(from = shown?.dominant, frame)
            return
        }

        val previous = shown
        if (previous == null || distance(previous, frame) <= CONTINUITY_THRESHOLD) {
            emit(frame)
        } else {
            startFade(from = previous.dominant, frame)
        }
    }

    private fun startFade(from: String?, target: Frame) {
        val to = target.dominant
        if (from == to) {
            job?.cancel()
            emit(target)
            return
        }
        job?.cancel()
        animatingTo = to
        job = scope.launch {
            emit(Frame(from, to, 0f))
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = FoamMotion.Spec.sharedElementFlight
            ) {
                emit(Frame(from, to, value))
            }
            // Land on the newest input; visually identical to the fade's end.
            latest?.let { emit(it) }
        }
    }

    private fun emit(frame: Frame) {
        shown = frame
        sink(frame.front, frame.back, frame.fraction)
    }

    /** Total-variation distance between what the two frames put on screen (0..1). */
    private fun distance(a: Frame, b: Frame): Float {
        val wa = weights(a)
        val wb = weights(b)
        var sum = 0f
        for (key in wa.keys + wb.keys) {
            sum += kotlin.math.abs((wa[key] ?: 0f) - (wb[key] ?: 0f))
        }
        return sum / 2f
    }

    private fun weights(frame: Frame): Map<String?, Float> {
        val map = HashMap<String?, Float>(2)
        map[frame.front] = 1f - frame.fraction
        map[frame.back] = (map[frame.back] ?: 0f) + frame.fraction
        return map
    }

    private companion object {
        const val CONTINUITY_THRESHOLD = 0.4f
    }
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
                        animationSpec = FoamMotion.Spec.sharedElementFlight
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
        targetValue = if (listBackdropArt != null && isDetailListRoute(currentRoute)) 1f else 0f,
        animationSpec = if (isSharedElementRoute(currentRoute) ||
            isSharedElementRoute(lastRoute)
        ) {
            FoamMotion.Spec.sharedElementFlight
        } else {
            FoamMotion.Spec.screenFadeIn
        },
        label = "listBackdropAlpha"
    )
    val display = listBackdropArt ?: lastListBackdropArt
    val show = (backgroundMode == BackgroundMode.DYNAMIC || forceDynamic) &&
            display != null && listBackdropAlpha > 0f
    if (show) {
        DynamicAlbumArtBackground(
            frontArtPath = (state.crossfadeFrom ?: display).path,
            frontArtIsEmbedded = (state.crossfadeFrom ?: display).isEmbeddedArt,
            backArtPath = state.crossfadeTo?.path,
            backArtIsEmbedded = state.crossfadeTo?.isEmbeddedArt ?: true,
            crossfadeFraction = { if (state.crossfadeTo != null) state.crossfade.value else 0f },
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
        with(LocalDensity.current) { navZoneHeightPx.toDp() }
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