package com.foamplayer.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class FoamColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primaryAccent: Color,
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
    primaryAccent = Color(0xFFFF5252),
    bitPerfectActive = Color(0xFF00E676),
    bitPerfectInactive = Color(0xFFFFB300),
    textPrimary = Color(0xFFF0F2F5),
    textSecondary = Color(0xFF8C93A8),
    borderOverlay = Color(0xFF252A37)
)

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
    miniPlayerRounding = 8.dp,
    fullPlayerRounding = 24.dp
)

val LocalFoamColors = staticCompositionLocalOf { DarkFoamColors }
val LocalFoamTypography = staticCompositionLocalOf { DefaultFoamTypography }
val LocalFoamShapes = staticCompositionLocalOf { DefaultFoamShapes }

object FoamTheme {
    val colors: FoamColors
        @Composable get() = LocalFoamColors.current

    val typography: FoamTypography
        @Composable get() = LocalFoamTypography.current

    val icons: FoamIcons
        @Composable get() = LocalFoamIcons.current

    val shapes: FoamShapes
        @Composable get() = LocalFoamShapes.current
}

@Composable
fun FoamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    iconStyle: FoamIconStyle = FoamIconStyle.FLAT,
    miniPlayerRounding: Dp = 8.dp,
    fullPlayerRounding: Dp = 24.dp,
    accentColor: Color? = null,
    content: @Composable () -> Unit
) {
    val icons = DefaultFoamIcons.copy(currentStyle = iconStyle)
    val shapes = FoamShapes(
        miniPlayerRounding = miniPlayerRounding,
        fullPlayerRounding = fullPlayerRounding
    )
    
    val targetAccentColor = accentColor ?: DarkFoamColors.primaryAccent
    val animatedAccentColor by animateColorAsState(
        targetValue = targetAccentColor,
        animationSpec = tween(durationMillis = 500),
        label = "AccentColorFade"
    )

    val colors = DarkFoamColors.copy(primaryAccent = animatedAccentColor)
    
    CompositionLocalProvider(
        LocalFoamColors provides colors,
        LocalFoamTypography provides DefaultFoamTypography,
        LocalFoamIcons provides icons,
        LocalFoamShapes provides shapes,
        content = content
    )
}
