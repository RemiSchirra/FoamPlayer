package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Equalizer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.audio.dsp.DspMath
import com.foamplayer.app.audio.dsp.DspPreset
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.audio.dsp.FilterType
import com.foamplayer.app.audio.dsp.GraphicEqSettings
import com.foamplayer.app.audio.dsp.NodeColorPalette
import com.foamplayer.app.audio.dsp.ParametricEqSettings
import com.foamplayer.app.audio.dsp.ParametricNode
import com.foamplayer.app.audio.dsp.PresetKind
import com.foamplayer.app.audio.dsp.ToneSettings
import com.foamplayer.app.ui.modals.FoamBottomModal
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.components.KnobControl
import com.foamplayer.app.ui.components.ScreenTitle
import com.foamplayer.app.ui.components.VerticalSlider
import com.foamplayer.app.ui.components.onSeekBarReleased
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Poweramp-style Equalizer & DSP screen:
 *  - power toggle + response mini map with a translucent white viewport overlay
 *    that scrolls the graphic EQ (per-node colored curves in parametric mode,
 *    separated bass/mid/treble tone curves in both modes),
 *  - a presets entry card opening a FoamModal with response previews for every
 *    preset, plus create/delete of user presets,
 *  - Graphic (21 horizontally scrollable band faders) / Parametric (draggable
 *    colored nodes) sections — mutually exclusive: enabling one disables the other,
 *  - rotary tone knobs — pre-amp in dB; bass/mid/treble boost-only, reading
 *    0-100% (log-tapered to 0..+12 dB, no negative dB) — and control knobs
 *    (balance, stereo width 0-100% = ×1..×2),
 *  - direct volume control slider with dB readout.
 *
 * All controls commit on release so a drag never floods the DSP engine with state
 * emissions; the graphs and the mini map render from local working copies in the
 * meantime, so they follow the finger while the engine sees one update.
 *
 * The DSP chain is purely linear (industry-standard EQ): the former Limiter and
 * Auto-headroom switches are gone, boosts are heard exactly as the curve shows.
 */
