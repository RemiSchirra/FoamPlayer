package com.foamplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun <T : Enum<T>> FoamEnumItem(
    title: String,
    description: String? = null,
    options: Array<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    getLabel: @Composable (T) -> String,
    getIcon: ((T) -> ImageVector)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
            BasicText(text = title, style = FoamTheme.typography.titleMedium)
            if (description != null) {
                BasicText(text = description, style = FoamTheme.typography.body)
            }
        }
        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FoamDimens.paddingMedium),
            horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
        ) {
            options.forEach { option ->
                val isSelected = option == selectedOption
                val interactionSource = remember { MutableInteractionSource() }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(
                            if (isSelected) FoamTheme.accentColor
                            else FoamTheme.colors.surface
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = ripple(color = FoamTheme.colors.textPrimary),
                            role = Role.RadioButton,
                            onClick = { onOptionSelected(option) }
                        )
                        .padding(vertical = FoamDimens.paddingMedium),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (getIcon != null) {
                            Icon(
                                imageVector = getIcon(option),
                                contentDescription = null,
                                modifier = Modifier.size(FoamDimens.iconSmall),
                                tint = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
                            )
                            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                        }
                        BasicText(
                            text = getLabel(option),
                            style = FoamTheme.typography.body.copy(
                                color = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }
    }
}
