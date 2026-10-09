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
 * Poweramp-style rotary control: a 270° arc from bottom-left to bottom-right, an
 * accent arc for the filled portion and a needle at the current angle.
 *
 * The knob follows the finger's rotation around its center: each pointer step
 * contributes the signed angle swept since the previous event, so the needle
 * tracks the finger 1:1 (wrap-around across the arc gap included) and keeps
 * working while the finger drifts outside the control. Touches inside a small
 * dead zone around the center are ignored — the angle there is pure noise and a
 * covered center would make the needle jump erratically. A double tap snaps
 * back to [defaultValue].
 *
 * The knob also acts as a detent around [defaultValue]: while the finger-tracked
 * value is within [snapFraction] of the range from the default, the knob sits
 * exactly on [defaultValue] and a haptic tick is played when entering that zone.
 * The finger-tracked value is kept separately from the displayed one, so the
 * detent never desyncs the needle from the finger once it leaves the zone.
 *
 * Every rotation step is committed straight through [onValueChange] so the knob
 * and the DSP engine update in realtime while dragging; persistence is debounced
 * engine-side, so a drag cannot flood DataStore. [sensitivity] scales the
 * rotation-to-value mapping: 1f means 270° of finger rotation sweep the full
 * [minValue]..[maxValue] range.
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
    // Manual drag tracking: detectDragGestures exposes no interaction source, so
    // the dragged state flips on gesture start/end to drive the label readout.
    var isDragging by remember { mutableStateOf(false) }

    // Value while dragging. Re-seeded from the hoisted value on every gesture
    // start, so external changes (preset applied, knob reset elsewhere...) are
    // always picked up; shown only while dragging, then the hoisted value —
    // kept current by the realtime commits below — takes over again.
    var dragValue by remember { mutableFloatStateOf(value) }

    // Finger-tracked value, never snapped. dragValue (displayed + committed) is
    // derived from it, so the default-value detent doesn't desync the needle
    // from the finger's rotation.
    var rawValue by remember { mutableFloatStateOf(value) }

    val haptic = LocalHapticFeedback.current

    // pointerInput survives recomposition, so the gesture lambdas must read the
    // value and callback through these to never act on stale captures.
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
                    // Touches closer than this to the center are ignored: the
                    // angle around the center is undefined there and a covered
                    // center would make the needle jump erratically.
                    val deadZoneRadius = minOf(this.size.width, this.size.height) * 0.2f
                    val range = maxValue - minValue
                    val snapRange = range * snapFraction
                    // Whether the finger-tracked value was inside the snap zone on
                    // the previous step, so the haptic tick fires only on entry.
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

            // Needle at the current angle.
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
 * Signed rotation (degrees) of the pointer around [center] between two
 * consecutive touch positions, normalized to ±180° so crossing the arc gap at
 * the bottom stays continuous. Returns null while either position sits inside
 * the dead zone around the center.
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

/** Angle of [offset] in degrees, in Canvas convention (0° right, +y down). */
private fun angleDeg(offset: Offset): Float =
    Math.toDegrees(atan2(offset.y, offset.x).toDouble()).toFloat()