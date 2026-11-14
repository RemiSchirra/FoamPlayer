package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.foamplayer.app.R
import com.foamplayer.app.audio.dsp.FilterType
import com.foamplayer.app.audio.dsp.NodeColorPalette
import com.foamplayer.app.audio.dsp.ParametricNode
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurface
import kotlin.math.exp

/**
 * Small centered dialog for picking a parametric node's color from
 * [NodeColorPalette]. The current color is ringed; tapping a swatch selects it
 * and closes the dialog. Uses the project's plain-[Dialog] pattern rather than
 * a bottom sheet, matching the other compact choice dialogs.
 */
@Composable
internal fun NodeColorDialog(
    currentArgb: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(FoamDimens.itemRounding))
                .foamSurface()
                .padding(FoamDimens.spacingMedium),
            verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
        ) {
            NodeColorPalette.chunked(5).forEach { rowColors ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    rowColors.forEach { argb ->
                        val selected = argb == currentArgb
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        FoamTheme.accentColor
                                    } else {
                                        FoamTheme.colors.borderOverlay
                                    },
                                    shape = CircleShape
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = FoamTheme.colors.textPrimary),
                                    onClick = { onSelect(argb) }
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Small centered dialog listing every [FilterType]. Each row shows the shape's
 * name next to a miniature response curve of that filter type on a flat 0 dB
 * base; the active filter is highlighted with a check, and picking one selects
 * it and closes the dialog.
 */
@Composable
internal fun NodeFilterDialog(
    node: ParametricNode,
    onSelect: (FilterType) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(FoamDimens.itemRounding))
                .foamSurface()
                .padding(vertical = FoamDimens.spacingSmall),
            verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingTiny)
        ) {
            FilterType.entries.forEach { type ->
                val selected = type == node.type
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary),
                            onClick = { onSelect(type) }
                        )
                        .padding(
                            horizontal = FoamDimens.paddingMedium,
                            vertical = FoamDimens.spacingSmall
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterCurvePreview(
                        type = type,
                        color = when {
                            selected -> FoamTheme.accentColor
                            else -> Color(node.colorArgb)
                        },
                        modifier = Modifier
                            .width(56.dp)
                            .height(28.dp)
                    )

                    BasicText(
                        text = type.displayLabel(),
                        style = FoamTheme.typography.titleMedium.copy(
                            color = if (selected) {
                                FoamTheme.accentColor
                            } else {
                                FoamTheme.colors.textPrimary
                            }
                        ),
                        modifier = Modifier
                            .padding(start = FoamDimens.spacingMedium)
                            .weight(1f)
                    )

                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = FoamTheme.accentColor,
                            modifier = Modifier.size(FoamDimens.iconSmall)
                        )
                    }
                }
            }
        }
    }
}

private const val PREVIEW_SAMPLES = 64

/** Smooth 0..1 step: 0 before [from], 1 after [to], eased in between. */
private fun smoothStep(from: Float, to: Float, t: Float): Float {
    val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/**
 * Schematic shape of a filter type, as a level in 0..1 (1 = top) for
 * t in 0..1 across the preview width. Purely illustrative: it only conveys the
 * tendency of the filter, not any actual node setting.
 */
private fun filterShape(type: FilterType, t: Float): Float = when (type) {
    // Flat on the left, then rolls off downward on the right.
    FilterType.LOW_PASS -> {
        val u = smoothStep(0.2f, 1f, t)
        1f - 0.85f * u * u
    }
    // Mirror image: rises on the left, flat on the right.
    FilterType.HIGH_PASS -> {
        val u = smoothStep(0.2f, 1f, 1f - t)
        1f - 0.85f * u * u
    }
    // Boosted plateau on the left stepping down to the right.
    FilterType.LOW_SHELF -> 0.9f - 0.8f * smoothStep(0.3f, 0.7f, t)
    // Low on the left stepping up to a plateau on the right.
    FilterType.HIGH_SHELF -> 0.1f + 0.8f * smoothStep(0.3f, 0.7f, t)
    // Narrow bell centered on the axis.
    FilterType.PEAKING -> {
        val d = (t - 0.5f) / 0.13f
        0.12f + 0.8f * exp(-d * d)
    }
}

/**
 * Small schematic icon of a filter type's response (see [filterShape]).
 * Used by the node card's filter pill and the filter dialog rows. It does not
 * depend on any node parameter, so it never changes with frequency/gain/Q.
 */
@Composable
internal fun FilterCurvePreview(
    type: FilterType,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        // Inset by half the stroke so round caps are not clipped by the canvas.
        val w = size.width - stroke
        val h = size.height - stroke

        val path = Path()
        for (i in 0..PREVIEW_SAMPLES) {
            val t = i / PREVIEW_SAMPLES.toFloat()
            val x = stroke / 2f + t * w
            val y = stroke / 2f + (1f - filterShape(type, t)) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** Localized display name for a filter shape (the enum's [FilterType.label] stays English for logs). */
@Composable
internal fun FilterType.displayLabel(): String = stringResource(
    when (this) {
        FilterType.PEAKING -> R.string.eq_filter_peaking
        FilterType.LOW_SHELF -> R.string.eq_filter_low_shelf
        FilterType.HIGH_SHELF -> R.string.eq_filter_high_shelf
        FilterType.LOW_PASS -> R.string.eq_filter_low_pass
        FilterType.HIGH_PASS -> R.string.eq_filter_high_pass
    }
)