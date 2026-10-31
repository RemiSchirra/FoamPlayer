package com.foamplayer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.foamplayer.app.data.settings.FoamIconStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's color palette. */
@Immutable
data class FoamColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val bitPerfectActive: Color,
    val bitPerfectInactive: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val borderOverlay: Color
)

val DarkFoamColors = FoamColors(
    background = Color(0xFF121212),
    surface = Color(0xFF212121),
    surfaceVariant = Color(0xFF2A2A2A),
    bitPerfectActive = Color(0xFF00E676),
    bitPerfectInactive = Color(0xFFFFB300),
    textPrimary = Color(0xFFE0E0E0),
    textSecondary = Color(0x99FFFFFF),
    borderOverlay = Color(0xFF333333)
)

val LightFoamColors = FoamColors(
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFFDFDFDF),
    bitPerfectActive = Color(0xFF00A344),
    bitPerfectInactive = Color(0xFFB26A00),
    textPrimary = Color(0xFF1C1C1C),
    textSecondary = Color(0x99000000),
    borderOverlay = Color(0xFFC9C9C9)
)

/** Default accent, used until [FoamTheme] provides a live one (e.g. in previews). */
val DefaultPrimaryAccent = Color(0xFFFF5252)

/** The app's text styles. */
@Immutable
data class FoamTypography(
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val monospace: TextStyle
)

private fun foamTypography(
    titleColor: Color,
    bodyColor: Color,
    monoColor: Color
) = FoamTypography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        color = titleColor
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        color = titleColor
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        color = bodyColor
    ),
    caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = bodyColor
    ),
    monospace = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        color = monoColor
    )
)

val DefaultFoamTypography = foamTypography(
    titleColor = DarkFoamColors.textPrimary,
    bodyColor = DarkFoamColors.textSecondary,
    monoColor = DarkFoamColors.bitPerfectActive
)

val LightFoamTypography = foamTypography(
    titleColor = LightFoamColors.textPrimary,
    bodyColor = LightFoamColors.textSecondary,
    monoColor = LightFoamColors.bitPerfectActive
)

/** Corner rounding: the player roundings are 0f..1f percentages of the art's half-size. */
@Immutable
data class FoamShapes(
    val miniPlayerRounding: Float,
    val fullPlayerRounding: Float,
    val cardRounding: Dp = 8.dp
)

val DefaultFoamShapes = FoamShapes(
    miniPlayerRounding = 0.25f,
    fullPlayerRounding = 0.15f
)

// Static locals: values rarely change after launch, so readers skip tracking
// and simply recompose wholesale on the rare value replacement.
val LocalFoamTypography = staticCompositionLocalOf { DefaultFoamTypography }
val LocalFoamShapes = staticCompositionLocalOf { DefaultFoamShapes }
val LocalIsDarkTheme = staticCompositionLocalOf { true }

// The palette is the untouched static base: the accent tint on container
// backgrounds is applied at draw time (see the foam* container modifiers in
// FoamContainerModifiers.kt), so this local only ever changes on a theme
// switch -- never while the accent animates.
val LocalFoamColors = staticCompositionLocalOf { DarkFoamColors }

// The live accent + tint toggle live in a stable holder provided through
// [LocalAccentTintState] (see FoamContainerModifiers.kt): [FoamTheme] writes
// into the holder on every recomposition and draw-phase readers observe it
// there, so accent frames never recompose the palette consumers. Leaf readers
// still get per-frame invalidation through [FoamTheme.accentColor], which
// reads the same holder.

/**
 * Live-preview override for the mini player rounding — the stored 0..100
 * percent pushed by the player settings screen while its slider is dragged.
 * Every [FoamTheme.shapes] reader applies it on top of the persisted shapes, so
 * the bottom chrome (mini player and the nav bar attached to it) follows the
 * slider in real time while the setting itself keeps its commit-on-release
 * cadence. `null` = no override.
 *
 * Owned and provided by [FoamTheme]; the settings screen clears it when it
 * leaves composition so the rest of the app only ever sees the committed value.
 */
@Stable
class MiniPlayerRoundingPreviewState {
    var miniPlayerRounding by mutableStateOf<Int?>(null)

    /** Drops the override; the persisted rounding drives the shapes again. */
    fun clear() {
        miniPlayerRounding = null
    }
}

val LocalMiniPlayerRoundingPreview =
    staticCompositionLocalOf { MiniPlayerRoundingPreviewState() }

// Whether the accent derives from album art; row highlights read it to pick
// between their own extracted art color and the single app accent.
val LocalAutomaticColorEnabled = compositionLocalOf { true }

/** Access point for the theme values provided by [FoamTheme]. */
object FoamTheme {
    /**
     * The static base palette: container tinting happens at draw time, so this
     * never changes while the accent animates -- only on a theme switch. Use
     * the foam* container modifiers (see FoamContainerModifiers.kt) for
     * tint-aware container backgrounds; one-shot color reads (icon tints,
     * canvas colors) get the base colors.
     */
    val colors: FoamColors
        @Composable @ReadOnlyComposable get() = LocalFoamColors.current

