package com.foamplayer.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun FoamSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueDisplay: String,
    modifier: Modifier = Modifier,
    steps: Int? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    description: String? = null,
    minMaxDisplay: Pair<String, String>? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        // Header : title, description, and current value
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(text = title, style = FoamTheme.typography.titleMedium)
                description?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    BasicText(text = it, style = FoamTheme.typography.body)
                }
            }
            BasicText(
                text = valueDisplay,
                style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.colors.primaryAccent)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            steps = steps ?: 0,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = FoamTheme.colors.primaryAccent,
                activeTrackColor = FoamTheme.colors.primaryAccent,
                inactiveTrackColor = FoamTheme.colors.textSecondary.copy(alpha = 0.5f),
                activeTickColor = Color.White.copy(alpha = 0.5f),
                inactiveTickColor = FoamTheme.colors.textSecondary.copy(alpha = 0.5f)
            )
        )

        // Min max footer
        minMaxDisplay?.let { (min, max) ->
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BasicText(text = min, style = FoamTheme.typography.caption)
                BasicText(text = max, style = FoamTheme.typography.caption)
            }
        }
    }
}