package com.foamplayer.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.foamplayer.app.R
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.BackgroundMode
import com.foamplayer.app.data.settings.GradientStop
import com.foamplayer.app.ui.components.FoamSliderStateItem
import com.foamplayer.app.ui.components.buildGradientImage
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun BackgroundSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val mode = settings.backgroundMode

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setCustomBackgroundUri(uri.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.background),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalBottomBarClearance.current),
        ) {
            BasicText(
                text = stringResource(R.string.dynamic_effects),
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FoamDimens.paddingMedium)
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                    .background(FoamTheme.colors.surface)
            ) {
                Column {
                    BackgroundMode.entries.forEachIndexed { index, modeOption ->
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .background(FoamTheme.colors.borderOverlay)
                            )
                        }
                        ModeOptionRow(
                            mode = modeOption,
                            isSelected = modeOption == mode,
                            onSelect = { viewModel.setBackgroundMode(modeOption) }
                        )
                    }
                }
            }

            when (mode) {
                BackgroundMode.CUSTOM -> {
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    CustomImageSection(
                        imageUri = settings.customBackgroundUri,
                        onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                        onClearImage = { viewModel.setCustomBackgroundUri(null) }
                    )
                }
                BackgroundMode.GRADIENT -> {
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    GradientEditor(
                        stops = settings.gradientStops,
                        onChange = viewModel::setGradientStops
                    )
                }
                BackgroundMode.DYNAMIC -> Unit
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))

            BasicText(
                text = "Appearance",
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FoamDimens.paddingMedium)
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                    .background(FoamTheme.colors.surface)
            ) {
                Column {
                    if (mode != BackgroundMode.GRADIENT) {
                        FoamSliderStateItem(
                            title = stringResource(R.string.background_blur),
                            initialValue = settings.backgroundBlur.toFloat(),
                            onValueChangeFinished = {
                                viewModel.setBackgroundBlur(it.roundToInt())
                            },
                            steps = 9,
                            valueRange = 0f..100f,
                            formatValue = { "${it.roundToInt()}%" },
                            minMaxDisplay = "None" to "Max",
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FoamDimens.separatorHeight)
                                .background(FoamTheme.colors.borderOverlay)
                        )
                    }
                    FoamSliderStateItem(
                        title = "App Color Intensity",
                        initialValue = settings.appBackgroundIntensity.toFloat(),
                        onValueChangeFinished = {
                            viewModel.setAppBackgroundIntensity(it.roundToInt())
                        },
                        steps = 9,
                        valueRange = 0f..100f,
                        formatValue = { "${it.roundToInt()}%" },
                        minMaxDisplay = "Dim" to "Vivid",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FoamDimens.separatorHeight)
                            .background(FoamTheme.colors.borderOverlay)
                    )
                    FoamSliderStateItem(
                        title = "Player Color Intensity",
                        initialValue = settings.playerBackgroundIntensity.toFloat(),
                        onValueChangeFinished = {
                            viewModel.setPlayerBackgroundIntensity(it.roundToInt())
                        },
                        steps = 9,
                        valueRange = 0f..100f,
                        formatValue = { "${it.roundToInt()}%" },
                        minMaxDisplay = "Dim" to "Vivid",
                    )
                }
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
        }
    }
}

/** One selectable row of the mode list (radio-style, FoamPlayer look). */
@Composable
private fun ModeOptionRow(
    mode: BackgroundMode,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val title = stringResource(
        when (mode) {
            BackgroundMode.DYNAMIC -> R.string.dynamic_background
            BackgroundMode.CUSTOM -> R.string.background_mode_custom
            BackgroundMode.GRADIENT -> R.string.background_mode_gradient
        }
    )
    val description = stringResource(
        when (mode) {
            BackgroundMode.DYNAMIC -> R.string.dynamic_background_desc
            BackgroundMode.CUSTOM -> R.string.background_mode_custom_desc
            BackgroundMode.GRADIENT -> R.string.background_mode_gradient_desc
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onSelect
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 2.dp,
                    color = if (isSelected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
                    shape = CircleShape
                )
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(FoamTheme.accentColor)
                )
            }
        }

        Spacer(modifier = Modifier.size(FoamDimens.paddingMedium))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = title, style = FoamTheme.typography.titleMedium)
            BasicText(
                text = description,
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary)
            )
        }
    }
}

