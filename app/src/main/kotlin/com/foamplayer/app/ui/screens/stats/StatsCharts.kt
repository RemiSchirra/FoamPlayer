package com.foamplayer.app.ui.screens.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foamplayer.app.ui.theme.FoamTheme

/** Maximum number of x-axis labels drawn under a chart, whatever the slot count. */
private const val MaxVisibleLabels = 6

/** Height of the label strip under a chart's bars. */
private val LabelStripHeight = 14.dp

/** Vertical gap between a chart's bars and its label strip. */
private val LabelGap = 5.dp

/** One donut slice: an artist's share of the period's listening time. */
data class DonutSlice(
    val label: String,
    val fraction: Float,
    val color: Color
)

/**
 * Flat bar chart of one value per [StatsTimeBucket] (listening time per
 * day/week/month). Bars grow in on data change; the peak bucket is drawn at
 * full accent while the others stay dimmed. Empty slots render as hairlines so
 * the timeline keeps its rhythm.
 */
@Composable
fun StatsActivityChart(
    buckets: List<StatsTimeBucket>,
    modifier: Modifier = Modifier
) {
    val progress = remember(buckets) { Animatable(0f) }
    LaunchedEffect(buckets) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }

    val accent = FoamTheme.accentColor
    val labelColor = FoamTheme.colors.textSecondary
    val baselineColor = FoamTheme.colors.borderOverlay
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor, textAlign = TextAlign.Center)

    // Text measurement is expensive; the labels are static across the growth
    // animation, so measure once per (bucket, style, density) change outside
    // the draw block instead of re-measuring every frame of the 600ms tween.
    val labelLayouts = remember(buckets, labelStyle, textMeasurer) {
        val stride = ((buckets.size + MaxVisibleLabels - 1) / MaxVisibleLabels).coerceAtLeast(1)
        buckets.indices
            .filter { it % stride == 0 || it == buckets.lastIndex }
            .associateWith { textMeasurer.measure(buckets[it].label, labelStyle) }
    }

    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        if (buckets.isEmpty()) return@Canvas

        val labelStripPx = LabelStripHeight.toPx()
        val labelGapPx = LabelGap.toPx()
        val chartHeight = size.height - labelStripPx - labelGapPx
        val slotWidth = size.width / buckets.size
        val maxValue = buckets.maxOf { it.totalDurationMs }.coerceAtLeast(1L)
        val labelStride = ((buckets.size + MaxVisibleLabels - 1) / MaxVisibleLabels).coerceAtLeast(1)
        val peakIndex = buckets.indices.maxBy { buckets[it].totalDurationMs }

        drawLine(
            color = baselineColor,
            start = Offset(0f, chartHeight),
            end = Offset(size.width, chartHeight),
            strokeWidth = 1.dp.toPx()
        )

        buckets.forEachIndexed { index, bucket ->
            val barWidth = (slotWidth * 0.62f).coerceAtMost(24.dp.toPx())
            val x = slotWidth * index + (slotWidth - barWidth) / 2

            if (bucket.totalDurationMs > 0) {
                val fraction = bucket.totalDurationMs.toFloat() / maxValue * progress.value
                val barHeight = (chartHeight * fraction).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = if (index == peakIndex) accent else accent.copy(alpha = 0.55f),
                    topLeft = Offset(x, chartHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.5f.dp.toPx())
                )
            } else {
                drawRoundRect(
                    color = accent.copy(alpha = 0.25f),
                    topLeft = Offset(x, chartHeight - 2.dp.toPx()),
                    size = Size(barWidth, 1.5f.dp.toPx()),
                    cornerRadius = CornerRadius(1f.dp.toPx())
                )
            }

            if (index % labelStride == 0 || index == buckets.lastIndex) {
                val measured = labelLayouts[index] ?: return@forEachIndexed
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = (slotWidth * index + slotWidth / 2 - measured.size.width / 2)
                            .coerceIn(0f, size.width - measured.size.width.toFloat()),
                        y = chartHeight + labelGapPx
                    )
                )
            }
        }
    }
}

