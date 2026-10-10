package com.foamplayer.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.foamplayer.app.ui.modals.DefaultStopPalette
import com.foamplayer.app.ui.modals.GradientColorPickerModal
import com.foamplayer.app.ui.navigation.LocalBackgroundSettingsPreview
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.flow.first

private const val ModeAnimMillis = 300

/** Shared transitions for every block that appears/disappears with the background mode. */
private val ModeEnter = fadeIn(tween(ModeAnimMillis, delayMillis = 60)) +
        expandVertically(tween(ModeAnimMillis), expandFrom = Alignment.Top)
private val ModeExit = fadeOut(tween(200)) +
        shrinkVertically(tween(ModeAnimMillis), shrinkTowards = Alignment.Top)

private const val StopAnimMillis = 250

/** Enter/exit for the rows of the gradient point list (and its add control). */
private val StopEnter = fadeIn(tween(StopAnimMillis, delayMillis = 50)) +
        expandVertically(tween(StopAnimMillis), expandFrom = Alignment.Top)
private val StopExit = fadeOut(tween(StopAnimMillis - 80)) +
        shrinkVertically(tween(StopAnimMillis), shrinkTowards = Alignment.Top)

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

    // Slider min/max labels resolve from resources ("None"/"Max", "Dim"/"Vivid").
    val intensityMin = stringResource(R.string.none)
    val intensityMax = stringResource(R.string.max)
    val colorMin = stringResource(R.string.dim)
    val colorMax = stringResource(R.string.vivid)

    // Live-preview overrides for the app-wide backdrop layers (owned by the
    // navigation root, provided through [LocalBackgroundSettingsPreview]): the
    // screen pushes every parameter it edits into it — mode taps, gradient dots
    // mid-drag, blur / app-intensity sliders mid-drag — so the backdrop behind
    // the page reacts in real time, while the persisted settings keep the usual
    // commit-on-release cadence. Cleared on leave, so every other screen sees
    // only the persisted values.
    val backgroundPreview = LocalBackgroundSettingsPreview.current
    DisposableEffect(Unit) {
        onDispose { backgroundPreview.clear() }
    }

    // No opaque root background on this page: it composes straight over the
    // root backdrop layers, which ARE its live preview of the edited
    // parameters (the nav wrapper gives only this route a transparent surface).
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        SettingsHeader(
            title = stringResource(R.string.background),
            onBack = onBack,
            transparent = true,
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
                                // Lead the persisted value by the DataStore
                                // round-trip: the backdrop switches on the tap.
                                backgroundPreview.mode = modeOption
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
                        onChange = viewModel::setGradientStops,
                        // Per-frame feed of the dots being dragged, so the root
                        // gradient backdrop tracks them in real time.
                        onPreviewStops = { backgroundPreview.gradientStops = it }
                    )
                }
            }
            // The two "force dynamic" toggles are only meaningful in CUSTOM or
            // GRADIENT mode: in DYNAMIC mode the art already drives every backdrop.
            // Compact layout: one shared title + summary line, then two slim
            // single-line [CompactSwitchRow]s so the whole group fits one card
            // without airy gaps.
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
                            Column(
                                modifier = Modifier.padding(
                                    start = FoamDimens.paddingMedium,
                                    end = FoamDimens.paddingMedium,
                                    top = FoamDimens.spacingSmall,
                                    bottom = FoamDimens.spacingTiny
                                )
                            ) {
                                BasicText(
                                    text = stringResource(R.string.force_dynamic_background_title),
                                    style = FoamTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                BasicText(
                                    text = stringResource(R.string.force_dynamic_background_summary),
                                    style = FoamTheme.typography.body
                                )
                            }
                            CompactSwitchRow(
                                title = stringResource(R.string.collections),
                                checked = forceDynamicInCollections,
                                onCheckedChange = {
                                    animateModeChanges = true
                                    viewModel.setForceDynamicBackgroundInCollections(it)
                                },
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .background(FoamTheme.colors.borderOverlay)
                            )
                            CompactSwitchRow(
                                title = stringResource(R.string.player),
                                checked = forceDynamicInPlayer,
                                onCheckedChange = {
                                    animateModeChanges = true
                                    viewModel.setForceDynamicBackgroundInPlayer(it)
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))

            BasicText(
                text = stringResource(R.string.appearance),
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
                        visible = mode != BackgroundMode.GRADIENT || forceDynamicInPlayer || forceDynamicInCollections,
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
                                // Live preview: blur applies to the backdrop on
                                // every drag frame; persistence stays
                                // commit-on-release.
                                onValueChange = { backgroundPreview.backgroundBlur = it.roundToInt() },
                                steps = 9,
                                valueRange = 0f..100f,
                                formatValue = { "${it.roundToInt()}%" },
                                minMaxDisplay = intensityMin to intensityMax,
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
                        title = stringResource(R.string.app_color_intensity),
                        initialValue = settings.appBackgroundIntensity.toFloat(),
                        onValueChangeFinished = {
                            viewModel.setAppBackgroundIntensity(it.roundToInt())
                        },
                        onValueChange = { backgroundPreview.appBackgroundIntensity = it.roundToInt() },
                        steps = 9,
                        valueRange = 0f..100f,
                        formatValue = { "${it.roundToInt()}%" },
                        minMaxDisplay = colorMin to colorMax,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FoamDimens.separatorHeight)
                            .background(FoamTheme.colors.borderOverlay)
                    )
                    FoamSliderStateItem(
                        title = stringResource(R.string.player_color_intensity),
                        initialValue = settings.playerBackgroundIntensity.toFloat(),
                        onValueChangeFinished = {
                            viewModel.setPlayerBackgroundIntensity(it.roundToInt())
                        },
                        steps = 9,
                        valueRange = 0f..100f,
                        formatValue = { "${it.roundToInt()}%" },
                        minMaxDisplay = colorMin to colorMax,
                    )
                }
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
        }
    }
}

