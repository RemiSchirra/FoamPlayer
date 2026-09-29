package com.foamplayer.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.components.buildGradientImage
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val ModeAnimMillis = 300

/** Shared transitions for every block that appears/disappears with the background mode. */
private val ModeEnter = fadeIn(tween(ModeAnimMillis, delayMillis = 60)) +
        expandVertically(tween(ModeAnimMillis), expandFrom = Alignment.Top)
private val ModeExit = fadeOut(tween(200)) +
        shrinkVertically(tween(ModeAnimMillis), shrinkTowards = Alignment.Top)

@Composable
fun BackgroundSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val mode = settings.backgroundMode
    val forceDynamicInCollections = settings.forceDynamicBackgroundInCollections
    val forceDynamicInPlayer = settings.forceDynamicBackgroundInPlayer

    // Transitions only play once the user has actually picked a mode. The
    // settings flow can emit its real value a moment after the screen opens,
    // and that first change must not animate: the screen should open already
    // in its final state.
    var animateModeChanges by remember { mutableStateOf(false) }
    val modeEnter = if (animateModeChanges) ModeEnter else EnterTransition.None
    val modeExit = if (animateModeChanges) ModeExit else ExitTransition.None

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
                            onSelect = {
                                animateModeChanges = true
                                viewModel.setBackgroundMode(modeOption)
                            }
                        )
                    }
                }
            }

            // Each mode-specific block animates in/out on its own. When switching
            // CUSTOM <-> GRADIENT one shrinks while the other expands, so the
            // content below glides instead of jumping. The spacer lives inside
            // the animated block so its height animates too.
            AnimatedVisibility(
                visible = mode == BackgroundMode.CUSTOM,
                enter = modeEnter,
                exit = modeExit
            ) {
                Column {
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    CustomImageSection(
                        imageUri = settings.customBackgroundUri,
                        onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                        onClearImage = { viewModel.setCustomBackgroundUri(null) }
                    )
                }
            }
            AnimatedVisibility(
                visible = mode == BackgroundMode.GRADIENT,
                enter = modeEnter,
                exit = modeExit
            ) {
                Column {
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    GradientEditor(
                        stops = settings.gradientStops,
                        onChange = viewModel::setGradientStops
                    )
                }
            }
            // Force dynamic switches.
            AnimatedVisibility(
                visible = mode != BackgroundMode.DYNAMIC,
                enter = modeEnter,
                exit = modeExit
            ) {
                Column {
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FoamDimens.paddingMedium)
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                    ) {
                        Column {
                            FoamSwitchItem(
                                title = stringResource(R.string.force_dynamic_background),
                                description = stringResource(R.string.force_dynamic_background_desc),
                                checked = forceDynamicInCollections,
                                onCheckedChange = viewModel::setForceDynamicBackgroundInCollections
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .background(FoamTheme.colors.borderOverlay)
                            )
                            FoamSwitchItem(
                                title = stringResource(R.string.force_dynamic_background_player),
                                description = stringResource(R.string.force_dynamic_background_player_desc),
                                checked = forceDynamicInPlayer,
                                onCheckedChange = viewModel::setForceDynamicBackgroundInPlayer
                            )
                        }
                    }
                }
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
                    AnimatedVisibility(
                        visible = mode != BackgroundMode.GRADIENT,
                        enter = modeEnter,
                        exit = modeExit
                    ) {
                        Column {
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

/**
 * Gradient editor: a vertical-format live preview on the left (a true 2D color
 * field with 2D-draggable color dots) and the point list on the right, with the
 * add control underneath it. The count stays within 1..4 points.
 *
 * Editing a point's color opens [GradientColorPickerModal] (a FoamModal sheet
 * with a full HSV picker); its OK commits, its Cancel discards. Position edits
 * land in a local snapshot state first so drags update on every frame
 * (commit-on-release, like [FoamSliderStateItem]); the persisted setting is
 * written when an edit completes -- drag release, color confirm, add or remove.
 * Persisting per drag frame would round-trip through DataStore on each pointer
 * event, which both lags the dots behind the finger and floods the disk.
 */
@Composable
private fun GradientEditor(
    stops: List<GradientStop>,
    onChange: (List<GradientStop>) -> Unit
) {
    var localStops by remember { mutableStateOf(stops) }
    LaunchedEffect(stops) { localStops = stops }

    var editingStopIndex by remember { mutableStateOf<Int?>(null) }
    var pickerStop by remember { mutableStateOf(AppSettings.DefaultGradientStops.first()) }
    var pickerLabel by remember { mutableStateOf("Point 1") }
    var pickerSession by remember { mutableIntStateOf(0) }

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

            // The point list, right; tapping a row opens its color picker. The
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
                        canRemove = localStops.size > AppSettings.MIN_GRADIENT_STOPS,
                        onEdit = {
                            pickerLabel = "Point ${index + 1}"
                            pickerStop = stop
                            pickerSession++
                            editingStopIndex = index
                        },
                        onRemove = {
                            localStops = localStops.filterIndexed { i, _ -> i != index }
                                .ifEmpty { listOf(AppSettings.DefaultGradientStops.first()) }
                            onChange(localStops)
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

        GradientColorPickerModal(
            visible = editingStopIndex != null,
            session = pickerSession,
            label = pickerLabel,
            initialArgb = pickerStop.argb,
            onConfirm = { argb ->
                val index = editingStopIndex
                if (index != null && index < localStops.size) {
                    localStops = localStops.mapIndexed { i, s ->
                        if (i == index) s.copy(argb = argb) else s
                    }
                    onChange(localStops)
                }
                editingStopIndex = null
            },
            onDismiss = { editingStopIndex = null }
        )
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
 * point. Tapping the row opens its color picker sheet. The cross is clipped
 * to a [CircleShape] *before* becoming clickable, so its ripple stays a
 * bounded circle instead of squaring off in the row's corner.
 */
@Composable
private fun StopRow(
    label: String,
    stop: GradientStop,
    canRemove: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onEdit
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
            style = FoamTheme.typography.body,
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