package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.audio.bitperfect.AudioOutputInfo
import com.foamplayer.app.audio.pipeline.PipelineStageStatus
import com.foamplayer.app.ui.components.FoamBottomModal
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Audio pipeline visualization (Poweramp-style signal path): a live vertical
 * rendering of every stage between the file and the output device — decoder,
 * volume, tone, both EQ sections, balance/stereo, limiter, resampler, output —
 * each with its current status and the values it is applying right now.
 */
@Composable
fun AudioPipelineModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    outputInfo: AudioOutputInfo?,
    viewModel: AudioSettingsViewModel = hiltViewModel()
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val info = outputInfo ?: playbackState.audioOutputInfo

    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = "Audio Pipeline",
                style = FoamTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            val track = playbackState.currentTrack
            BasicText(
                text = track?.let { "${it.title} · ${it.format}" } ?: "Nothing playing",
                style = FoamTheme.typography.caption
            )

            Spacer(modifier = Modifier.height(FoamDimens.paddingMedium))

            val stages = info?.pipeline?.stages
            if (stages.isNullOrEmpty()) {
                BasicText(
                    text = "Start playback to inspect the live audio path.",
                    style = FoamTheme.typography.body
                )
            } else {
                stages.forEachIndexed { index, stage ->
                    PipelineStageRow(stage = stage, isLast = index == stages.lastIndex)
                }
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
        }
    }
}

@Composable
private fun PipelineStageRow(
    stage: com.foamplayer.app.audio.pipeline.PipelineStage,
    isLast: Boolean
) {
    val statusColor = when (stage.status) {
        PipelineStageStatus.ACTIVE -> FoamTheme.colors.bitPerfectActive
        PipelineStageStatus.BYPASSED -> FoamTheme.colors.textSecondary
        PipelineStageStatus.WARN -> FoamTheme.colors.bitPerfectInactive
    }
    val lineColor = FoamTheme.colors.borderOverlay

    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        // Status dot + connector line.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(lineColor)
                        .drawBehind {
                            drawRect(
                                color = lineColor,
                                topLeft = Offset(0f, size.height),
                                size = Size(size.width, 5.dp.toPx())
                            )
                        }
                )
            }
        }

        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = stage.name,
                    style = FoamTheme.typography.body.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (stage.status == PipelineStageStatus.BYPASSED) {
                            FoamTheme.colors.textSecondary
                        } else {
                            FoamTheme.colors.textPrimary
                        }
                    )
                )
                Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                StageBadge(stage.status)
            }
            BasicText(text = stage.headline, style = FoamTheme.typography.caption)
            stage.details.forEach { detail ->
                BasicText(
                    text = detail,
                    style = FoamTheme.typography.caption.copy(
                        color = FoamTheme.colors.textSecondary.copy(alpha = 0.8f)
                    )
                )
            }
        }
    }
}

@Composable
private fun StageBadge(status: PipelineStageStatus) {
    val (label, color) = when (status) {
        PipelineStageStatus.ACTIVE -> "ACTIVE" to FoamTheme.colors.bitPerfectActive
        PipelineStageStatus.BYPASSED -> "BYPASS" to FoamTheme.colors.textSecondary
        PipelineStageStatus.WARN -> "WARN" to FoamTheme.colors.bitPerfectInactive
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
        )
    }
}
