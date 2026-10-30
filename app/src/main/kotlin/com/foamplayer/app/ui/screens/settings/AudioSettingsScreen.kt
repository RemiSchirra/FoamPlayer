package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.audio.bitperfect.AudioOutputInfo
import com.foamplayer.app.ui.components.FidelityScoreCard
import com.foamplayer.app.ui.components.FoamNavigationButton
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.modals.AudioPipelineModal
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Audio settings: live fidelity score with itemized breakdown, engine
 * switches plus the current output summary, entries into the playback and
 * output-devices subscreens, and the audio pipeline modal.
 */
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit,
    onNavigateToPlayback: () -> Unit,
    onNavigateToBehavior: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToEqualizer: () -> Unit,
    viewModel: AudioSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val dspState by viewModel.dspState.collectAsState()

    var showPipelineModal by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.audio),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            item {
                BasicText(
                    text = stringResource(R.string.audio_fidelity_score),
                    style = FoamTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                FidelityScoreCard(
                    score = playbackState.audioOutputInfo?.fidelity,
                    hasTrack = playbackState.currentTrack != null
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                BasicText(
                    text = stringResource(R.string.engine_settings),
                    style = FoamTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    Column {
                        FoamSwitchItem(
                            title = stringResource(R.string.bit_perfect_output),
                            description = stringResource(R.string.bit_perfect_desc),
                            checked = settings.bitPerfectEnabled,
                            onCheckedChange = { viewModel.setBitPerfectEnabled(it) }
                        )
                        SectionDivider()
                        FoamSwitchItem(
                            title = stringResource(R.string.direct_volume_control),
                            description = stringResource(R.string.direct_volume_control_desc),
                            checked = dspState.directVolumeControl,
                            onCheckedChange = { viewModel.setDirectVolumeControl(it) }
                        )
                        SectionDivider()
                        OutputSummaryRow(outputInfo = playbackState.audioOutputInfo)
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    Column {
                        FoamNavigationButton(
                            title = stringResource(R.string.playback),
                            description = stringResource(R.string.playback_desc),
                            onClick = onNavigateToPlayback
                        )
                        SectionDivider()
                        FoamNavigationButton(
                            title = stringResource(R.string.audio_behavior),
                            description = stringResource(R.string.audio_behavior_desc),
                            onClick = onNavigateToBehavior
                        )
                        SectionDivider()
                        FoamNavigationButton(
                            title = stringResource(R.string.output_devices),
                            description = stringResource(R.string.audio_devices_desc),
                            onClick = onNavigateToDevices
                        )
                        SectionDivider()
                        FoamNavigationButton(
                            title = stringResource(R.string.equalizer_dsp),
                            description = stringResource(R.string.equalizer_dsp_desc),
                            onClick = onNavigateToEqualizer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary),
                            onClick = { showPipelineModal = true }
                        )
                        .padding(FoamDimens.paddingMedium)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            BasicText(
                                text = stringResource(R.string.audio_pipeline),
                                style = FoamTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                            BasicText(
                                text = stringResource(R.string.audio_pipeline_desc),
                                style = FoamTheme.typography.body
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = FoamTheme.colors.textSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.paddingExtraLarge))
            }
        }
    }

    AudioPipelineModal(
        visible = showPipelineModal,
        onDismiss = { showPipelineModal = false },
        outputInfo = playbackState.audioOutputInfo
    )
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FoamTheme.colors.borderOverlay)
    )
}

/** Current output device + sample-rate path, with a bit-perfect/mixed verdict dot. */
@Composable
private fun OutputSummaryRow(outputInfo: AudioOutputInfo?) {
    val bitPerfectLabel = stringResource(R.string.mode_bit_perfect)
    val mixedLabel = stringResource(R.string.mode_mixed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (outputInfo?.isBitPerfectMode == true) {
                        FoamTheme.colors.bitPerfectActive
                    } else {
                        FoamTheme.colors.bitPerfectInactive
                    }
                )
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = outputInfo?.let { "${it.deviceName} · ${it.deviceType}" }
                    ?: stringResource(R.string.no_output_devices),
                style = FoamTheme.typography.body
            )
            BasicText(
                text = outputInfo?.fallbackReason
                    ?: outputInfo?.let { info ->
                        "${info.sourceSampleRate} Hz · ${info.sourceBitDepth}-bit → " +
                                "${info.outputSampleRate} Hz · ${info.outputBitDepth}-bit"
                    }
                    ?: stringResource(R.string.fidelity_no_playback),
                style = FoamTheme.typography.caption
            )
        }
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        BasicText(
            text = if (outputInfo?.isBitPerfectMode == true) bitPerfectLabel else mixedLabel,
            style = FoamTheme.typography.caption.copy(
                color = if (outputInfo?.isBitPerfectMode == true) {
                    FoamTheme.colors.bitPerfectActive
                } else {
                    FoamTheme.colors.bitPerfectInactive
                },
                fontWeight = FontWeight.Bold
            )
        )
    }
}
