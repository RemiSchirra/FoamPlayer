package com.foamplayer.app.ui.screens.player

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.theme.FoamTheme


/**
 * Extends a full-bleed layer beyond the bottom edge of its parent by [extraHeight]:
 * the measured height grows, the content stays anchored to the top, and the overflow
 * paints over whatever sits below (Compose never clips children unless explicitly
 * asked to, so the overflow reaches the nav zone for free).
 *
 * The full player's backdrop uses this so its blurred artwork + bottom black fade
 * run behind the app's navigation zone down to the physical bottom of the screen --
 * replacing the fake black rect the nav zone used to draw to mimic that continuation.
 */
internal fun Modifier.extendBottom(extraHeight: Dp): Modifier {
    if (extraHeight <= 0.dp) return this
    return then(
        object : LayoutModifier {
            override fun MeasureScope.measure(
                measurable: Measurable,
                constraints: Constraints
            ): MeasureResult {
                val expanded = Constraints(
                    minWidth = constraints.minWidth,
                    maxWidth = constraints.maxWidth,
                    minHeight = constraints.minHeight,
                    maxHeight = if (constraints.hasBoundedHeight) {
                        constraints.maxHeight + extraHeight.roundToPx()
                    } else {
                        constraints.maxHeight
                    }
                )
                val placeable = measurable.measure(expanded)
                return layout(placeable.width, placeable.height) {
                    placeable.place(0, 0)
                }
            }
        }
    )
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
 * A blurred, crossfading artwork layer for non-settings areas of the app.
 */
@Composable
internal fun DynamicAlbumArtBackground(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: Float,
    blur: Dp,
    intensity: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Box(modifier = modifier) {
        frontArtPath?.let { path ->
            val request = remember(path) {
                ImageRequest.Builder(context)
                    .data(EmbeddedAlbumArt(path.toUri()))
                    .size(AlbumArtRequestSize)
                    .crossfade(300)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().performantBlur(blur),
                contentScale = ContentScale.Crop,
            )
        }
        if (backArtPath != null && backArtPath != frontArtPath) {
            val backRequest = remember(backArtPath) {
                ImageRequest.Builder(context)
                    .data(EmbeddedAlbumArt(backArtPath.toUri()))
                    .size(AlbumArtRequestSize)
                    .crossfade(300)
                    .build()
            }
            AsyncImage(
                model = backRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .performantBlur(blur)
                    .graphicsLayer { alpha = crossfadeFraction },
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 1f - intensity.coerceIn(0f, 1f)))
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
 */
@Composable
internal fun AlbumArtBackdrop(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: () -> Float,
    immersive: Boolean,
    subViewProgress: Float,
    dynamicBackgroundEnabled: Boolean,
    backgroundBlur: Dp,
    modifier: Modifier = Modifier,
    foregroundModifier: Modifier = Modifier
) {
    if (immersive) {
        ImmersiveAlbumArtBackdrop(
            frontArtPath = frontArtPath,
            backArtPath = backArtPath,
            crossfadeFraction = crossfadeFraction,
            subViewProgress = subViewProgress,
            modifier = modifier,
            foregroundModifier = foregroundModifier
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(FoamTheme.colors.background))
        if (dynamicBackgroundEnabled) {
            // The two art layers remain composed throughout a pager transition so the
            // currently playing track can dissolve into the next track's background.
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f }) {
                if (frontArtPath != null) {
                    AsyncImage(
                        model = EmbeddedAlbumArt(frontArtPath.toUri()),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().performantBlur(backgroundBlur),
                        contentScale = ContentScale.Crop
                    )
                }
                if (backArtPath != null && backArtPath != frontArtPath) {
                    AsyncImage(
                        model = EmbeddedAlbumArt(backArtPath.toUri()),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().performantBlur(backgroundBlur).graphicsLayer { alpha = crossfadeFraction() },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
        BackdropGradient()
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
    subViewProgress: Float,
    modifier: Modifier = Modifier,
    foregroundModifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sharpArtHeight = maxHeight * 0.65f

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
            AlbumArtImagePair(
                frontArtPath = frontArtPath,
                backArtPath = backArtPath,
                crossfadeFraction = crossfadeFraction,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - subViewProgress }
                    .fadeIntoBlur()
            )
        }
        BackdropGradient()
    }
}

@Composable
private fun BackdropGradient(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to Color.Transparent,
                    0.75f to Color.Black.copy(alpha = 0.5f),
                    1f to Color.Black.copy(alpha = 1f)
                )
            )
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
                0.7f to Color.White,
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
        .crossfade(false)
        .build()

    Box(modifier = modifier) {
        if (frontArtPath != null) {
            AsyncImage(
                model = request(frontArtPath),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (backArtPath != null && backArtPath != frontArtPath) {
            AsyncImage(
                model = request(backArtPath),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = crossfadeFraction() },
                contentScale = ContentScale.Crop
            )
        }
    }
}