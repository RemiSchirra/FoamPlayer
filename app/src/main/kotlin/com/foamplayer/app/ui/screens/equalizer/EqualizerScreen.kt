package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import com.foamplayer.app.R
import com.foamplayer.app.audio.dsp.DspMath
import com.foamplayer.app.audio.dsp.DspPreset
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.audio.dsp.GraphicEqSettings
import com.foamplayer.app.audio.dsp.ParametricEqSettings
import com.foamplayer.app.audio.dsp.ParametricNode
import com.foamplayer.app.audio.dsp.PresetKind
import com.foamplayer.app.audio.dsp.ToneSettings
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.components.FoamSwitch
import com.foamplayer.app.ui.components.KnobControl
import com.foamplayer.app.ui.components.MarqueeText
import com.foamplayer.app.ui.components.ScreenTitle
import com.foamplayer.app.ui.components.SystemVolumeControl
import com.foamplayer.app.ui.components.VerticalFoamSlider
import com.foamplayer.app.ui.components.VerticalSlider
import com.foamplayer.app.ui.modals.FoamBottomModal
import com.foamplayer.app.ui.modals.ModalButton
import com.foamplayer.app.ui.modals.PlaylistNameField
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurface
import com.foamplayer.app.ui.theme.foamSurfaceVariant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Equalizer & DSP screen: power toggle, response mini map, graphic or
 * parametric EQ editor, tone/balance/virtualizer cards and system volume.
 *
 * Edits commit on release; graphs render from local working copies while dragging.
 * Preset switches are eased on screen only — the engine applies them instantly.
 */
