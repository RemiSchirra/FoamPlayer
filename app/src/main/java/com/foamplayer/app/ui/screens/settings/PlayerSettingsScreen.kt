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
fun PlayerSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val miniRounding = settings.miniPlayerRounding
    val fullRounding = settings.fullPlayerRounding
    var localMiniRounding by remember { mutableFloatStateOf(miniRounding.toFloat()) }
    var localFullRounding by remember { mutableFloatStateOf(fullRounding.toFloat()) }
    val isImmersiveEnabled = settings.immersiveModeEnabled
    val isAnimateSeekBarEnabled = settings.animateSeekBarEnabled
    val isAutoOpenPlayerEnabled = settings.autoOpenPlayerEnabled

    LaunchedEffect(miniRounding) { localMiniRounding = miniRounding.toFloat() }
    LaunchedEffect(fullRounding) { localFullRounding = fullRounding.toFloat() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = "Player",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium)
        ) {
            item {
                BasicText(text = "Behaviour", style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    FoamSwitchItem(
                        title = "Auto Open Player",
                        description = "Open player screen when a track starts",
                        checked = isAutoOpenPlayerEnabled,
                        onCheckedChange = { viewModel.setAutoOpenPlayerEnabled(it) }
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                BasicText(text = "Visuals", style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    Column {
                        FoamSwitchItem(
                            title = "Immersive Mode",
                            description = "Album art fills the entire screen",
                            checked = isImmersiveEnabled,
                            onCheckedChange = { viewModel.setImmersiveModeEnabled(it) }
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FoamDimens.separatorHeight)
                                .background(FoamTheme.colors.borderOverlay)
                        )
                        FoamSwitchItem(
                            title = "Animate Seek Bar",
                            description = "Smooth progress bar movement",
                            checked = isAnimateSeekBarEnabled,
                            onCheckedChange = { viewModel.setAnimateSeekBarEnabled(it) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                BasicText(text = "Interface Rounding", style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    Column {
                        FoamSliderItem(
                            title = "Mini Player",
                            value = localMiniRounding,
                            onValueChange = { localMiniRounding = it },
                            onValueChangeFinished = {
                                viewModel.setMiniPlayerRounding(localMiniRounding.roundToInt())
                            },
                            steps = 9,
                            valueRange = 0f..100f,
                            valueDisplay = "${localMiniRounding.roundToInt()}%",
                            minMaxDisplay = "Square" to "Circle"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FoamDimens.separatorHeight)
                                .background(FoamTheme.colors.borderOverlay)
                        )
                        FoamSliderItem(
                            title = "Full Player",
                            value = localFullRounding,
                            onValueChange = { localFullRounding = it },
                            onValueChangeFinished = {
                                viewModel.setFullPlayerRounding(localFullRounding.roundToInt())
                            },
                            steps = 9,
                            valueRange = 0f..100f,
                            valueDisplay = "${localFullRounding.roundToInt()}%",
                            minMaxDisplay = "Square" to "Circle"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            }
        }
    }
}