@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel = hiltViewModel()
) {
    val state by viewModel.dspState.collectAsState()
    val userPresets by viewModel.userPresets.collectAsState()

    var selectedSection by remember { mutableStateOf(DspMath.Section.GRAPHIC) }
    var selectedNodeId by remember { mutableStateOf<Long?>(null) }
    var showPresetsModal by remember { mutableStateOf(false) }
    var showSavePresetModal by remember { mutableStateOf(false) }

    // The displayed EQ section is defined by the preset/state, not by tabs:
    // applying a parametric preset switches to the parametric editor, applying
    // a graphic preset switches back. With both sections off, the last shown
    // section stays.
    LaunchedEffect(state.graphicEq.enabled, state.parametricEq.enabled) {
        when {
            state.parametricEq.enabled -> selectedSection = DspMath.Section.PARAMETRIC
            state.graphicEq.enabled -> selectedSection = DspMath.Section.GRAPHIC
        }
    }

    // Scroll state of the graphic strip, shared with the mini map's viewport overlay.
    val stripScrollState = rememberScrollState()
    var stripViewportWidthPx by remember { mutableIntStateOf(0) }

    // Working copies of the sections being edited: the graphs and the mini map
    // render from these so a drag is visible immediately; the engine is updated
    // once per commit.
    var workingGains by remember { mutableStateOf(state.graphicEq.gainsDb) }
    var workingNodes by remember { mutableStateOf(state.parametricEq.nodes) }
    // Re-sync when the engine state changes externally (preset applied, node
    // added/removed, section toggled). Rare, so a mid-drag override is acceptable.
    LaunchedEffect(state.graphicEq.gainsDb) { workingGains = state.graphicEq.gainsDb }
    LaunchedEffect(state.parametricEq.nodes) { workingNodes = state.parametricEq.nodes }

    // Live mini-map curve: ONLY the active EQ section — tone never mixes into it,
    // its shelves are drawn as their own separated curves alongside (see
    // [toneCurves]). The section under edit renders from the working copies
    // (graphic gains / parametric nodes) so a drag is visible immediately; the
    // graphic preview stays visible even while its section is toggled off,
    // matching the always-editable fader strip.
    val miniMapCurve = remember(selectedSection, state, workingGains, workingNodes) {
        when (selectedSection) {
            DspMath.Section.GRAPHIC -> DspMath.sectionResponse(
                state.copy(
                    graphicEq = state.graphicEq.copy(enabled = true, gainsDb = workingGains)
                ),
                DspMath.Section.GRAPHIC
            )
            DspMath.Section.PARAMETRIC -> DspMath.sectionResponse(
                state.copy(parametricEq = state.parametricEq.copy(nodes = workingNodes)),
                DspMath.Section.PARAMETRIC
            )
        }
    }

    // Separated per-tone curves: bass, mid and treble each drawn in their own
    // color next to the section curve — overlays, never summed into it.
    val toneCurves = remember(state.tone) { DspMath.toneCurves(state.tone) }

    // Band selector state: which count chip is active in the strip header.
    var showBandPicker by remember { mutableStateOf(false) }

    val allPresets = remember(userPresets) { DspPreset.BUILT_INS + userPresets }
    val currentPreset = remember(allPresets, state) { allPresets.matching(state) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, start = 8.dp, end = 8.dp)
            .padding(bottom = LocalBottomBarClearance.current)
    ) {
        // ------------------------------------------------------------ header
        ScreenTitle(
            icon = Icons.Rounded.Equalizer,
            title = "Equalizer & DSP",
            subtitle = {
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
            },
            actions = {
                PowerButton(
                    enabled = state.enabled,
                    onToggle = { viewModel.setEnabled(!state.enabled) }
                )
            }
        )

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
                    EqMiniMap(
                        combinedCurve = miniMapCurve,
                        nodes = if (selectedSection == DspMath.Section.PARAMETRIC) workingNodes else emptyList(),
                        active = state.enabled,
                        toneCurves = toneCurves,
                        showViewport = selectedSection == DspMath.Section.GRAPHIC,
                        scrollState = stripScrollState,
                        stripViewportWidthPx = stripViewportWidthPx,
                        combinedCurveBandCount = workingGains.size,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(horizontalAlignment = Alignment.End) {
                    MiniMapValue("PRE-AMP", formatDb(state.preampDb))
                    MiniMapValue("BASS", formatTonePercent(state.tone.bassDb))
                    MiniMapValue("TREBLE", formatTonePercent(state.tone.trebleDb))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ----------------------------------------------------------- presets
        PresetsEntryCard(
            currentPresetName = currentPreset?.name,
            onClick = { showPresetsModal = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------- active EQ section header
        // (defined by the applied preset's kind — graphic or parametric, never both)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = when (selectedSection) {
                    DspMath.Section.GRAPHIC -> "${state.graphicEq.count}-band graphic equalizer"
                    DspMath.Section.PARAMETRIC ->
                        "Parametric equalizer · ${workingNodes.size}/${ParametricEqSettings.MAX_NODES} nodes"
                },
                style = FoamTheme.typography.caption,
                modifier = Modifier.weight(1f)
            )

            // Band-count selector, only in graphic mode (Poweramp-style layouts).
            if (selectedSection == DspMath.Section.GRAPHIC) {
                Box {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(FoamTheme.colors.surfaceVariant)
                            .clickable { showBandPicker = !showBandPicker }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(
                                text = "${state.graphicEq.count} BANDS",
                                style = FoamTheme.typography.caption.copy(
                                    color = FoamTheme.accentColor,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showBandPicker,
                        onDismissRequest = { showBandPicker = false }
                    ) {
                        GraphicEqSettings.UI_BAND_COUNTS.forEach { count ->
                            DropdownMenuItem(
                                onClick = {
                                    viewModel.setGraphicBandCount(count)
                                    showBandPicker = false
                                }
                            ) {
                                BasicText(
                                    text = "$count bands",
                                    style = FoamTheme.typography.caption.copy(
                                        color = if (count == state.graphicEq.count) {
                                            FoamTheme.accentColor
                                        } else {
                                            FoamTheme.colors.textPrimary
                                        }
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))
            }

            // Quick add-node action, only in parametric mode.
            if (selectedSection == DspMath.Section.PARAMETRIC) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (workingNodes.size < ParametricEqSettings.MAX_NODES) {
                                FoamTheme.colors.surfaceVariant
                            } else {
                                FoamTheme.colors.surfaceVariant.copy(alpha = 0.4f)
                            }
                        )
                        .clickable(
                            enabled = workingNodes.size < ParametricEqSettings.MAX_NODES
                        ) {
                            val id = viewModel.addParametricNode()
                            selectedNodeId = id
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = FoamTheme.accentColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        BasicText(
                            text = "NODE",
                            style = FoamTheme.typography.caption.copy(
                                color = FoamTheme.accentColor,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))
            }

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

        // --------------------------- preamp fader + section graph (Poweramp layout)
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

            when (selectedSection) {
                DspMath.Section.GRAPHIC -> GraphicEqStrip(
                    gains = workingGains,
                    enabled = state.graphicEq.enabled,
                    scrollState = stripScrollState,
                    onPreview = { index, gain ->
                        workingGains = workingGains
                            .toMutableList()
                            .also { it[index] = gain }
                    },
                    onCommit = { viewModel.setGraphicGains(workingGains) },
                    onViewportWidthChanged = { stripViewportWidthPx = it },
                    modifier = Modifier.weight(1f)
                )
                DspMath.Section.PARAMETRIC -> EqualizerGraph(
                    state = state,
                    nodes = workingNodes,
                    enabled = state.parametricEq.enabled,
                    selectedNodeId = selectedNodeId,
                    onNodeSelect = { selectedNodeId = it },
                    onNodesChange = { workingNodes = it },
                    onNodeCommit = { node -> viewModel.updateParametricNode(node) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ------------------------------------------- parametric node editor
        if (selectedSection == DspMath.Section.PARAMETRIC) {
            Spacer(modifier = Modifier.height(8.dp))
            val selectedNode = workingNodes.firstOrNull { it.id == selectedNodeId }
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
                    canAdd = workingNodes.size < ParametricEqSettings.MAX_NODES,
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

    // -------------------------------------------------------- presets modal
    PresetsModal(
        visible = showPresetsModal,
        presets = allPresets,
        state = state,
        onApply = { preset ->
            viewModel.applyPreset(preset)
            showPresetsModal = false
        },
        onDelete = { preset -> viewModel.deleteUserPreset(preset.name) },
        onSaveAsNew = {
            showPresetsModal = false
            showSavePresetModal = true
        },
        onDismiss = { showPresetsModal = false }
    )

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

/** The DspPreset currently reflected by [state], if any. */
private fun List<DspPreset>.matching(state: DspState): DspPreset? = firstOrNull { preset ->
    when {
        preset.fullState != null ->
            preset.fullState.copy(volumePercent = state.volumePercent) == state
        preset.kind == PresetKind.GRAPHIC ->
            state.graphicEq.enabled && state.graphicEq.gainsDb == preset.graphicGainsDb
        else -> false
    }
}

@Composable
private fun PresetsEntryCard(
    currentPresetName: String?,
    onClick: () -> Unit
) {
    FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Equalizer,
                contentDescription = null,
                tint = FoamTheme.accentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                BasicText(text = "Presets", style = FoamTheme.typography.titleMedium)
                BasicText(
                    text = currentPresetName ?: "Custom · tap to browse & save",
                    style = FoamTheme.typography.caption
                )
            }
            BasicText(
                text = "VIEW ALL",
                style = FoamTheme.typography.caption.copy(
                    color = FoamTheme.accentColor,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

/**
 * Preset browser in a bottom sheet: every preset gets a live response preview
 * (section curve in accent, per-node and per-tone curves in their colors).
 * Built-ins can only be applied; user presets can also be deleted.
 */
@Composable
private fun PresetsModal(
    visible: Boolean,
    presets: List<DspPreset>,
    state: DspState,
    onApply: (DspPreset) -> Unit,
    onDelete: (DspPreset) -> Unit,
    onSaveAsNew: () -> Unit,
    onDismiss: () -> Unit
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(text = "Presets", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            BasicText(
                text = "A preset drives either the graphic or the parametric EQ",
                style = FoamTheme.typography.caption
            )
            Spacer(modifier = Modifier.height(12.dp))

            presets.forEach { preset ->
                PresetRow(
                    preset = preset,
                    selected = presets.matching(state) == preset,
                    onApply = { onApply(preset) },
                    onDelete = { onDelete(preset) }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Save the current chain as a new user preset.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(FoamTheme.colors.surfaceVariant)
                    .clickable(onClick = onSaveAsNew)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.BookmarkAdd,
                        contentDescription = null,
                        tint = FoamTheme.accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    BasicText(
                        text = "Save current as new preset",
                        style = FoamTheme.typography.caption.copy(
                            color = FoamTheme.accentColor,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PresetRow(
    preset: DspPreset,
    selected: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    // One response sample per preset, computed once per modal content: the
    // preset's own EQ section only — its tone (if any) is drawn separately.
    val previewState = remember(preset) { preset.previewState() }
    val previewSection = when (preset.kind) {
        PresetKind.PARAMETRIC -> DspMath.Section.PARAMETRIC
        else -> DspMath.Section.GRAPHIC
    }
    val previewCurve = remember(previewState, previewSection) {
        DspMath.sectionResponse(previewState, previewSection)
    }
    val previewToneCurves = remember(previewState) { DspMath.toneCurves(previewState.tone) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) FoamTheme.accentColor.copy(alpha = 0.16f) else FoamTheme.colors.surfaceVariant)
            .clickable(onClick = onApply)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EqMiniMap(
            combinedCurve = previewCurve,
            nodes = previewState.parametricEq.nodes,
            active = true,
            toneCurves = previewToneCurves,
            modifier = Modifier
                .width(72.dp)
                .height(36.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = preset.name,
                style = FoamTheme.typography.body.copy(
                    color = FoamTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            )
            BasicText(
                text = if (preset.isBuiltIn) {
                    "${if (preset.kind == PresetKind.PARAMETRIC) "PARAMETRIC" else "GRAPHIC"} · built-in"
                } else {
                    "${if (preset.kind == PresetKind.PARAMETRIC) "PARAMETRIC" else "GRAPHIC"} · user"
                },
                style = FoamTheme.typography.caption.copy(
                    color = if (preset.kind == PresetKind.PARAMETRIC) {
                        FoamTheme.accentColor
                    } else {
                        FoamTheme.colors.textSecondary
                    },
                    fontWeight = FontWeight.Bold
                )
            )
        }

        if (!preset.isBuiltIn) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete preset ${preset.name}",
                    tint = FoamTheme.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// --------------------------------------------------------------- mode tabs

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
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(node.colorArgb))
                )
                Spacer(modifier = Modifier.width(8.dp))
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

            NodeColorPicker(node = node, onUpdate = onUpdate)

            Spacer(modifier = Modifier.height(12.dp))

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
private fun NodeColorPicker(
    node: ParametricNode,
    onUpdate: (ParametricNode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = "COLOR",
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
        )
        NodeColorPalette.forEach { colorArgb ->
            val selected = node.colorArgb == colorArgb
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(colorArgb))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) {
                            FoamTheme.colors.textPrimary
                        } else {
                            FoamTheme.colors.borderOverlay
                        },
                        shape = CircleShape
                    )
                    .clickable { onUpdate(node.copy(colorArgb = colorArgb)) }
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
                ToneKnob(
                    label = "BASS",
                    db = state.tone.bassDb,
                    onDbChange = { viewModel.setBassDb(it) }
                )
                ToneKnob(
                    label = "MID",
                    db = state.tone.midDb,
                    onDbChange = { viewModel.setMidDb(it) }
                )
                ToneKnob(
                    label = "TREBLE",
                    db = state.tone.trebleDb,
                    onDbChange = { viewModel.setTrebleDb(it) }
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
                    value = stereoWidthToPercent(state.stereoWidth),
                    onValueChange = { percent ->
                        viewModel.setStereoWidth(stereoPercentToWidth(percent))
                    },
                    label = "STEREO",
                    valueText = { formatPercent(it) },
                    minValue = 0f,
                    maxValue = KNOB_MAX_PERCENT
                )
            }
        }
    }
}

/**
 * Boost-only tone knob (bass / mid / treble): reads 0-100% instead of dB.
 * 0% is 0 dB (neutral, needle at the arc's start), 100% is the full +12 dB
 * boost; the percent is log-tapered into dB by [tonePercentToDb] so the
 * commonly used small boosts get most of the knob's travel. Double-tap snaps
 * back to 0% / 0 dB.
 */
@Composable
private fun ToneKnob(
    label: String,
    db: Float,
    onDbChange: (Float) -> Unit
) {
    KnobControl(
        value = toneDbToPercent(db),
        onValueChange = { percent -> onDbChange(tonePercentToDb(percent)) },
        label = label,
        valueText = { formatPercent(it) },
        minValue = 0f,
        maxValue = KNOB_MAX_PERCENT
    )
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

// ---------------------------------------------------- tone/stereo knob maps

/** Every percent-style knob reads 0..[KNOB_MAX_PERCENT]. */
private const val KNOB_MAX_PERCENT = 100f

/**
 * Log-tapered percent → dB for the tone knobs: dB = MAX · log10(1 + 9·x) with
 * x = percent/100. 0% maps to 0 dB (neutral) and 100% to [ToneSettings.MAX_DB];
 * the curve rises fast at first (10% ≈ 3.3 dB, 25% ≈ 6.1 dB, 50% ≈ 8.9 dB) so
 * fine control lives in the small-boost region and no negative dB exists
 * anywhere on the dial.
 */
private fun tonePercentToDb(percent: Float): Float {
    val x = (percent / KNOB_MAX_PERCENT).coerceIn(0f, 1f)
    return ToneSettings.MAX_DB * log10(1f + 9f * x)
}

/** Inverse of [tonePercentToDb], clamped to the knob's 0-100% range. */
private fun toneDbToPercent(db: Float): Float {
    val clamped = db.coerceIn(0f, ToneSettings.MAX_DB)
    val x = ((10.0.pow((clamped / ToneSettings.MAX_DB).toDouble()) - 1.0) / 9.0).toFloat()
    return x * KNOB_MAX_PERCENT
}

/** Stereo width knob: 0% = ×1 (normal), 100% = [DspState.MAX_STEREO_WIDTH]. */
private fun stereoPercentToWidth(percent: Float): Float =
    1f + (percent / KNOB_MAX_PERCENT).coerceIn(0f, 1f)

/** Inverse of [stereoPercentToWidth], clamped to the knob's 0-100% range. */
private fun stereoWidthToPercent(width: Float): Float =
    (width - 1f).coerceIn(0f, 1f) * KNOB_MAX_PERCENT

// ------------------------------------------------------------------- format

private fun formatDb(db: Float): String = "%+.1f dB".format(db)

private fun formatPercent(percent: Float): String = "%.0f%%".format(percent)

/** Mini-map readout for the tone knobs: boost percent of [ToneSettings.MAX_DB]. */
private fun formatTonePercent(db: Float): String = formatPercent(toneDbToPercent(db))

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
