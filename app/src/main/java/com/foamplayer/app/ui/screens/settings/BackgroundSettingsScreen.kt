package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.ui.components.FoamSliderItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt

@Composable
fun BackgroundSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var localBackgroundBlur by remember { mutableFloatStateOf(settings.backgroundBlur.toFloat()) }

    LaunchedEffect(settings.backgroundBlur) {
        localBackgroundBlur = settings.backgroundBlur.toFloat()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .padding(horizontal = 16.dp),
    ) {
        SettingsHeader(title = "Background", onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        FoamSwitchItem(
            title = "Dynamic background",
            description = "Uses current track as background",
            checked = settings.dynamicBackgroundEnabled,
            onCheckedChange = viewModel::setDynamicBackgroundEnabled,
        )

        Spacer(modifier = Modifier.height(24.dp))
        FoamSliderItem(
            title = "Background blur",
            value = localBackgroundBlur,
            onValueChange = { localBackgroundBlur = it },
            onValueChangeFinished = {
                viewModel.setBackgroundBlur(localBackgroundBlur.roundToInt())
            },
            // Nine internal ticks create the requested ten 0–100 intervals.
            steps = 9,
            valueRange = 0f..100f,
            valueDisplay = localBackgroundBlur.roundToInt().toString(),
            minMaxDisplay = "None" to "Max",
        )
    }
}