@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel = hiltViewModel()
) {
    val state by viewModel.dspState.collectAsState()
    val userPresets by viewModel.userPresets.collectAsState()
    val playerState by viewModel.playbackState.collectAsState()
    val currentPlaylistName by viewModel.currentPlaylistName.collectAsState()
    val assignments by viewModel.assignments.collectAsState()

    var selectedSection by remember { mutableStateOf(DspMath.Section.GRAPHIC) }
    var showPresetsModal by remember { mutableStateOf(false) }
    var showSavePresetModal by remember { mutableStateOf(false) }
    var showPresetMenuModal by remember { mutableStateOf(false) }
    var showRenamePresetModal by remember { mutableStateOf(false) }
    var showAssignPresetModal by remember { mutableStateOf(false) }

    LaunchedEffect(state.graphicEq.enabled, state.parametricEq.enabled) {
        when {
            state.parametricEq.enabled -> selectedSection = DspMath.Section.PARAMETRIC
            state.graphicEq.enabled -> selectedSection = DspMath.Section.GRAPHIC
        }
    }

    val stripScrollState = rememberScrollState()
    val nodesScrollState = rememberScrollState()
    var stripViewportWidthPx by remember { mutableIntStateOf(0) }

    var workingGains by remember { mutableStateOf(state.graphicEq.gainsDb) }
    var workingNodes by remember { mutableStateOf(state.parametricEq.nodes) }

    // Preset changes hit the engine instantly; these eased copies are purely
    // visual, so the faders, graph and mini map morph into the new shape instead
    // of snapping. Live drags snap them (see below) to stay under the finger.
    val displayedGains = remember { TweenedFloats(state.graphicEq.gainsDb) }
    val displayedNodes = remember { TweenedNodes(state.parametricEq.nodes) }
    LaunchedEffect(state.graphicEq.gainsDb) {
        workingGains = state.graphicEq.gainsDb
        displayedGains.animateTo(state.graphicEq.gainsDb)
    }
    LaunchedEffect(state.parametricEq.nodes) {
        workingNodes = state.parametricEq.nodes
        displayedNodes.animateTo(state.parametricEq.nodes)
    }
    val screenScope = rememberCoroutineScope()

    var bassValue by rememberSaveable { mutableFloatStateOf(state.tone.bassDb) }
    var bassApplied by rememberSaveable { mutableStateOf(state.tone.bassDb != 0f) }
    var trebleValue by rememberSaveable { mutableFloatStateOf(state.tone.trebleDb) }
    var trebleApplied by rememberSaveable { mutableStateOf(state.tone.trebleDb != 0f) }

    // Non-neutral engine values are adopted (preset applies). A neutral one is
    // adopted only while the card still shows applied — a preset reset the
    // knob; after a manual toggle-off the knob keeps its own value.
    LaunchedEffect(state.tone.bassDb) {
        if (state.tone.bassDb != 0f) {
            bassValue = state.tone.bassDb
            bassApplied = true
        } else if (bassApplied) {
            bassApplied = false
            bassValue = 0f
        }
    }
    LaunchedEffect(state.tone.trebleDb) {
        if (state.tone.trebleDb != 0f) {
            trebleValue = state.tone.trebleDb
            trebleApplied = true
        } else if (trebleApplied) {
            trebleApplied = false
            trebleValue = 0f
        }
    }

    // Eased display copies of the tone knob values (bass/treble) and the fixed
    // mid band, used by the knobs and the mini map's separated tone curves.
    val bassDisplay = remember { TweenedFloat(bassValue) }
    val trebleDisplay = remember { TweenedFloat(trebleValue) }
    val midDisplay = remember { TweenedFloat(state.tone.midDb) }
    LaunchedEffect(bassValue) { bassDisplay.animateTo(bassValue) }
    LaunchedEffect(trebleValue) { trebleDisplay.animateTo(trebleValue) }
    LaunchedEffect(state.tone.midDb) { midDisplay.animateTo(state.tone.midDb) }

    // The pre-amp fader gets the same visual-only easing.
    val preampDisplay = remember { TweenedFloat(state.preampDb) }
    LaunchedEffect(state.preampDb) { preampDisplay.animateTo(state.preampDb) }

    val tweenedGains = displayedGains.value
    val tweenedNodes = displayedNodes.value

    val miniMapCurve = remember(selectedSection, state, tweenedGains, tweenedNodes) {
        when (selectedSection) {
            DspMath.Section.GRAPHIC -> DspMath.sectionResponse(
                state.copy(
                    graphicEq = state.graphicEq.copy(enabled = true, gainsDb = tweenedGains)
                ),
                DspMath.Section.GRAPHIC
            )
            DspMath.Section.PARAMETRIC -> DspMath.sectionResponse(
                state.copy(parametricEq = state.parametricEq.copy(nodes = tweenedNodes)),
                DspMath.Section.PARAMETRIC
            )
        }
    }

    val bassShownDb = bassDisplay.value
    val trebleShownDb = trebleDisplay.value
    val midShownDb = midDisplay.value

    // The parametric graph's curve includes the fixed tone section, so feed it
    // the eased tone values as well — a preset morphs the whole curve.
    val tweenedToneState = state.copy(
        tone = ToneSettings(bassDb = bassShownDb, midDb = midShownDb, trebleDb = trebleShownDb)
    )

    val miniMapToneCurves = remember(
        bassShownDb, bassApplied, trebleShownDb, trebleApplied, midShownDb
    ) {
        DspMath.toneCurves(
            ToneSettings(
                bassDb = bassShownDb,
                midDb = midShownDb,
                trebleDb = trebleShownDb
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
            nodes = if (selectedSection == DspMath.Section.PARAMETRIC) tweenedNodes else emptyList(),
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
                .foamSurface()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // More button: rename + assign for the active preset, or
                    // save-as-new in custom state (no preset matches). Pinned
                    // to the row's left edge so it doesn't drift with the pill.
                    PresetMoreButton(
                        isCustom = currentPreset == null,
                        onClick = {
                            if (currentPreset != null) showPresetMenuModal = true
                            else showSavePresetModal = true
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        PresetChip(
                            currentPresetName = currentPreset?.name,
                            onClick = { showPresetsModal = true }
                        )
                    }

                    FoamSwitch(
                        checked = when (selectedSection) {
                            DspMath.Section.GRAPHIC -> state.graphicEq.enabled
                            DspMath.Section.PARAMETRIC -> state.parametricEq.enabled
                        },
                        onCheckedChange = { on ->
                            when (selectedSection) {
                                DspMath.Section.GRAPHIC -> viewModel.setGraphicEnabled(on)
                                DspMath.Section.PARAMETRIC -> viewModel.setParametricEnabled(on)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    VerticalSlider(
                        value = preampDisplay.value,
                        onCommit = { viewModel.setPreampDb(it) },
                        onValueChange = {
                            viewModel.setPreampDb(it)
                            screenScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                preampDisplay.snapTo(it)
                            }
                        },
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
                            gains = tweenedGains,
                            enabled = state.graphicEq.enabled,
                            scrollState = stripScrollState,
                            onPreview = { index, gain ->
                                workingGains = workingGains
                                    .toMutableList()
                                    .also { it[index] = gain }
                                // A live drag overrides the preset easing.
                                screenScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    displayedGains.snapTo(workingGains)
                                }
                            },
                            onCommit = { viewModel.setGraphicGains(workingGains) },
                            onViewportWidthChanged = { stripViewportWidthPx = it },
                            modifier = Modifier.weight(1f)
                        )
                        DspMath.Section.PARAMETRIC -> ParametricNodeList(
                            nodes = tweenedNodes,
                            committedNodes = state.parametricEq.nodes,
                            enabled = state.parametricEq.enabled,
                            canAdd = workingNodes.size < ParametricEqSettings.MAX_NODES,
                            scrollState = nodesScrollState,
                            onPreview = { node ->
                                workingNodes = workingNodes.map { if (it.id == node.id) node else it }
                                // A live drag overrides the preset easing.
                                screenScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    displayedNodes.snapTo(workingNodes)
                                }
                            },
                            onCommit = { node -> viewModel.updateParametricNode(node) },
                            onDelete = { id -> viewModel.removeParametricNode(id) },
                            onAdd = { viewModel.addParametricNode() },
                            modifier = Modifier.weight(1f)
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
            bassDisplayValue = bassShownDb,
            onBassValueChange = { db ->
                bassValue = db
                // Live knob drag: follow the finger, don't ease.
                screenScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    bassDisplay.snapTo(db)
                }
            },
            bassApplied = bassApplied,
            onBassAppliedChange = { bassApplied = it },
            trebleValue = trebleValue,
            trebleDisplayValue = trebleShownDb,
            onTrebleValueChange = { db ->
                trebleValue = db
                screenScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    trebleDisplay.snapTo(db)
                }
            },
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

    val autoEqState by viewModel.autoEqState.collectAsState()

    PresetsModal(
        visible = showPresetsModal,
        presets = allPresets,
        state = state,
        autoEqState = autoEqState,
        autoEqActions = AutoEqActions(
            loadSites = viewModel::loadAutoEqSites,
            selectDatabase = viewModel::selectAutoEqDatabase,
            selectFrom = viewModel::selectAutoEqFrom,
            selectTarget = viewModel::selectAutoEqTarget,
            setOutputMode = viewModel::setAutoEqOutputMode,
            generate = viewModel::generateAutoEq,
            apply = {
                viewModel.applyAutoEq()
                showPresetsModal = false
            }
        ),
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

    // The presets pill's more menu: rename + assign for the active preset.
    PresetMenuModal(
        visible = showPresetMenuModal && currentPreset != null,
        presetName = currentPreset?.name,
        onDismiss = { showPresetMenuModal = false },
        onRename = {
            showPresetMenuModal = false
            showRenamePresetModal = true
        },
        onAssign = {
            showPresetMenuModal = false
            showAssignPresetModal = true
        }
    )

    // Renames the user preset; every assignment to the old name is remapped.
    RenamePresetModal(
        visible = showRenamePresetModal,
        presetName = currentPreset?.name,
        onRename = { newName ->
            currentPreset?.name?.let { viewModel.renameUserPreset(it, newName) }
            showRenamePresetModal = false
        },
        onDismiss = { showRenamePresetModal = false }
    )

    // Assigns the active preset to what is playing (track/album/playlist) or
    // to the output-device kinds; the auto-switch follows.
    AssignPresetModal(
        visible = showAssignPresetModal,
        presetName = currentPreset?.name,
        assignments = assignments,
        currentTrack = playerState.currentTrack,
        queueSourceId = playerState.queueSourceId,
        currentPlaylistName = currentPlaylistName,
        onDismiss = { showAssignPresetModal = false },
        onSetTrack = { id, name -> viewModel.setTrackAssignment(id, name) },
        onSetAlbum = { album, name -> viewModel.setAlbumAssignment(album, name) },
        onSetPlaylist = { playlistId, name -> viewModel.setPlaylistAssignment(playlistId, name) },
        onSetDevice = { kind, name -> viewModel.setDeviceAssignment(kind, name) }
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
            .background(if (enabled) FoamTheme.accentColor else FoamTheme.tintedSurfaceVariant)
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
            tint = if (enabled) FoamTheme.tintedBackground else FoamTheme.colors.textPrimary,
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
                .foamSurfaceVariant()
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

/** Round button opening the preset menu (rename / assign), or save-as-new
 *  (save icon) when the current sound is a custom preset. */
@Composable
private fun PresetMoreButton(
    isCustom: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .foamSurfaceVariant()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isCustom) {
            Icon(
                imageVector = Icons.Rounded.SaveAlt,
                contentDescription = stringResource(R.string.eq_preset_more),
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MoreHoriz,
                contentDescription = stringResource(R.string.eq_preset_more),
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Bottom sheet listing presets and the Auto EQ flow with a live response preview. */
@Composable
private fun PresetsModal(
    visible: Boolean,
    presets: List<DspPreset>,
    state: DspState,
    autoEqState: AutoEqUiState,
    autoEqActions: AutoEqActions,
    onApply: (DspPreset) -> Unit,
    onDelete: (DspPreset) -> Unit,
    onSaveAsNew: () -> Unit,
    onDismiss: () -> Unit
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        var tab by remember { mutableStateOf("presets") }
        // Reset to the first tab only when the sheet opens. Resetting on close
        // too would slide the content back to "presets" during the exit animation.
        LaunchedEffect(visible) { if (visible) tab = "presets" }

        Column(modifier = Modifier.fillMaxWidth()) {
            PresetsTabs(selected = tab, onSelect = { tab = it })
            Spacer(modifier = Modifier.height(12.dp))

            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    // autoeq sits to the right of presets: slide the incoming
                    // surface in from the direction of travel, and let the
                    // sheet's height animate between the two tab contents.
                    val direction = if (targetState == "autoeq") 1 else -1
                    (
                            slideInHorizontally(tween(FoamMotion.Duration.STANDARD)) { fullWidth -> direction * fullWidth } +
                                    fadeIn(tween(FoamMotion.Duration.STANDARD))
                            ) togetherWith
                            (
                                    slideOutHorizontally(tween(FoamMotion.Duration.STANDARD)) { fullWidth -> -direction * fullWidth } +
                                            fadeOut(tween(FoamMotion.Duration.STANDARD))
                                    )
                },
                label = "presetsTabContent"
            ) { currentTab ->
                when (currentTab) {
                    "autoeq" -> AutoEqContent(state = autoEqState, actions = autoEqActions)
                    else -> PresetsTab(
                        presets = presets,
                        state = state,
                        onApply = onApply,
                        onDelete = onDelete,
                        onSaveAsNew = onSaveAsNew
                    )
                }
            }
        }
    }
}

/** The presets tab: title, description, every preset row, and the save-as-new pill. */
@Composable
private fun PresetsTab(
    presets: List<DspPreset>,
    state: DspState,
    onApply: (DspPreset) -> Unit,
    onDelete: (DspPreset) -> Unit,
    onSaveAsNew: () -> Unit
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
                .foamSurfaceVariant()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = FoamTheme.colors.textPrimary),
                    onClick = onSaveAsNew
                )
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

/** Presets | Auto EQ pills choosing the preset modal's tab; the highlight slides. */
@Composable
private fun PresetsTabs(
    selected: String,
    onSelect: (String) -> Unit
) {
    val tabs = listOf(
        "presets" to R.string.eq_tab_presets,
        "autoeq" to R.string.eq_tab_autoeq
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .foamSurfaceVariant()
    ) {
        val tabWidth = maxWidth / 2
        val indicatorOffset by animateDpAsState(
            targetValue = if (selected == "autoeq") tabWidth else 0.dp,
            animationSpec = tween(FoamMotion.Duration.STANDARD),
            label = "presetsTabIndicator"
        )

        // Sliding selection highlight behind the labels; the labels define
        // the pill's size, so matchParentSize matches them without influencing it.
        Box(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .offset { IntOffset(x = indicatorOffset.roundToPx(), y = 0) }
                    .padding(2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(FoamTheme.accentColor)
            )
        }

        Row(modifier = Modifier.padding(2.dp)) {
            tabs.forEach { (tabKey, labelRes) ->
                val isSelected = tabKey == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary),
                            onClick = { onSelect(tabKey) }
                        )
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = isSelected,
                        transitionSpec = {
                            (
                                    slideInHorizontally(tween(FoamMotion.Duration.STANDARD)) { it / -2 } +
                                            fadeIn(tween(FoamMotion.Duration.STANDARD))
                                    ) togetherWith
                                    (
                                            slideOutHorizontally(tween(FoamMotion.Duration.STANDARD)) { it / 2 } +
                                                    fadeOut(tween(FoamMotion.Duration.STANDARD))
                                            )
                        },
                        label = "presetsTabLabel"
                    ) { selectedNow ->
                        BasicText(
                            text = stringResource(labelRes),
                            style = FoamTheme.typography.caption.copy(
                                color = if (selectedNow) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                                fontWeight = if (selectedNow) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
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
            .background(if (selected) FoamTheme.accentColor.copy(alpha = 0.16f) else FoamTheme.tintedSurfaceVariant)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onApply
            )
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = onDelete
                    ),
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

            FoamSwitch(
                checked = applied,
                onCheckedChange = onAppliedChange,
                modifier = Modifier.alpha(if (applied) 1f else 0.5f)
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
    bassDisplayValue: Float,
    onBassValueChange: (Float) -> Unit,
    bassApplied: Boolean,
    onBassAppliedChange: (Boolean) -> Unit,
    trebleValue: Float,
    trebleDisplayValue: Float,
    onTrebleValueChange: (Float) -> Unit,
    trebleApplied: Boolean,
    onTrebleAppliedChange: (Boolean) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val spacing = 10.dp
        val edgePadding = 8.dp
        val cardWidth = ((maxWidth - edgePadding * 2 - spacing * 2) / 3f) * 1.12f
        val knobSize = cardWidth - GlassCardPadding * 2
        val scope = rememberCoroutineScope()

        var balanceValue by rememberSaveable { mutableFloatStateOf(state.balance) }
        var widthValue by rememberSaveable { mutableFloatStateOf(state.stereoWidth) }

        var balanceApplied by rememberSaveable { mutableStateOf(state.balance != 0f) }
        var widthApplied by rememberSaveable { mutableStateOf(state.stereoWidth != 1f) }

        // Same adoption rule as the tone knobs: a neutral engine value resets
        // the card only when it still shows applied (a preset reset the knob).
        LaunchedEffect(state.balance) {
            if (state.balance != 0f) {
                balanceValue = state.balance
                balanceApplied = true
            } else if (balanceApplied) {
                balanceApplied = false
                balanceValue = 0f
            }
        }
        LaunchedEffect(state.stereoWidth) {
            if (state.stereoWidth != 1f) {
                widthValue = state.stereoWidth
                widthApplied = true
            } else if (widthApplied) {
                widthApplied = false
                widthValue = 1f
            }
        }

        // Eased display copies for the balance/width knobs, so a preset morphs
        // them; user drags snap them instead (see the callbacks below).
        val balanceDisplay = remember { TweenedFloat(balanceValue) }
        val widthDisplay = remember { TweenedFloat(widthValue) }
        LaunchedEffect(balanceValue) { balanceDisplay.animateTo(balanceValue) }
        LaunchedEffect(widthValue) { widthDisplay.animateTo(widthValue) }

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
                value = toneDbToPercent(bassDisplayValue),
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
                value = toneDbToPercent(trebleDisplayValue),
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
                value = balanceDisplay.value,
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
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        balanceDisplay.snapTo(balance)
                    }
                },
                modifier = Modifier.width(cardWidth)
            )

            ControlCard(
                name = stringResource(R.string.eq_virtualizer),
                value = widthDisplay.value,
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
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        widthDisplay.snapTo(width)
                    }
                },
                modifier = Modifier.width(cardWidth)
            )
        }
    }
}

