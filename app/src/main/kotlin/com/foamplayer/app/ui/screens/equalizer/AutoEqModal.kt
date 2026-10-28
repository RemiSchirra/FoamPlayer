package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.audio.dsp.DspMath
import com.foamplayer.app.audio.dsp.FilterType
import com.foamplayer.app.audio.dsp.NodeColorPalette
import com.foamplayer.app.audio.dsp.ParametricNode
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.squig.SquigDatabase
import com.foamplayer.squig.SquigDevice
import java.util.Locale

/** The Auto EQ flow's inner picker navigation. */
private enum class AutoEqPicker { DATABASE, FROM, TARGET }

/** One searchable section of the device lists. */
private data class AutoEqDeviceGroup(val header: String, val items: List<SquigDevice>)

/**
 * The Auto EQ tab inside the preset selection modal: pick a squig.link
 * database, the device you own ("from"), and a target; generate and apply.
 */
@Composable
internal fun AutoEqContent(
    state: AutoEqUiState,
    actions: AutoEqActions,
) {
    LaunchedEffect(Unit) { actions.loadSites() }

    var picker by remember { mutableStateOf<AutoEqPicker?>(null) }

    when (picker) {
        AutoEqPicker.DATABASE -> AutoEqDatabasePicker(state = state, actions = actions, onBack = { picker = null })
        AutoEqPicker.FROM -> AutoEqDevicePicker(
            title = stringResource(R.string.eq_autoeq_pick_from),
            groups = state.database?.let { database -> database.devices.groupedByBrand() }.orEmpty(),
            loading = state.databaseLoading,
            error = state.databaseError,
            selected = state.fromDevice,
            onSelect = {
                actions.selectFrom(it)
                picker = null
            },
            onBack = { picker = null },
        )
        AutoEqPicker.TARGET -> AutoEqDevicePicker(
            title = stringResource(R.string.eq_autoeq_pick_target),
            groups = state.database?.let(::targetGroups).orEmpty(),
            loading = state.databaseLoading,
            error = state.databaseError,
            selected = state.targetDevice,
            onSelect = {
                actions.selectTarget(it)
                picker = null
            },
            onBack = { picker = null },
        )
        null -> AutoEqOverview(state = state, actions = actions, onPick = { picker = it })
    }
}

// ---- Overview ------------------------------------------------------------------

@Composable
private fun AutoEqOverview(
    state: AutoEqUiState,
    actions: AutoEqActions,
    onPick: (AutoEqPicker) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BasicText(text = stringResource(R.string.eq_tab_autoeq), style = FoamTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        BasicText(
            text = stringResource(R.string.eq_autoeq_desc),
            style = FoamTheme.typography.caption
        )
        Spacer(modifier = Modifier.height(12.dp))

        AutoEqSelectionRow(
            label = stringResource(R.string.eq_autoeq_database),
            value = state.selectedSite?.let { site ->
                "${site.name.ifBlank { site.username }} · ${state.selectedDb?.type.orEmpty()}"
            },
            placeholder = when {
                state.databaseLoading -> stringResource(R.string.eq_autoeq_loading)
                state.sitesError != null -> stringResource(R.string.eq_autoeq_select_database)
                else -> stringResource(R.string.eq_autoeq_select_database)
            },
            onClick = { onPick(AutoEqPicker.DATABASE) }
        )
        Spacer(modifier = Modifier.height(6.dp))

        AutoEqSelectionRow(
            label = stringResource(R.string.eq_autoeq_from),
            value = state.fromDevice?.displayName,
            placeholder = stringResource(R.string.eq_autoeq_select_device),
            onClick = { onPick(AutoEqPicker.FROM) }
        )
        Spacer(modifier = Modifier.height(6.dp))

        AutoEqSelectionRow(
            label = stringResource(R.string.eq_autoeq_target),
            value = state.targetDevice?.displayName,
            placeholder = stringResource(R.string.eq_autoeq_select_device),
            onClick = { onPick(AutoEqPicker.TARGET) }
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
            onClick = actions.generate
        )
        Spacer(modifier = Modifier.height(10.dp))

        state.generation?.let { generation ->
            AutoEqResultCard(generation = generation, mode = state.outputMode, onApply = actions.apply)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Caption label + selectable value pill. */
@Composable
private fun AutoEqSelectionRow(
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
            modifier = Modifier.width(64.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(FoamTheme.colors.surfaceVariant)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = FoamTheme.colors.textPrimary),
                    onClick = onClick
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            BasicText(
                text = value ?: placeholder,
                style = FoamTheme.typography.body.copy(
                    color = if (value != null) {
                        FoamTheme.colors.textPrimary
                    } else {
                        FoamTheme.colors.textSecondary
                    },
                    fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal
                )
            )
        }
    }
}

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

// ---- Pickers -------------------------------------------------------------------

@Composable
private fun AutoEqPickerHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(FoamTheme.colors.surfaceVariant)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ChevronLeft,
                contentDescription = stringResource(R.string.eq_autoeq_back),
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        BasicText(text = title, style = FoamTheme.typography.titleMedium)
    }
}

