package com.foamplayer.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.graphicsLayer
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

/** Extra canvas space below a ≤180° arc so the stroke's round caps are not clipped. */
private val HalfArcBottomMargin = 6.dp

/**
 * Rotary knob: an arc with an accent fill and a needle at the current angle.
 * The needle tracks the finger's rotation around the center 1:1 (wrap-around
 * included) and keeps working while the finger drifts outside the control.
 * Touches near the center are ignored. Double-tap resets to [defaultValue].
 *
 * The arc is centered on the vertical axis: [arcSweepDegrees] = 270 gives the
 * classic knob starting at 135° (bottom-left); ≤ 180 renders as a top
 * half-circle in a half-height canvas whose arc center sits on the bottom edge
 * ([HalfArcBottomMargin] keeps the round caps unclipped). In that half-circle
 * layout [centerContent] is bottom-aligned, so its center sits on the chord —
 * the same level as the arc's start/end points.
 *
 * Acts as a detent around [defaultValue]: within [snapFraction] of the range
 * the knob sits exactly on the default, with a haptic tick on entering the
 * zone. The raw finger value is kept separate from the displayed one so the
 * detent never desyncs the needle from the finger.
 *
 * Every rotation step commits through [onValueChange] so the knob and DSP
 * update in realtime while dragging (persistence is debounced engine-side).
 * [sensitivity] scales the rotation-to-value mapping: 1f means a full
 * [arcSweepDegrees] sweep of finger rotation covers the full range.
 *
 * [label] (optional) sits above the arc, swapped for the live value while
 * dragging; [centerContent] renders fixed content at the arc's center (e.g. an
 * always-visible readout). [enabled] only dims the control — gestures stay
 * active, so dragging a disabled knob re-engages it through [onValueChange].
 */
@Composable
fun KnobControl(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String?,
    valueText: (Float) -> String,
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    minValue: Float = -1f,
    maxValue: Float = 1f,
    defaultValue: Float = 0f,
    sensitivity: Float = 1f,
    /** Fraction of the range around [defaultValue] where the knob snaps to it. 0f disables snapping. */
    snapFraction: Float = 0.04f,
    /** Total arc sweep in degrees, centered on the vertical axis; ≤ 180 draws a top half-circle. */
    arcSweepDegrees: Float = 270f,
    /** Draw the needle line at the current angle (the half-circle gauge look omits it). */
    showNeedle: Boolean = true,
    /** Dim the control when false; gestures stay active so a drag re-engages it. */
    enabled: Boolean = true,
    /** Fixed content drawn at the arc's center (e.g. an always-visible value readout). */
    centerContent: (@Composable () -> Unit)? = null
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

    // Arc geometry: the start angle keeps the sweep centered on the vertical
    // axis (270° → 135°, 180° → 180°). A ≤180° arc fits the top half of the
    // square, so the canvas is cropped to it.
    val arcStartDeg = 90f + (360f - arcSweepDegrees) / 2f
    val halfArc = arcSweepDegrees <= 180f
    val canvasHeight = if (halfArc) size / 2 + HalfArcBottomMargin else size

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        if (label != null) {
            BasicText(
                text = if (isDragging) valueText(dragValue) else label,
                style = FoamTheme.typography.caption.copy(
                    color = if (isDragging) accent else FoamTheme.colors.textSecondary,
                    fontWeight = if (isDragging) FontWeight.Bold else FontWeight.Normal
                )
            )
        }

        Box(
            modifier = Modifier
                .padding(top = if (label != null) 4.dp else 0.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.35f },
            // Half-circle gauge: the readout is bottom-aligned so its center
            // sits on the chord, level with the arc's start/end points.
            contentAlignment = if (halfArc) Alignment.BottomCenter else Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(width = size, height = canvasHeight)
                    .pointerInput(
                        minValue, maxValue, sensitivity, defaultValue, snapFraction,
                        arcSweepDegrees, halfArc
                    ) {
                        // Arc center: canvas middle for the full knob, bottom edge
                        // (minus the cap margin) for the half-circle.
                        val centerY = if (halfArc) {
                            this.size.height - HalfArcBottomMargin.toPx()
                        } else {
                            this.size.height / 2f
                        }
                        val center = Offset(this.size.width / 2f, centerY)
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
                                    val delta = rotation / arcSweepDegrees * range * sensitivity
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
                val arcCenterY = if (halfArc) {
                    this.size.height - HalfArcBottomMargin.toPx()
                } else {
                    this.size.height / 2f
                }
                val arcDiameter = minOf(this.size.width, this.size.height * 2f) - inset * 2
                val arcSize = Size(arcDiameter, arcDiameter)
                val topLeft = Offset(inset, arcCenterY - arcDiameter / 2f)

                drawArc(
                    color = trackColor,
                    startAngle = arcStartDeg,
                    sweepAngle = arcSweepDegrees,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                val fraction = (((if (isDragging) dragValue else value) - minValue) /
                        (maxValue - minValue)).coerceIn(0f, 1f)

                drawArc(
                    color = accent,
                    startAngle = arcStartDeg,
                    sweepAngle = arcSweepDegrees * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                if (showNeedle) {
                    val angleRad = Math.toRadians((arcStartDeg + arcSweepDegrees * fraction).toDouble())
                    val radius = arcDiameter / 2f
                    val center = Offset(this.size.width / 2, arcCenterY)
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

            centerContent?.invoke()
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
