package com.foamplayer.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.abs

/**
 * Vertical fader (Poweramp-style): a narrow track with a draggable thumb. When
 * [bipolar], the fill grows from the CENTER of the range (0 dB for a pre-amp) in
 * the direction of the value, instead of always from the bottom.
 *
 * A tap jumps to the tapped position; a drag updates live; the value is committed
 * once through [onCommit] on release, mirroring the app's commit-on-release sliders.
 * The value readout above the fader reflects the live drag value.
 */
@Composable
fun VerticalSlider(
    value: Float,
    onCommit: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    valueText: (Float) -> String,
    modifier: Modifier = Modifier,
    bipolar: Boolean = true
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember(value) { mutableFloatStateOf(value) }
    val display = if (isDragging) dragValue else value

    val accent = FoamTheme.accentColor
    val trackColor = FoamTheme.colors.surfaceVariant
    val thumbColor = FoamTheme.colors.textPrimary

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        BasicText(
            text = valueText(display),
            style = FoamTheme.typography.monospace.copy(
                color = if (isDragging) accent else FoamTheme.colors.textSecondary,
                fontWeight = if (isDragging) FontWeight.Bold else FontWeight.Normal
            )
        )

        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .pointerInput(valueRange) {
                    detectDragGestures(
                        onDragStart = { start ->
                            isDragging = true
                            dragValue = valueAtY(start.y, size.height.toFloat(), valueRange)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragValue = valueAtY(
                                change.position.y.coerceIn(0f, size.height.toFloat()),
                                size.height.toFloat(),
                                valueRange
                            )
                        },
                        onDragEnd = {
                            isDragging = false
                            onCommit(dragValue)
                        },
                        onDragCancel = {
                            isDragging = false
                            onCommit(dragValue)
                        }
                    )
                }
                .pointerInput(valueRange) {
                    detectTapGestures(
                        onTap = { position ->
                            val tapped = valueAtY(position.y, size.height.toFloat(), valueRange)
                            dragValue = tapped
                            onCommit(tapped)
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val trackWidth = 6.dp.toPx()
            val trackLeft = (w - trackWidth) / 2f

            // Track.
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(trackLeft, 0f),
                size = Size(trackWidth, h),
                cornerRadius = CornerRadius(trackWidth / 2f)
            )

            val thumbY = yForValue(display, valueRange, h)

            // Fill: from center (bipolar) or from the bottom.
            val fillAnchor = if (bipolar) {
                yForValue(rangeCenter(valueRange), valueRange, h)
            } else {
                h
            }
            val fillTop = minOf(thumbY, fillAnchor)
            val fillHeight = abs(thumbY - fillAnchor)
            if (fillHeight > 1f) {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(trackLeft, fillTop),
                    size = Size(trackWidth, fillHeight),
                    cornerRadius = CornerRadius(trackWidth / 2f)
                )
            }

            // Thumb: horizontal rounded bar.
            val thumbWidth = w * 0.72f
            val thumbHeight = 5.dp.toPx()
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset((w - thumbWidth) / 2f, thumbY - thumbHeight / 2f),
                size = Size(thumbWidth, thumbHeight),
                cornerRadius = CornerRadius(thumbHeight / 2f)
            )
            if (isDragging) {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset((w - thumbWidth) / 2f, thumbY - thumbHeight / 2f),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(thumbHeight / 2f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
        )
    }
}

private fun rangeCenter(range: ClosedFloatingPointRange<Float>): Float =
    (range.start + range.endInclusive) / 2f

private fun valueAtY(y: Float, height: Float, range: ClosedFloatingPointRange<Float>): Float {
    val t = 1f - (y / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return range.start + t * (range.endInclusive - range.start)
}

private fun yForValue(value: Float, range: ClosedFloatingPointRange<Float>, height: Float): Float {
    val t = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    return (1f - t) * height
}
