package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            .padding(horizontal = 16.dp)
    ) {
        item {
            SettingsHeader(title = "Component Test", onBack = onBack)
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            BasicText(text = "Switches", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(FoamTheme.colors.surface)
            ) {
                FoamSwitchItem(
                    title = "Test Switch",
                    description = "Demonstration of the flat switch component",
                    checked = switchState,
                    onCheckedChange = { switchState = it }
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            BasicText(text = "Sliders", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(FoamTheme.colors.surface)
            ) {
                Column {
                    FoamSliderItem(
                        title = "Continuous Slider",
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueDisplay = "${(sliderValue * 100).toInt()}%"
                    )
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FoamTheme.colors.borderOverlay).padding(horizontal = 16.dp))
                    FoamSliderItem(
                        title = "Snapped Slider",
                        value = snappedSliderValue,
                        onValueChange = { snappedSliderValue = it },
                        steps = 4,
                        valueDisplay = "Step ${(snappedSliderValue * 5).toInt()}",
                        minMaxDisplay = "0" to "5"
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
