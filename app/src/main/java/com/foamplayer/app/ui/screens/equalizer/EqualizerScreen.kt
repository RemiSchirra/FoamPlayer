package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.audio.dsp.DspMath
import com.foamplayer.app.audio.dsp.DspPreset
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.audio.dsp.FilterType
import com.foamplayer.app.audio.dsp.GraphicEqSettings
import com.foamplayer.app.audio.dsp.ParametricEqSettings
import com.foamplayer.app.audio.dsp.ParametricNode
import com.foamplayer.app.audio.dsp.ToneSettings
import com.foamplayer.app.ui.components.FoamBottomModal
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.components.KnobControl
import com.foamplayer.app.ui.components.VerticalSlider
import com.foamplayer.app.ui.components.onSeekBarReleased
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Poweramp-style Equalizer & DSP screen:
 *  - power toggle + combined-response mini map,
 *  - preset chips (built-ins + user-saved),
 *  - Graphic (10-band draggable handles) / Parametric (draggable nodes) sections,
 *  - rotary tone knobs (pre-amp, bass, mid, treble) and control knobs (balance,
 *    stereo width) with a limiter switch,
 *  - direct volume control slider with dB readout.
 *
 * All controls commit on release so a drag never floods the DSP engine with state
 * emissions; the graphs render from local working copies in the meantime.
 */
