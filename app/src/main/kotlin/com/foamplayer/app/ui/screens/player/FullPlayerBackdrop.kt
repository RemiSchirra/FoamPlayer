package com.foamplayer.app.ui.screens.player

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlin.math.roundToInt
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.data.settings.BackgroundMode
import com.foamplayer.app.data.settings.GradientStop
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.NoAlbumArtPlaceholder
import com.foamplayer.app.ui.components.albumArtMemoryCacheKey
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.buildGradientImage
import com.foamplayer.app.ui.theme.FoamTheme


/**
 * Which layer of the backdrop's front/back art pair belongs to the track that owns the
 * animated canvas. [NONE] = the canvas' track is not part of the pair (or no canvas).
 */
enum class CanvasAnchor { NONE, FRONT, BACK }

/**
 * Backdrop state written by [FullPlayerScreen] and read by the root-level backdrop host in
 * FoamNavigation. The full player's new modes (custom image / gradient) reuse the same
 * treatment as the dynamic art: the full player's backdrop is deliberately rendered at the
 * very root of the hierarchy -- in the exact same fillMaxSize Box as the app-wide
 * DynamicAlbumArtBackground -- so its blurred artwork is cropped to the physical screen
 * with no overflow tricks, no bottom-extension arithmetic and no possibility of being
 * clipped by an intermediate container.
 *
 * [frontArtPath]/[backArtPath]/[immersive] are snapshot states; [crossfadeFraction] and
 * [subViewBlurProgress] are plain lambda fields whose bodies only read snapshot state
 * (pager offset, transition fractions), so they are evaluated during the host's draw phase
 * and never go stale.
 */
@Stable
class FullPlayerBackdropState {
    var frontArtPath by mutableStateOf<String?>(null)
    var backArtPath by mutableStateOf<String?>(null)
    var immersive by mutableStateOf(false)
    /** Resolved animated-canvas stream for the current track; null = static art only. */
    var canvasUrl by mutableStateOf<String?>(null)
    /** False while the video is hidden (subview up / player closing): playback pauses, frame stays. */
    var canvasPlaying by mutableStateOf(false)
    /**
     * Slot of the canvas' track in the front/back pair, published together with the art
     * paths so both always describe the same pager snapshot. The canvas is never faded by
     * the swipe: it stays at full opacity as the bottom layer and the other page fades in
     * on top of it.
     */
    var canvasAnchor by mutableStateOf(CanvasAnchor.NONE)
    var crossfadeFraction by mutableStateOf({ 0f })
    var subViewBlurProgress by mutableStateOf({ 0f })
}

/**
 * Calculate a factor for a requested [blur] radius.
 * @return an interpolated value between 0.2 (full downscale) and 1 (no downscale)
 */
private fun blurDownscaleFactor(blur: Dp): Float {
    val radiusDp = blur.value
    return when {
        radiusDp <= 8f -> 1f
        radiusDp >= 80f -> 0.2f
        else -> {
            val t = (radiusDp - 8f) / (80f - 8f)
            1f - t * 0.8f
        }
    }
}

/**
 * Renders an image at scale [factor].
 */
private fun Modifier.renderAtScale(factor: Float): Modifier {
    if (factor >= 0.999f) return this
    return this.then(
        object : LayoutModifier {
            override fun MeasureScope.measure(
                measurable: Measurable,
                constraints: Constraints
            ): MeasureResult {
                val scaledConstraints = Constraints(
                    minWidth = (constraints.minWidth * factor).roundToInt(),
                    maxWidth = if (constraints.hasBoundedWidth)
                        (constraints.maxWidth * factor).roundToInt() else constraints.maxWidth,
                    minHeight = (constraints.minHeight * factor).roundToInt(),
                    maxHeight = if (constraints.hasBoundedHeight)
                        (constraints.maxHeight * factor).roundToInt() else constraints.maxHeight,
                )
                val placeable = measurable.measure(scaledConstraints)
                val fullWidth = (placeable.width / factor).roundToInt()
                val fullHeight = (placeable.height / factor).roundToInt()
                return layout(fullWidth, fullHeight) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = 1f / factor
                        scaleY = 1f / factor
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            }
        }
    )
}

