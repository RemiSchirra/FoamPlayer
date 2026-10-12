package com.foamplayer.app.ui.screens.equalizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.foamplayer.app.R
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
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.components.KnobControl
import com.foamplayer.app.ui.components.ScreenTitle
import com.foamplayer.app.ui.components.VerticalSlider
import com.foamplayer.app.ui.components.onSeekBarReleased
import com.foamplayer.app.ui.modals.FoamBottomModal
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
 *  - a preset chip showing the currently applied preset; tapping it opens a
 *    FoamModal with response previews for every preset, plus create/delete of
 *    user presets,
 *  - Graphic (ISO band layout, chosen in Settings > Audio > Equalizer;
 *    horizontally scrollable band faders) / Parametric (draggable colored
 *    nodes) sections — mutually exclusive: enabling one disables the other,
 *  - rotary tone knobs — pre-amp in dB; bass/mid/treble boost-only, reading
 *    0-100% (log-tapered to 0..+12 dB, no negative dB) — and control knobs
 *    (balance, stereo width 0-100% = ×1..×2),
 *  - direct volume control slider with dB readout.
 *
 * All controls commit on release so a drag never floods the DSP engine with state
 * emissions; the graphs and the mini map render from local working copies in the
 * meantime, so they follow the finger while the engine sees one update. The
 * pre-amp fader is the exception: it streams into the engine live while dragging
 * (it is a single scalar, and the engine's persistence is debounced).
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
    // once per commit. The pre-amp streams live instead (no working copy).
    var workingGains by remember { mutableStateOf(state.graphicEq.gainsDb) }
    var workingNodes by remember { mutableStateOf(state.parametricEq.nodes) }
    // Re-sync when the engine state changes externally (preset applied, node
    // added/removed, section toggled, band layout changed in settings). Rare, so
    // a mid-drag override is acceptable.
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
            title = stringResource(R.string.equalizer_dsp),
            subtitle = {
                BasicText(
                    text = when {
                        state.isNeutral -> stringResource(R.string.eq_bit_perfect_passthrough)
                        else -> stringResource(R.string.eq_float_processing)
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
        // Full-bleed: the screen column pads 8.dp on each side, so the map is
        // measured 16.dp wider than its constraints and shifted left by 8.dp to
        // touch both screen edges.
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
                .layout { measurable, constraints ->
                    val bleed = 8.dp.roundToPx()
                    val fullWidth = constraints.maxWidth + bleed * 2
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = fullWidth, maxWidth = fullWidth)
                    )
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.place(-bleed, 0)
                    }
                }
                .height(72.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // preset chip
        PresetChip(
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
                    DspMath.Section.GRAPHIC -> stringResource(R.string.eq_graphic_title, state.graphicEq.count)
                    DspMath.Section.PARAMETRIC -> stringResource(
                        R.string.eq_parametric_title,
                        workingNodes.size,
                        ParametricEqSettings.MAX_NODES
                    )
                },
                style = FoamTheme.typography.caption,
                modifier = Modifier.weight(1f)
            )

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
                            text = stringResource(R.string.eq_node),
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

        // preamp fader + section graph
        Row(modifier = Modifier.fillMaxWidth()) {
            VerticalSlider(
                value = state.preampDb,
                onCommit = { viewModel.setPreampDb(it) },
                onValueChange = { viewModel.setPreampDb(it) },
                valueRange = DspState.MIN_PREAMP_DB..DspState.MAX_PREAMP_DB,
                label = stringResource(R.string.eq_preamp),
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

        // parametric node editor
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

        // knobs
        KnobsSection(state = state, viewModel = viewModel)

        Spacer(modifier = Modifier.height(16.dp))

        // volume
        VolumeSection(state = state, viewModel = viewModel)

        Spacer(modifier = Modifier.height(24.dp))
    }

    // presets modal
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

    // save preset modal
    SavePresetModal(
        visible = showSavePresetModal,
        onDismiss = { showSavePresetModal = false },
        onSave = { name ->
            viewModel.saveUserPreset(name)
            showSavePresetModal = false
        }
    )
}

// header

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
            contentDescription = stringResource(
                if (enabled) R.string.disable_dsp else R.string.enable_dsp
            ),
            tint = if (enabled) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
            modifier = Modifier.size(24.dp)
        )
    }
}

