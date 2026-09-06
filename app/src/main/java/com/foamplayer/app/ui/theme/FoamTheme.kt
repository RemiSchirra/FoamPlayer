package com.foamplayer.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
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
    background = Color(0xFF0A0B0E),
    surface = Color(0xFF12141B),
    surfaceVariant = Color(0xFF1A1D27),
    bitPerfectActive = Color(0xFF00E676),
    bitPerfectInactive = Color(0xFFFFB300),
    textPrimary = Color(0xFFF0F2F5),
    textSecondary = Color(0xFF8C93A8),
    borderOverlay = Color(0xFF252A37)
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
        fontSize = 22.sp,
        color = Color(0xFFF0F2F5)
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = Color(0xFFF0F2F5)
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = Color(0xFF8C93A8)
    ),
    caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 11.sp,
        color = Color(0xFF8C93A8)
    ),
    monospace = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = Color(0xFF00E676)
    )
)

@Immutable
data class FoamShapes(
    val miniPlayerRounding: Dp,
    val fullPlayerRounding: Dp
)

val DefaultFoamShapes = FoamShapes(
    miniPlayerRounding = 14.dp,
    fullPlayerRounding = 24.dp
)

// These three rarely (if ever) change after launch, so a staticCompositionLocalOf is
// the right call: it skips the bookkeeping needed to track *who* reads which field,
// and just fully recomposes its content on the rare occasions the value is replaced.
val LocalFoamColors = staticCompositionLocalOf { DarkFoamColors }
val LocalFoamTypography = staticCompositionLocalOf { DefaultFoamTypography }
val LocalFoamShapes = staticCompositionLocalOf { DefaultFoamShapes }

// The accent color is the one theme value that changes at animation frequency --
// continuously while swiping between tracks in the full player. It deliberately gets
// its own *non*-static local: compositionLocalOf keeps per-reader subscriptions, so a
// new value here only recomposes the handful of composables that actually read
// FoamTheme.accentColor, instead of forcing every consumer of the (otherwise static)
// FoamColors struct to recompose on every drag frame. Keeping it out of FoamColors
// entirely is what makes that isolation possible -- folding it back into that struct
// would put every other field's readers back in the blast radius.
val LocalPrimaryAccentColor = compositionLocalOf { DefaultPrimaryAccent }

object FoamTheme {
    val colors: FoamColors
        @Composable get() = LocalFoamColors.current

    val typography: FoamTypography
        @Composable get() = LocalFoamTypography.current

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
    miniPlayerRounding: Dp = 14.dp,
    fullPlayerRounding: Dp = 24.dp,
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