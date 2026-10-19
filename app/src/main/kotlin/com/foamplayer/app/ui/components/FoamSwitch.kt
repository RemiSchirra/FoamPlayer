package com.foamplayer.app.ui.components

import androidx.compose.material.LocalRippleConfiguration
import androidx.compose.material.RippleConfiguration
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * The app's standard [Switch].
 */
@Composable
fun FoamSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = FoamTheme.colors
    CompositionLocalProvider(
        LocalRippleConfiguration provides RippleConfiguration(color = FoamTheme.colors.textPrimary)
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FoamTheme.accentColor,
                checkedTrackColor = FoamTheme.accentColor.copy(alpha = 0.5f),
                uncheckedThumbColor = lerp(colors.textPrimary, colors.background, 0.3f),
                uncheckedTrackColor = colors.textSecondary.copy(alpha = 0.5f)
            )
        )
    }
}
