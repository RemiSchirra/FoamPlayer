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

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var persistJob: Job? = null
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

    // ------------------------------------------------------------------ setters

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

    // ----------------------------------------------------------------- presets

    /**
     * Applies a preset. User presets (fullState set) restore their saved whole
     * state, keeping the current volume; built-ins adjust their pre-amp and
     * only their own EQ section — graphic or parametric — and disable the
     * opposite section. Built-in graphic shapes are authored on a 31-band grid
     * and resampled on the log-frequency axis to the user's band layout, so the
     * shape (not the per-band numbers) survives any band count.
     */
    fun applyPreset(preset: DspPreset) {
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
    }

    // ---------------------------------------------------------------- plumbing

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

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 400L
    }
}