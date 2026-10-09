package com.foamplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Settings row exposing one enum's options as side-by-side selectable chips.
 * The selected chip fills with the accent color; [getLabel]/[getIcon] render
 * each option's appearance.
 *
 * Chips live in a [FlowRow]: when a line runs out of horizontal room the whole
 * option moves to the next line instead of its label folding — every label is
 * clamped to a single line, and items of the same line still share the width
 * evenly (FlowRow weights are per-line), so the classic full-width look is
 * kept whenever everything fits.
 */
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
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FoamDimens.paddingMedium),
            horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall),
            verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
        ) {
            options.forEach { option ->
                val isSelected = option == selectedOption
                val interactionSource = remember { MutableInteractionSource() }

                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
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
                        // Generous horizontal breathing room so the text never
                        // touches the rounded edge; tighter vertical padding so
                        // the chip row stays compact.
                        .padding(horizontal = 14.dp, vertical = FoamDimens.spacingSmall),
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
                            maxLines = 1,
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