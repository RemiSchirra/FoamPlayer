package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.components.FoamSliderItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun TestSettingsScreen(
    onBack: () -> Unit
) {
    var switchState by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0.5f) }
    var snappedSliderValue by remember { mutableFloatStateOf(0.5f) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        item {
            SettingsHeader(title = "Component Test", onBack = onBack)
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            BasicText(text = "Switches", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            FoamSwitchItem(
                title = "Test Switch",
                description = "This is a description for the test switch component.",
                checked = switchState,
                onCheckedChange = { switchState = it }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            BasicText(text = "Sliders", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            FoamSliderItem(
                title = "Continuous Slider",
                description = "Standard smooth slider component.",
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueDisplay = "${(sliderValue * 100).toInt()}%"
            )

            Spacer(modifier = Modifier.height(16.dp))

            FoamSliderItem(
                title = "Snapped Slider (5 steps)",
                description = "Slider with snapping enabled.",
                value = snappedSliderValue,
                onValueChange = { snappedSliderValue = it },
                steps = 4, // Changed from 5 to 4
                valueDisplay = "Step ${(snappedSliderValue * 5).toInt()}",
                minMaxDisplay = "0" to "5"
            )
        }
    }
}
