package com.foamplayer.app.audio.dsp

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
@Singleton
class DspEngine @Inject constructor(
    private val settingsRepository: SettingsRepository
) {

    /** The audio processor inserted into the player's audio sink. */
    val processor = DspAudioProcessor()

    private val _state = MutableStateFlow(DspState())
    val state: StateFlow<DspState> = _state.asStateFlow()

    private val _userPresets = MutableStateFlow<List<DspPreset>>(emptyList())
    val userPresets: StateFlow<List<DspPreset>> = _userPresets.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var persistJob: Job? = null
    private var seeded = false

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

    private fun applySeed(state: DspState) {
        _state.value = state
        processor.setState(state)
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

    fun setGraphicEnabled(enabled: Boolean) = update {
        it.copy(graphicEq = it.graphicEq.copy(enabled = enabled))
    }

    /** Single-band commit (tap on a band handle). */
    fun setGraphicGain(index: Int, gainDb: Float) = update {
        if (index !in it.graphicEq.gainsDb.indices) it else it.copy(
            graphicEq = it.graphicEq.copy(
                enabled = true,
                gainsDb = it.graphicEq.gainsDb.toMutableList().also { gains ->
                    gains[index] = gainDb.coerceIn(GraphicEqSettings.MIN_GAIN_DB, GraphicEqSettings.MAX_GAIN_DB)
                }
            )
        )
    }

    /** Bulk commit (end of a drag), published as one state emission. */
    fun setGraphicGains(gainsDb: List<Float>) = update {
        it.copy(
            graphicEq = it.graphicEq.copy(
                enabled = true,
                gainsDb = List(GraphicEqSettings.BAND_COUNT) { index ->
                    gainsDb.getOrElse(index) { 0f }.coerceIn(
                        GraphicEqSettings.MIN_GAIN_DB, GraphicEqSettings.MAX_GAIN_DB
                    )
                }
            )
        )
    }

    fun setParametricEnabled(enabled: Boolean) = update {
        it.copy(parametricEq = it.parametricEq.copy(enabled = enabled))
    }

    /** Adds a node at [frequencyHz] and returns its id. */
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
                        )
                    )
                )
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
                            q = node.q.coerceIn(ParametricNode.MIN_Q, ParametricNode.MAX_Q)
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

    fun setLimiterEnabled(enabled: Boolean) = update { it.copy(limiterEnabled = enabled) }

    fun setAutoHeadroom(enabled: Boolean) = update { it.copy(autoHeadroom = enabled) }

    fun setDirectVolumeControl(enabled: Boolean) = update {
        it.copy(directVolumeControl = enabled)
    }

    fun setVolumePercent(percent: Int) = update {
        it.copy(volumePercent = percent.coerceIn(0, DspState.MAX_VOLUME_PERCENT))
    }

    // ----------------------------------------------------------------- presets

    /**
     * Applies a preset. Built-in presets adjust the graphic EQ + tone (Poweramp
     * preset behavior); user presets restore their saved full state, keeping the
     * current volume.
     */
    fun applyPreset(preset: DspPreset) {
        if (preset.fullState != null) {
            update { preset.fullState.copy(volumePercent = it.volumePercent) }
        } else {
            update {
                it.copy(
                    enabled = true,
                    tone = preset.tone,
                    graphicEq = GraphicEqSettings(enabled = true, gainsDb = preset.graphicGainsDb)
                )
            }
        }
    }

    fun saveUserPreset(name: String): Boolean {
        val sanitized = DspPersistence.sanitizeName(name)
        if (sanitized.isEmpty()) return false
        val preset = DspPreset(
            name = sanitized,
            graphicGainsDb = _state.value.graphicEq.gainsDb,
            fullState = _state.value,
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

    private fun update(transform: (DspState) -> DspState) {
        val newState = transform(_state.value)
        _state.value = newState
        processor.setState(newState)
        persistState(newState)
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
