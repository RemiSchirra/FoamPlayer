package com.foamplayer.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.LocalContentColor
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt

/** Settings row with a title, live value readout and a Material slider (see [FoamSliderStateItem] for the hoisted variant). */
@Composable
fun FoamSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
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
            .padding(vertical = FoamDimens.spacingMedium, horizontal = FoamDimens.paddingMedium)
    ) {
        // Header : title, description, and current value
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
                style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.accentColor)
            )
        }

        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

        CompositionLocalProvider(LocalContentColor provides FoamTheme.colors.textPrimary) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                steps = steps ?: 0,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = FoamTheme.accentColor,
                    activeTrackColor = FoamTheme.accentColor,
                    inactiveTrackColor = FoamTheme.colors.textSecondary.copy(alpha = 0.5f),
                    activeTickColor = FoamTheme.colors.background.copy(alpha = 0.5f),
                    inactiveTickColor = FoamTheme.colors.textSecondary.copy(alpha = 0.5f)
                )
            )
        }

        // Min max footer
        minMaxDisplay?.let { (min, max) ->
            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
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

/**
 * Self-contained variant of [FoamSliderItem] for the commit-on-release settings
 * pattern: the drag value lives in this row's own composition scope, so dragging
 * recomposes only this row instead of the whole screen.
 *
 * [onValueChange] observes every drag frame, BEFORE the commit-on-release write:
 * callers that render a live preview of the value (e.g. the background settings
 * screen feeding the app-wide backdrop) use it to update the preview without
 * round-tripping through DataStore on each pointer event.
 */
@Composable
fun FoamSliderStateItem(
    title: String,
    initialValue: Float,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    description: String? = null,
    minMaxDisplay: Pair<String, String>? = null,
    formatValue: (Float) -> String = { it.roundToInt().toString() },
    onValueChange: ((Float) -> Unit)? = null
) {
    var localValue by remember { mutableFloatStateOf(initialValue) }
    // Re-sync when the underlying setting changes from elsewhere (e.g. a
    // DataStore write landing after the commit).
    LaunchedEffect(initialValue) { localValue = initialValue }

    FoamSliderItem(
        title = title,
        value = localValue,
        onValueChange = {
            localValue = it
            onValueChange?.invoke(it)
        },
        onValueChangeFinished = { onValueChangeFinished(localValue) },
        valueDisplay = formatValue(localValue),
        modifier = modifier,
        steps = steps,
        valueRange = valueRange,
        description = description,
        minMaxDisplay = minMaxDisplay
    )
}
