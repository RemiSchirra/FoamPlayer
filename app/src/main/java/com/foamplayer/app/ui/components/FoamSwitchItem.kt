package com.foamplayer.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun FoamSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
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
            colors = SwitchDefaults.colors(
                checkedThumbColor = FoamTheme.colors.primaryAccent,
                checkedTrackColor = FoamTheme.colors.primaryAccent.copy(alpha = 0.5f),
                uncheckedThumbColor = FoamTheme.colors.textSecondary.copy(alpha = 1f),
                uncheckedTrackColor = FoamTheme.colors.textSecondary.copy(alpha = 0.5f)
            )
        )
    }
}
