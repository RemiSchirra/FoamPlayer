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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 270° arc starting at 135° (bottom-left), shared by drawing and gestures. */
private const val ARC_START_DEG = 135f
private const val ARC_SWEEP_DEG = 270f

/**
 * Rotary knob: a 270° arc with an accent fill and a needle at the current
 * angle. The needle tracks the finger's rotation around the center 1:1
 * (wrap-around included) and keeps working while the finger drifts outside
 * the control. Touches near the center are ignored. Double-tap resets to
 * [defaultValue].
 *
 * Acts as a detent around [defaultValue]: within [snapFraction] of the range
 * the knob sits exactly on the default, with a haptic tick on entering the
 * zone. The raw finger value is kept separate from the displayed one so the
 * detent never desyncs the needle from the finger.
 *
 * Every rotation step commits through [onValueChange] so the knob and DSP
 * update in realtime while dragging (persistence is debounced engine-side).
 * [sensitivity] scales the rotation-to-value mapping: 1f means 270° of finger
 * rotation sweeps the full range.
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
    sensitivity: Float = 1f,
    /** Fraction of the range around [defaultValue] where the knob snaps to it. 0f disables snapping. */
    snapFraction: Float = 0.04f
) {
    // detectDragGestures exposes no interaction source; flip on gesture start/end.
    var isDragging by remember { mutableStateOf(false) }

    // Displayed/committed value while dragging. Re-seeded from the hoisted
    // value on every gesture start so external changes are always picked up.
    var dragValue by remember { mutableFloatStateOf(value) }

    // Finger-tracked value, never snapped; dragValue derives from it.
    var rawValue by remember { mutableFloatStateOf(value) }

    val haptic = LocalHapticFeedback.current

    // pointerInput survives recomposition: read through these to avoid stale captures.
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

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
                .pointerInput(minValue, maxValue, sensitivity, defaultValue, snapFraction) {
                    // PointerInputScope.size in px; the Dp `size` param is shadowed.
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    val deadZoneRadius = minOf(this.size.width, this.size.height) * 0.2f
                    val range = maxValue - minValue
                    val snapRange = range * snapFraction
                    var wasSnapped = false

                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            rawValue = latestValue.coerceIn(minValue, maxValue)
                            dragValue = rawValue
                            // Starting on the default must not buzz on the first move.
                            wasSnapped = snapRange > 0f &&
                                    abs(rawValue - defaultValue) <= snapRange
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val rotation = pointerRotationDelta(
                                center = center,
                                from = change.previousPosition,
                                to = change.position,
                                deadZoneRadius = deadZoneRadius
                            )
                            if (rotation != null && range > 0f) {
                                val delta = rotation / ARC_SWEEP_DEG * range * sensitivity
                                rawValue = (rawValue + delta).coerceIn(minValue, maxValue)

                                val inSnapZone = snapRange > 0f &&
                                        abs(rawValue - defaultValue) <= snapRange
                                if (inSnapZone && !wasSnapped) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                wasSnapped = inSnapZone

                                val newValue = if (inSnapZone) defaultValue else rawValue
                                // Skip redundant commits while parked on the detent.
                                if (newValue != dragValue) {
                                    dragValue = newValue
                                    latestOnValueChange(newValue)
                                }
                            }
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                }
                .pointerInput(minValue, maxValue, defaultValue) {
                    detectTapGestures(
                        onDoubleTap = {
                            rawValue = defaultValue
                            dragValue = defaultValue
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnValueChange(defaultValue)
                        }
                    )
                }
        ) {
            val stroke = 5.dp.toPx()
            val inset = stroke
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = ARC_START_DEG,
                sweepAngle = ARC_SWEEP_DEG,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            val fraction = (((if (isDragging) dragValue else value) - minValue) /
                    (maxValue - minValue)).coerceIn(0f, 1f)

            drawArc(
                color = accent,
                startAngle = ARC_START_DEG,
                sweepAngle = ARC_SWEEP_DEG * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            val angleRad = Math.toRadians((ARC_START_DEG + ARC_SWEEP_DEG * fraction).toDouble())
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
        }
    }
}

/**
 * Signed pointer rotation (degrees) between two consecutive touch positions,
 * normalized to ±180° so crossing the arc gap stays continuous. Returns null
 * while either position sits inside the dead zone.
 */
private fun pointerRotationDelta(
    center: Offset,
    from: Offset,
    to: Offset,
    deadZoneRadius: Float
): Float? {
    if ((from - center).getDistance() < deadZoneRadius) return null
    if ((to - center).getDistance() < deadZoneRadius) return null
    var normalized = angleDeg(to - center) - angleDeg(from - center) + 180f
    normalized %= 360f
    if (normalized < 0f) normalized += 360f
    return normalized - 180f
}

/** Angle of [offset] in degrees, Canvas convention (0° right, +y down). */
private fun angleDeg(offset: Offset): Float =
    Math.toDegrees(atan2(offset.y, offset.x).toDouble()).toFloat()
