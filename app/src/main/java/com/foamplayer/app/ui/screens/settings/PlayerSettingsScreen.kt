package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.ui.components.FoamEnumItem
import com.foamplayer.app.ui.components.FoamSliderStateItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
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
            title = stringResource(R.string.player),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
                ) {
                    BasicText(text = stringResource(R.string.behaviour), style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                    ) {
                        FoamSwitchItem(
                            title = stringResource(R.string.auto_open_player),
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
                    BasicText(text = stringResource(R.string.visuals), style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                    ) {
                        Column {
                            FoamSwitchItem(
                                title = stringResource(R.string.immersive_mode),
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
                                title = stringResource(R.string.animate_seek_bar),
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
                    BasicText(text = stringResource(R.string.interface_rounding), style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                    ) {
                        Column {
                            FoamSliderStateItem(
                                title = stringResource(R.string.mini_player),
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
                                title = stringResource(R.string.full_player),
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