@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel = hiltViewModel()
) {
    val state by viewModel.dspState.collectAsState()
    val userPresets by viewModel.userPresets.collectAsState()

    var selectedSection by remember { mutableStateOf(DspMath.Section.GRAPHIC) }
    var selectedNodeId by remember { mutableStateOf<Long?>(null) }
    var showSavePresetModal by remember { mutableStateOf(false) }

    // Live combined curve for the mini map (and everything that reads it).
    val combinedCurve = remember(state) { DspMath.combinedResponse(state) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = LocalBottomBarClearance.current)
    ) {
        // ------------------------------------------------------------ header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(text = "Equalizer & DSP", style = FoamTheme.typography.titleLarge)
                BasicText(
                    text = when {
                        state.isNeutral -> "Bit-perfect passthrough"
                        else -> "32-bit float processing"
                    },
                    style = FoamTheme.typography.caption.copy(
                        color = if (state.isNeutral) {
                            FoamTheme.colors.bitPerfectActive
                        } else {
                            FoamTheme.colors.bitPerfectInactive
                        }
                    )
                )
            }

            PowerButton(
                enabled = state.enabled,
                onToggle = { viewModel.setEnabled(!state.enabled) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---------------------------------------------------------- mini map
        FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = "FREQUENCY RESPONSE",
                        style = FoamTheme.typography.caption.copy(
                            color = FoamTheme.colors.textSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ResponseMiniMap(
                        curve = combinedCurve,
                        active = state.enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(horizontalAlignment = Alignment.End) {
                    MiniMapValue("PRE-AMP", formatDb(state.preampDb))
                    MiniMapValue("BASS", formatDb(state.tone.bassDb))
                    MiniMapValue("TREBLE", formatDb(state.tone.trebleDb))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ----------------------------------------------------------- presets
        PresetsRow(
            state = state,
            userPresets = userPresets,
            onPresetSelected = { viewModel.applyPreset(it) },
            onSaveRequested = { showSavePresetModal = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------------------- mode tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTab(
                label = "GRAPHIC",
                active = selectedSection == DspMath.Section.GRAPHIC,
                sectionEnabled = state.graphicEq.enabled,
                onClick = { selectedSection = DspMath.Section.GRAPHIC },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            SectionTab(
                label = "PARAMETRIC",
                active = selectedSection == DspMath.Section.PARAMETRIC,
                sectionEnabled = state.parametricEq.enabled,
                onClick = { selectedSection = DspMath.Section.PARAMETRIC },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = when (selectedSection) {
                    DspMath.Section.GRAPHIC -> "10-band graphic equalizer"
                    DspMath.Section.PARAMETRIC ->
                        "Parametric equalizer · ${state.parametricEq.nodes.size}/${ParametricEqSettings.MAX_NODES} nodes"
                },
                style = FoamTheme.typography.caption,
                modifier = Modifier.weight(1f)
            )
            SectionPowerToggle(
                enabled = when (selectedSection) {
                    DspMath.Section.GRAPHIC -> state.graphicEq.enabled
                    DspMath.Section.PARAMETRIC -> state.parametricEq.enabled
                },
                onToggle = { on ->
                    when (selectedSection) {
                        DspMath.Section.GRAPHIC -> viewModel.setGraphicEnabled(on)
                        DspMath.Section.PARAMETRIC -> viewModel.setParametricEnabled(on)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --------------------------- preamp fader + main graph (Poweramp layout)
        Row(modifier = Modifier.fillMaxWidth()) {
            VerticalSlider(
                value = state.preampDb,
                onCommit = { viewModel.setPreampDb(it) },
                valueRange = DspState.MIN_PREAMP_DB..DspState.MAX_PREAMP_DB,
                label = "PRE-AMP",
                valueText = { formatDb(it) },
                modifier = Modifier
                    .width(56.dp)
                    .height(230.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            EqualizerGraph(
                state = state,
                section = selectedSection,
                selectedNodeId = selectedNodeId,
                onNodeSelect = { selectedNodeId = it },
                onGraphicGainsCommit = { gains -> viewModel.setGraphicGains(gains) },
                onNodeCommit = { node -> viewModel.updateParametricNode(node) },
                modifier = Modifier.weight(1f)
            )
        }

        // ------------------------------------------- parametric node editor
        if (selectedSection == DspMath.Section.PARAMETRIC) {
            Spacer(modifier = Modifier.height(8.dp))
            val selectedNode = state.parametricEq.nodes.firstOrNull { it.id == selectedNodeId }
            if (selectedNode != null) {
                ParametricNodeEditor(
                    node = selectedNode,
                    onUpdate = { viewModel.updateParametricNode(it) },
                    onDelete = {
                        viewModel.removeParametricNode(selectedNode.id)
                        selectedNodeId = null
                    }
                )
            } else {
                AddNodeRow(
                    canAdd = state.parametricEq.nodes.size < ParametricEqSettings.MAX_NODES,
                    onAdd = {
                        val id = viewModel.addParametricNode()
                        selectedNodeId = id
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------------------------ knobs
        KnobsSection(state = state, viewModel = viewModel)

        Spacer(modifier = Modifier.height(16.dp))

        // ----------------------------------------------------------- volume
        VolumeSection(state = state, viewModel = viewModel)

        Spacer(modifier = Modifier.height(24.dp))
    }

    // ---------------------------------------------------- save preset modal
    SavePresetModal(
        visible = showSavePresetModal,
        onDismiss = { showSavePresetModal = false },
        onSave = { name ->
            viewModel.saveUserPreset(name)
            showSavePresetModal = false
        }
    )
}

// ------------------------------------------------------------------- header

@Composable
private fun PowerButton(
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) FoamTheme.accentColor else FoamTheme.colors.surfaceVariant)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                role = Role.Switch,
                onClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.PowerSettingsNew,
            contentDescription = if (enabled) "Disable DSP" else "Enable DSP",
            tint = if (enabled) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
            modifier = Modifier.size(24.dp)
        )
    }
}

// ----------------------------------------------------------------- mini map

@Composable
private fun MiniMapValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
        )
        Spacer(modifier = Modifier.width(6.dp))
        BasicText(
            text = value,
            style = FoamTheme.typography.monospace
        )
    }
}

// ------------------------------------------------------------------ presets

@Composable
private fun PresetsRow(
    state: DspState,
    userPresets: List<DspPreset>,
    onPresetSelected: (DspPreset) -> Unit,
    onSaveRequested: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DspPreset.BUILT_INS.forEach { preset ->
            PresetChip(
                label = preset.name,
                selected = state.graphicEq.gainsDb == preset.graphicGainsDb && preset.fullState == null,
                onClick = { onPresetSelected(preset) }
            )
        }
        userPresets.forEach { preset ->
            PresetChip(
                label = preset.name,
                selected = preset.fullState?.copy(volumePercent = state.volumePercent) == state,
                onClick = { onPresetSelected(preset) }
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(FoamTheme.colors.surface)
                .clickable(onClick = onSaveRequested)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkAdd,
                    contentDescription = "Save preset",
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                BasicText(
                    text = "Save",
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
                )
            }
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) FoamTheme.accentColor else FoamTheme.colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
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

// --------------------------------------------------------------- mode tabs

@Composable
private fun SectionTab(
    label: String,
    active: Boolean,
    sectionEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    active && sectionEnabled -> FoamTheme.accentColor
                    active -> FoamTheme.accentColor.copy(alpha = 0.45f)
                    else -> FoamTheme.colors.surfaceVariant
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (sectionEnabled) FoamTheme.colors.background
                        else FoamTheme.colors.textSecondary
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicText(
                text = label,
                style = FoamTheme.typography.titleMedium.copy(
                    color = if (active) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
private fun SectionPowerToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = if (enabled) "ON" else "OFF",
            style = FoamTheme.typography.caption.copy(
                color = if (enabled) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FoamTheme.accentColor,
                checkedTrackColor = FoamTheme.accentColor.copy(alpha = 0.5f),
                uncheckedThumbColor = FoamTheme.colors.textSecondary,
                uncheckedTrackColor = FoamTheme.colors.textSecondary.copy(alpha = 0.4f)
            )
        )
    }
}

// -------------------------------------------------------- node editor panel

@Composable
private fun ParametricNodeEditor(
    node: ParametricNode,
    onUpdate: (ParametricNode) -> Unit,
    onDelete: () -> Unit
) {
    FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = "NODE ${node.id}",
                    style = FoamTheme.typography.caption.copy(
                        color = FoamTheme.accentColor,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FoamTheme.colors.surfaceVariant)
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete node",
                        tint = FoamTheme.colors.textPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Filter type selector.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterType.entries.forEach { type ->
                    val selected = node.type == type
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) FoamTheme.accentColor else FoamTheme.colors.surfaceVariant)
                            .clickable { onUpdate(node.copy(type = type)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        BasicText(
                            text = type.label,
                            style = FoamTheme.typography.caption.copy(
                                color = if (selected) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            NodeSlider(
                label = "FREQ",
                valueText = frequencyDisplay(node.frequencyHz),
                value = frequencyToNormalized(node.frequencyHz),
                onCommit = { normalized ->
                    onUpdate(node.copy(frequencyHz = normalizedToFrequency(normalized)))
                }
            )

            if (node.type.gainAdjustable) {
                NodeSlider(
                    label = "GAIN",
                    valueText = formatDb(node.gainDb),
                    value = (node.gainDb - ParametricNode.MIN_GAIN_DB) /
                            (ParametricNode.MAX_GAIN_DB - ParametricNode.MIN_GAIN_DB),
                    onCommit = { normalized ->
                        onUpdate(
                            node.copy(
                                gainDb = ParametricNode.MIN_GAIN_DB + normalized *
                                        (ParametricNode.MAX_GAIN_DB - ParametricNode.MIN_GAIN_DB)
                            )
                        )
                    }
                )
            }

            NodeSlider(
                label = "Q",
                valueText = "%.2f".format(node.q),
                value = (node.q - ParametricNode.MIN_Q) / (ParametricNode.MAX_Q - ParametricNode.MIN_Q),
                onCommit = { normalized ->
                    onUpdate(
                        node.copy(
                            q = ParametricNode.MIN_Q + normalized *
                                    (ParametricNode.MAX_Q - ParametricNode.MIN_Q)
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun NodeSlider(
    label: String,
    valueText: String,
    value: Float,
    onCommit: (Float) -> Unit
) {
    var dragValue by remember(value) { mutableStateOf(value) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
            modifier = Modifier.width(44.dp)
        )
        FoamSlider(
            value = { dragValue },
            onValueChange = { dragValue = it },
            modifier = Modifier
                .weight(1f)
                .onSeekBarReleased { onCommit(dragValue) }
        )
        BasicText(
            text = valueText,
            style = FoamTheme.typography.monospace,
            modifier = Modifier.width(64.dp)
        )
    }
}

@Composable
private fun AddNodeRow(
    canAdd: Boolean,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FoamTheme.colors.surface)
            .clickable(enabled = canAdd, onClick = onAdd)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = if (canAdd) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        BasicText(
            text = if (canAdd) "Add parametric node" else "Node limit reached",
            style = FoamTheme.typography.caption.copy(
                color = if (canAdd) FoamTheme.accentColor else FoamTheme.colors.textSecondary
            )
        )
    }
}

// -------------------------------------------------------------------- knobs

@Composable
private fun KnobsSection(
    state: DspState,
    viewModel: EqualizerViewModel
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            SectionCaption("TONE")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KnobControl(
                    value = state.tone.bassDb,
                    onValueChange = { viewModel.setBassDb(it) },
                    label = "BASS",
                    valueText = { formatDb(it) },
                    minValue = ToneSettings.MIN_DB,
                    maxValue = ToneSettings.MAX_DB
                )
                KnobControl(
                    value = state.tone.midDb,
                    onValueChange = { viewModel.setMidDb(it) },
                    label = "MID",
                    valueText = { formatDb(it) },
                    minValue = ToneSettings.MIN_DB,
                    maxValue = ToneSettings.MAX_DB
                )
                KnobControl(
                    value = state.tone.trebleDb,
                    onValueChange = { viewModel.setTrebleDb(it) },
                    label = "TREBLE",
                    valueText = { formatDb(it) },
                    minValue = ToneSettings.MIN_DB,
                    maxValue = ToneSettings.MAX_DB
                )
            }
        }

        Column {
            SectionCaption("CONTROLS")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KnobControl(
                    value = state.balance,
                    onValueChange = { viewModel.setBalance(it) },
                    label = "BALANCE",
                    valueText = { formatBalance(it) },
                    minValue = -DspState.MAX_BALANCE,
                    maxValue = DspState.MAX_BALANCE
                )
                KnobControl(
                    value = state.stereoWidth,
                    onValueChange = { viewModel.setStereoWidth(it) },
                    label = "STEREO",
                    valueText = { "×%.1f".format(it) },
                    minValue = 0f,
                    maxValue = DspState.MAX_STEREO_WIDTH,
                    defaultValue = 1f
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    DspSwitchRow(
        title = "Limiter",
        caption = "Peaks limiter — keeps boosts from hard-clipping",
        checked = state.limiterEnabled,
        onChange = { viewModel.setLimiterEnabled(it) }
    )
    DspSwitchRow(
        title = "Auto headroom",
        caption = "Lower overall level by the EQ boost for zero clipping",
        checked = state.autoHeadroom,
        onChange = { viewModel.setAutoHeadroom(it) }
    )
}

@Composable
private fun DspSwitchRow(
    title: String,
    caption: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = title, style = FoamTheme.typography.titleMedium)
            BasicText(text = caption, style = FoamTheme.typography.caption)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FoamTheme.accentColor,
                checkedTrackColor = FoamTheme.accentColor.copy(alpha = 0.5f),
                uncheckedThumbColor = FoamTheme.colors.textSecondary,
                uncheckedTrackColor = FoamTheme.colors.textSecondary.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
private fun SectionCaption(text: String) {
    BasicText(
        text = text,
        style = FoamTheme.typography.caption.copy(
            color = FoamTheme.colors.textSecondary,
            fontWeight = FontWeight.Bold
        ),
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

// ------------------------------------------------------------------- volume

@Composable
private fun VolumeSection(
    state: DspState,
    viewModel: EqualizerViewModel
) {
    FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.volumePercent == 0) {
                        Icons.Rounded.VolumeOff
                    } else {
                        Icons.Rounded.VolumeUp
                    },
                    contentDescription = null,
                    tint = FoamTheme.accentColor,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            viewModel.setVolumePercent(if (state.volumePercent == 0) 100 else 0)
                        }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(text = "Volume", style = FoamTheme.typography.titleMedium)
                    BasicText(
                        text = "Direct volume control (32-bit float)",
                        style = FoamTheme.typography.caption
                    )
                }
                BasicText(
                    text = "%d%% · %.1f dB".format(
                        state.volumePercent,
                        DspMath.volumePercentToDb(state.volumePercent)
                    ),
                    style = FoamTheme.typography.monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Commit-on-release: the drag stays local, the engine sees one update.
            var dragVolume by remember(state.volumePercent) {
                mutableIntStateOf(state.volumePercent)
            }
            FoamSlider(
                value = { dragVolume / 100f },
                onValueChange = { dragVolume = (it * 100).roundToInt() },
                modifier = Modifier.onSeekBarReleased {
                    viewModel.setVolumePercent(dragVolume)
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BasicText(text = "Mute", style = FoamTheme.typography.caption)
                BasicText(text = "Full scale", style = FoamTheme.typography.caption)
            }
        }
    }
}

// -------------------------------------------------------- save preset modal

@Composable
private fun SavePresetModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    // Start each session with an empty name field.
    LaunchedEffect(visible) { if (visible) name = "" }

    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(text = "Save Preset", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { BasicText(text = "Preset name", style = FoamTheme.typography.body) },
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
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicText(text = "Cancel", style = FoamTheme.typography.body)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(FoamTheme.accentColor)
                        .clickable(enabled = name.isNotBlank()) { onSave(name) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicText(
                        text = "Save",
                        style = FoamTheme.typography.body.copy(
                            color = FoamTheme.colors.background,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ------------------------------------------------------------------- format

private fun formatDb(db: Float): String = "%+.1f dB".format(db)

private fun formatBalance(balance: Float): String = when {
    balance == 0f -> "CENTER"
    balance < 0f -> "L %.0f%%".format(-balance * 100)
    else -> "R %.0f%%".format(balance * 100)
}

internal fun frequencyDisplay(hz: Float): String =
    if (hz >= 1000f) "%.1f kHz".format(hz / 1000f) else "%.0f Hz".format(hz)

private fun frequencyToNormalized(hz: Float): Float {
    val logMin = ln(ParametricNode.MIN_FREQ_HZ.toDouble())
    val logMax = ln(ParametricNode.MAX_FREQ_HZ.toDouble())
    return ((ln(hz.toDouble()) - logMin) / (logMax - logMin)).toFloat().coerceIn(0f, 1f)
}

private fun normalizedToFrequency(normalized: Float): Float {
    val logMin = ln(ParametricNode.MIN_FREQ_HZ.toDouble())
    val logMax = ln(ParametricNode.MAX_FREQ_HZ.toDouble())
    return 10.0.pow(logMin + (logMax - logMin) * normalized.toDouble()).toFloat()
}
