package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.FoamSliderItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.modals.FoamBottomModal
import com.foamplayer.app.ui.modals.ModalButton
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.FoamDimens

/**
 * Developer screen, compiled in only when the build sets devMode=true
 * (BuildConfig.DEV_MODE): the UI component demos, plus a developer-tools
 * section with helpers for re-testing the first-run flow and for generating
 * or clearing listening data. With devMode off, neither this screen nor its
 * settings entry is compiled into the app.
 */
@Composable
fun TestSettingsScreen(
    onBack: () -> Unit,
    onOnboardingReset: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var switchState by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0.5f) }
    var snappedSliderValue by remember { mutableFloatStateOf(0.5f) }

    // Destructive action awaiting its confirmation sheet (null = none).
    var confirmAction by remember { mutableStateOf<DevAction?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.test_components),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            // ------------------------------------------------ developer tools
            item {
                BasicText(
                    text = stringResource(R.string.dev_tools_section),
                    style = FoamTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                        .padding(FoamDimens.paddingMedium)
                ) {
                    ModalButton(
                        label = stringResource(R.string.dev_reset_onboarding),
                        filled = false,
                        onClick = { confirmAction = DevAction.RESET_ONBOARDING }
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
                    ModalButton(
                        label = stringResource(R.string.dev_clear_song_stats),
                        filled = false,
                        onClick = { confirmAction = DevAction.CLEAR_SONG_STATS }
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
                    ModalButton(
                        label = stringResource(R.string.dev_generate_random_data),
                        filled = false,
                        onClick = { confirmAction = DevAction.GENERATE_RANDOM_DATA }
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            // ------------------------------------------------ component demos
            item {
                BasicText(text = stringResource(R.string.switches), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    FoamSwitchItem(
                        title = stringResource(R.string.test_switch),
                        description = stringResource(R.string.test_switch_desc),
                        checked = switchState,
                        onCheckedChange = { switchState = it }
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                BasicText(text = stringResource(R.string.sliders), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    Column {
                        FoamSliderItem(
                            title = stringResource(R.string.test_slider_continuous),
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            valueDisplay = "${(sliderValue * 100).toInt()}%"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FoamDimens.separatorHeight)
                                .background(FoamTheme.colors.borderOverlay)
                        )
                        FoamSliderItem(
                            title = stringResource(R.string.test_slider_snapped),
                            value = snappedSliderValue,
                            onValueChange = { snappedSliderValue = it },
                            steps = 4,
                            valueDisplay = stringResource(R.string.test_slider_step, (snappedSliderValue * 5).toInt()),
                            minMaxDisplay = "0" to "5"
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            }
        }
    }

    DevConfirmSheet(
        action = confirmAction,
        onDismiss = { confirmAction = null },
        onConfirm = { action ->
            confirmAction = null
            when (action) {
                // The onboarding lives on its own route: reset the flag, then
                // navigate there from a cleared back stack (the nav layer
                // closes the settings stack in the same navigation).
                DevAction.RESET_ONBOARDING -> {
                    viewModel.setOnboardingCompleted(false)
                    onOnboardingReset()
                }
                DevAction.CLEAR_SONG_STATS -> viewModel.clearAllSongStats()
                DevAction.GENERATE_RANDOM_DATA -> viewModel.generateRandomStats()
            }
        }
    )
}

private enum class DevAction { RESET_ONBOARDING, CLEAR_SONG_STATS, GENERATE_RANDOM_DATA }

/** Confirmation sheet for the developer tools' destructive actions. */
@Composable
private fun DevConfirmSheet(
    action: DevAction?,
    onDismiss: () -> Unit,
    onConfirm: (DevAction) -> Unit
) {
    FoamBottomModal(
        visible = action != null,
        onDismissRequest = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            BasicText(
                text = when (action) {
                    DevAction.RESET_ONBOARDING -> stringResource(R.string.dev_reset_onboarding_title)
                    DevAction.CLEAR_SONG_STATS -> stringResource(R.string.dev_clear_song_stats_title)
                    DevAction.GENERATE_RANDOM_DATA -> stringResource(R.string.dev_generate_random_data_title)
                    null -> ""
                },
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingSmall,
                    vertical = FoamDimens.spacingTiny
                )
            )
            BasicText(
                text = when (action) {
                    DevAction.RESET_ONBOARDING -> stringResource(R.string.dev_reset_onboarding_message)
                    DevAction.CLEAR_SONG_STATS -> stringResource(R.string.dev_clear_song_stats_message)
                    DevAction.GENERATE_RANDOM_DATA -> stringResource(R.string.dev_generate_random_data_message)
                    null -> ""
                },
                style = FoamTheme.typography.body,
                modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
            )
            ModalButton(
                label = stringResource(R.string.ok),
                filled = true,
                onClick = { action?.let(onConfirm) }
            )
            ModalButton(
                label = stringResource(R.string.cancel),
                filled = false,
                onClick = onDismiss
            )
        }
    }
}