/**
 * Performant blur modifier, downscale the image first, then apply blur.
 */
private fun Modifier.performantBlur(radius: Dp): Modifier {
    if (radius <= 0.dp) return this
    val factor = blurDownscaleFactor(radius)
    return this
        .renderAtScale(factor)
        .blur(radius * factor)
}

/**
 * The scrim color drawn over blurred album-art backdrops: black in dark theme,
 * the light background color in light theme so intensity lightens the art
 * instead of dimming it.
 */
@Composable
internal fun backdropScrimColor(): Color =
    if (FoamTheme.isDark) Color.Black else FoamTheme.colors.background

/**
 * A blurred, crossfading artwork layer for non-settings areas of the app.
 *
 * The art paths normally point at audio files whose embedded picture is read
 * through [EmbeddedAlbumArt]; the `*IsEmbedded` flags mark the rare callers
 * (playlist covers) that pass a plain image URI Coil must load directly.
 *
 * [crossfadeFraction] is a deferred (lambda) read: callers feed it from per-frame
 * sources (pager drag offsets), and consuming it inside the layer block keeps those
 * frames draw-only instead of recomposing the backdrop.
 */
@Composable
internal fun DynamicAlbumArtBackground(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: () -> Float,
    blur: Dp,
    intensity: Float,
    modifier: Modifier = Modifier,
    frontArtIsEmbedded: Boolean = true,
    backArtIsEmbedded: Boolean = true,
) {
    val context = LocalContext.current

    fun artRequest(path: String, isEmbedded: Boolean) = ImageRequest.Builder(context)
        .data(if (isEmbedded) EmbeddedAlbumArt(path.toUri()) else path)
        .size(AlbumArtRequestSize)
        .memoryCacheKey(albumArtMemoryCacheKey(path))
        .crossfade(300)
        .build()

    Box(modifier = modifier) {
        frontArtPath?.let { path ->
            val request = remember(path, frontArtIsEmbedded) {
                artRequest(path, frontArtIsEmbedded)
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().performantBlur(blur),
                contentScale = ContentScale.Crop,
            )
        }
        if (backArtPath != null && backArtPath != frontArtPath) {
            val backRequest = remember(backArtPath, backArtIsEmbedded) {
                artRequest(backArtPath, backArtIsEmbedded)
            }
            AsyncImage(
                model = backRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .performantBlur(blur)
                    .graphicsLayer { alpha = crossfadeFraction() },
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backdropScrimColor().copy(alpha = 1f - intensity.coerceIn(0f, 1f)))
        )
    }
}

/**
 * One backdrop layer for [BackgroundMode.CUSTOM]: a blurred version of the
 * user's chosen image, cropping like the album art treatment does, with the
 * standard intensity scrim on top.
 */
@Composable
internal fun CustomImageBackground(
    imageUri: String?,
    blur: Dp,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        if (imageUri != null) {
            val context = LocalContext.current
            val request = remember(imageUri) {
                ImageRequest.Builder(context)
                    .data(imageUri.toUri())
                    .crossfade(300)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().performantBlur(blur),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backdropScrimColor().copy(alpha = 1f - intensity.coerceIn(0f, 1f)))
        )
    }
}

/**
 * One backdrop layer for [BackgroundMode.GRADIENT]: the user's stops rendered
 * as the same true 2D color field the settings preview shows (each point
 * painting its own neighborhood via [buildGradientImage]), scaled up with
 * bilinear filtering, with the standard intensity scrim on top.
 *
 * The field is rasterized once per [stops] change (portrait proportions -- the
 * surfaces this fills are phone screens), which is cheap enough to rebuild
 * live while the editor's dots are dragged on another screen.
 */
