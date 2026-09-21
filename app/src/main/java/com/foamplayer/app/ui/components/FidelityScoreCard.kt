package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foamplayer.app.R
import com.foamplayer.app.audio.fidelity.FidelityItem
import com.foamplayer.app.audio.fidelity.FidelityScore
import com.foamplayer.app.audio.fidelity.FidelityStatus
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Live audio fidelity verdict with the itemized breakdown, shared between the
 * audio settings screen and the player's audio-info modal. Tapping toggles the
 * itemized score breakdown.
 */
@Composable
fun FidelityScoreCard(
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
                            if (index > 0) FidelitySectionDivider()
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
private fun FidelitySectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FoamTheme.colors.borderOverlay)
    )
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
