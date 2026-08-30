package com.foamplayer.app.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.round

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun FoamSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueDisplay: String,
    modifier: Modifier = Modifier,
    steps: Int? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    description: String? = null,
    minMaxDisplay: Pair<String, String>? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(text = title, style = FoamTheme.typography.titleMedium)
                description?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    BasicText(text = it, style = FoamTheme.typography.body)
                }
            }
            BasicText(
                text = valueDisplay,
                style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.colors.primaryAccent)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Wrap the Slider in a BoxWithConstraints to calculate absolute dimensions
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            // Material sliders have a hardcoded 16.dp horizontal padding for the thumb center
            val paddingPx = with(density) { 16.dp.toPx() }
            val widthPx = constraints.maxWidth.toFloat()
            val trackWidthPx = widthPx - (2 * paddingPx)

            // Calculate the absolute value based on screen coordinates
            fun updateValueFromTouch(touchX: Float) {
                if (trackWidthPx <= 0) return

                val clampedX = touchX.coerceIn(paddingPx, widthPx - paddingPx)
                val fraction = (clampedX - paddingPx) / trackWidthPx

                val finalValue = if (steps != null && steps > 0) {
                    val intervals = steps + 1
                    val snappedFraction = round(fraction * intervals) / intervals.toFloat()
                    valueRange.start + (snappedFraction * (valueRange.endInclusive - valueRange.start))
                } else {
                    valueRange.start + (fraction * (valueRange.endInclusive - valueRange.start))
                }
                onValueChange(finalValue)
            }

            // The original Slider now acts purely as a visual renderer
            Slider(
                value = value,
                onValueChange = { /* Handled by our interceptor Box */ },
                steps = steps ?: 0,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = FoamTheme.colors.primaryAccent,
                    activeTrackColor = FoamTheme.colors.primaryAccent,
                    inactiveTrackColor = FoamTheme.colors.surfaceVariant,
                    activeTickColor = FoamTheme.colors.surfaceVariant.copy(alpha = 0.4f),
                    inactiveTickColor = FoamTheme.colors.primaryAccent.copy(alpha = 0.4f)
                )
            )

            // Transparent touch interceptor overlay
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            updateValueFromTouch(down.position.x)
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                updateValueFromTouch(event.changes.first().position.x)
                            } while (event.changes.any { it.pressed })
                        }
                    }
            )
        }

        minMaxDisplay?.let { (min, max) ->
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BasicText(text = min, style = FoamTheme.typography.caption)
                BasicText(text = max, style = FoamTheme.typography.caption)
            }
        }
    }
}