/**
 * Slim single-line toggle row used inside the "force dynamic" card, where two
 * stacked rows must sit close together. Same look and behavior as the shared
 * FoamSwitchItem (bounded ripple, accent-colored switch, switch hidden from
 * semantics because the row is toggleable) but with tighter vertical padding:
 * FoamSwitchItem's fixed row padding is too airy for a card that stacks a
 * header plus two rows.
 */
@Composable
private fun CompactSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingTiny),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = title,
            style = FoamTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.clearAndSetSemantics {},
            colors = SwitchDefaults.colors(
                checkedThumbColor = FoamTheme.accentColor,
                checkedTrackColor = FoamTheme.accentColor.copy(alpha = 0.5f),
                uncheckedThumbColor = FoamTheme.colors.textSecondary.copy(alpha = 1f),
                uncheckedTrackColor = FoamTheme.colors.textSecondary.copy(alpha = 0.5f)
            )
        )
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
 * Adding or removing a point animates its list row (fade + expand/shrink). A
 * removed row is no longer in the stop list but stays composed, in its old
 * place, until its exit finishes -- see [ExitingStopRow].
 *
 * Editing a point's color opens [com.foamplayer.app.ui.modals.GradientColorPickerModal] (a FoamModal sheet
 * with a full HSV picker); its OK commits, its Cancel discards. Position edits
 * land in a local snapshot state first so drags update on every frame
 * (commit-on-release, like [FoamSliderStateItem]); the persisted setting is
 * written when an edit completes -- drag release, color confirm, add or remove.
 * Persisting per drag frame would round-trip through DataStore on each pointer
 * event, which both lags the dots behind the finger and floods the disk.
 *
 * [onPreviewStops] receives the live stop list on every edit frame (drags
 * included), feeding the root gradient backdrop so it tracks the dots in
 * real time.
 */
