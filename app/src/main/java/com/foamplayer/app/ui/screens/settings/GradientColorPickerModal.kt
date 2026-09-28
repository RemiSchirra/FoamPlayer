package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.FoamBottomModal
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import java.util.Locale

/**
 * Default palette offered in the color picker as one-tap shortcuts: 12
 * vibrant, evenly spread hues. Also used to color freshly added points so
 * they never start identical to an existing one (see [GradientEditor]).
 */
internal val DefaultStopPalette = listOf(
    0xFFFF3B30.toInt(), // red
    0xFFFF9500.toInt(), // orange
    0xFFFFCC00.toInt(), // yellow
    0xFFB8E62E.toInt(), // lime
    0xFF34C759.toInt(), // green
    0xFF00C7BE.toInt(), // teal
    0xFF32ADE6.toInt(), // sky
    0xFF007AFF.toInt(), // blue
    0xFF5856D6.toInt(), // indigo
    0xFFAF52DE.toInt(), // purple
    0xFFFF2D55.toInt(), // magenta
    0xFFFF6482.toInt()  // pink
)

/** Spectrum behind the hue bar: the six primary/secondary hues, red-wrapped. */
private val HueBrush = Brush.horizontalGradient(
    listOf(
        Color(0xFFFF0000),
        Color(0xFFFFFF00),
        Color(0xFF00FF00),
        Color(0xFF00FFFF),
        Color(0xFF0000FF),
        Color(0xFFFF00FF),
        Color(0xFFFF0000)
    )
)

/**
 * "Pick a color" sheet for one gradient point, hosted in a [FoamBottomModal]:
 * a true HSV picker -- a 2D saturation/brightness field and a hue bar, both
 * grab-anywhere draggable -- plus the default palette as shortcuts and a live
 * swatch + hex readout (display only; there is deliberately no text entry).
 *
 * The sheet edits a draft: nothing reaches the stop until the user taps OK,
 * so a Cancel (button, scrim or drag-down) discards the edits. The draft is
 * keyed on [session], which the host bumps on every open, so the picker always
 * starts from the stop's committed color. The host keeps the last edited index
 * after closing, which lets the sheet play its exit animation against real
 * values instead of placeholder ones.
 *
 * No alpha control: the gradient renderer treats every stop as opaque.
 */
@Composable
internal fun GradientColorPickerModal(
    visible: Boolean,
    session: Int,
    label: String,
    initialArgb: Int,
    onConfirm: (argb: Int) -> Unit,
    onDismiss: () -> Unit
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        ColorPickerContent(
            session = session,
            label = label,
            initialArgb = initialArgb,
            onConfirm = onConfirm,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun ColorPickerContent(
    session: Int,
    label: String,
    initialArgb: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Draft HSV for the point being edited. The dialog content is disposed once
    // the sheet's exit animation finishes, so this state re-seeds on every open
    // anyway; keying on [session] makes even a quick close-and-reopen re-seed
    // (a Cancel truly discards) and keeps the first frame already correct.
    val seed = remember(session) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb, it) }
    }
    var hue by remember(session) { mutableFloatStateOf(seed[0]) }
    var saturation by remember(session) { mutableFloatStateOf(seed[1]) }
    var value by remember(session) { mutableFloatStateOf(seed[2]) }

    val draftArgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))

    Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
        // Title + live readout: draft swatch and its hex, display only.
        Row(
            modifier = Modifier.padding(
                horizontal = FoamDimens.paddingSmall,
                vertical = FoamDimens.spacingTiny
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = label,
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(draftArgb))
                    .border(1.dp, FoamTheme.colors.borderOverlay, CircleShape)
            )
            Spacer(modifier = Modifier.width(FoamDimens.paddingSmall))
            BasicText(
                text = String.format(Locale.US, "#%06X", draftArgb and 0xFFFFFF),
                style = FoamTheme.typography.monospace.copy(color = FoamTheme.colors.textSecondary)
            )
        }

        SaturationValueField(
            hue = hue,
            saturation = saturation,
            value = value,
            onSaturationValue = { s, v ->
                saturation = s
                value = v
            }
        )

        HueBar(hue = hue, onHueChange = { hue = it })

        // One-tap palette shortcuts: they seed the draft -- the field's rings
        // track along -- rather than committing, keeping OK the single commit.
        DefaultStopPalette.chunked(6).forEach { rowColors ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                rowColors.forEach { argb ->
                    val isSelected = argb == draftArgb
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                            .background(Color(argb))
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = FoamTheme.colors.textPrimary,
                                shape = RoundedCornerShape(FoamDimens.buttonRounding)
                            )
                            .clickable {
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(argb, hsv)
                                hue = hsv[0]
                                saturation = hsv[1]
                                value = hsv[2]
                            }
                    )
                }
            }
        }

        PickerButton(
            label = stringResource(R.string.ok),
            filled = true,
            onClick = { onConfirm(draftArgb) }
        )
        PickerButton(
            label = stringResource(R.string.cancel),
            filled = false,
            onClick = onDismiss
        )
    }
}