/** "Custom image" picker row: preview + pick/clear, mirroring the playlist cover row. */
@Composable
private fun CustomImageSection(
    imageUri: String?,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium)
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = FoamTheme.colors.textPrimary),
                    onClick = onPickImage
                )
                .padding(FoamDimens.paddingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                    .background(FoamTheme.colors.background),
                contentAlignment = Alignment.Center
            ) {
                if (imageUri != null) {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = FoamTheme.colors.textSecondary,
                        modifier = Modifier.size(FoamDimens.iconSmall)
                    )
                }
            }

            Spacer(modifier = Modifier.size(FoamDimens.paddingMedium))

            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = stringResource(R.string.background_custom_image),
                    style = FoamTheme.typography.titleMedium
                )
                BasicText(
                    text = imageUri?.substringAfterLast('/') ?: stringResource(R.string.background_custom_pick_hint),
                    style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (imageUri != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary),
                            onClick = onClearImage
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear_selection),
                        tint = FoamTheme.colors.textSecondary,
                        modifier = Modifier.size(FoamDimens.iconSmall)
                    )
                }
            }
        }
    }
}

/** Default palette offered in each point's expanded editor: 12 vibrant, evenly spread hues. */
private val DefaultStopPalette = listOf(
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

/**
 * Gradient editor: a vertical-format live preview on the left (a true 2D color
 * field with 2D-draggable color dots) and the point list on the right, with the
 * add control underneath it. The count stays within 1..4 points.
 *
 * Edits land in a local snapshot state first so drags update on every frame
 * (commit-on-release, like [FoamSliderStateItem]); the persisted setting is
 * written when an edit completes -- drag release, color pick, add or remove.
 * Persisting per drag frame would round-trip through DataStore on each pointer
 * event, which both lags the dots behind the finger and floods the disk.
 */
@Composable
private fun GradientEditor(
    stops: List<GradientStop>,
    onChange: (List<GradientStop>) -> Unit
) {
    var localStops by remember { mutableStateOf(stops) }
    // Re-sync when the persisted value changes from elsewhere (import, reset);
    // a drag never persists mid-flight, so this cannot fight an active gesture.
    LaunchedEffect(stops) { localStops = stops }
    var expandedStopIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Vertical-format 2D gradient preview with the draggable dots, left.
            GradientPreview(
                stops = localStops,
                onDrag = { index, x, y ->
                    localStops = localStops.mapIndexed { i, s ->
                        if (i == index) s.copy(offset = y, x = x) else s
                    }
                },
                onDragEnd = { onChange(localStops) },
                modifier = Modifier
                    .width(140.dp)
                    .height(260.dp)
            )

            Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))

            // The point list, right; tap a row to expand its color editor. The
            // add control sits underneath the list, inside the same card.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                    .background(FoamTheme.colors.surface)
            ) {
                localStops.forEachIndexed { index, stop ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FoamDimens.separatorHeight)
                                .background(FoamTheme.colors.borderOverlay)
                        )
                    }
                    StopRow(
                        label = "Point ${index + 1}",
                        stop = stop,
                        isExpanded = expandedStopIndex == index,
                        canRemove = localStops.size > AppSettings.MIN_GRADIENT_STOPS,
                        onToggle = {
                            expandedStopIndex = if (expandedStopIndex == index) null else index
                        },
                        onColorChange = { argb ->
                            localStops = localStops.mapIndexed { i, s ->
                                if (i == index) s.copy(argb = argb) else s
                            }
                            onChange(localStops)
                        },
                        onRemove = {
                            localStops = localStops.filterIndexed { i, _ -> i != index }
                                .ifEmpty { listOf(AppSettings.DefaultGradientStops.first()) }
                            onChange(localStops)
                            expandedStopIndex = null
                        }
                    )
                }

                if (localStops.size < AppSettings.MAX_GRADIENT_STOPS) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FoamDimens.separatorHeight)
                            .background(FoamTheme.colors.borderOverlay)
                    )
                    AddStopButton(onAdd = {
                        // Land the new dot between the last one and the bottom
                        // edge, mirrored horizontally and in a color none of the
                        // existing points use -- identical stops are deduped by
                        // the settings sanitizer, so an exact clone of the last
                        // point would silently vanish (the "add does nothing" bug).
                        val last = localStops.lastOrNull() ?: return@AddStopButton
                        val newOffset = ((last.offset + 1f) / 2f).coerceIn(0f, 1f)
                        val newX = (1f - last.x).coerceIn(0f, 1f)
                        val usedColors = localStops.map { it.argb }.toSet()
                        val newColor = DefaultStopPalette.firstOrNull { it !in usedColors }
                            ?: last.argb
                        localStops = localStops + GradientStop(newOffset, newColor, newX)
                        onChange(localStops)
                    })
                }
            }
        }
    }
}