/**
 * Ring of the top slices' share (top artists' listening time). The caller
 * draws the center content on top.
 */
@Composable
fun StatsDonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 18.dp
) {
    val progress = remember(slices) { Animatable(0f) }
    LaunchedEffect(slices) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }

    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val diameter = minOf(size.width, size.height) - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arcSize = Size(diameter, diameter)
        val gapSweep = if (slices.size > 1) 4f else 0f

        var startAngle = -90f
        slices.forEach { slice ->
            val sweep = 360f * slice.fraction * progress.value
            val drawn = (sweep - gapSweep).coerceAtLeast(0.5f)
            drawArc(
                color = slice.color,
                startAngle = startAngle + gapSweep / 2,
                sweepAngle = drawn,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            startAngle += sweep
        }
    }
}

/**
 * The listening clock: play count per local hour (0..23), peak hour at full
 * accent, all other hours dimmed. Always 24 slots so the day's shape reads.
 */
@Composable
fun StatsHourChart(
    hours: List<Int>,
    modifier: Modifier = Modifier
) {
    val progress = remember(hours) { Animatable(0f) }
    LaunchedEffect(hours) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }

    val accent = FoamTheme.accentColor
    val labelColor = FoamTheme.colors.textSecondary
    val baselineColor = FoamTheme.colors.borderOverlay
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor, textAlign = TextAlign.Center)

    // Static hour labels measured once outside the draw block (see
    // StatsActivityChart) instead of on every animation frame.
    val hourLabelLayouts = remember(labelStyle, textMeasurer) {
        listOf(0, 6, 12, 18).associateWith { textMeasurer.measure("$it", labelStyle) }
    }

    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        if (hours.size != 24) return@Canvas

        val labelStripPx = LabelStripHeight.toPx()
        val labelGapPx = LabelGap.toPx()
        val chartHeight = size.height - labelStripPx - labelGapPx
        val slotWidth = size.width / hours.size
        val maxValue = hours.max().coerceAtLeast(1)
        val peakIndex = hours.indices.maxBy { hours[it] }

        drawLine(
            color = baselineColor,
            start = Offset(0f, chartHeight),
            end = Offset(size.width, chartHeight),
            strokeWidth = 1.dp.toPx()
        )

        hours.forEachIndexed { hour, plays ->
            val barWidth = (slotWidth * 0.6f).coerceAtMost(14.dp.toPx())
            val x = slotWidth * hour + (slotWidth - barWidth) / 2
            if (plays > 0) {
                val fraction = plays.toFloat() / maxValue * progress.value
                val barHeight = (chartHeight * fraction).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = if (hour == peakIndex) accent else accent.copy(alpha = 0.55f),
                    topLeft = Offset(x, chartHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2f.dp.toPx())
                )
            } else {
                drawRoundRect(
                    color = accent.copy(alpha = 0.25f),
                    topLeft = Offset(x, chartHeight - 2.dp.toPx()),
                    size = Size(barWidth, 1.5f.dp.toPx()),
                    cornerRadius = CornerRadius(1f.dp.toPx())
                )
            }
        }

        listOf(0, 6, 12, 18).forEach { hour ->
            val measured = hourLabelLayouts[hour] ?: return@forEach
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = (slotWidth * hour + slotWidth / 2 - measured.size.width / 2)
                        .coerceIn(0f, size.width - measured.size.width.toFloat()),
                    y = chartHeight + labelGapPx
                )
            )
        }
    }
}

/** Builds a multi-slice palette anchored on the app's accent color. */
internal fun donutPalette(accent: Color, count: Int): List<Color> {
    val base = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), base)
    return List(count) { index ->
        val hue = (base[0] + index * 38f) % 360f
        val saturation = (base[1] - index * 0.07f).coerceIn(0.45f, 1f)
        val value = (base[2] + index * 0.06f).coerceIn(0.62f, 1f)
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    }
}