@Composable
private fun GradientEditor(
    stops: List<GradientStop>,
    onChange: (List<GradientStop>) -> Unit,
    onPreviewStops: (List<GradientStop>) -> Unit
) {
    var localStops by remember { mutableStateOf(stops) }

    // One animation holder per live stop, parallel to [localStops] (same size,
    // same order). Rows present when the editor opens start visible, so the
    // screen opens in its final state; only rows the user adds animate in.
    var rowStates by remember { mutableStateOf(stops.map { StopRowState(animateIn = false) }) }
    // Rows the user removed that are still animating out.
    var exitingRows by remember { mutableStateOf(emptyList<ExitingStopRow>()) }

    LaunchedEffect(stops) {
        localStops = stops
        // Stops changed from outside (initial load, settings sanitizer): keep
        // the holders in step, without animating.
        if (rowStates.size != stops.size) {
            rowStates = if (stops.size < rowStates.size) {
                rowStates.take(stops.size)
            } else {
                rowStates + List(stops.size - rowStates.size) { StopRowState(animateIn = false) }
            }
        }
    }

    var editingStopIndex by remember { mutableStateOf<Int?>(null) }
    var pickerStop by remember { mutableStateOf(AppSettings.DefaultGradientStops.first()) }
    // The point number of the color picker's current target ("Point %1$d"
    // resolves from resources at render time).
    var pickerPointNumber by remember { mutableIntStateOf(1) }
    var pickerSession by remember { mutableIntStateOf(0) }

    fun addStop() {
        // Land the new dot between the last one and the bottom edge, mirrored
        // horizontally and in a color none of the existing points use --
        // identical stops are deduped by the settings sanitizer, so an exact
        // clone of the last point would silently vanish (the "add does nothing"
        // bug).
        val last = localStops.lastOrNull() ?: return
        val newOffset = ((last.offset + 1f) / 2f).coerceIn(0f, 1f)
        val newX = (1f - last.x).coerceIn(0f, 1f)
        val usedColors = localStops.map { it.argb }.toSet()
        val newColor = DefaultStopPalette.firstOrNull { it !in usedColors }
            ?: last.argb
        localStops = localStops + GradientStop(newOffset, newColor, newX)
        onPreviewStops(localStops)
        rowStates = rowStates + StopRowState(animateIn = true)
        onChange(localStops)
    }

    fun removeStop(row: StopRowState) {
        // Looked up by row, not by position: a second tap on a row that is
        // already leaving finds nothing and is ignored.
        val index = rowStates.indexOf(row)
        if (index < 0) return
        val removed = localStops.getOrNull(index) ?: return
        exitingRows = exitingRows + ExitingStopRow(row, removed, index + 1, index)
        row.visibleState.targetState = false

        val remaining = localStops.filterIndexed { i, _ -> i != index }
        if (remaining.isEmpty()) {
            localStops = listOf(AppSettings.DefaultGradientStops.first())
            rowStates = listOf(StopRowState(animateIn = true))
        } else {
            localStops = remaining
            rowStates = rowStates.filterIndexed { i, _ -> i != index }
        }
        onPreviewStops(localStops)
        onChange(localStops)
    }

    Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Vertical-format 2D gradient preview with the draggable dots, left.
            GradientPreview(
                stops = localStops,
                onDrag = { index, x, y ->
                    val updated = localStops.mapIndexed { i, s ->
                        if (i == index) s.copy(offset = y, x = x) else s
                    }
                    localStops = updated
                    onPreviewStops(updated)
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
                // Live rows plus the rows still animating out, spliced back at the
                // slot they were removed from (latest removal first, so rows that
                // left together keep their original order).
                val entries = buildList<StopListEntry> {
                    localStops.forEachIndexed { index, stop ->
                        val row = rowStates.getOrNull(index) ?: return@forEachIndexed
                        add(StopListEntry(row, stop, index + 1, index > 0, index))
                    }
                    exitingRows.asReversed().forEach { exiting ->
                        add(
                            exiting.slot.coerceAtMost(size),
                            StopListEntry(exiting.row, exiting.stop, exiting.pointNumber, exiting.slot > 0, null)
                        )
                    }
                }

                entries.forEach { entry ->
                    key(entry.row) {
                        val row = entry.row
                        if (entry.liveIndex == null) {
                            // Drop the row for good once its exit has finished.
                            LaunchedEffect(row) {
                                snapshotFlow {
                                    row.visibleState.isIdle && !row.visibleState.currentState
                                }.first { it }
                                exitingRows = exitingRows.filterNot { it.row === row }
                            }
                        }
                        // The separator lives inside the animated block so it
                        // grows and shrinks together with its row.
                        AnimatedVisibility(
                            visibleState = row.visibleState,
                            enter = StopEnter,
                            exit = StopExit
                        ) {
                            Column {
                                if (entry.showSeparator) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(FoamDimens.separatorHeight)
                                            .background(FoamTheme.colors.borderOverlay)
                                    )
                                }
                                StopRow(
                                    label = stringResource(R.string.gradient_point, entry.pointNumber),
                                    stop = entry.stop,
                                    // A leaving row keeps its cross so it doesn't
                                    // flicker while it fades out.
                                    canRemove = entry.liveIndex == null ||
                                            localStops.size > AppSettings.MIN_GRADIENT_STOPS,
                                    enabled = entry.liveIndex != null,
                                    onEdit = {
                                        val index = entry.liveIndex ?: return@StopRow
                                        pickerPointNumber = entry.pointNumber
                                        pickerStop = entry.stop
                                        pickerSession++
                                        editingStopIndex = index
                                    },
                                    onRemove = { removeStop(row) }
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = localStops.size < AppSettings.MAX_GRADIENT_STOPS,
                    enter = StopEnter,
                    exit = StopExit
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FoamDimens.separatorHeight)
                                .background(FoamTheme.colors.borderOverlay)
                        )
                        AddStopButton(onAdd = { addStop() })
                    }
                }
            }
        }

        GradientColorPickerModal(
            visible = editingStopIndex != null,
            session = pickerSession,
            label = stringResource(R.string.gradient_point, pickerPointNumber),
            initialArgb = pickerStop.argb,
            onConfirm = { argb ->
                val index = editingStopIndex
                if (index != null && index < localStops.size) {
                    localStops = localStops.mapIndexed { i, s ->
                        if (i == index) s.copy(argb = argb) else s
                    }
                    onPreviewStops(localStops)
                    onChange(localStops)
                }
                editingStopIndex = null
            },
            onDismiss = { editingStopIndex = null }
        )
    }
}