/**
 * The gradient preview: a tall rectangle rendering the points as a true 2D
 * color field (each dot paints its neighborhood, not vertical bands), with one
 * 2D-draggable dot per point.
 *
 * ONE pointer handler is installed for the whole surface and hit-tests the
 * dots itself: with a detector per dot the children overlay each other and
 * only the first would ever receive a drag. Any dot can therefore be grabbed
 * and moved; its Y position drives the stop's place in the field, X is free
 * placement so overlapping dots can be pulled apart.
 *
 * [onDrag] fires on every frame with the dragged dot's index and its new
 * normalized position; the caller owns instant, in-composition state so the
 * dot tracks the finger with no persistence round-trip in the loop. [onDragEnd]
 * commits the final positions.
 */
@Composable
private fun GradientPreview(
    stops: List<GradientStop>,
    onDrag: (index: Int, x: Float, y: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    // Fresh reads inside the gesture loop: the handler below outlives many
    // recompositions, so the callbacks and the list it hit-tests against must
    // never be captured stale.
    val latestStops by rememberUpdatedState(stops)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    // Rasterize the 2D field at a small resolution and let Compose scale it up
    // with bilinear filtering: smooth at any size, cheap to rebuild per frame.
    val fieldBitmap = remember(stops) { buildGradientImage(stops, 64, 114) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .onSizeChanged { previewSize = it }
            .pointerInput(Unit) {
                val hitRadiusPx = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Grab the dot nearest to the touch, within its hit radius.
                    val hit = nearestStop(
                        latestStops,
                        down.position,
                        size.width.toFloat(),
                        size.height.toFloat()
                    )?.takeIf { (_, distance) -> distance <= hitRadiusPx }
                    draggingIndex = hit?.first ?: -1
                    if (draggingIndex < 0) return@awaitEachGesture
                    // Snap the dot to the finger immediately: apply the down
                    // position before any move arrives, so there is no dead
                    // frame between grabbing and the dot following.
                    latestOnDrag(
                        draggingIndex,
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
                        val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                        val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                        latestOnDrag(draggingIndex, nx, ny)
                    }
                    latestOnDragEnd()
                }
            }
    ) {
        Image(
            bitmap = fieldBitmap,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.Medium,
            modifier = Modifier.fillMaxSize()
        )
        stops.forEachIndexed { index, stop ->
            GradientDot(
                stop = stop,
                previewWidthPx = previewSize.width.toFloat(),
                previewHeightPx = previewSize.height.toFloat(),
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
    }
}

/** Nearest stop to [position]; null when [stops] is empty. */
private fun nearestStop(
    stops: List<GradientStop>,
    position: Offset,
    widthPx: Float,
    heightPx: Float
): Pair<Int, Float>? {
    if (stops.isEmpty()) return null
    var bestIndex = 0
    var bestDistance = Float.MAX_VALUE
    for (i in stops.indices) {
        val dx = position.x - stops[i].x * widthPx
        val dy = position.y - stops[i].offset * heightPx
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = i
        }
    }
    return bestIndex to bestDistance
}

/** One visual dot marker; positioning and dragging are handled by the preview. */
@Composable
private fun GradientDot(
    stop: GradientStop,
    previewWidthPx: Float,
    previewHeightPx: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (stop.x * previewWidthPx).roundToInt() - 14.dp.roundToPx(),
                    (stop.offset * previewHeightPx).roundToInt() - 14.dp.roundToPx()
                )
            }
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.95f))
            .border(2.dp, FoamTheme.colors.textPrimary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Color(stop.argb))
        )
    }
}

