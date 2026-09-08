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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.ui.components.FoamSliderItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt

@Composable
fun BackgroundSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var localBackgroundBlur by remember { mutableFloatStateOf(settings.backgroundBlur.toFloat()) }
    var localBackgroundIntensity by remember {
        mutableFloatStateOf(settings.dynamicBackgroundIntensity.toFloat())
    }

    LaunchedEffect(settings.backgroundBlur) {
        localBackgroundBlur = settings.backgroundBlur.toFloat()
    }
    LaunchedEffect(settings.dynamicBackgroundIntensity) {
        localBackgroundIntensity = settings.dynamicBackgroundIntensity.toFloat()
    }

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
                .padding(horizontal = FoamDimens.paddingMedium),
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
                    FoamSliderItem(
                        title = "Background Blur",
                        value = localBackgroundBlur,
                        onValueChange = { localBackgroundBlur = it },
                        onValueChangeFinished = {
                            viewModel.setBackgroundBlur(localBackgroundBlur.roundToInt())
                        },
                        steps = 9,
                        valueRange = 0f..100f,
                        valueDisplay = "${localBackgroundBlur.roundToInt()}%",
                        minMaxDisplay = "None" to "Max",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FoamDimens.separatorHeight)
                            .background(FoamTheme.colors.borderOverlay)
                    )
                    FoamSliderItem(
                        title = "Color Intensity",
                        value = localBackgroundIntensity,
                        onValueChange = { localBackgroundIntensity = it },
                        onValueChangeFinished = {
                            viewModel.setDynamicBackgroundIntensity(localBackgroundIntensity.roundToInt())
                        },
                        steps = 9,
                        valueRange = 0f..100f,
                        valueDisplay = "${localBackgroundIntensity.roundToInt()}%",
                        minMaxDisplay = "Dim" to "Vivid",
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
        }
    }
}