@Composable
internal fun GradientBackground(
    stops: List<GradientStop>,
    intensity: Float,
    modifier: Modifier = Modifier
) {
    val fieldBitmap = remember(stops) { buildGradientImage(stops, 128, 256) }
    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = fieldBitmap,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.Medium,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backdropScrimColor().copy(alpha = 1f - intensity.coerceIn(0f, 1f)))
        )
    }
}

/**
 * Background layer for the classic blurred treatment and the immersive art hero.
 *
 * The two-layer front/back + [crossfadeFraction] scheme below is a manual crossfade, not
 * Coil's built-in one -- deliberately. Coil's AsyncImage skips its own crossfade transition
 * whenever the image resolves from its in-memory cache, which is exactly what happens here:
 * the caller prefetches the art for tracks around the current queue position into that same
 * cache (see AlbumArtPaletteCache.ensure), so jumping onto one of them is a cache hit for a
 * genuinely different image and would silently pop instead of fade. Forcing Coil to skip its
 * cache read (CachePolicy.WRITE_ONLY) avoids the pop but introduces a worse one: the request
 * now has to go through a Loading state first, and AsyncImage clears to nothing for that gap,
 * flashing through to the background color underneath before the new image fades in.
 *
 * Driving frontArtPath/backArtPath/crossfadeFraction ourselves sidesteps Coil's transition
 * logic (and its cache quirks) entirely -- both images can load straight from cache (fast,
 * synchronous, no Loading gap) and the fade is just our own alpha animation on top. See
 * FullPlayerScreen's `programmaticBackdropCrossfade` for the piece that feeds this a smooth
 * fraction even when the queue index changes without a live pager drag behind it (e.g.
 * tapping a track in the Queue subview).
 *
 * [intensity] is the player's own background intensity (separate from the app-wide
 * DynamicAlbumArtBackground's): with identical blur and crop box, this backdrop is
 * pixel-identical to the app-wide one whenever both intensities match, and the
 * open/close crossfade between them stays seamless. [bottomFadeInset] ends the black
 * fade at the bottom of the region where the backdrop is actually visible (the docked
 * bar's top edge hides everything below it); pass 0.dp when the backdrop is visible all
 * the way to the physical bottom of the screen.
 *
 * [backgroundMode] extends the classic dynamic treatment with two user-configured
 * layers: a blurred custom image and a user-edited gradient. [forceDynamic] overrides
 * both of them with the classic blurred treatment ("force dynamic background in
 * player"), matching the collections switch: the art drives the background regardless
 * of the selected mode.
 */