// presets

/**
 * The DspPreset currently reflected by [state], if any. User presets are
 * compared on their full state; built-in graphic presets on their resampled
 * gains, so a preset matches whatever band layout the user currently has.
 */
private fun List<DspPreset>.matching(state: DspState): DspPreset? = firstOrNull { preset ->
    when {
        preset.fullState != null ->
            preset.fullState.copy(volumePercent = state.volumePercent) == state
        preset.kind == PresetKind.GRAPHIC ->
            state.graphicEq.enabled &&
                    state.preampDb == preset.preampDb &&
                    GraphicEqSettings.resampleGains(preset.graphicGainsDb, state.graphicEq.count) ==
                    state.graphicEq.gainsDb
        else -> false
    }
}

/**
 * The preset selector: a single pill chip, centered on the screen, showing the
 * preset currently applied to the engine ("Custom" when the state matches
 * none); tapping it opens the preset browser ([PresetsModal]). The chip is
 * clipped to its pill shape first, so the bounded ripple follows the outline.
 */
@Composable
private fun PresetChip(
    currentPresetName: String?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(FoamTheme.colors.surfaceVariant)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = FoamTheme.colors.textPrimary),
                    onClick = onClick
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            BasicText(
                text = currentPresetName ?: stringResource(R.string.eq_preset_custom_hint),
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
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
            BasicText(text = stringResource(R.string.eq_presets), style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            BasicText(
                text = stringResource(R.string.eq_presets_desc),
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
                        text = stringResource(R.string.eq_save_as_new),
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

    val kindLabel = stringResource(
        if (preset.kind == PresetKind.PARAMETRIC) R.string.eq_preset_kind_parametric
        else R.string.eq_preset_kind_graphic
    )
    val originLabel = stringResource(
        if (preset.isBuiltIn) R.string.eq_preset_built_in else R.string.eq_preset_user
    )
    val deletePresetDescription = stringResource(R.string.eq_delete_preset, preset.name)

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
                text = "$kindLabel · $originLabel",
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
                    contentDescription = deletePresetDescription,
                    tint = FoamTheme.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// mode tabs

@Composable
private fun SectionPowerToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = stringResource(if (enabled) R.string.on else R.string.off),
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

// node editor panel

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
                    text = stringResource(R.string.eq_node_id, node.id),
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
                        contentDescription = stringResource(R.string.eq_delete_node),
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
                label = stringResource(R.string.eq_freq),
                valueText = frequencyDisplay(node.frequencyHz),
                value = frequencyToNormalized(node.frequencyHz),
                onCommit = { normalized ->
                    onUpdate(node.copy(frequencyHz = normalizedToFrequency(normalized)))
                }
            )

            if (node.type.gainAdjustable) {
                NodeSlider(
                    label = stringResource(R.string.eq_gain),
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
                label = stringResource(R.string.eq_q),
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
            text = stringResource(R.string.eq_color),
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
            text = stringResource(if (canAdd) R.string.eq_add_node else R.string.eq_node_limit),
            style = FoamTheme.typography.caption.copy(
                color = if (canAdd) FoamTheme.accentColor else FoamTheme.colors.textSecondary
            )
        )
    }
}

// knobs

@Composable
private fun KnobsSection(
    state: DspState,
    viewModel: EqualizerViewModel
) {
    // Balance readout labels are plain resources: resolve them once so the
    // non-composable valueText lambda can format the live value with them.
    val balanceCenter = stringResource(R.string.eq_balance_center)
    val balanceLeft = stringResource(R.string.eq_balance_left)
    val balanceRight = stringResource(R.string.eq_balance_right)
    fun balanceText(balance: Float): String = when {
        balance == 0f -> balanceCenter
        balance < 0f -> balanceLeft.format(-balance * 100)
        else -> balanceRight.format(balance * 100)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            SectionCaption(stringResource(R.string.eq_tone))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToneKnob(
                    label = stringResource(R.string.eq_bass),
                    db = state.tone.bassDb,
                    onDbChange = { viewModel.setBassDb(it) }
                )
                ToneKnob(
                    label = stringResource(R.string.eq_mid),
                    db = state.tone.midDb,
                    onDbChange = { viewModel.setMidDb(it) }
                )
                ToneKnob(
                    label = stringResource(R.string.eq_treble),
                    db = state.tone.trebleDb,
                    onDbChange = { viewModel.setTrebleDb(it) }
                )
            }
        }

        Column {
            SectionCaption(stringResource(R.string.eq_controls))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KnobControl(
                    value = state.balance,
                    onValueChange = { viewModel.setBalance(it) },
                    label = stringResource(R.string.eq_balance),
                    valueText = { balanceText(it) },
                    minValue = -DspState.MAX_BALANCE,
                    maxValue = DspState.MAX_BALANCE
                )
                KnobControl(
                    value = stereoWidthToPercent(state.stereoWidth),
                    onValueChange = { percent ->
                        viewModel.setStereoWidth(stereoPercentToWidth(percent))
                    },
                    label = stringResource(R.string.eq_stereo),
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

// volume

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
                    BasicText(text = stringResource(R.string.eq_volume), style = FoamTheme.typography.titleMedium)
                    BasicText(
                        text = stringResource(R.string.eq_volume_desc),
                        style = FoamTheme.typography.caption
                    )
                }
                BasicText(
                    text = stringResource(
                        R.string.eq_volume_readout,
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
                BasicText(text = stringResource(R.string.eq_mute), style = FoamTheme.typography.caption)
                BasicText(text = stringResource(R.string.eq_full_scale), style = FoamTheme.typography.caption)
            }

            Spacer(modifier = Modifier.height(16.dp))

            SystemVolumeControl()
        }
    }
}

