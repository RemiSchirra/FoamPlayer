package com.foamplayer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
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
val LocalFoamColors = staticCompositionLocalOf { DarkFoamColors }
val LocalFoamTypography = staticCompositionLocalOf { DefaultFoamTypography }
val LocalFoamShapes = staticCompositionLocalOf { DefaultFoamShapes }
val LocalIsDarkTheme = staticCompositionLocalOf { true }

// The accent animates per-frame (e.g. while swiping between tracks); a
// non-static local keeps that invalidation scoped to accent readers only.
val LocalPrimaryAccentColor = compositionLocalOf { DefaultPrimaryAccent }

// Whether the accent derives from album art; row highlights read it to pick
// between their own extracted art color and the single app accent.
val LocalAutomaticColorEnabled = compositionLocalOf { true }

/** Access point for the theme values provided by [FoamTheme]. */
object FoamTheme {
    val colors: FoamColors
        @Composable @ReadOnlyComposable get() = LocalFoamColors.current

    val typography: FoamTypography
        @Composable @ReadOnlyComposable get() = LocalFoamTypography.current

    val icons: FoamIcons
        @Composable get() = LocalFoamIcons.current

    val shapes: FoamShapes
        @Composable get() = LocalFoamShapes.current

    /** Whether the current palette is the dark one (Light theme = false). */
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalIsDarkTheme.current

    /** The live, possibly per-frame-animated accent color (already theme-adapted). */
    val accentColor: Color
        @Composable get() = LocalPrimaryAccentColor.current
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
    val colors = remember(darkTheme) { if (darkTheme) DarkFoamColors else LightFoamColors }
    val typography = remember(darkTheme) { if (darkTheme) DefaultFoamTypography else LightFoamTypography }

    val targetAccentColor = remember(accentColor, darkTheme) {
        (accentColor ?: DefaultPrimaryAccent).adaptAccentForTheme(darkTheme)
    }

    CompositionLocalProvider(
        LocalFoamColors provides colors,
        LocalFoamTypography provides typography,
        LocalIsDarkTheme provides darkTheme,
        LocalFoamIcons provides icons,
        LocalFoamShapes provides shapes,
        LocalPrimaryAccentColor provides targetAccentColor,
        content = content
    )
}
