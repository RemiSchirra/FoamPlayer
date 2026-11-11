package com.foamplayer.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurface
import kotlin.math.round

/** Simple surface card with rounded corners. */
@Composable
fun FoamGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .foamSurface()
            .padding(contentPadding)
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
    activeColor: Color = FoamTheme.accentColor,
    inactiveColor: Color = FoamTheme.colors.surfaceVariant,
    growFactor: Float = 1.8f
) = FoamSlider(
    value = { value },
    onValueChange = onValueChange,
    modifier = modifier,
    steps = steps,
    trackHeight = trackHeight,
    activeColor = activeColor,
    inactiveColor = inactiveColor,
    growFactor = growFactor
)

/**
 * Lambda-based overload for per-frame values (seek-bar progress). The value is
 * read during the draw phase instead of in composition, so position ticks and
 * drags only invalidate the track's drawing — nothing recomposes.
 */
@Composable
fun FoamSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int? = null,
    trackHeight: Dp = 4.dp,
    activeColor: Color = FoamTheme.accentColor,
    inactiveColor: Color = FoamTheme.tintedSurfaceVariant,
    growFactor: Float = 1.8f
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    var isDragging by remember { mutableStateOf(false) }

    val animatedTrackHeight by animateDpAsState(
        targetValue = if (isDragging) trackHeight * growFactor else trackHeight,
        label = "trackHeightAnimation"
    )

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

                    isDragging = true

                    val width = size.width
                    if (width > 0) {
                        currentOnValueChange(snap(down.position.x / width))
                    }
                    down.consume()

                    try {
                        drag(down.id) { change ->
                            if (width > 0) {
                                currentOnValueChange(snap(change.position.x / width))
                            }
                            if (change.pressed) change.consume()
                        }
                    } finally {
                        isDragging = false
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedTrackHeight)
                .clip(CircleShape)
                .background(inactiveColor)
        )

        // Progress: the fraction is read inside drawBehind, so per-frame values
        // redraw the track without recomposing this composable. The rounded
        // rect matches the CircleShape clip: radius = half the height.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedTrackHeight)
                .drawBehind {
                    val fraction = value().coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        drawRoundRect(
                            color = activeColor,
                            size = Size(size.width * fraction, size.height),
                            cornerRadius = CornerRadius(size.height / 2f)
                        )
                    }
                }
        )
    }
}

/**
 * Vertical counterpart of [FoamSlider]: the same slim, thumb-less track that
 * widens while dragged, rotated to fill the height of its slot. The accent fill
 * always grows from the bottom. [onValueChange] reports the value continuously
 * for a live preview; [onCommit], when provided, fires once on release.
 */
@Composable
fun VerticalFoamSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onCommit: ((Float) -> Unit)? = null,
    trackWidth: Dp = 4.dp,
    activeColor: Color = FoamTheme.accentColor,
    inactiveColor: Color = FoamTheme.tintedSurfaceVariant,
    growFactor: Float = 1.8f
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnCommit by rememberUpdatedState(onCommit)

    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember(value) { mutableFloatStateOf(value.coerceIn(0f, 1f)) }
    val displayed = if (isDragging) dragValue else value.coerceIn(0f, 1f)

    val animatedTrackWidth by animateDpAsState(
        targetValue = if (isDragging) trackWidth * growFactor else trackWidth,
        label = "verticalTrackWidthAnimation"
    )

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    val height = size.height
                    if (height > 0) {
                        dragValue = 1f - (down.position.y / height).coerceIn(0f, 1f)
                        currentOnValueChange(dragValue)
                    }
                    down.consume()
                    try {
                        drag(down.id) { change ->
                            if (height > 0) {
                                dragValue = 1f - (change.position.y / height).coerceIn(0f, 1f)
                                currentOnValueChange(dragValue)
                            }
                            if (change.pressed) change.consume()
                        }
                    } finally {
                        isDragging = false
                        currentOnCommit?.invoke(dragValue)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(animatedTrackWidth)
                .clip(CircleShape)
                .background(inactiveColor)
        )

        // Progress is drawn over the track, always from the bottom. The rounded
        // rect matches the CircleShape clip.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(animatedTrackWidth)
                .drawBehind {
                    val start = (1f - displayed) * size.height
                    val fillHeight = size.height - start
                    if (fillHeight > 1f) {
                        drawRoundRect(
                            color = activeColor,
                            topLeft = Offset(0f, start),
                            size = Size(size.width, fillHeight),
                            cornerRadius = CornerRadius(size.width / 2f)
                        )
                    }
                }
        )
    }
}