/**
 * Animation holder of one row of the point list, matched to a stop by
 * position. Compared by identity, so it doubles as the row's Compose key.
 * [animateIn] is true only for rows the user just added.
 */
private class StopRowState(animateIn: Boolean) {
    val visibleState = MutableTransitionState(!animateIn).apply { targetState = true }
}

/**
 * A row the user removed. It keeps the stop and point number it had and is
 * spliced back into the list at [slot] until its exit animation ends.
 */
private class ExitingStopRow(
    val row: StopRowState,
    val stop: GradientStop,
    val pointNumber: Int,
    val slot: Int
)

/** One rendered row of the point list, live or leaving. */
private class StopListEntry(
    val row: StopRowState,
    val stop: GradientStop,
    val pointNumber: Int,
    val showSeparator: Boolean,
    /** Position in the live stop list; null while the row is animating out. */
    val liveIndex: Int?
)

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
 *
 * The cross sits in a fixed 32 dp slot and fades in/out: the row keeps the
 * same height whether or not it can be removed, so the surviving row doesn't
 * jump when the point count reaches (or leaves) the minimum. [enabled] is
 * false for a row that is animating out, so it can't be tapped twice.
 */
@Composable
private fun StopRow(
    label: String,
    stop: GradientStop,
    canRemove: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
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

        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            // Fully qualified: inside this Box the implicit RowScope of the outer
            // Row would otherwise capture the call, and Compose forbids that.
            androidx.compose.animation.AnimatedVisibility(
                visible = canRemove,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150))
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        // Clip BEFORE the clickable: the ripple is bounded by the
                        // node's clip, so the cross's ripple stays a circle.
                        .clip(CircleShape)
                        .clickable(
                            enabled = enabled,
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
