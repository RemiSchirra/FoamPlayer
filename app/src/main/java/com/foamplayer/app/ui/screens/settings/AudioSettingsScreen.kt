package com.foamplayer.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.audio.bitperfect.AudioOutputInfo
import com.foamplayer.app.audio.device.AudioOutputDevice
import com.foamplayer.app.audio.device.DeviceAudioConfig
import com.foamplayer.app.audio.device.DeviceBitPerfectMode
import com.foamplayer.app.audio.fidelity.FidelityItem
import com.foamplayer.app.audio.fidelity.FidelityScore
import com.foamplayer.app.audio.fidelity.FidelityStatus
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Audio settings (Poweramp-inspired):
 *  - live audio fidelity score with itemized breakdown,
 *  - engine switches (bit-perfect master, direct volume control) plus the current
 *    output summary,
 *  - per-output-device configuration (bit-perfect mode, sample-rate override),
 *  - audio pipeline visualization modal.
 */
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit,
    viewModel: AudioSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val activeDevice by viewModel.activeDevice.collectAsState()
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
            // ------------------------------------------------ fidelity score
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

            // ------------------------------------------------ engine settings
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

            // ------------------------------------------------- output devices
            item {
                BasicText(
                    text = stringResource(R.string.output_devices),
                    style = FoamTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

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

            // ------------------------------------------- audio pipeline entry
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

// ---------------------------------------------------------------- shared bits

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FoamTheme.colors.borderOverlay)
    )
}

// ----------------------------------------------------------- fidelity card

@Composable
private fun FidelityScoreCard(
    score: FidelityScore?,
    hasTrack: Boolean
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

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
                    FidelityRing(score = score)

                    Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))

                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = score?.label
                                ?: if (hasTrack) "Measuring…" else stringResource(R.string.fidelity_no_playback),
                            style = FoamTheme.typography.titleMedium.copy(
                                color = scoreColor(score),
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                        BasicText(
                            text = stringResource(R.string.audio_fidelity_desc),
                            style = FoamTheme.typography.caption
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = FoamTheme.colors.textSecondary
                    )
                }
            }

            // Itemized breakdown.
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = FoamDimens.paddingMedium)
                        .padding(bottom = FoamDimens.paddingMedium)
                ) {
                    if (score != null) {
                        score.items.forEachIndexed { index, item ->
                            if (index > 0) SectionDivider()
                            FidelityItemRow(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun scoreColor(score: FidelityScore?) = when {
    score == null -> FoamTheme.colors.textSecondary
    score.total >= 85 -> FoamTheme.colors.bitPerfectActive
    score.total >= 55 -> FoamTheme.colors.bitPerfectInactive
    else -> FoamTheme.accentColor
}

@Composable
private fun FidelityRing(score: FidelityScore?) {
    val ringColor = scoreColor(score)
    val trackColor = FoamTheme.colors.surfaceVariant
    val fraction = score?.fraction ?: 0f

    Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(72.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (fraction > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = score?.total?.toString() ?: "—",
                style = FoamTheme.typography.titleMedium.copy(
                    color = ringColor,
                    fontWeight = FontWeight.Bold
                )
            )
            BasicText(
                text = "/ ${score?.max ?: 100}",
                style = FoamTheme.typography.caption.copy(fontSize = 9.sp)
            )
        }
    }
}

@Composable
private fun FidelityItemRow(item: FidelityItem) {
    val statusColor = when (item.status) {
        FidelityStatus.PASS -> FoamTheme.colors.bitPerfectActive
        FidelityStatus.WARN -> FoamTheme.colors.bitPerfectInactive
        FidelityStatus.FAIL -> FoamTheme.accentColor
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FoamDimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = item.label, style = FoamTheme.typography.body)
            BasicText(text = item.detail, style = FoamTheme.typography.caption)
        }
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        BasicText(
            text = "${item.points}/${item.maxPoints}",
            style = FoamTheme.typography.monospace.copy(color = statusColor)
        )
    }
}

// ------------------------------------------------------ output summary row

@Composable
private fun OutputSummaryRow(outputInfo: AudioOutputInfo?) {
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
            text = if (outputInfo?.isBitPerfectMode == true) "BIT-PERFECT" else "MIXED",
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
                            text = "${device.typeLabel} · up to ${formatRate(device.maxSupportedSampleRate)}" +
                                    if (device.supportsDirectPlayback) " · direct" else "",
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
                                    text = formatRate(rate),
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

private fun formatRate(rate: Int): String = when (rate) {
    0 -> "Auto"
    44_100 -> "44.1 kHz"
    192_000 -> "192 kHz"
    else -> "${rate / 1000} kHz"
}