/**
 * One row of the point list: color chip, label and a cross to delete the
 * point. Tapping the row expands its color editor. The cross is clipped to a
 * [CircleShape] *before* becoming clickable, so its ripple stays a bounded
 * circle instead of squaring off in the row's corner.
 */
@Composable
private fun StopRow(
    label: String,
    stop: GradientStop,
    isExpanded: Boolean,
    canRemove: Boolean,
    onToggle: () -> Unit,
    onColorChange: (Int) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = FoamTheme.colors.textPrimary),
                    onClick = onToggle
                )
                .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(stop.argb))
                    .border(1.dp, FoamTheme.colors.borderOverlay, CircleShape)
            )
            Spacer(modifier = Modifier.size(FoamDimens.paddingMedium))
            BasicText(
                text = label,
                style = FoamTheme.typography.body.copy(
                    fontWeight = if (isExpanded) FontWeight.Bold else FontWeight.Normal
                ),
                modifier = Modifier.weight(1f)
            )

            if (canRemove) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        // Clip BEFORE the clickable: the ripple is bounded by the
                        // node's clip, so the cross's ripple stays a circle.
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary),
                            onClick = onRemove
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.remove),
                        tint = FoamTheme.colors.textSecondary,
                        modifier = Modifier.size(FoamDimens.iconSmall)
                    )
                }
            }
        }

        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FoamDimens.paddingMedium)
                    .padding(bottom = FoamDimens.spacingMedium)
            ) {
                // Default palette grid (rows of four, sized for the narrow column).
                DefaultStopPalette.chunked(4).forEach { rowColors ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        rowColors.forEach { argb ->
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                                    .background(Color(argb))
                                    .border(
                                        width = if (argb == stop.argb) 2.dp else 0.dp,
                                        color = FoamTheme.colors.textPrimary,
                                        shape = RoundedCornerShape(FoamDimens.buttonRounding)
                                    )
                                    .clickable { onColorChange(argb) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))

                // Custom color input (#RRGGBB or #AARRGGBB).
                CustomColorInput(onSubmit = onColorChange)
            }
        }
    }
}

@Composable
private fun CustomColorInput(onSubmit: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .background(FoamTheme.colors.surfaceVariant)
                .padding(horizontal = FoamDimens.paddingSmall, vertical = FoamDimens.spacingSmall)
        )
        Spacer(modifier = Modifier.size(FoamDimens.spacingSmall))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .background(FoamTheme.accentColor)
                .clickable {
                    parseHexColor(text)?.let(onSubmit)
                }
                .padding(horizontal = FoamDimens.paddingSmall, vertical = FoamDimens.spacingSmall)
        ) {
            BasicText(
                text = stringResource(R.string.ok),
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.background)
            )
        }
    }
}

@Composable
private fun AddStopButton(onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onAdd
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(FoamDimens.iconSmall)
        )
        Spacer(modifier = Modifier.size(FoamDimens.spacingSmall))
        BasicText(
            text = stringResource(R.string.background_add_stop),
            style = FoamTheme.typography.body.copy(color = FoamTheme.accentColor)
        )
    }
}

/** Parses "#RGB", "#RRGGBB" or "#AARRGGBB"; returns null for anything else. */
private fun parseHexColor(input: String): Int? {
    val cleaned = input.trim().removePrefix("#")
    return when (cleaned.length) {
        3 -> cleaned.map { "$it$it" }.joinToString("").toLongOrNull(16)
            ?.let { 0xFF000000.toInt() or it.toInt() }
        6 -> cleaned.toLongOrNull(16)?.let { 0xFF000000.toInt() or it.toInt() }
        8 -> cleaned.toLongOrNull(16)?.toInt()
        else -> null
    }
}