    val typography: FoamTypography
        @Composable @ReadOnlyComposable get() = LocalFoamTypography.current

    val icons: FoamIcons
        @Composable get() = LocalFoamIcons.current

    /**
     * The theme's shapes, with the settings screen's live rounding preview (see
     * [MiniPlayerRoundingPreviewState]) applied when one is pushed. The
     * persisted shapes still come from a static local; only the preview field is
     * tracked, so a slider drag recomposes the shape readers (bottom chrome)
     * alone instead of the navigation tree.
     */
    val shapes: FoamShapes
        @Composable get() {
            val themeShapes = LocalFoamShapes.current
            val previewPercent = LocalMiniPlayerRoundingPreview.current.miniPlayerRounding
            return remember(themeShapes, previewPercent) {
                val rounding =
                    previewPercent?.let { it / 100f } ?: themeShapes.miniPlayerRounding
                if (rounding == themeShapes.miniPlayerRounding) themeShapes
                else themeShapes.copy(miniPlayerRounding = rounding)
            }
        }

    /** Whether the current palette is the dark one (Light theme = false). */
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalIsDarkTheme.current

    /** The live, possibly per-frame-animated accent color (already theme-adapted). */
    val accentColor: Color
        @Composable get() = LocalAccentTintState.current.color

    /**
     * One-shot tint-aware surface read for leaf components (slider tracks, seek
     * bars) that sit on tinted containers. Reads the live accent, so the
     * reader's scope recomposes on accent frames -- use only alongside
     * [accentColor] where that already happens; custom draw-phase blocks should
     * resolve the tint inside their draw lambda instead (see
     * [AccentTintState.resolve]).
     */
    val tintedSurface: Color
        @Composable get() = LocalAccentTintState.current.resolve(
            LocalFoamColors.current.surface, SurfaceAccentTint
        )
}

/**
 * Adapts a raw accent color (palette pick or album-art extraction) to the
 * active theme: dark palettes are tuned around lightened colors, bright
 * accents are darkened on light backgrounds to stay legible.
 */
fun Color.adaptAccentForTheme(darkTheme: Boolean): Color {
    val lum = luminance()
    return if (darkTheme) {
        val t = 0.45f * (1f - smoothstep(0.05f, 0.3f, lum))
        if (t <= 0f) this else lerp(this, Color.White, t)
    } else {
        val t = 0.25f + 0.35f * smoothstep(0.3f, 0.95f, lum)
        lerp(this, Color.Black, t)
    }
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Provides the app's theme: colors, typography, icon style, shapes and accent. */
@Composable
fun FoamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    iconStyle: FoamIconStyle = FoamIconStyle.FLAT,
    miniPlayerRounding: Float = 0.4f,
    fullPlayerRounding: Float = 0.4f,
    accentColor: Color? = null,
    /** Blends the accent into backgrounds and containers (Settings > Colors). */
    accentTintEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    // Remembered per input so the static locals' consumers skip when only the
    // accent (per-frame) changes.
    val icons = remember(iconStyle) { DefaultFoamIcons.copy(currentStyle = iconStyle) }
    val shapes = remember(miniPlayerRounding, fullPlayerRounding) {
        FoamShapes(
            miniPlayerRounding = miniPlayerRounding,
            fullPlayerRounding = fullPlayerRounding
        )
    }
    val baseColors = remember(darkTheme) { if (darkTheme) DarkFoamColors else LightFoamColors }
    val typography = remember(darkTheme) { if (darkTheme) DefaultFoamTypography else LightFoamTypography }

    val targetAccentColor = remember(accentColor, darkTheme) {
        (accentColor ?: DefaultPrimaryAccent).adaptAccentForTheme(darkTheme)
    }

    // The tint is applied at draw time (see the foam* container modifiers):
    // the palette stays the static base, so an accent frame only invalidates
    // container draws -- no recomposition, no layout. The holder instance is
    // remembered once and written into on every recomposition (same pattern
    // as [MiniPlayerRoundingPreviewState]); equal-value writes are no-ops.
    val accentTint = remember { AccentTintState() }
    accentTint.color = targetAccentColor
    accentTint.enabled = accentTintEnabled

    // Live overrides pushed by the settings screens (see
    // [MiniPlayerRoundingPreviewState]); the holder itself never changes.
    val roundingPreview = remember { MiniPlayerRoundingPreviewState() }

    CompositionLocalProvider(
        LocalFoamColors provides baseColors,
        LocalFoamTypography provides typography,
        LocalIsDarkTheme provides darkTheme,
        LocalFoamIcons provides icons,
        LocalFoamShapes provides shapes,
        LocalMiniPlayerRoundingPreview provides roundingPreview,
        LocalAccentTintState provides accentTint,
        content = content
    )
}
