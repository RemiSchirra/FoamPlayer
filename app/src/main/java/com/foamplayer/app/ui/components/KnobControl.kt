package com.foamplayer.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Poweramp-style rotary control: a 270° arc from bottom-left to bottom-right, an
 * accent arc for the filled portion and a needle at the current angle. Dragging
 * vertically (or horizontally) changes the value; a double tap snaps back to
 * [defaultValue]. The value lives locally during a drag and is committed once
 * through [onValueChange] on release, mirroring the app's commit-on-release sliders
 * so a drag never floods the DSP engine (and the screen) with state emissions.
 */
@Composable
fun KnobControl(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    valueText: (Float) -> String,
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    minValue: Float = -1f,
    maxValue: Float = 1f,
    defaultValue: Float = 0f,
    sensitivity: Float = 0.4f
) {
    // Manual drag tracking: detectDragGestures exposes no interaction source, so
    // the dragged state flips on gesture start/end to drive the label readout.
    var isDragging by remember { mutableStateOf(false) }

    // Local value while dragging; re-synced from the external value whenever it
    // changes (preset applied, knob toggled elsewhere...).
    var dragValue by remember(value) { mutableFloatStateOf(value) }
    val displayValue = if (isDragging) dragValue else value

    val accent = FoamTheme.accentColor
    val trackColor = FoamTheme.colors.surfaceVariant
    val needleColor = FoamTheme.colors.textPrimary

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        BasicText(
            text = if (isDragging) valueText(dragValue) else label,
            style = FoamTheme.typography.caption.copy(
                color = if (isDragging) accent else FoamTheme.colors.textSecondary,
                fontWeight = if (isDragging) FontWeight.Bold else FontWeight.Normal
            )
        )

        Canvas(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(size)
                .pointerInput(minValue, maxValue, sensitivity) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            dragValue = value.coerceIn(minValue, maxValue)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val range = maxValue - minValue
                            if (range > 0f) {
                                val delta = (dragAmount.x - dragAmount.y) * sensitivity * range / 100f
                                dragValue = (dragValue + delta).coerceIn(minValue, maxValue)
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            onValueChange(dragValue)
                        },
                        onDragCancel = {
                            isDragging = false
                            onValueChange(dragValue)
                        }
                    )
                }
                .pointerInput(minValue, maxValue, defaultValue) {
                    detectTapGestures(
                        onDoubleTap = {
                            dragValue = defaultValue
                            onValueChange(defaultValue)
                        }
                    )
                }
        ) {
            val stroke = 5.dp.toPx()
            val inset = stroke
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            // 270° sweep starting at 135° (bottom-left).
            val startAngle = 135f
            val sweep = 270f

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            val fraction = ((displayValue - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)

            drawArc(
                color = accent,
                startAngle = startAngle,
                sweepAngle = sweep * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // Needle at the current angle.
            val angleRad = Math.toRadians((startAngle + sweep * fraction).toDouble())
            val radius = (this.size.minDimension - inset * 2) / 2f
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val inner = Offset(
                center.x + (radius * 0.45f * cos(angleRad)).toFloat(),
                center.y + (radius * 0.45f * sin(angleRad)).toFloat()
            )
            val outer = Offset(
                center.x + (radius * 0.85f * cos(angleRad)).toFloat(),
                center.y + (radius * 0.85f * sin(angleRad)).toFloat()
            )
            drawLine(needleColor, inner, outer, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color.Transparent, radius = 1f)
        }
    }
}
