package com.foamplayer.app.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.foamplayer.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Controller of the whole DSP chain (the app's replacement for the platform
 * Equalizer effect). Owns the [DspState]:
 *  - every setter publishes instantly into [state] (the EQ screen feels live),
 *  - every publication is pushed into [processor] so ExoPlayer's audio sink picks
 *    the new chain up on the very next buffer,
 *  - every publication is persisted to DataStore (debounced write-through).
 *
 * Injected as a singleton so the playback service, the EQ screen and the audio
 * settings screen all see — and drive — the exact same state.
 */
@UnstableApi
@Singleton
class DspEngine @Inject constructor(
    private val settingsRepository: SettingsRepository
) {

    /** The audio processor inserted into the (original) player's audio sink. */
    val processor = DspAudioProcessor()

    private val _state = MutableStateFlow(DspState())
    val state: StateFlow<DspState> = _state.asStateFlow()

    private val _userPresets = MutableStateFlow<List<DspPreset>>(emptyList())
    val userPresets: StateFlow<List<DspPreset>> = _userPresets.asStateFlow()

    private val _assignments = MutableStateFlow(PresetAssignments())

    /**
     * Preset assignments per playback target (track/album/playlist/device).
     * Seeded from DataStore with the chain state; read by the auto-switch and
     * the assign modal.
     */
    val assignments: StateFlow<PresetAssignments> = _assignments.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var persistJob: Job? = null
    private var persistAssignmentsJob: Job? = null
    private var seeded = false

    /**
     * Extra processors installed into auxiliary players (crossfade secondaries).
     * Each one receives the exact same state publications as [processor], so
     * both overlapping streams are treated identically by the DSP chain. A
     * processor promoted to primary keeps its registration; one destroyed with
     * its player is removed via [removeMirrorProcessor].
     */
    private val mirrorProcessors = CopyOnWriteArrayList<DspAudioProcessor>()

    init {
        // Seed once from DataStore before the first user change; afterwards the
        // in-memory state is the single source of truth (the app is the only writer).
        scope.launch {
            try {
                val settings = settingsRepository.settings.first()
                applySeed(DspPersistence.deserialize(settings.dspStateSerialized))
                _userPresets.value = DspPersistence.deserializePresets(settings.dspUserPresets)
                _assignments.value = PresetAssignments.deserialize(settings.dspAssignments)
            } catch (e: Exception) {
                applySeed(DspState())
            } finally {
                seeded = true
            }
        }
    }

    /**
     * Builds an extra [DspAudioProcessor] mirroring this chain's live state,
     * for a crossfade's secondary player. Seeded with the current state and
     * registered for future publications.
     */
    @OptIn(UnstableApi::class)
    fun createMirrorProcessor(): DspAudioProcessor =
        DspAudioProcessor().also { mirror ->
            mirror.setState(_state.value)
            mirrorProcessors.add(mirror)
        }

    /** Unregisters a mirror whose player was released (fade cancelled / aborted). */
    @OptIn(UnstableApi::class)
    fun removeMirrorProcessor(processor: DspAudioProcessor) {
        mirrorProcessors.remove(processor)
    }

    private fun applySeed(state: DspState) {
        val seededState = enforceExclusivity(state)
        _state.value = seededState
        publishState(seededState)
    }

    // setters

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun setPreampDb(db: Float) = update {
        it.copy(preampDb = db.coerceIn(DspState.MIN_PREAMP_DB, DspState.MAX_PREAMP_DB))
    }

    fun setBassDb(db: Float) = update {
        it.copy(tone = it.tone.copy(bassDb = db.coerceIn(ToneSettings.MIN_DB, ToneSettings.MAX_DB)))
    }

    fun setMidDb(db: Float) = update {
        it.copy(tone = it.tone.copy(midDb = db.coerceIn(ToneSettings.MIN_DB, ToneSettings.MAX_DB)))
    }

    fun setTrebleDb(db: Float) = update {
        it.copy(tone = it.tone.copy(trebleDb = db.coerceIn(ToneSettings.MIN_DB, ToneSettings.MAX_DB)))
    }

    /**
     * Reshapes the graphic EQ to [count] bands, resampling the current curve on
     * the log-frequency axis so the audible response is preserved across the
     * layout change (Poweramp-style band-count switching).
     */
    fun setGraphicBandCount(count: Int) = update { current ->
        val target = count.coerceIn(
            GraphicEqSettings.MIN_BAND_COUNT, GraphicEqSettings.MAX_BAND_COUNT
        )
        current.copy(graphicEq = resampleGraphic(current.graphicEq, target))
    }

    /**
     * Graphic and parametric EQ are mutually exclusive: enabling one section
     * always disables the other, so the DSP chain never runs both at once.
     */
    fun setGraphicEnabled(enabled: Boolean) = update {
        it.copy(
            graphicEq = it.graphicEq.copy(enabled = enabled),
            parametricEq = it.parametricEq.copy(enabled = if (enabled) false else it.parametricEq.enabled)
        )
    }

    /** Single-band commit (tap on a band fader). Enables graphic, disables parametric. */
    fun setGraphicGain(index: Int, gainDb: Float) = update {
        if (index !in it.graphicEq.gainsDb.indices) it else it.copy(
            graphicEq = it.graphicEq.copy(
                enabled = true,
                gainsDb = it.graphicEq.gainsDb.toMutableList().also { gains ->
                    gains[index] = gainDb.coerceIn(GraphicEqSettings.MIN_GAIN_DB, GraphicEqSettings.MAX_GAIN_DB)
                }
            ),
            parametricEq = it.parametricEq.copy(enabled = false)
        )
    }

    /** Bulk commit (end of a drag), published as one state emission. */
    fun setGraphicGains(gainsDb: List<Float>) = update {
        it.copy(
            graphicEq = it.graphicEq.copy(
                enabled = true,
                gainsDb = List(it.graphicEq.count) { index ->
                    gainsDb.getOrElse(index) { 0f }.coerceIn(
                        GraphicEqSettings.MIN_GAIN_DB, GraphicEqSettings.MAX_GAIN_DB
                    )
                }
            ),
            parametricEq = it.parametricEq.copy(enabled = false)
        )
    }

    fun setParametricEnabled(enabled: Boolean) = update {
        it.copy(
            parametricEq = it.parametricEq.copy(enabled = enabled),
            graphicEq = it.graphicEq.copy(enabled = if (enabled) false else it.graphicEq.enabled)
        )
    }

    /** Adds a node at [frequencyHz] and returns its id. Enables parametric, disables graphic. */
    fun addParametricNode(frequencyHz: Float = 1000f): Long {
        val id = nextNodeId++
        update {
            if (it.parametricEq.nodes.size >= ParametricEqSettings.MAX_NODES) it else it.copy(
                parametricEq = it.parametricEq.copy(
                    enabled = true,
                    nodes = it.parametricEq.nodes + ParametricNode(
                        id = id,
                        frequencyHz = frequencyHz.coerceIn(
                            ParametricNode.MIN_FREQ_HZ, ParametricNode.MAX_FREQ_HZ
                        ),
                        colorArgb = NodeColorPalette[it.parametricEq.nodes.size % NodeColorPalette.size]
                    )
                ),
                graphicEq = it.graphicEq.copy(enabled = false)
            )
        }
        return id
    }

    fun updateParametricNode(node: ParametricNode) = update { current ->
        current.copy(
            parametricEq = current.parametricEq.copy(
                nodes = current.parametricEq.nodes.map {
                    if (it.id == node.id) {
                        node.copy(
                            frequencyHz = node.frequencyHz.coerceIn(
                                ParametricNode.MIN_FREQ_HZ, ParametricNode.MAX_FREQ_HZ
                            ),
                            gainDb = node.gainDb.coerceIn(
                                ParametricNode.MIN_GAIN_DB, ParametricNode.MAX_GAIN_DB
                            ),
                            q = node.q.coerceIn(ParametricNode.MIN_Q, ParametricNode.MAX_Q),
                            slope = node.slope.coerceIn(ParametricNode.MIN_SLOPE, ParametricNode.MAX_SLOPE)
                        )
                    } else it
                }
            )
        )
    }

    fun removeParametricNode(id: Long) = update { current ->
        current.copy(
            parametricEq = current.parametricEq.copy(
                nodes = current.parametricEq.nodes.filterNot { it.id == id }
            )
        )
    }

    fun setBalance(balance: Float) = update {
        it.copy(balance = balance.coerceIn(-DspState.MAX_BALANCE, DspState.MAX_BALANCE))
    }

    fun setStereoWidth(width: Float) = update {
        it.copy(stereoWidth = width.coerceIn(0f, DspState.MAX_STEREO_WIDTH))
    }

    fun setDirectVolumeControl(enabled: Boolean) = update {
        it.copy(directVolumeControl = enabled)
    }

    fun setVolumePercent(percent: Int) = update {
        it.copy(volumePercent = percent.coerceIn(0, DspState.MAX_VOLUME_PERCENT))
    }

    // presets

    /**
     * Applies a preset as a complete sound snapshot: user presets (fullState
     * set) restore their saved whole state, keeping the current volume;
     * built-ins restore their pre-amp, tone and own EQ section — graphic or
     * parametric — disable the opposite section, and reset balance and stereo
     * width to neutral. Built-in graphic shapes are authored on a 31-band grid
     * and resampled on the log-frequency axis to the user's band layout, so the
     * shape (not the per-band numbers) survives any band count.
     */
    fun applyPreset(preset: DspPreset) {
        recordManualPreset(preset.name)
        applyPresetInternal(preset)
    }

    /** The actual apply; the auto path skips the manual-choice recording. */
    private fun applyPresetInternal(preset: DspPreset) {
        if (preset.fullState != null) {
            update { preset.fullState.copy(volumePercent = it.volumePercent) }
            return
        }
        when (preset.kind) {
            PresetKind.GRAPHIC -> update {
                it.copy(
                    enabled = true,
                    preampDb = preset.preampDb.coerceIn(DspState.MIN_PREAMP_DB, DspState.MAX_PREAMP_DB),
                    tone = preset.tone,
                    balance = 0f,
                    stereoWidth = 1f,
                    graphicEq = it.graphicEq.copy(
                        enabled = true,
                        gainsDb = GraphicEqSettings.resampleGains(
                            preset.graphicGainsDb,
                            it.graphicEq.count
                        )
                    ),
                    parametricEq = it.parametricEq.copy(enabled = false)
                )
            }
            PresetKind.PARAMETRIC -> update {
                it.copy(
                    enabled = true,
                    preampDb = preset.preampDb.coerceIn(DspState.MIN_PREAMP_DB, DspState.MAX_PREAMP_DB),
                    tone = preset.tone,
                    balance = 0f,
                    stereoWidth = 1f,
                    parametricEq = ParametricEqSettings(
                        enabled = true,
                        nodes = preset.parametricNodes.map { node -> node.copy(id = nextNodeId++) }
                    ),
                    graphicEq = it.graphicEq.copy(enabled = false)
                )
            }
        }
    }

    fun saveUserPreset(name: String): Boolean {
        val sanitized = DspPersistence.sanitizeName(name)
        if (sanitized.isEmpty()) return false
        val state = _state.value
        val preset = DspPreset(
            name = sanitized,
            kind = if (state.parametricEq.enabled) PresetKind.PARAMETRIC else PresetKind.GRAPHIC,
            graphicGainsDb = state.graphicEq.gainsDb,
            parametricNodes = state.parametricEq.nodes,
            preampDb = state.preampDb,
            fullState = state,
            isBuiltIn = false
        )
        val updated = _userPresets.value.filterNot { it.name == sanitized } + preset
        _userPresets.value = updated
        persistPresets(updated)
        return true
    }

    fun deleteUserPreset(name: String) {
        val updated = _userPresets.value.filterNot { it.name == name }
        _userPresets.value = updated
        persistPresets(updated)
        // A deleted preset can never resolve again: drop every assignment to it.
        setAssignments { it.withoutPreset(name) }
    }

    /** Renames a user preset and remaps every assignment pointing at the old name. */
    fun renameUserPreset(oldName: String, newName: String): Boolean {
        val sanitized = DspPersistence.sanitizeName(newName)
        if (sanitized.isEmpty()) return false
        val presets = _userPresets.value
        if (presets.none { it.name == oldName }) return false
        val updated = presets.map {
            if (it.name == oldName) it.copy(name = sanitized) else it
        }
        _userPresets.value = updated
        persistPresets(updated)
        setAssignments { it.withRenamedPreset(oldName, sanitized) }
        return true
    }

    // assignments

    fun setTrackAssignment(trackId: Long, presetName: String?) =
        setAssignments { it.withAssignment(AssignedTarget.Track(trackId), presetName) }

    fun setAlbumAssignment(album: String, presetName: String?) =
        setAssignments { it.withAssignment(AssignedTarget.Album(album), presetName) }

    fun setPlaylistAssignment(playlistId: Long, presetName: String?) =
        setAssignments { it.withAssignment(AssignedTarget.Playlist(playlistId), presetName) }

    fun setDeviceAssignment(kind: AssignedDeviceKind, presetName: String?) =
        setAssignments { it.withAssignment(AssignedTarget.Device(kind), presetName) }

    /**
     * Records the manual (hand-picked) preset. A hand-pick also supersedes
     * the pre-assignment baseline by clearing it: the user's explicit choice
     * is what an unassigned track must fall back to when no baseline survives.
     */
    private fun recordManualPreset(name: String) {
        preAssignmentState = null
        if (_assignments.value.lastManualPresetName == name) return
        setAssignments { it.copy(lastManualPresetName = name) }
    }

    private fun setAssignments(transform: (PresetAssignments) -> PresetAssignments) {
        val updated = transform(_assignments.value)
        if (updated == _assignments.value) return
        _assignments.value = updated
        persistAssignments(updated)
    }

    /**
     * The chain state captured just before an assignment-driven apply first
     * took over the sound (since the last manual choice or revert). This is
     * the primary revert target — the sound the assignment actually
     * displaced, even when the persisted last manual pick happens to name
     * the same preset the assignment applied.
     */
    private var preAssignmentState: DspState? = null

    /**
     * The auto path: applies the preset [name] resolves to — built-in or user —
     * WITHOUT recording it as a manual choice. The chain state is captured as
     * the pre-assignment baseline before anything else, so a later revert can
     * restore the exact sound this assignment took over — even when the state
     * already matches and the apply itself is skipped. Returns false when no
     * preset matches (e.g. a deleted preset still referenced by an assignment).
     */
    fun applyAssignedPreset(name: String): Boolean {
        val preset = findPreset(name) ?: return false
        // Capture the sound an assignment is taking over — once per takeover,
        // so chained assigned tracks do not shadow the baseline — even when
        // the apply itself is skipped below (the state already matches).
        if (preAssignmentState == null) preAssignmentState = _state.value
        if (preset.matchesState(state.value)) return true
        applyPresetInternal(preset)
        return true
    }

    /**
     * The auto path for a track with no assignment: restores the chain state
     * captured before an assignment took over (the displaced sound), falling
     * back to the last hand-picked preset only when no baseline survives —
     * e.g. the default EQ a playlist-assigned track displaced, or the
     * hand-picked sound from an earlier session. Returns false when there is
     * nothing to revert to: no baseline and no resolvable manual choice.
     */
    fun revertFromAssignment(): Boolean {
        val baseline = preAssignmentState
        if (baseline != null) {
            preAssignmentState = null
            val restored = baseline.copy(volumePercent = _state.value.volumePercent)
            if (_state.value != restored) update { restored }
            return true
        }
        // No baseline: fall back to the last hand-picked preset.
        val manualName = _assignments.value.lastManualPresetName ?: return false
        val preset = findPreset(manualName) ?: return false
        if (!preset.matchesState(state.value)) applyPresetInternal(preset)
        return true
    }

    /**
     * The name of the preset (built-in or user) whose state the chain currently
     * reflects, or null when the state matches no preset (Custom, or defaults
     * no preset produces). Read by the auto-switcher to detect drift between
     * what is assigned and what the chain actually sounds like.
     */
    fun statePresetName(): String? =
        DspPreset.BUILT_INS.firstOrNull { it.matchesState(_state.value) }?.name
            ?: _userPresets.value.firstOrNull { it.matchesState(_state.value) }?.name

    private fun findPreset(name: String): DspPreset? =
        DspPreset.BUILT_INS.firstOrNull { it.name == name }
            ?: _userPresets.value.firstOrNull { it.name == name }

    // plumbing

    private var nextNodeId = 1L

    /**
     * Resamples a graphic-EQ configuration to [targetCount] bands on the
     * log-frequency axis, so the audible curve (not the per-band numbers)
     * survives a layout change.
     */
    private fun resampleGraphic(graphic: GraphicEqSettings, targetCount: Int): GraphicEqSettings =
        graphic.copy(
            count = targetCount,
            gainsDb = GraphicEqSettings.resampleGains(graphic.gainsDb, targetCount)
        )

    private fun update(transform: (DspState) -> DspState) {
        val newState = enforceExclusivity(transform(_state.value))
        _state.value = newState
        publishState(newState)
        persistState(newState)
    }

    /** Pushes a state publication into the main processor and every live mirror. */
    private fun publishState(state: DspState) {
        processor.setState(state)
        for (mirror in mirrorProcessors) mirror.setState(state)
    }

    /**
     * Single source of truth for section exclusivity: the graphic and parametric
     * EQ can never both be enabled, no matter which setter (or legacy persisted
     * state) says otherwise. The graphic section wins the coin flip.
     */
    private fun enforceExclusivity(state: DspState): DspState =
        if (state.graphicEq.enabled && state.parametricEq.enabled) {
            state.copy(parametricEq = state.parametricEq.copy(enabled = false))
        } else {
            state
        }

    private fun persistState(state: DspState) {
        if (!seeded) return // don't write back defaults before the seed landed
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            try {
                settingsRepository.setDspState(state)
            } catch (e: Exception) {
                // Persistence is best-effort; the audio path must never fail on it.
            }
        }
    }

    private fun persistPresets(presets: List<DspPreset>) {
        if (!seeded) return
        scope.launch {
            try {
                settingsRepository.setDspUserPresets(presets)
            } catch (e: Exception) {
            }
        }
    }

    private fun persistAssignments(assignments: PresetAssignments) {
        if (!seeded) return // don't write back defaults before the seed landed
        persistAssignmentsJob?.cancel()
        persistAssignmentsJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS.milliseconds)
            try {
                settingsRepository.setDspAssignments(assignments)
            } catch (e: Exception) {
                // Persistence is best-effort; the audio path must never fail on it.
            }
        }
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 400L
    }
}