/**
 * Android media-stream (STREAM_MUSIC) volume, the same value the phone's
 * hardware +/- keys change. The slider is continuous over the stream's range
 * (the readout is that position as a percentage); the stream itself snaps to
 * its nearest step, and every step change is written to the [AudioManager]
 * immediately while dragging.
 *
 * Hardware presses can move the real volume by less than one whole step, while
 * [AudioManager.getStreamVolume] only returns the rounded whole step (the
 * volume broadcast still fires, with the same rounded value). To follow every
 * press anyway, the half-step position is reconstructed from the broadcast
 * sequence: with presses of half a step, a rounded value that changes by +1
 * means the real value is now half a step below it, -1 means exactly on it,
 * and an unchanged value means the press toggled between those two spots.
 * Any whole-step change re-derives this, so a wrong guess corrects itself.
 */
@Composable
private fun SystemVolumeControl() {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxIndex = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    // Rounded whole step as Android reports it, plus whether the real volume
    // currently sits half a step below it.
    var index by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var halfStep by remember { mutableStateOf(false) }
    // The rounded value as of the last processed broadcast / local write, used
    // to classify the next broadcast independently of polling.
    var lastKnownIndex by remember { mutableIntStateOf(index) }
    // Our own writes echo back as broadcasts; ignore those briefly.
    var lastLocalChangeMs by remember { mutableLongStateOf(0L) }

    fun fractionOf(wholeStep: Int, half: Boolean): Float =
        ((wholeStep - if (half) 0.5f else 0f).coerceAtLeast(0f) / maxIndex).coerceIn(0f, 1f)

    // What the slider and readout show: a continuous 0..1 fraction, not rounded
    // to steps or whole percents (like Android's own panel, whose thumb follows
    // the finger smoothly). The stream itself can only sit on its own steps, so
    // while the user drags this may sit between them; any external change
    // resyncs it to the true volume. Finger input snaps it (no lag behind the
    // thumb); changes coming from Android (hardware keys, other apps) glide.
    val scope = rememberCoroutineScope()
    val shownFraction = remember { Animatable(fractionOf(index, false)) }
    fun showFromSystem(newFraction: Float) {
        scope.launch {
            shownFraction.animateTo(
                newFraction,
                tween(durationMillis = SYSTEM_VOLUME_GLIDE_MS, easing = FastOutSlowInEasing)
            )
        }
    }

    fun onMusicVolumeBroadcast() {
        if (SystemClock.elapsedRealtime() - lastLocalChangeMs <= LOCAL_CHANGE_ECHO_MS) return
        val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxIndex)
        halfStep = when (actual - lastKnownIndex) {
            0 -> !halfStep   // a sub-step press: the rounded value didn't move
            1 -> true
            else -> false    // -1 lands exactly on the whole step; larger jumps are not key presses
        }
        Log.d(SYSTEM_VOLUME_TAG, "broadcast: $lastKnownIndex -> $actual, half=$halfStep")
        lastKnownIndex = actual
        index = actual
        showFromSystem(fractionOf(actual, halfStep))
    }

    DisposableEffect(audioManager, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                onMusicVolumeBroadcast()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Fallback for devices that don't send the broadcast: adopt a differing
    // system value only if it persists across two polls (a broadcast, when it
    // exists, arrives within milliseconds and wins).
    LaunchedEffect(audioManager) {
        var mismatches = 0
        while (true) {
            delay(SYSTEM_VOLUME_POLL_MS)
            val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxIndex)
            val settled = SystemClock.elapsedRealtime() - lastLocalChangeMs > LOCAL_CHANGE_ECHO_MS
            if (actual != index && settled) {
                if (++mismatches >= 2) {
                    index = actual
                    lastKnownIndex = actual
                    halfStep = false
                    showFromSystem(fractionOf(actual, false))
                    mismatches = 0
                }
            } else {
                mismatches = 0
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.PhoneAndroid,
            contentDescription = null,
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        BasicText(
            text = stringResource(R.string.eq_volume),
            style = FoamTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        BasicText(
            text = formatPercent((shownFraction.value * 100f).roundToInt().toFloat()),
            style = FoamTheme.typography.monospace
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    FoamSlider(
        value = { shownFraction.value },
        onValueChange = { raw ->
            val fraction = raw.coerceIn(0f, 1f)
            // Finger input: the thumb follows continuously, cancelling any glide in progress.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                shownFraction.snapTo(fraction)
            }
            val newIndex = (fraction * maxIndex).roundToInt().coerceIn(0, maxIndex)
            if (newIndex != index || halfStep) {
                index = newIndex
                halfStep = false
                lastKnownIndex = newIndex
                lastLocalChangeMs = SystemClock.elapsedRealtime()
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newIndex, 0)
                } catch (_: SecurityException) {
                    // Volume policy refused the change; the fallback poll restores the real value.
                }
            }
        }
    )
}

private const val SYSTEM_VOLUME_POLL_MS = 250L
private const val SYSTEM_VOLUME_GLIDE_MS = 250
private const val SYSTEM_VOLUME_TAG = "SystemVolume"
private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

/** Window after a local volume write during which system change callbacks are treated as echoes. */
private const val LOCAL_CHANGE_ECHO_MS = 300L

// save preset modal

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
            BasicText(text = stringResource(R.string.eq_save_preset_title), style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = {
                    BasicText(
                        text = stringResource(R.string.eq_preset_name_hint),
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
                    BasicText(text = stringResource(R.string.cancel), style = FoamTheme.typography.body)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(FoamTheme.accentColor)
                        .clickable(enabled = name.isNotBlank()) { onSave(name) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicText(
                        text = stringResource(R.string.save),
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

// tone/stereo knob maps

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

// format

private fun formatDb(db: Float): String = "%+.1f dB".format(db)

private fun formatPercent(percent: Float): String = "%.0f%%".format(percent)

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