@Composable
internal fun AlbumArtBackdrop(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: () -> Float,
    immersive: Boolean,
    subViewProgress: () -> Float,
    backgroundMode: BackgroundMode,
    customBackgroundUri: String?,
    gradientStops: List<GradientStop>,
    backgroundBlur: Dp,
    intensity: Float,
    bottomFadeInset: Dp,
    modifier: Modifier = Modifier,
    foregroundModifier: Modifier = Modifier,
    forceDynamic: Boolean = false,
    canvasUrl: String? = null,
    canvasPlaying: Boolean = false,
    canvasAnchor: CanvasAnchor = CanvasAnchor.NONE
) {
    if (immersive) {
        ImmersiveAlbumArtBackdrop(
            frontArtPath = frontArtPath,
            backArtPath = backArtPath,
            crossfadeFraction = crossfadeFraction,
            subViewProgress = subViewProgress,
            bottomFadeInset = bottomFadeInset,
            modifier = modifier,
            foregroundModifier = foregroundModifier,
            canvasUrl = canvasUrl,
            canvasPlaying = canvasPlaying,
            canvasAnchor = canvasAnchor
        )
        return
    }

    val effectiveMode = if (forceDynamic) BackgroundMode.DYNAMIC else backgroundMode

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(FoamTheme.colors.background))
        when (effectiveMode) {
            BackgroundMode.DYNAMIC -> {
                val context = LocalContext.current
                // The two art layers remain composed throughout a pager transition so the
                // currently playing track can dissolve into the next track's background.
                Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f }) {
                    if (frontArtPath != null) {
                        val frontRequest = remember(frontArtPath) {
                            ImageRequest.Builder(context)
                                .data(EmbeddedAlbumArt(frontArtPath.toUri()))
                                .size(AlbumArtRequestSize)
                                .memoryCacheKey(albumArtMemoryCacheKey(frontArtPath))
                                .crossfade(false)
                                .build()
                        }
                        AsyncImage(
                            model = frontRequest,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().performantBlur(backgroundBlur),
                            contentScale = ContentScale.Crop
                        )
                    }
                    if (backArtPath != null && backArtPath != frontArtPath) {
                        val backRequest = remember(backArtPath) {
                            ImageRequest.Builder(context)
                                .data(EmbeddedAlbumArt(backArtPath.toUri()))
                                .size(AlbumArtRequestSize)
                                .memoryCacheKey(albumArtMemoryCacheKey(backArtPath))
                                .crossfade(false)
                                .build()
                        }
                        AsyncImage(
                            model = backRequest,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .performantBlur(backgroundBlur)
                                .graphicsLayer { alpha = crossfadeFraction() },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backdropScrimColor().copy(alpha = 1f - intensity.coerceIn(0f, 1f)))
                )
            }
            BackgroundMode.CUSTOM -> CustomImageBackground(
                imageUri = customBackgroundUri,
                blur = backgroundBlur,
                intensity = intensity,
                modifier = Modifier.fillMaxSize()
            )
            BackgroundMode.GRADIENT -> GradientBackground(
                stops = gradientStops,
                intensity = intensity,
                modifier = Modifier.fillMaxSize()
            )
        }
        BackdropGradient(modifier = Modifier.padding(bottom = bottomFadeInset))
    }
}

/**
 * Edge-to-edge art treatment that dissolves into the blurred background.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun ImmersiveAlbumArtBackdrop(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: () -> Float,
    subViewProgress: () -> Float,
    bottomFadeInset: Dp,
    modifier: Modifier = Modifier,
    foregroundModifier: Modifier = Modifier,
    canvasUrl: String? = null,
    canvasPlaying: Boolean = false,
    canvasAnchor: CanvasAnchor = CanvasAnchor.NONE
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sharpArtHeight = maxHeight * 0.55f

        Box(modifier = Modifier.fillMaxSize().background(FoamTheme.colors.background))
        AlbumArtImagePair(
            frontArtPath = frontArtPath,
            backArtPath = backArtPath,
            crossfadeFraction = crossfadeFraction,
            modifier = Modifier.fillMaxSize().performantBlur(35.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sharpArtHeight)
                .then(foregroundModifier)
        ) {
            CanvasAwareArtPair(
                frontArtPath = frontArtPath,
                backArtPath = backArtPath,
                crossfadeFraction = crossfadeFraction,
                canvasUrl = canvasUrl,
                canvasPlaying = canvasPlaying,
                canvasAnchor = canvasAnchor,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - subViewProgress() }
                    .fadeIntoBlur()
            )
        }
        BackdropGradient(modifier = Modifier.padding(bottom = bottomFadeInset))
    }
}

// Fades to the active theme's background color (black in dark mode, pale in
// light mode) so the art dissolves into the app behind the player controls.
// Rebuilt on theme change only: a static CompositionLocal read keeps the
// brush allocation out of every draw pass.
@Composable
private fun backdropFadeBrush(): Brush {
    val fadeColor = backdropScrimColor()
    return remember(fadeColor) {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.5f to Color.Transparent,
            0.75f to fadeColor.copy(alpha = 0.5f),
            1f to fadeColor
        )
    }
}

@Composable
private fun BackdropGradient(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backdropFadeBrush())
    )
}

/**
 * Fades the sharp top art into the blur below it.
 */
private fun Modifier.fadeIntoBlur(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val alphaMask = Brush.verticalGradient(
                0f to Color.White,
                0.75f to Color.White,
                1f to Color.Transparent
            )
            onDrawWithContent {
                drawContent()
                drawRect(alphaMask, blendMode = BlendMode.DstIn)
            }
        }

