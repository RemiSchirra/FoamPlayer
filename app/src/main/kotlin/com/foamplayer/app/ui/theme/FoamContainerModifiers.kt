package com.foamplayer.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp

/**
 * Live accent tint. [FoamTheme] writes into it, the container modifiers below
 * read it inside their draw lambdas, so an accent change only redraws
 * containers -- nothing recomposes.
 */
@Stable
class AccentTintState {
    var color by mutableStateOf(DefaultPrimaryAccent)
    var enabled by mutableStateOf(true)

    /**
     * Scale of the background tint: 1 = full tint, 0 = the plain palette
     * background. [FoamTheme] drives it to 0 while the accent is the
     * text-primary fallback (no album art to extract from), so tinted
     * backgrounds land on the untouched palette color instead of greying
     * toward the text color. Read in the draw phase only.
     */
    var backgroundTintScale by mutableFloatStateOf(1f)

    /** Base palette color -> tint-aware draw color. Call from a draw lambda. */
    fun resolve(base: Color, amount: Float): Color =
        if (enabled) lerp(start = base, stop = color, fraction = amount) else base

    /**
     * Background variant of [resolve]: the tint fades out with
     * [backgroundTintScale]. Call from a draw lambda.
     */
    fun resolveBackground(base: Color, amount: Float): Color =
        if (enabled) {
            lerp(
                start = base,
                stop = color,
                fraction = amount * backgroundTintScale.coerceIn(0f, 1f)
            )
        } else {
            base
        }
}

val LocalAccentTintState = staticCompositionLocalOf { AccentTintState() }

/**
 * How far each container color blends toward the accent, applied at draw time
 * as `lerp(base, accent, k)` (Settings > Colors).
 */
internal const val BackgroundAccentTint = 0.2f
internal const val SurfaceAccentTint = 0.1f
internal const val SurfaceVariantAccentTint = 0.1f
internal const val BorderAccentTint = 0.15f

/**
 * `Modifier.background(FoamTheme.colors.X)` with the accent tint applied in
 * the draw pass, so an animating accent never recomposes the owning node.
 */
@Composable
private fun Modifier.tintedContainerBackground(
    baseColor: Color,
    tintAmount: Float,
    shape: Shape,
    alpha: Float,
    useBackgroundScale: Boolean = false
): Modifier {
    val tint = LocalAccentTintState.current
    return remember(tint, baseColor, tintAmount, shape, alpha) {
        drawBehind {
            val color = if (useBackgroundScale) {
                tint.resolveBackground(baseColor, tintAmount)
            } else {
                tint.resolve(baseColor, tintAmount)
            }.copy(alpha = alpha)
            if (shape == RectangleShape) {
                drawRect(color)
            } else {
                drawOutline(shape.createOutline(size, layoutDirection, this), color = color)
            }
        }
    }
}

/**
 * Tint-aware `Modifier.background(FoamTheme.colors.background)`.
 * [shape]/[alpha] mirror `background`.
 */
@Composable
fun Modifier.foamBackground(
    shape: Shape = RectangleShape,
    alpha: Float = 1f
): Modifier = tintedContainerBackground(
    baseColor = LocalFoamColors.current.background,
    tintAmount = BackgroundAccentTint,
    shape = shape,
    alpha = alpha,
    useBackgroundScale = true
)

/**
 * Vertical fade of the tinted background color: fully opaque at the top,
 * transparent at the bottom. Like the other foam* container backgrounds, the
 * tint resolves inside the draw pass, so an accent change only redraws the
 * fade -- nothing recomposes.
 */
@Composable
fun Modifier.foamBackgroundTopFade(): Modifier {
    val tint = LocalAccentTintState.current
    val palette = LocalFoamColors.current
    return remember(tint, palette) {
        drawBehind {
            val color = tint.resolveBackground(palette.background, BackgroundAccentTint)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to color,
                    1f to color.copy(alpha = 0f)
                )
            )
        }
    }
}

/** Tint-aware `Modifier.background(FoamTheme.colors.surface)`. */
@Composable
fun Modifier.foamSurface(
    shape: Shape = RectangleShape,
    alpha: Float = 1f
): Modifier = tintedContainerBackground(
    baseColor = LocalFoamColors.current.surface,
    tintAmount = SurfaceAccentTint,
    shape = shape,
    alpha = alpha
)

/** Tint-aware `Modifier.background(FoamTheme.colors.surfaceVariant)`. */
@Composable
fun Modifier.foamSurfaceVariant(
    shape: Shape = RectangleShape,
    alpha: Float = 1f
): Modifier = tintedContainerBackground(
    baseColor = LocalFoamColors.current.surfaceVariant,
    tintAmount = SurfaceVariantAccentTint,
    shape = shape,
    alpha = alpha
)

/** Tint-aware `Modifier.background(FoamTheme.colors.borderOverlay)` (hairlines, dividers). */
@Composable
fun Modifier.foamBorderOverlay(
    shape: Shape = RectangleShape,
    alpha: Float = 1f
): Modifier = tintedContainerBackground(
    baseColor = LocalFoamColors.current.borderOverlay,
    tintAmount = BorderAccentTint,
    shape = shape,
    alpha = alpha
)

/**
 * Background blending surface -> surfaceVariant by [fraction], with the
 * accent tint applied in the draw pass. Pair with `animateFloatAsState` for
 * drag row feedback.
 */
@Composable
fun Modifier.foamSurfaceBlend(
    fraction: Float,
    shape: Shape = RectangleShape,
    alpha: Float = 1f
): Modifier {
    val tint = LocalAccentTintState.current
    val palette = LocalFoamColors.current
    return remember(tint, palette, fraction, shape, alpha) {
        drawBehind {
            val color = lerp(
                start = tint.resolve(palette.surface, SurfaceAccentTint),
                stop = tint.resolve(palette.surfaceVariant, SurfaceVariantAccentTint),
                fraction = fraction.coerceIn(0f, 1f)
            ).copy(alpha = alpha)
            if (shape == RectangleShape) {
                drawRect(color)
            } else {
                drawOutline(shape.createOutline(size, layoutDirection, this), color = color)
            }
        }
    }
}
