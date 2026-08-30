package com.foamplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.round

@Composable
fun FoamGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(FoamTheme.colors.surface)
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
fun FoamSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int? = null,
    trackHeight: Dp = 4.dp,
    activeColor: Color = FoamTheme.colors.primaryAccent,
    inactiveColor: Color = FoamTheme.colors.surfaceVariant
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    fun snap(v: Float): Float {
        if (steps == null || steps <= 0) return v.coerceIn(0f, 1f)
        return (round(v * steps) / steps).coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(steps) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width
                    if (width > 0) {
                        currentOnValueChange(snap(down.position.x / width))
                    }
                    down.consume()
                    
                    drag(down.id) { change ->
                        if (width > 0) {
                            currentOnValueChange(snap(change.position.x / width))
                        }
                        if (change.pressed) change.consume()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // Track Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(CircleShape)
                .background(inactiveColor)
        )

        // Progress
        Box(
            modifier = Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .height(trackHeight)
                .clip(CircleShape)
                .background(activeColor)
        )
    }
}