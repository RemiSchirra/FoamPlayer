package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.ui.components.FoamSliderStateItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt

@Composable
fun BackgroundSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = "Background",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FoamDimens.paddingMedium)
                .padding(bottom = LocalBottomBarClearance.current),
        ) {
            BasicText(text = "Dynamic Effects", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                    .background(FoamTheme.colors.surface)
            ) {
                FoamSwitchItem(
                    title = "Dynamic Background",
                    description = "Extract colors from track art for background",
                    checked = settings.dynamicBackgroundEnabled,
                    onCheckedChange = viewModel::setDynamicBackgroundEnabled,
                )
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            
            BasicText(text = "Appearance", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                    .background(FoamTheme.colors.surface)
            ) {
                Column {
                    FoamSliderStateItem(
                        title = "Background Blur",
                        initialValue = settings.backgroundBlur.toFloat(),
                        onValueChangeFinished = {
                            viewModel.setBackgroundBlur(it.roundToInt())
                        },
                        steps = 9,
                        valueRange = 0f..100f,
                        formatValue = { "${it.roundToInt()}%" },
                        minMaxDisplay = "None" to "Max",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FoamDimens.separatorHeight)
                            .background(FoamTheme.colors.borderOverlay)
                    )
                    FoamSliderStateItem(
                        title = "App Color Intensity",
                        initialValue = settings.appBackgroundIntensity.toFloat(),
                        onValueChangeFinished = {
                            viewModel.setAppBackgroundIntensity(it.roundToInt())
                        },
                        steps = 9,
                        valueRange = 0f..100f,
                        formatValue = { "${it.roundToInt()}%" },
                        minMaxDisplay = "Dim" to "Vivid",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FoamDimens.separatorHeight)
                            .background(FoamTheme.colors.borderOverlay)
                    )
                    FoamSliderStateItem(
                        title = "Player Color Intensity",
                        initialValue = settings.playerBackgroundIntensity.toFloat(),
                        onValueChangeFinished = {
                            viewModel.setPlayerBackgroundIntensity(it.roundToInt())
                        },
                        steps = 9,
                        valueRange = 0f..100f,
                        formatValue = { "${it.roundToInt()}%" },
                        minMaxDisplay = "Dim" to "Vivid",
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
        }
    }
}