@Composable
private fun AutoEqDatabasePicker(
    state: AutoEqUiState,
    actions: AutoEqActions,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { actions.loadSites() }

    Column(modifier = Modifier.fillMaxWidth()) {
        AutoEqPickerHeader(
            title = stringResource(R.string.eq_autoeq_pick_database),
            onBack = onBack
        )
        Spacer(modifier = Modifier.height(10.dp))

        when {
            state.sitesLoading -> AutoEqLoading()
            state.sitesError != null -> AutoEqErrorText(state.sitesError)
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.sites) { site ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BasicText(
                            text = site.name.ifBlank { site.username },
                            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            site.dbs.forEach { db ->
                                val selected = state.selectedSite?.username == site.username &&
                                        state.selectedDb?.type == db.type
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) FoamTheme.accentColor else FoamTheme.colors.surfaceVariant
                                        )
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(color = FoamTheme.colors.textPrimary),
                                            onClick = { actions.selectDatabase(site, db) }
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    BasicText(
                                        text = db.type,
                                        style = FoamTheme.typography.caption.copy(
                                            color = if (selected) {
                                                FoamTheme.colors.background
                                            } else {
                                                FoamTheme.colors.textPrimary
                                            },
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Searchable, brand-grouped device list. The target picker adds the targets section. */
@Composable
private fun AutoEqDevicePicker(
    title: String,
    groups: List<AutoEqDeviceGroup>,
    loading: Boolean,
    error: String?,
    selected: SquigDevice?,
    onSelect: (SquigDevice) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        AutoEqPickerHeader(title = title, onBack = onBack)
        Spacer(modifier = Modifier.height(10.dp))

        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                BasicText(
                    text = stringResource(R.string.eq_autoeq_search),
                    style = FoamTheme.typography.body
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = FoamTheme.colors.surfaceVariant,
                focusedIndicatorColor = FoamTheme.accentColor,
                unfocusedIndicatorColor = FoamTheme.colors.borderOverlay,
                textColor = FoamTheme.colors.textPrimary,
                cursorColor = FoamTheme.accentColor
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        when {
            loading -> AutoEqLoading()
            error != null -> AutoEqErrorText(error)
            else -> {
                val filtered = remember(groups, query) { groups.filteredBy(query) }
                if (filtered.isEmpty()) {
                    BasicText(
                        text = stringResource(R.string.eq_autoeq_empty),
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        filtered.forEach { group ->
                            item(key = "header:${group.header}") {
                                BasicText(
                                    text = group.header,
                                    style = FoamTheme.typography.caption.copy(
                                        color = FoamTheme.accentColor,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                )
                            }
                            items(group.items, key = { "${group.header}|${it.file}" }) { device ->
                                AutoEqDeviceRow(
                                    device = device,
                                    selected = device == selected,
                                    onSelect = { onSelect(device) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoEqDeviceRow(
    device: SquigDevice,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) FoamTheme.accentColor.copy(alpha = 0.16f) else FoamTheme.colors.surfaceVariant
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onSelect
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = device.model,
                style = FoamTheme.typography.body.copy(
                    color = FoamTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            )
            device.reviewScore?.takeIf(String::isNotBlank)?.let { score ->
                BasicText(
                    text = "★ $score",
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(FoamTheme.accentColor)
            )
        }
    }
}

// ---- Helpers -------------------------------------------------------------------

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

@Composable
private fun AutoEqErrorText(error: String) {
    BasicText(
        text = error,
        style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor),
        modifier = Modifier.padding(vertical = 8.dp)
    )
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

private fun List<AutoEqDeviceGroup>.filteredBy(query: String): List<AutoEqDeviceGroup> {
    if (query.isBlank()) return this
    val needle = query.trim().lowercase()
    return mapNotNull { group ->
        val items = group.items.filter { it.displayName.lowercase().contains(needle) }
        if (items.isEmpty()) null else AutoEqDeviceGroup(group.header, items)
    }
}

private fun formatPreamp(db: Float): String = "%.1f dB".format(db)

private fun formatError(db: Float): String = "%.1f dB".format(db)
