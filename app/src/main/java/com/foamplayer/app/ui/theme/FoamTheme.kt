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
        fontSize = 24.sp, // Slightly larger for a professional flat look
        color = Color(0xFFE0E0E0)
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, // Refined title size
        color = Color(0xFFE0E0E0)
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp, // Standard flat body size
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

@Immutable
data class FoamShapes(
    val miniPlayerRounding: Float, // Percentage 0f..1f
    val fullPlayerRounding: Float, // Percentage 0f..1f
    val cardRounding: Dp = 8.dp // Flatter corners for professional look
)

val DefaultFoamShapes = FoamShapes(
    miniPlayerRounding = 0.25f,
    fullPlayerRounding = 0.15f
)

// These three rarely (if ever) change after launch, so a staticCompositionLocalOf is
// the right call: it skips the bookkeeping needed to track *who* reads which field,
// and just fully recomposes its content on the rare occasions the value is replaced.
val LocalFoamColors = staticCompositionLocalOf { DarkFoamColors }
val LocalFoamTypography = staticCompositionLocalOf { DefaultFoamTypography }
val LocalFoamShapes = staticCompositionLocalOf { DefaultFoamShapes }

// The accent color is the one theme value that changes at animation frequency --
// continuously while swiping between tracks in the full player. It deliberately gets
// its own *non*-static CompositionLocal so that only components reading 'accentColor'
// (and not everything else using FoamTheme) are invalidated on each frame.
val LocalPrimaryAccentColor = compositionLocalOf { DefaultPrimaryAccent }

object FoamTheme {
    val colors: FoamColors
        @Composable @ReadOnlyComposable get() = LocalFoamColors.current

    val typography: FoamTypography
        @Composable @ReadOnlyComposable get() = LocalFoamTypography.current

    val icons: FoamIcons
        @Composable get() = LocalFoamIcons.current

    val shapes: FoamShapes
        @Composable get() = LocalFoamShapes.current

    /** The live, possibly per-frame-animated accent color. Read this instead of colors.primaryAccent. */
    val accentColor: Color
        @Composable get() = LocalPrimaryAccentColor.current
}

@Composable
fun FoamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    iconStyle: FoamIconStyle = FoamIconStyle.FLAT,
    miniPlayerRounding: Float = 0.4f,
    fullPlayerRounding: Float = 0.4f,
    accentColor: Color? = null,
    content: @Composable () -> Unit
) {
    // Only rebuilt when their own inputs change, so CompositionLocalProvider sees a
    // structurally-equal value (and skips reinvalidating their -- static -- locals'
    // consumers) on every recomposition this function goes through as accentColor
    // changes each frame.
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