/**
 * The 2D saturation/brightness field: white-to-pure-hue horizontally, bright
 * to black vertically, with a ring at the current color's position. The field
 * is drawn (two cheap gradient passes over one rect) rather than composed, so
 * drag frames only redraw and the hue-derived brush is reallocated per hue.
 */
@Composable
private fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValue: (saturation: Float, value: Float) -> Unit
) {
    val saturationBrush = remember(hue) {
        Brush.horizontalGradient(
            listOf(Color.White, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))))
        )
    }
    val valueBrush = remember {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .drawBehind {
                drawRect(saturationBrush)
                drawRect(valueBrush)
                val center = Offset(saturation * size.width, (1f - value) * size.height)
                val radius = 11.dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.4f), radius = radius, center = center, style = Stroke(4.dp.toPx()))
                drawCircle(Color.White, radius = radius, center = center, style = Stroke(2.dp.toPx()))
            }
            // Border after the field drawing so the outline reads over it.
            .border(1.dp, FoamTheme.colors.borderOverlay, RoundedCornerShape(FoamDimens.itemRounding))
            .normalizedDrag { x, y -> onSaturationValue(x, 1f - y) }
    )
}

/** Horizontal hue strip with a draggable ring; drag X maps to 0..360 degrees. */
@Composable
private fun HueBar(
    hue: Float,
    onHueChange: (Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(CircleShape)
            .drawBehind {
                drawRect(HueBrush)
                val center = Offset((hue / 360f) * size.width, size.height / 2f)
                val radius = 9.dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.4f), radius = radius, center = center, style = Stroke(4.dp.toPx()))
                drawCircle(Color.White, radius = radius, center = center, style = Stroke(2.dp.toPx()))
            }
            .normalizedDrag { x, _ -> onHueChange(x * 360f) }
    )
}

/**
 * Drag handler reporting every touch position as normalized 0..1 coordinates,
 * starting with the initial down (grab-anywhere, snap-to-finger -- the same
 * behavior as the gradient preview's dots). Consuming the changes also keeps
 * the hosting sheet's scroll / drag-to-dismiss from fighting the picker.
 * [onDrag] is read through [rememberUpdatedState]: the pointerInput below
 * outlives many recompositions and must never call a stale lambda.
 */
@Composable
private fun Modifier.normalizedDrag(onDrag: (x: Float, y: Float) -> Unit): Modifier {
    val latestOnDrag by rememberUpdatedState(onDrag)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            latestOnDrag(
                (down.position.x / size.width).coerceIn(0f, 1f),
                (down.position.y / size.height).coerceIn(0f, 1f)
            )
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: event.changes.firstOrNull { it.pressed }
                    ?: break
                if (!change.pressed) break
                change.consume()
                latestOnDrag(
                    (change.position.x / size.width).coerceIn(0f, 1f),
                    (change.position.y / size.height).coerceIn(0f, 1f)
                )
            }
        }
    }
}

/** Filled (accent) or flat full-width button for the picker's OK / Cancel. */
@Composable
private fun PickerButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(if (filled) FoamTheme.accentColor else FoamTheme.colors.surfaceVariant)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(vertical = FoamDimens.spacingStandard),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.body.copy(
                color = if (filled) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        )
    }
}
