package com.foamplayer.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.audio.device.AudioOutputDevice
import com.foamplayer.app.audio.device.DeviceAudioConfig
import com.foamplayer.app.audio.device.DeviceBitPerfectMode
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Output devices subscreen of the Audio settings: every attached output with its
 * per-device configuration (bit-perfect mode, sample-rate override).
 */
@Composable
fun AudioDevicesSettingsScreen(
    onBack: () -> Unit,
    viewModel: AudioSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val activeDevice by viewModel.activeDevice.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.output_devices),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(top = FoamDimens.spacingMedium, bottom = LocalBottomBarClearance.current)
        ) {
            if (devices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                            .padding(FoamDimens.paddingMedium)
                    ) {
                        BasicText(
                            text = stringResource(R.string.no_output_devices),
                            style = FoamTheme.typography.body
                        )
                    }
                }
            } else {
                items(devices, key = { it.id }) { device ->
                    DeviceConfigCard(
                        device = device,
                        isActive = activeDevice?.id == device.id,
                        config = settings.deviceAudioConfig(device.id),
                        onBitPerfectModeChange = {
                            viewModel.setDeviceBitPerfectMode(device.id, it)
                        },
                        onSampleRateChange = {
                            viewModel.setDeviceSampleRateOverride(device.id, it)
                        }
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                }
            }
        }
    }
}

// --------------------------------------------------------- device config

private val SAMPLE_RATE_OPTIONS = listOf(0, 44_100, 48_000, 96_000, 192_000)

@Composable
private fun DeviceConfigCard(
    device: AudioOutputDevice,
    isActive: Boolean,
    config: DeviceAudioConfig,
    onBitPerfectModeChange: (DeviceBitPerfectMode) -> Unit,
    onSampleRateChange: (Int) -> Unit
) {
    var expanded by rememberSaveable(device.id) { mutableStateOf(false) }

    // Device capability caption: "<type> · up to <rate>" + optional " · direct".
    val autoLabel = stringResource(R.string.rate_auto)
    val maxRateTemplate = stringResource(R.string.device_max_rate)
    val directLabel = stringResource(R.string.device_direct)
    val capabilityCaption = listOf(
        device.typeLabel,
        maxRateTemplate.replace("%1\$s", formatRate(device.maxSupportedSampleRate, autoLabel)),
        directLabel.takeIf { device.supportsDirectPlayback }
    ).filterNotNull().joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { expanded = !expanded }
            )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = { expanded = !expanded }
                    )
                    .padding(FoamDimens.paddingMedium)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) FoamTheme.colors.bitPerfectActive
                                else FoamTheme.colors.borderOverlay
                            )
                    )
                    Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = device.name,
                            style = FoamTheme.typography.titleMedium.copy(
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                        BasicText(
                            text = capabilityCaption,
                            style = FoamTheme.typography.caption
                        )
                    }
                    if (isActive) {
                        BasicText(
                            text = stringResource(R.string.device_active),
                            style = FoamTheme.typography.caption.copy(
                                color = FoamTheme.colors.bitPerfectActive,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = FoamTheme.colors.textSecondary
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = FoamDimens.paddingMedium)
                        .padding(bottom = FoamDimens.paddingMedium)
                ) {
                    BasicText(
                        text = stringResource(R.string.device_bit_perfect_mode),
                        style = FoamTheme.typography.caption.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
                    ) {
                        DeviceBitPerfectMode.entries.forEach { mode ->
                            val selected = config.bitPerfectMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                                    .background(
                                        if (selected) FoamTheme.accentColor
                                        else FoamTheme.colors.surfaceVariant
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(color = FoamTheme.colors.textPrimary),
                                        role = Role.RadioButton,
                                        onClick = { onBitPerfectModeChange(mode) }
                                    )
                                    .padding(vertical = FoamDimens.spacingSmall),
                                contentAlignment = Alignment.Center
                            ) {
                                BasicText(
                                    text = mode.label,
                                    style = FoamTheme.typography.caption.copy(
                                        color = if (selected) FoamTheme.colors.background
                                        else FoamTheme.colors.textPrimary,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(FoamDimens.paddingMedium))

                    BasicText(
                        text = stringResource(R.string.device_sample_rate_override),
                        style = FoamTheme.typography.caption.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
                    ) {
                        SAMPLE_RATE_OPTIONS.forEach { rate ->
                            val selected = config.sampleRateOverrideHz == rate
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                                    .background(
                                        if (selected) FoamTheme.accentColor
                                        else FoamTheme.colors.surfaceVariant
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(color = FoamTheme.colors.textPrimary),
                                        role = Role.RadioButton,
                                        onClick = { onSampleRateChange(rate) }
                                    )
                                    .padding(
                                        horizontal = FoamDimens.paddingSmall,
                                        vertical = FoamDimens.spacingSmall
                                    )
                            ) {
                                BasicText(
                                    text = formatRate(rate, autoLabel),
                                    style = FoamTheme.typography.caption.copy(
                                        color = if (selected) FoamTheme.colors.background
                                        else FoamTheme.colors.textPrimary,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatRate(rate: Int, autoLabel: String): String = when (rate) {
    0 -> autoLabel
    44_100 -> "44.1 kHz"
    192_000 -> "192 kHz"
    else -> "${rate / 1000} kHz"
}