package com.foamplayer.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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

    /** Base palette color -> tint-aware draw color. Call from a draw lambda. */
    fun resolve(base: Color, amount: Float): Color =
        if (enabled) lerp(start = base, stop = color, fraction = amount) else base
}

val LocalAccentTintState = staticCompositionLocalOf { AccentTintState() }

/**
 * How far each container color blends toward the accent, applied at draw time
 * as `lerp(base, accent, k)` (Settings > Colors).
 */
internal const val BackgroundAccentTint = 0.08f
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
    alpha: Float
): Modifier {
    val tint = LocalAccentTintState.current
    return remember(tint, baseColor, tintAmount, shape, alpha) {
        drawBehind {
            val color = tint.resolve(baseColor, tintAmount).copy(alpha = alpha)
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
    alpha = alpha
)

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
