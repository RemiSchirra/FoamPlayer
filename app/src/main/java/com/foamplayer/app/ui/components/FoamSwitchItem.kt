package com.foamplayer.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Toggle row with a bounded ripple. When the caller supplies an
 * [interactionSource] it also owns the ripple (typically drawn at card level
 * with [Modifier.indication] so it reaches the card's borders) and the row
 * itself draws none.
 */
@Composable
fun FoamSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    interactionSource: MutableInteractionSource? = null
) {
    val rowInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val rowIndication = if (interactionSource != null) {
        null
    } else {
        ripple(color = FoamTheme.colors.textPrimary)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = rowInteractionSource,
                indication = rowIndication,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium
            )
            description?.let {
                Spacer(modifier = Modifier.height(2.dp))
                BasicText(
                    text = it,
                    style = FoamTheme.typography.body
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.clearAndSetSemantics {},
            colors = SwitchDefaults.colors(
                checkedThumbColor = FoamTheme.accentColor,
                checkedTrackColor = FoamTheme.accentColor.copy(alpha = 0.5f),
                uncheckedThumbColor = FoamTheme.colors.textSecondary.copy(alpha = 1f),
                uncheckedTrackColor = FoamTheme.colors.textSecondary.copy(alpha = 0.5f)
            )
        )
    }
}
