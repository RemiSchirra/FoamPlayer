package com.foamplayer.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/** [AnimatedVisibility] whose transitions are disabled until [animate] is first set. */
@Composable
internal fun SettingsExpandVisibility(
    visible: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (animate) fadeIn() + expandVertically() else EnterTransition.None,
        exit = if (animate) fadeOut() + shrinkVertically() else ExitTransition.None,
        content = content
    )
}

/** Filled accent button used for account and permission actions in settings. */
@Composable
internal fun SettingsActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(
                if (enabled) FoamTheme.accentColor else FoamTheme.colors.surfaceVariant
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingSmall),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.titleMedium.copy(
                color = if (enabled) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

/** Status line under an account section: spinner, check or error icon + text. */
@Composable
internal fun SettingsStatusRow(
    text: String,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FoamDimens.spacingMedium)
            .padding(horizontal = FoamDimens.spacingTiny),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        BasicText(text = text, style = FoamTheme.typography.body)
    }
}
