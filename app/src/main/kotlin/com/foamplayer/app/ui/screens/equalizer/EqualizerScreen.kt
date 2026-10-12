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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
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
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.components.KnobControl
import com.foamplayer.app.ui.components.MarqueeText
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
 * Equalizer & DSP screen: power toggle, response mini map, graphic or
 * parametric EQ editor, tone/balance/virtualizer cards and system volume.
 *
 * Edits commit on release; graphs render from local working copies while dragging.
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

    LaunchedEffect(state.graphicEq.enabled, state.parametricEq.enabled) {
        when {
            state.parametricEq.enabled -> selectedSection = DspMath.Section.PARAMETRIC
            state.graphicEq.enabled -> selectedSection = DspMath.Section.GRAPHIC
        }
    }

    val stripScrollState = rememberScrollState()
    var stripViewportWidthPx by remember { mutableIntStateOf(0) }

    var workingGains by remember { mutableStateOf(state.graphicEq.gainsDb) }
    var workingNodes by remember { mutableStateOf(state.parametricEq.nodes) }
    LaunchedEffect(state.graphicEq.gainsDb) { workingGains = state.graphicEq.gainsDb }
    LaunchedEffect(state.parametricEq.nodes) { workingNodes = state.parametricEq.nodes }

    var bassValue by rememberSaveable { mutableFloatStateOf(state.tone.bassDb) }
    var bassApplied by rememberSaveable { mutableStateOf(state.tone.bassDb != 0f) }
    var trebleValue by rememberSaveable { mutableFloatStateOf(state.tone.trebleDb) }
    var trebleApplied by rememberSaveable { mutableStateOf(state.tone.trebleDb != 0f) }

    // A neutral engine value only means the card is off, so it is never adopted.
    LaunchedEffect(state.tone.bassDb) {
        if (state.tone.bassDb != 0f) {
            bassValue = state.tone.bassDb
            bassApplied = true
        }
    }
    LaunchedEffect(state.tone.trebleDb) {
        if (state.tone.trebleDb != 0f) {
            trebleValue = state.tone.trebleDb
            trebleApplied = true
        }
    }

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

    val miniMapToneCurves = remember(
        bassValue, bassApplied, trebleValue, trebleApplied, state.tone.midDb
    ) {
        DspMath.toneCurves(
            ToneSettings(
                bassDb = bassValue,
                midDb = state.tone.midDb,
                trebleDb = trebleValue
            )
        ).map { (band, points) ->
            MiniMapToneCurve(
                band = band,
                points = points,
                active = when (band) {
                    DspMath.ToneBand.BASS -> bassApplied
                    DspMath.ToneBand.TREBLE -> trebleApplied
                    DspMath.ToneBand.MID -> true
                }
            )
        }
    }

    val allPresets = remember(userPresets) { DspPreset.BUILT_INS + userPresets }
    val currentPreset = remember(allPresets, state) { allPresets.matching(state) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, start = 8.dp, end = 8.dp)
            .padding(bottom = LocalBottomBarClearance.current)
    ) {
        ScreenTitle(
            icon = Icons.Rounded.Equalizer,
            title = stringResource(R.string.equalizer_dsp),
            actions = {
                PowerButton(
                    enabled = state.enabled,
                    onToggle = { viewModel.setEnabled(!state.enabled) }
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        EqMiniMap(
            combinedCurve = miniMapCurve,
            nodes = if (selectedSection == DspMath.Section.PARAMETRIC) workingNodes else emptyList(),
            active = state.enabled,
            toneCurves = miniMapToneCurves,
            sectionActive = when (selectedSection) {
                DspMath.Section.GRAPHIC -> state.graphicEq.enabled
                DspMath.Section.PARAMETRIC -> state.parametricEq.enabled
            },
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

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(FoamTheme.colors.surface)
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        PresetChip(
                            currentPresetName = currentPreset?.name,
                            onClick = { showPresetsModal = true }
                        )
                    }

                    Switch(
                        checked = when (selectedSection) {
                            DspMath.Section.GRAPHIC -> state.graphicEq.enabled
                            DspMath.Section.PARAMETRIC -> state.parametricEq.enabled
                        },
                        onCheckedChange = { on ->
                            when (selectedSection) {
                                DspMath.Section.GRAPHIC -> viewModel.setGraphicEnabled(on)
                                DspMath.Section.PARAMETRIC -> viewModel.setParametricEnabled(on)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FoamTheme.accentColor,
                            checkedTrackColor = FoamTheme.accentColor.copy(alpha = 0.5f),
                            uncheckedThumbColor = FoamTheme.colors.textSecondary,
                            uncheckedTrackColor = FoamTheme.colors.textSecondary.copy(alpha = 0.4f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (selectedSection == DspMath.Section.PARAMETRIC) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
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
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

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
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        ControlsRow(
            state = state,
            viewModel = viewModel,
            bassValue = bassValue,
            onBassValueChange = { bassValue = it },
            bassApplied = bassApplied,
            onBassAppliedChange = { bassApplied = it },
            trebleValue = trebleValue,
            onTrebleValueChange = { trebleValue = it },
            trebleApplied = trebleApplied,
            onTrebleAppliedChange = { trebleApplied = it },
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
        )

        Spacer(modifier = Modifier.height(10.dp))

        VolumeSection()

        Spacer(modifier = Modifier.height(24.dp))
    }

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

    SavePresetModal(
        visible = showSavePresetModal,
        onDismiss = { showSavePresetModal = false },
        onSave = { name ->
            viewModel.saveUserPreset(name)
            showSavePresetModal = false
        }
    )
}

@Composable
private fun PowerButton(
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
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

/** The preset reflected by [state], if any. */
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

/** Pill showing the applied preset name; opens the preset browser. */
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

/** Bottom sheet listing presets with a live response preview. */
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
    val previewState = remember(preset) { preset.previewState() }
    val previewSection = when (preset.kind) {
        PresetKind.PARAMETRIC -> DspMath.Section.PARAMETRIC
        else -> DspMath.Section.GRAPHIC
    }
    val previewCurve = remember(previewState, previewSection) {
        DspMath.sectionResponse(previewState, previewSection)
    }
    val previewToneCurves = remember(previewState) {
        DspMath.toneCurves(previewState.tone).map { (band, points) ->
            MiniMapToneCurve(band = band, points = points, active = true)
        }
    }

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

/** Mirrors [FoamGlassCard]'s content padding. */
private val GlassCardPadding = 16.dp

/**
 * Knob card with an on/off switch. When [applied] is false the engine gets the
 * neutral value while the knob keeps its own. [knobSize] is the card's content width.
 */
@Composable
private fun ControlCard(
    name: String,
    value: Float,
    valueText: (Float) -> String,
    minValue: Float,
    maxValue: Float,
    defaultValue: Float,
    applied: Boolean,
    knobSize: Dp,
    onAppliedChange: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    FoamGlassCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KnobControl(
                value = value,
                onValueChange = onValueChange,
                label = null,
                valueText = valueText,
                size = knobSize,
                minValue = minValue,
                maxValue = maxValue,
                defaultValue = defaultValue,
                arcSweepDegrees = 180f,
                showNeedle = false,
                enabled = applied
            ) {
                BasicText(
                    text = valueText(value),
                    style = FoamTheme.typography.monospace.copy(
                        color = FoamTheme.colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Switch(
                checked = applied,
                onCheckedChange = onAppliedChange,
                modifier = Modifier.alpha(if (applied) 1f else 0.5f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = FoamTheme.accentColor,
                    checkedTrackColor = FoamTheme.accentColor.copy(alpha = 0.5f),
                    uncheckedThumbColor = FoamTheme.colors.textSecondary,
                    uncheckedTrackColor = FoamTheme.colors.textSecondary.copy(alpha = 0.4f)
                )
            )

            MarqueeText(
                text = name,
                style = FoamTheme.typography.caption.copy(
                    color = if (applied) FoamTheme.colors.textPrimary else FoamTheme.colors.textSecondary,
                    fontWeight = FontWeight.Bold
                ),
                mode = MarqueeMode.PING_PONG
            )
        }
    }
}

/** Scrollable row of control cards: bass, treble, balance, virtualizer. */
@Composable
private fun ControlsRow(
    state: DspState,
    viewModel: EqualizerViewModel,
    modifier: Modifier = Modifier,
    bassValue: Float,
    onBassValueChange: (Float) -> Unit,
    bassApplied: Boolean,
    onBassAppliedChange: (Boolean) -> Unit,
    trebleValue: Float,
    onTrebleValueChange: (Float) -> Unit,
    trebleApplied: Boolean,
    onTrebleAppliedChange: (Boolean) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val spacing = 10.dp
        val edgePadding = 8.dp
        val cardWidth = ((maxWidth - edgePadding * 2 - spacing * 2) / 3f) * 1.12f
        val knobSize = cardWidth - GlassCardPadding * 2

        var balanceValue by rememberSaveable { mutableFloatStateOf(state.balance) }
        var widthValue by rememberSaveable { mutableFloatStateOf(state.stereoWidth) }

        var balanceApplied by rememberSaveable { mutableStateOf(state.balance != 0f) }
        var widthApplied by rememberSaveable { mutableStateOf(state.stereoWidth != 1f) }

        LaunchedEffect(state.balance) {
            if (state.balance != 0f) {
                balanceValue = state.balance
                balanceApplied = true
            }
        }
        LaunchedEffect(state.stereoWidth) {
            if (state.stereoWidth != 1f) {
                widthValue = state.stereoWidth
                widthApplied = true
            }
        }

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
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = edgePadding),
            horizontalArrangement = Arrangement.spacedBy(spacing)
        ) {
            ControlCard(
                name = stringResource(R.string.eq_bass_boost),
                value = toneDbToPercent(bassValue),
                valueText = { formatPercent(it) },
                minValue = 0f,
                maxValue = KNOB_MAX_PERCENT,
                defaultValue = 0f,
                applied = bassApplied,
                knobSize = knobSize,
                onAppliedChange = { on ->
                    onBassAppliedChange(on)
                    viewModel.setBassDb(if (on) bassValue else 0f)
                },
                onValueChange = { percent ->
                    val db = tonePercentToDb(percent)
                    onBassValueChange(db)
                    onBassAppliedChange(true)
                    viewModel.setBassDb(db)
                },
                modifier = Modifier.width(cardWidth)
            )

            ControlCard(
                name = stringResource(R.string.eq_treble_boost),
                value = toneDbToPercent(trebleValue),
                valueText = { formatPercent(it) },
                minValue = 0f,
                maxValue = KNOB_MAX_PERCENT,
                defaultValue = 0f,
                applied = trebleApplied,
                knobSize = knobSize,
                onAppliedChange = { on ->
                    onTrebleAppliedChange(on)
                    viewModel.setTrebleDb(if (on) trebleValue else 0f)
                },
                onValueChange = { percent ->
                    val db = tonePercentToDb(percent)
                    onTrebleValueChange(db)
                    onTrebleAppliedChange(true)
                    viewModel.setTrebleDb(db)
                },
                modifier = Modifier.width(cardWidth)
            )

            ControlCard(
                name = stringResource(R.string.eq_balance),
                value = balanceValue,
                valueText = { balanceText(it) },
                minValue = -DspState.MAX_BALANCE,
                maxValue = DspState.MAX_BALANCE,
                defaultValue = 0f,
                applied = balanceApplied,
                knobSize = knobSize,
                onAppliedChange = { on ->
                    balanceApplied = on
                    viewModel.setBalance(if (on) balanceValue else 0f)
                },
                onValueChange = { balance ->
                    balanceValue = balance
                    balanceApplied = true
                    viewModel.setBalance(balance)
                },
                modifier = Modifier.width(cardWidth)
            )

            ControlCard(
                name = stringResource(R.string.eq_virtualizer),
                value = widthValue,
                valueText = { formatPercent(stereoWidthToPercent(it)) },
                minValue = 1f,
                maxValue = DspState.MAX_STEREO_WIDTH,
                defaultValue = 1f,
                applied = widthApplied,
                knobSize = knobSize,
                onAppliedChange = { on ->
                    widthApplied = on
                    viewModel.setStereoWidth(if (on) widthValue else 1f)
                },
                onValueChange = { width ->
                    widthValue = width
                    widthApplied = true
                    viewModel.setStereoWidth(width)
                },
                modifier = Modifier.width(cardWidth)
            )
        }
    }
}

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

@Composable
private fun VolumeSection() {
    FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
        SystemVolumeControl()
    }
}

/**
 * Slider bound to the music stream volume, kept in sync with hardware keys.
 * Half-step key presses are inferred from the broadcast sequence.
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
    var index by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var halfStep by remember { mutableStateOf(false) }
    var lastKnownIndex by remember { mutableIntStateOf(index) }
    var lastLocalChangeMs by remember { mutableLongStateOf(0L) }

    fun fractionOf(wholeStep: Int, half: Boolean): Float =
        ((wholeStep - if (half) 0.5f else 0f).coerceAtLeast(0f) / maxIndex).coerceIn(0f, 1f)

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
            0 -> !halfStep
            1 -> true
            else -> false
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

    // Fallback for devices that don't send the volume broadcast.
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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

        FoamSlider(
            value = { shownFraction.value },
            onValueChange = { raw ->
                val fraction = raw.coerceIn(0f, 1f)
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
                        // Refused by volume policy; the poll restores the real value.
                    }
                }
            }
        )
    }
}

private const val SYSTEM_VOLUME_POLL_MS = 250L
private const val SYSTEM_VOLUME_GLIDE_MS = 250
private const val SYSTEM_VOLUME_TAG = "SystemVolume"
private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

private const val LOCAL_CHANGE_ECHO_MS = 300L

@Composable
private fun SavePresetModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
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

private const val KNOB_MAX_PERCENT = 100f

/** Log-tapered percent to dB: 0% is neutral, 100% is [ToneSettings.MAX_DB]. */
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

/** Virtualizer knob readout: 0% = ×1 (normal), 100% = [DspState.MAX_STEREO_WIDTH]. */
private fun stereoWidthToPercent(width: Float): Float =
    (width - 1f).coerceIn(0f, 1f) * KNOB_MAX_PERCENT

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
