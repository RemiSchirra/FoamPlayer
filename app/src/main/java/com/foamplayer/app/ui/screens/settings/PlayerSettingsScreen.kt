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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.ui.components.FoamEnumItem
import com.foamplayer.app.ui.components.FoamSliderStateItem
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
    val isImmersiveEnabled = settings.immersiveModeEnabled
    val isAnimateSeekBarEnabled = settings.animateSeekBarEnabled
    val isAutoOpenPlayerEnabled = settings.autoOpenPlayerEnabled
    val isAnimatedCoverEnabled = settings.animatedCoverEnabled
    val isAnimatedCoverAllowMobileData = settings.animatedCoverAllowMobileData
    val marqueeMode = settings.marqueeMode

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
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
                ) {
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
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
                ) {
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
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .background(FoamTheme.colors.borderOverlay)
                            )
                            FoamSwitchItem(
                                title = "Animated Album Cover",
                                description = "Animated artwork in the immersive player",
                                checked = isAnimatedCoverEnabled,
                                onCheckedChange = { viewModel.setAnimatedCoverEnabled(it) }
                            )
                            if (isAnimatedCoverEnabled) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(FoamDimens.separatorHeight)
                                        .background(FoamTheme.colors.borderOverlay)
                                )
                                FoamSwitchItem(
                                    title = "Allow Mobile Data",
                                    description = "Load animated covers on mobile data, not Wi-Fi only",
                                    checked = isAnimatedCoverAllowMobileData,
                                    onCheckedChange = { viewModel.setAnimatedCoverAllowMobileData(it) }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                FoamEnumItem(
                    title = "Text animation",
                    description = "Scrolling effect for long titles in the full player",
                    options = MarqueeMode.entries.toTypedArray(),
                    selectedOption = marqueeMode,
                    onOptionSelected = { viewModel.setMarqueeMode(it) },
                    getLabel = { it.label }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
                ) {
                    BasicText(text = "Interface Rounding", style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                    ) {
                        Column {
                            FoamSliderStateItem(
                                title = "Mini Player",
                                initialValue = settings.miniPlayerRounding.toFloat(),
                                onValueChangeFinished = {
                                    viewModel.setMiniPlayerRounding(it.roundToInt())
                                },
                                steps = 9,
                                valueRange = 0f..100f,
                                formatValue = { "${it.roundToInt()}%" },
                                minMaxDisplay = "Square" to "Circle"
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .background(FoamTheme.colors.borderOverlay)
                            )
                            FoamSliderStateItem(
                                title = "Full Player",
                                initialValue = settings.fullPlayerRounding.toFloat(),
                                onValueChangeFinished = {
                                    viewModel.setFullPlayerRounding(it.roundToInt())
                                },
                                steps = 9,
                                valueRange = 0f..100f,
                                formatValue = { "${it.roundToInt()}%" },
                                minMaxDisplay = "Square" to "Circle"
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            }
        }
    }
}