@Composable
private fun AlbumArtImagePair(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: () -> Float,
    modifier: Modifier
) {
    val context = LocalContext.current
    fun request(path: String) = ImageRequest.Builder(context)
        .data(EmbeddedAlbumArt(path.toUri()))
        .size(AlbumArtRequestSize)
        .memoryCacheKey(albumArtMemoryCacheKey(path))
        .crossfade(false)
        .build()

    Box(modifier = modifier) {
        // A track with no artwork has no blurred layer either; a flat placeholder-surface
        // field keeps the sharp no-art region (see CanvasAwareArtPair) dissolving into a
        // matching shade instead of into the bare background color.
        if (frontArtPath == null && backArtPath == null) {
            Box(modifier = Modifier.fillMaxSize().background(FoamTheme.colors.surfaceVariant))
        }
        if (frontArtPath != null) {
            val frontRequest = remember(frontArtPath) { request(frontArtPath) }
            AsyncImage(
                model = frontRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (backArtPath != null && backArtPath != frontArtPath) {
            val backRequest = remember(backArtPath) { request(backArtPath) }
            AsyncImage(
                model = backRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = crossfadeFraction() },
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * The sharp art pair with the animated canvas.
 *
 * Layer order is always: bottom art, canvas, top art. The canvas' track is the BOTTOM
 * layer (art + video, video at full opacity) and the other page is the TOP layer that
 * fades in over it -- a plain crossfade where the video is never dimmed, so neither the
 * static art of the canvas' track nor a second image can show through it:
 *  - canvas track is the front page (swiping forward): bottom = front, top = back,
 *    top alpha = crossfadeFraction (0 -> 1);
 *  - canvas track is the back page (swiping backward): bottom = back, top = front,
 *    top alpha = 1 - crossfadeFraction (the front page is the incoming one).
 * Same picture the plain front/back pair produces, only the canvas stays in a fixed
 * place of the tree (never re-parented, so the player is never rebuilt mid-swipe).
 * When the canvas' track is not in the pair ([CanvasAnchor.NONE]) the video is hidden
 * and the pair renders as usual.
 *
 * An empty bottom slot (the current track has no artwork) renders the image-like
 * no-art placeholder instead -- in immersive this sharp region IS the visible art
 * (the hero's own layer is alpha-faded to 0 at rest-open), so without it an artless
 * track would show nothing at all in the art area.
 */
@Composable
private fun CanvasAwareArtPair(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: () -> Float,
    canvasUrl: String?,
    canvasPlaying: Boolean,
    canvasAnchor: CanvasAnchor,
    modifier: Modifier
) {
    val context = LocalContext.current
    fun request(path: String) = ImageRequest.Builder(context)
        .data(EmbeddedAlbumArt(path.toUri()))
        .size(AlbumArtRequestSize)
        .memoryCacheKey(albumArtMemoryCacheKey(path))
        .crossfade(false)
        .build()

    val canvasIsBack = canvasUrl != null && canvasAnchor == CanvasAnchor.BACK
    val bottomPath = if (canvasIsBack) backArtPath else frontArtPath
    val topPath = if (canvasIsBack) frontArtPath else backArtPath

    Box(modifier = modifier) {
        if (bottomPath != null) {
            val bottomRequest = remember(bottomPath) { request(bottomPath) }
            AsyncImage(
                model = bottomRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            NoAlbumArtPlaceholder(modifier = Modifier.fillMaxSize())
        }
        if (canvasUrl != null) {
            val canvasVisible = canvasAnchor != CanvasAnchor.NONE
            AnimatedCanvasArt(
                url = canvasUrl,
                playing = canvasPlaying,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (canvasVisible) 1f else 0f }
            )
        }
        if (topPath != null && topPath != bottomPath) {
            val topRequest = remember(topPath) { request(topPath) }
            AsyncImage(
                model = topRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val fraction = crossfadeFraction()
                        alpha = if (canvasIsBack) 1f - fraction else fraction
                    },
                contentScale = ContentScale.Crop
            )
        }
    }
}
