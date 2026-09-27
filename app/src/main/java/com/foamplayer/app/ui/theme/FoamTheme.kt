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
    background = Color(0xFF121212), // Dark Grey Background
    surface = Color(0xFF212121),
    surfaceVariant = Color(0xFF2A2A2A),
    bitPerfectActive = Color(0xFF00E676),
    bitPerfectInactive = Color(0xFFFFB300),
    textPrimary = Color(0xFFE0E0E0),
    textSecondary = Color(0xFF9E9E9E),
    borderOverlay = Color(0xFF333333)
)

/** Default accent color, used until [FoamTheme] provides a live one (e.g. in previews). */
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

val DefaultFoamTypography = FoamTypography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        color = Color(0xFFE0E0E0)
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        color = Color(0xFFE0E0E0)
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        color = Color(0xFF9E9E9E)
    ),
    caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = Color(0xFF9E9E9E)
    ),
    monospace = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        color = Color(0xFF00E676)
    )
)

/** Corner rounding: the two player roundings are 0f..1f percentages of the art's half-size. */
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

// Rarely change after launch: static locals skip reader tracking and just
// recompose their content wholesale on the rare value replacement.
val LocalFoamColors = staticCompositionLocalOf { DarkFoamColors }
val LocalFoamTypography = staticCompositionLocalOf { DefaultFoamTypography }
val LocalFoamShapes = staticCompositionLocalOf { DefaultFoamShapes }

// The accent color changes at animation frequency (per-frame while swiping
// between tracks); a non-static local keeps its invalidation scoped to readers
// of the accent alone.
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

    /** The live, possibly per-frame-animated accent color. */
    val accentColor: Color
        @Composable get() = LocalPrimaryAccentColor.current
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

    val targetAccentColor = accentColor ?: DefaultPrimaryAccent

    CompositionLocalProvider(
        LocalFoamColors provides DarkFoamColors,
        LocalFoamTypography provides DefaultFoamTypography,
        LocalFoamIcons provides icons,
        LocalFoamShapes provides shapes,
        LocalPrimaryAccentColor provides targetAccentColor,
        content = content
    )
}