private val NODE_CARD_HEIGHT = 230.dp
private val NODE_CARD_WIDTH = 184.dp
private val NODE_GAIN_SLIDER_WIDTH = 40.dp
private val NODE_KNOB_SIZE = 96.dp

/**
 * The parametric EQ as a horizontally scrollable list of vertical node cards
 * (same height as the graphic strip, so both cards match), with the add button
 * at the end of the list. Each card is a channel strip: the gain fader runs the
 * full card height on the left, while a filter pill (compact name plus a
 * miniature response curve) and the stacked frequency / Q knobs sit on the
 * right. The color dot and delete button are tucked into the card's bottom-right
 * corner. Tapping the color dot or the filter pill opens a small centered
 * dialog. Slider and knob drags preview locally through [onPreview] and commit
 * once on release through [onCommit].
 */
@Composable
private fun ParametricNodeList(
    nodes: List<ParametricNode>,
    committedNodes: List<ParametricNode>,
    enabled: Boolean,
    canAdd: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    onPreview: (ParametricNode) -> Unit,
    onCommit: (ParametricNode) -> Unit,
    onDelete: (Long) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var colorNodeId by remember { mutableStateOf<Long?>(null) }
    var filterNodeId by remember { mutableStateOf<Long?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(NODE_CARD_HEIGHT)
            .alpha(if (enabled) 1f else 0.5f)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        nodes.forEach { node ->
            key(node.id) {
                ParametricNodeCard(
                    node = node,
                    onPreview = onPreview,
                    onCommit = onCommit,
                    onDelete = { onDelete(node.id) },
                    onColorClick = { colorNodeId = node.id },
                    onFilterClick = { filterNodeId = node.id },
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
        AddNodeButton(
            canAdd = canAdd,
            onAdd = onAdd,
            modifier = Modifier.fillMaxHeight()
        )
    }

    // Dialogs edit the committed node, not the eased display copy, so opening
    // one mid preset-transition never snapshots interpolated values.
    val colorNode = colorNodeId?.let { id -> committedNodes.firstOrNull { it.id == id } }
    if (colorNode != null) {
        NodeColorDialog(
            currentArgb = colorNode.colorArgb,
            onSelect = { argb ->
                onCommit(colorNode.copy(colorArgb = argb))
                colorNodeId = null
            },
            onDismiss = { colorNodeId = null }
        )
    }

    val filterNode = filterNodeId?.let { id -> committedNodes.firstOrNull { it.id == id } }
    if (filterNode != null) {
        NodeFilterDialog(
            node = filterNode,
            onSelect = { type ->
                onCommit(filterNode.copy(type = type))
                filterNodeId = null
            },
            onDismiss = { filterNodeId = null }
        )
    }
}

@Composable
private fun ParametricNodeCard(
    node: ParametricNode,
    onPreview: (ParametricNode) -> Unit,
    onCommit: (ParametricNode) -> Unit,
    onDelete: () -> Unit,
    onColorClick: () -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Always edit the freshest node: the controls' callbacks outlive recompositions.
    val latestNode by rememberUpdatedState(node)

    Row(
        modifier = modifier
            .width(NODE_CARD_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            .foamSurfaceVariant()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (node.type.gainAdjustable) {
            Column(
                modifier = Modifier
                    .width(NODE_GAIN_SLIDER_WIDTH)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText(
                    text = "%+.1f".format(node.gainDb),
                    style = FoamTheme.typography.monospace.copy(
                        color = FoamTheme.colors.textSecondary,
                        fontSize = 10.sp
                    )
                )
                VerticalFoamSlider(
                    value = gainToNormalized(node.gainDb),
                    onValueChange = {
                        onPreview(latestNode.copy(gainDb = normalizedToGain(it)))
                    },
                    onCommit = {
                        onCommit(latestNode.copy(gainDb = normalizedToGain(it)))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                BasicText(
                    text = stringResource(R.string.eq_gain),
                    style = FoamTheme.typography.caption.copy(
                        color = FoamTheme.colors.textSecondary,
                        fontSize = 10.sp
                    )
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Filter pill: just a miniature schematic of the filter type, so the
            // node's shape reads at a glance before opening the picker.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(FoamTheme.accentColor)
                    .clickable(onClick = onFilterClick)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterCurvePreview(
                    type = node.type,
                    color = FoamTheme.tintedBackground,
                    modifier = Modifier
                        .width(40.dp)
                        .height(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NodeKnob(
                    label = stringResource(R.string.eq_freq),
                    value = frequencyToNormalized(node.frequencyHz),
                    valueText = { frequencyDisplay(normalizedToFrequency(it)) },
                    minValue = 0f,
                    maxValue = 1f,
                    defaultValue = frequencyToNormalized(1000f),
                    onValueChange = {
                        onPreview(latestNode.copy(frequencyHz = normalizedToFrequency(it)))
                    },
                    onCommit = {
                        onCommit(latestNode.copy(frequencyHz = normalizedToFrequency(it)))
                    }
                )
                NodeKnob(
                    label = stringResource(R.string.eq_q),
                    value = node.q,
                    valueText = { "%.2f".format(it) },
                    minValue = ParametricNode.MIN_Q,
                    maxValue = ParametricNode.MAX_Q,
                    defaultValue = 1.2f,
                    onValueChange = { onPreview(latestNode.copy(q = it)) },
                    onCommit = { onCommit(latestNode.copy(q = it)) }
                )
            }

            // Color + delete tucked into the card's bottom-right corner.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(node.colorArgb))
                        .border(1.dp, FoamTheme.colors.borderOverlay, CircleShape)
                        .clickable(onClick = onColorClick)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
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
        }
    }
}

/** Compact half-circle knob with an always-visible value, used inside a node card. */
@Composable
private fun NodeKnob(
    label: String,
    value: Float,
    valueText: (Float) -> String,
    minValue: Float,
    maxValue: Float,
    defaultValue: Float,
    onValueChange: (Float) -> Unit,
    onCommit: (Float) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        KnobControl(
            value = value,
            onValueChange = onValueChange,
            onCommit = onCommit,
            label = null,
            valueText = valueText,
            size = NODE_KNOB_SIZE,
            minValue = minValue,
            maxValue = maxValue,
            defaultValue = defaultValue,
            arcSweepDegrees = 180f,
            showNeedle = false,
            // No default detent: frequency and Q should track the finger smoothly.
            snapFraction = 0f
        ) {
            BasicText(
                text = valueText(value),
                style = FoamTheme.typography.monospace.copy(
                    color = FoamTheme.colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
        )
    }
}

@Composable
private fun AddNodeButton(
    canAdd: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .foamSurfaceVariant()
            .alpha(if (canAdd) 1f else 0.4f)
            .clickable(enabled = canAdd, onClick = onAdd)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = stringResource(R.string.eq_add_node),
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicText(
            text = stringResource(if (canAdd) R.string.eq_node else R.string.eq_node_limit),
            style = FoamTheme.typography.caption.copy(
                color = FoamTheme.accentColor,
                fontWeight = FontWeight.Bold
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

@Composable
private fun SavePresetModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    LaunchedEffect(visible) { if (visible) name = "" }

    // Valid save: non-blank (the engine rejects duplicates behind the call).
    val canSave = name.isNotBlank()

    // Auto-focused field so the keyboard is already up when the sheet settles
    // (Done submits), matching the rename modal.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) focusRequester.requestFocus()
    }

    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            BasicText(
                text = stringResource(R.string.eq_save_preset_title),
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingSmall,
                    vertical = FoamDimens.spacingTiny
                )
            )

            PlaylistNameField(
                value = name,
                onValueChange = { name = it },
                hint = stringResource(R.string.eq_preset_name_hint),
                focusRequester = focusRequester,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (canSave) onSave(name.trim()) }
                )
            )

            ModalButton(
                label = stringResource(R.string.save),
                filled = true,
                enabled = canSave,
                onClick = { onSave(name.trim()) }
            )
            ModalButton(
                label = stringResource(R.string.cancel),
                filled = false,
                onClick = onDismiss
            )
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

// preset transition tween

// A preset changes the engine instantly (as it must), but the screen should
// morph into the new sound instead of snapping. These holders only own the
// displayed value: the engine is never touched, and a live drag snaps them so
// the controls stay locked to the finger.

/** Displayed scalar eased toward preset changes, or snapped for live drags. */
private class TweenedFloat(initial: Float) {
    private val animatable = Animatable(initial)
    private var target = initial

    val value: Float get() = animatable.value

    suspend fun animateTo(newTarget: Float) {
        if (newTarget == target) return
        target = newTarget
        animatable.animateTo(
            newTarget,
            tween(durationMillis = FoamMotion.Duration.SWIFT, easing = FoamMotion.Easings.STANDARD)
        )
    }

    suspend fun snapTo(newValue: Float) {
        if (newValue == target && animatable.value == newValue) return
        target = newValue
        animatable.snapTo(newValue)
    }
}

/**
 * Displayed float list eased toward preset changes. When the list length changes
 * (band-layout switch) the target is shown directly — there is no sensible
 * per-band pairing across layouts.
 */
private class TweenedFloats(initial: List<Float>) {
    private val progress = Animatable(1f)
    private var from by mutableStateOf(initial)
    private var to by mutableStateOf(initial)

    val value: List<Float>
        get() {
            val fraction = progress.value
            if (fraction >= 1f || from.size != to.size) return to
            return List(to.size) { index ->
                val start = from[index]
                start + (to[index] - start) * fraction
            }
        }

    suspend fun animateTo(target: List<Float>) {
        if (target == to) return
        // No per-band pairing exists across a band-layout change: snap.
        if (target.size != to.size) {
            from = target
            to = target
            progress.snapTo(1f)
            return
        }
        val start = value
        from = start
        to = target
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            tween(durationMillis = FoamMotion.Duration.SWIFT, easing = FoamMotion.Easings.STANDARD)
        )
    }

    suspend fun snapTo(target: List<Float>) {
        if (target == to && progress.value >= 1f) return
        from = target
        to = target
        progress.snapTo(1f)
    }
}

/**
 * Displayed parametric node list eased toward preset changes. Nodes are paired
 * by index (preset layouts have no stable ids across applies); a node's type and
 * color switch with the target, while frequency, gain, Q and slope move.
 */
private class TweenedNodes(initial: List<ParametricNode>) {
    private val progress = Animatable(1f)
    private var from by mutableStateOf(initial)
    private var to by mutableStateOf(initial)

    val value: List<ParametricNode>
        get() {
            val fraction = progress.value
            if (fraction >= 1f || from.size != to.size) return to
            return List(to.size) { index -> tweenNode(from[index], to[index], fraction) }
        }

    suspend fun animateTo(target: List<ParametricNode>) {
        if (target == to) return
        // Adding/removing nodes has no per-node pairing: snap.
        if (target.size != to.size) {
            from = target
            to = target
            progress.snapTo(1f)
            return
        }
        val start = value
        from = start
        to = target
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            tween(durationMillis = FoamMotion.Duration.SWIFT, easing = FoamMotion.Easings.STANDARD)
        )
    }

    suspend fun snapTo(target: List<ParametricNode>) {
        if (target == to && progress.value >= 1f) return
        from = target
        to = target
        progress.snapTo(1f)
    }
}

/** Lerps a node's movable parameters; identity fields come from [to]. */
private fun tweenNode(from: ParametricNode, to: ParametricNode, fraction: Float): ParametricNode {
    fun lerp(start: Float, end: Float) = start + (end - start) * fraction
    return to.copy(
        // Log-space so the node moves at a constant speed across the log axis.
        // The epsilon keeps the 0 Hz knob floor out of ln(0).
        frequencyHz = exp(
            lerp(
                ln(from.frequencyHz.coerceAtLeast(0.01f)),
                ln(to.frequencyHz.coerceAtLeast(0.01f))
            )
        ),
        gainDb = lerp(from.gainDb, to.gainDb),
        q = lerp(from.q, to.q),
        slope = lerp(from.slope, to.slope)
    )
}

/**
 * Offset of the knob's log taper. Adding it to the frequency puts 0 Hz at the
 * floor while keeping the audible range spaced logarithmically (20 Hz lands at
 * roughly 10% of the travel, as with a pure 20 Hz..20 kHz log scale).
 */
private const val FREQ_TAPER_OFFSET_HZ = 20.0

internal fun frequencyDisplay(hz: Float): String =
    if (hz >= 1000f) "%.2fK".format(hz / 1000f) else "%.0f".format(hz)

private fun frequencyToNormalized(hz: Float): Float {
    val clamped = hz.coerceIn(0f, ParametricNode.MAX_FREQ_HZ)
    val logMin = ln(FREQ_TAPER_OFFSET_HZ)
    val logMax = ln(ParametricNode.MAX_FREQ_HZ + FREQ_TAPER_OFFSET_HZ)
    return ((ln(clamped + FREQ_TAPER_OFFSET_HZ) - logMin) / (logMax - logMin))
        .toFloat()
        .coerceIn(0f, 1f)
}

private fun normalizedToFrequency(normalized: Float): Float {
    val logMin = ln(FREQ_TAPER_OFFSET_HZ)
    val logMax = ln(ParametricNode.MAX_FREQ_HZ + FREQ_TAPER_OFFSET_HZ)
    val hz = exp(logMin + (logMax - logMin) * normalized.coerceIn(0f, 1f).toDouble()) -
            FREQ_TAPER_OFFSET_HZ
    return hz.toFloat().coerceIn(0f, ParametricNode.MAX_FREQ_HZ)
}

private fun gainToNormalized(db: Float): Float {
    val range = ParametricNode.MAX_GAIN_DB - ParametricNode.MIN_GAIN_DB
    return ((db - ParametricNode.MIN_GAIN_DB) / range).coerceIn(0f, 1f)
}

private fun normalizedToGain(normalized: Float): Float {
    val range = ParametricNode.MAX_GAIN_DB - ParametricNode.MIN_GAIN_DB
    return ParametricNode.MIN_GAIN_DB + normalized.coerceIn(0f, 1f) * range
}