package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.foamplayer.app.R
import com.foamplayer.app.audio.dsp.DspMath
import com.foamplayer.app.audio.dsp.FilterType
import com.foamplayer.app.audio.dsp.NodeColorPalette
import com.foamplayer.app.audio.dsp.ParametricNode
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.squig.SquigDatabase
import com.foamplayer.squig.SquigDevice



/** One searchable section of the device lists. */
private data class AutoEqDeviceGroup(val header: String, val items: List<SquigDevice>)

/**
 * The Auto EQ tab inside the preset selection modal: pick a squig.link
 * database, the device you own ("from"), and a target; generate and apply.
 * The three picks are inline text bars with filtered dropdowns (genre
 * text-bar pattern), so the flow is self-contained in one surface.
 */
@Composable
internal fun AutoEqContent(
    state: AutoEqUiState,
    actions: AutoEqActions,
) {
    LaunchedEffect(Unit) { actions.loadSites() }

    AutoEqOverview(state = state, actions = actions)
}

// Overview

@Composable
private fun AutoEqOverview(
    state: AutoEqUiState,
    actions: AutoEqActions,
) {
    // Tapping "Generate EQ" while a selection bar is focused dismisses the
    // keyboard along with the click (SearchScreen pattern).
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboard: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        BasicText(text = stringResource(R.string.eq_tab_autoeq), style = FoamTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        BasicText(
            text = stringResource(R.string.eq_autoeq_desc),
            style = FoamTheme.typography.caption
        )
        Spacer(modifier = Modifier.height(12.dp))

        AutoEqSelectionBar(
            label = stringResource(R.string.eq_autoeq_database),
            value = state.selectedSite?.let { site ->
                "${site.name.ifBlank { site.username }} - ${state.selectedDb?.type.orEmpty()}"
            },
            hint = if (state.sitesLoading) {
                stringResource(R.string.eq_autoeq_loading)
            } else {
                stringResource(R.string.eq_autoeq_select_database)
            },
            loading = state.sitesLoading,
            options = state.sites.flatMapIndexed { siteIndex, site ->
                site.dbs.mapIndexed { dbIndex, db ->
                    AutoEqOption(
                        id = "db|$siteIndex|$dbIndex",
                        header = site.name.ifBlank { site.username },
                        title = db.type,
                        selected = state.selectedSite?.username == site.username &&
                                state.selectedDb?.type == db.type,
                        fill = "${site.name.ifBlank { site.username }} - ${db.type}",
                        onPick = { actions.selectDatabase(site, db) }
                    )
                }
            },
        )
        Spacer(modifier = Modifier.height(6.dp))

        AutoEqSelectionBar(
            label = stringResource(R.string.eq_autoeq_from),
            value = state.fromDevice?.displayName,
            hint = stringResource(R.string.eq_autoeq_select_device),
            loading = state.databaseLoading,
            options = state.database?.devices.orEmpty().groupedByBrand()
                .flatMapIndexed { groupIndex, group ->
                    group.items.mapIndexed { deviceIndex, device ->
                        AutoEqOption(
                            id = "dev|$groupIndex|$deviceIndex",
                            header = group.header,
                            title = device.model,
                            subtitle = device.reviewScore?.takeIf(String::isNotBlank)?.let { "★ $it" },
                            selected = device == state.fromDevice,
                            fill = device.displayName,
                            onPick = { actions.selectFrom(device) }
                        )
                    }
                },
        )
        Spacer(modifier = Modifier.height(6.dp))

        AutoEqSelectionBar(
            label = stringResource(R.string.eq_autoeq_target),
            value = state.targetDevice?.displayName,
            hint = stringResource(R.string.eq_autoeq_select_device),
            loading = state.databaseLoading,
            options = state.database?.let { database ->
                targetGroups(database).flatMapIndexed { groupIndex, group ->
                    group.items.mapIndexed { deviceIndex, device ->
                        AutoEqOption(
                            id = "tgt|$groupIndex|$deviceIndex",
                            header = group.header,
                            title = device.model,
                            subtitle = device.reviewScore?.takeIf(String::isNotBlank)?.let { "★ $it" },
                            selected = device == state.targetDevice,
                            fill = device.displayName,
                            onPick = { actions.selectTarget(device) }
                        )
                    }
                }
            }.orEmpty(),
        )
        Spacer(modifier = Modifier.height(12.dp))

        AutoEqModeRow(mode = state.outputMode, onSet = actions.setOutputMode)
        Spacer(modifier = Modifier.height(12.dp))

        val error = state.databaseError ?: state.generateError
        if (error != null) {
            BasicText(
                text = error,
                style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        AutoEqGenerateButton(
            enabled = state.canGenerate,
            generating = state.generating,
            onClick = {
                dismissKeyboard()
                actions.generate()
            }
        )
        Spacer(modifier = Modifier.height(10.dp))

        // The result card expands/collapses so its measured height animates,
        // which in turn animates the modal's fitted height instead of the
        // sheet snapping to the new size in one frame.
        AnimatedVisibility(
            visible = state.generation != null,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            state.generation?.let { generation ->
                AutoEqResultCard(generation = generation, mode = state.outputMode, onApply = actions.apply)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** One pickable row of an Auto EQ selection bar's dropdown. */
private data class AutoEqOption(
    val id: String,
    val header: String,
    val title: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val fill: String,
    val onPick: () -> Unit,
)

/**
 * Caption label + pickable value bar with a filtered dropdown (the genre
 * text-bar pattern):
 * - The dropdown hangs directly under the bar and reads as one surface with
 *   it (same color, no gap, the bar's bottom corners square off while open).
 * - It is filtered live by the typed text (case-insensitive "contains" on
 *   the title and group header, prefix matches first), and opens as soon as
 *   the user types.
 * - The chevron in the bar shows/hides it at any time.
 * - Picking a row fills the bar and closes the list; it only comes back when
 *   the user types again or taps the chevron.
 * - The bar's text re-initializes whenever the picked value changes, so it
 *   always shows the current selection.
 * - The list overlays the content below it (a [zIndex] lift on the bar's
 *   wrapper) with a zero layout footprint, so the modal itself never moves
 *   or resizes.
 * - The caret is the accent color, and disappears as soon as the field loses
 *   focus: focus is cleared whenever the keyboard is closed, by any means.
 */
@Composable
private fun AutoEqSelectionBar(
    label: String,
    value: String?,
    hint: String,
    loading: Boolean,
    options: List<AutoEqOption>,
) {
    var text by remember(value) { mutableStateOf(value.orEmpty()) }
    var expanded by remember { mutableStateOf(false) }
    var barHeight by remember { mutableIntStateOf(0) }

    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // The caret disappears when the field loses focus: clear it whenever the
    // keyboard is closed, by whatever means.
    LaunchedEffect(imeVisible) { if (!imeVisible) focusManager.clearFocus() }

    val query = text.trim()
    val filtered = remember(options, query) { options.filteredByQuery(query) }
    val listVisible = expanded && (loading || filtered.isNotEmpty())

    // Drives the dropdown's AnimatedVisibility. Its currentState lags the
    // target until the exit animation completes, so the bar wrapper stays
    // z-lifted above the rows below while the list fades out.
    val dropdownState = remember { MutableTransitionState(false) }
    dropdownState.targetState = listVisible

    // Square the bar's bottom corners while the list is attached to it.
    val bottomCorner by animateIntAsState(
        targetValue = if (listVisible) 0 else 10,
        label = "AutoEqBarBottomCorner"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (listVisible) 180f else 0f,
        label = "AutoEqBarChevron"
    )

    Box(
        modifier = Modifier.fillMaxWidth().zIndex(
            if (dropdownState.currentState || dropdownState.targetState) 1f else 0f
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = label,
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                modifier = Modifier.width(64.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(
                                topStart = 10.dp,
                                topEnd = 10.dp,
                                bottomEnd = bottomCorner.dp,
                                bottomStart = bottomCorner.dp
                            )
                        )
                        .background(FoamTheme.colors.surfaceVariant)
                        .onSizeChanged { barHeight = it.height }
                        .padding(start = 12.dp, end = 4.dp)
                        .height(36.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            expanded = true
                        },
                        singleLine = true,
                        textStyle = FoamTheme.typography.body.copy(
                            color = FoamTheme.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        cursorBrush = SolidColor(FoamTheme.accentColor),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            expanded = false
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 32.dp)
                    )
                    if (text.isEmpty()) {
                        BasicText(
                            text = hint,
                            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 32.dp)
                        )
                    }
                    // When the text is filled down to at most one possible
                    // option (picked, or a fully-narrowed query), the chevron
                    // is replaced by a cross that clears the input.
                    val canClear = text.isNotEmpty() && filtered.size <= 1
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(28.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = {
                                    if (canClear) {
                                        text = ""
                                        expanded = false
                                    } else {
                                        expanded = !listVisible
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (canClear) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                tint = FoamTheme.colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = FoamTheme.colors.textSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .graphicsLayer { rotationZ = chevronRotation }
                            )
                        }
                    }
                }

                // The list overlays the content below (the modal never
                // moves): it hangs flush under the bar via offset, the bar's
                // wrapper is z-lifted above the following rows while the list
                // is open, and the list reports a zero footprint so it never
                // grows the modal's fitted height.
                AutoEqDropdown(
                    visibleState = dropdownState,
                    loading = loading,
                    options = filtered,
                    onSelect = { option ->
                        text = option.fill
                        expanded = false
                        option.onPick()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(x = 0, y = barHeight) }
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(0, 0) { placeable.place(0, 0) }
                        }
                )
            }
        }
    }
}

/**
 * The Auto EQ suggestion list: reads as the continuation of the bar above it
 * (same surface, rounded bottom), hanging flush under the bar on top of the
 * following content. Height-capped by [AutoEqDropdownMaxHeight] and
 * scrollable; the group header renders once per group; picking a row fills
 * the bar. Rows are keyed by [AutoEqOption.id], which is already unique.
 */
@Composable
private fun AutoEqDropdown(
    visibleState: MutableTransitionState<Boolean>,
    loading: Boolean,
    options: List<AutoEqOption>,
    onSelect: (AutoEqOption) -> Unit,
    modifier: Modifier = Modifier
) {
    // Indices at which a new (contiguous) brand run starts; the filter
    // preserves order, so boundaries mirror the grouping.
    val headerBoundaries = remember(options) {
        buildSet {
            options.forEachIndexed { index, option ->
                if (index == 0 || options[index - 1].header != option.header) add(index)
            }
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = AutoEqDropdownMaxHeight)
                .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                .background(FoamTheme.colors.surfaceVariant)
        ) {
            if (loading) {
                item(key = "loading") { AutoEqLoading() }
            } else {
                itemsIndexed(
                    options,
                    key = { _, option -> option.id }
                ) { index, option ->
                    val showHeader = index in headerBoundaries
                    Column {
                        if (showHeader) {
                            BasicText(
                                text = option.header,
                                style = FoamTheme.typography.caption.copy(
                                    color = FoamTheme.accentColor,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 2.dp)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = FoamTheme.colors.textPrimary),
                                    onClick = { onSelect(option) }
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                BasicText(
                                    text = option.title,
                                    style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                option.subtitle?.let { subtitle ->
                                    BasicText(
                                        text = subtitle,
                                        style = FoamTheme.typography.caption.copy(
                                            color = FoamTheme.colors.textSecondary
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (option.selected) FoamTheme.accentColor else Color.Transparent)
                            )
                        }
                    }
                }
            }
        }
    }
}

private val AutoEqDropdownMaxHeight = 280.dp

/** Parametric | Graphic pills choosing the generated EQ's output section. */
@Composable
private fun AutoEqModeRow(
    mode: AutoEqOutputMode,
    onSet: (AutoEqOutputMode) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = stringResource(R.string.eq_autoeq_output),
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
            modifier = Modifier.width(64.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(FoamTheme.colors.surfaceVariant)
                .padding(2.dp)
        ) {
            listOf(
                AutoEqOutputMode.PARAMETRIC to stringResource(R.string.eq_autoeq_parametric),
                AutoEqOutputMode.GRAPHIC to stringResource(R.string.eq_autoeq_graphic)
            ).forEach { (outputMode, label) ->
                val selected = outputMode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) FoamTheme.accentColor else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary),
                            onClick = { onSet(outputMode) }
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    BasicText(
                        text = label,
                        style = FoamTheme.typography.caption.copy(
                            color = if (selected) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}

/** Full-width generate pill; disabled until a database and both devices are picked. */
@Composable
private fun AutoEqGenerateButton(
    enabled: Boolean,
    generating: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FoamTheme.colors.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (generating) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = FoamTheme.accentColor
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        BasicText(
            text = stringResource(if (generating) R.string.eq_autoeq_generating else R.string.eq_autoeq_generate),
            style = FoamTheme.typography.caption.copy(
                color = FoamTheme.accentColor,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

/** The generated fitting: live response preview, stats, and an apply action. */
@Composable
private fun AutoEqResultCard(
    generation: AutoEqGeneration,
    mode: AutoEqOutputMode,
    onApply: () -> Unit
) {
    val result = generation.result
    val previewCurve = remember(generation) {
        List(DspMath.DEFAULT_RESPONSE_POINTS) { index ->
            result.correctionCurve.interpolate(
                DspMath.responseFrequencyAt(index, DspMath.DEFAULT_RESPONSE_POINTS)
            )
        }
    }
    val previewNodes = remember(generation, mode) {
        when (mode) {
            AutoEqOutputMode.PARAMETRIC -> result.filters.mapIndexed { index, filter ->
                ParametricNode(
                    id = (index + 1).toLong(),
                    type = FilterType.PEAKING,
                    frequencyHz = filter.frequencyHz,
                    gainDb = filter.gainDb,
                    q = filter.q,
                    colorArgb = NodeColorPalette[index % NodeColorPalette.size]
                )
            }
            AutoEqOutputMode.GRAPHIC -> emptyList()
        }
    }

    FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(
                    R.string.eq_autoeq_result_title,
                    generation.fromName,
                    generation.targetName
                ),
                style = FoamTheme.typography.body.copy(
                    color = FoamTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(10.dp))

            EqMiniMap(
                combinedCurve = previewCurve,
                nodes = previewNodes,
                active = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            BasicText(
                text = stringResource(
                    R.string.eq_autoeq_result_stats,
                    formatPreamp(result.preampDb),
                    result.filters.size,
                    formatError(result.residualRmsDb)
                ),
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(FoamTheme.accentColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = onApply
                    )
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(R.string.eq_autoeq_apply),
                    style = FoamTheme.typography.body.copy(
                        color = FoamTheme.colors.background,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

// Selection bar helpers

/** Live filter of an Auto EQ dropdown: case-insensitive "contains" on the
 * title and group header, prefix matches first. */
private fun List<AutoEqOption>.filteredByQuery(query: String): List<AutoEqOption> {
    if (query.isBlank()) return this
    val needle = query.lowercase()
    return filter { option ->
        option.title.lowercase().contains(needle) || option.header.lowercase().contains(needle)
    }.sortedByDescending { option ->
        option.title.startsWith(needle, ignoreCase = true) ||
                option.header.startsWith(needle, ignoreCase = true)
    }
}



// Helpers

@Composable
private fun AutoEqLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = FoamTheme.accentColor
        )
    }
}



/** Brand groups, sorted case-insensitively, models sorted inside each. */
private fun List<SquigDevice>.groupedByBrand(): List<AutoEqDeviceGroup> =
    groupBy { it.brand.ifBlank { "Other" } }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (brand, items) ->
            AutoEqDeviceGroup(brand, items.sortedBy { it.model.lowercase() })
        }

/** Target picker: the database's preference targets first, then every device. */
private fun targetGroups(database: SquigDatabase): List<AutoEqDeviceGroup> = buildList {
    if (database.targets.isNotEmpty()) {
        database.targets.groupBy { it.brand }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (type, items) ->
            add(AutoEqDeviceGroup(type, items.sortedBy { it.model.lowercase() }))
        }
        database.devices.groupedByBrand().forEach { add(it) }
    } else {
        database.devices.groupedByBrand().forEach { add(it) }
    }
}



private fun formatPreamp(db: Float): String = "%.1f dB".format(db)

private fun formatError(db: Float): String = "%.1f dB".format(db)
