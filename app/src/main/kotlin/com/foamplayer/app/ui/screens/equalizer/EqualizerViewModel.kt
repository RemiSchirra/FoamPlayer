package com.foamplayer.app.ui.screens.equalizer

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import com.foamplayer.app.audio.dsp.AssignedDeviceKind
import com.foamplayer.app.audio.dsp.DspEngine
import com.foamplayer.app.audio.dsp.DspPreset
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.audio.dsp.FilterType
import com.foamplayer.app.audio.dsp.GraphicEqSettings
import com.foamplayer.app.audio.dsp.NodeColorPalette
import com.foamplayer.app.audio.dsp.ParametricNode
import com.foamplayer.app.audio.dsp.PresetAssignments
import com.foamplayer.app.audio.dsp.PresetKind
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.repository.PlaylistRepository
import com.foamplayer.squig.AutoEq
import com.foamplayer.squig.SquigDb
import com.foamplayer.squig.SquigDevice
import com.foamplayer.squig.SquigLink
import com.foamplayer.squig.SquigSite
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the whole DSP chain from the Equalizer screen: state, section toggles,
 * tone/control knobs, graphic faders, parametric nodes, presets and volume all
 * forward straight into [DspEngine], whose publication also feeds the player's
 * audio sink and the audio-path evaluation. The graphic band layout is chosen
 * in Settings > Audio > Equalizer (AudioSettingsViewModel), not here.
 *
 * The chain is purely linear (industry-standard EQ): no limiter and no auto
 * headroom exist anywhere in the settings or the audio path, so a boost at any
 * frequency is exactly the boost the response curve shows.
 */
@HiltViewModel
class EqualizerViewModel @OptIn(UnstableApi::class)
@Inject constructor(
    private val dspEngine: DspEngine,
    private val audioEngine: AudioEngine,
    playlistRepository: PlaylistRepository
) : ViewModel() {

    val dspState: StateFlow<DspState> = dspEngine.state

    val userPresets: StateFlow<List<DspPreset>> = dspEngine.userPresets

    /** Preset assignments per playback target (track/album/playlist/device). */
    val assignments: StateFlow<PresetAssignments> = dspEngine.assignments

    /** The playback state driving the assign modal's track/album/playlist rows. */
    val playbackState: StateFlow<PlaybackState> = audioEngine.playbackState

    /** The name of the playlist the queue was started from, for the assign modal's row. */
    val currentPlaylistName: StateFlow<String?> = combine(
        audioEngine.playbackState,
        playlistRepository.getAllPlaylists()
    ) { state, playlists ->
        val id = state.queueSourceId
            ?.takeIf { it.startsWith("playlist:") }
            ?.removePrefix("playlist:")
            ?.toLongOrNull()
        playlists.firstOrNull { it.playlistId == id }?.name
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(UnstableApi::class)
    fun setEnabled(enabled: Boolean) = dspEngine.setEnabled(enabled)

    @OptIn(UnstableApi::class)
    fun setPreampDb(db: Float) = dspEngine.setPreampDb(db)

    @OptIn(UnstableApi::class)
    fun setBassDb(db: Float) = dspEngine.setBassDb(db)

    @OptIn(UnstableApi::class)
    fun setMidDb(db: Float) = dspEngine.setMidDb(db)

    @OptIn(UnstableApi::class)
    fun setTrebleDb(db: Float) = dspEngine.setTrebleDb(db)

    @OptIn(UnstableApi::class)
    fun setBalance(balance: Float) = dspEngine.setBalance(balance)

    @OptIn(UnstableApi::class)
    fun setStereoWidth(width: Float) = dspEngine.setStereoWidth(width)

    @OptIn(UnstableApi::class)
    fun setDirectVolumeControl(enabled: Boolean) = dspEngine.setDirectVolumeControl(enabled)

    @OptIn(UnstableApi::class)
    fun setVolumePercent(percent: Int) = dspEngine.setVolumePercent(percent)

    @OptIn(UnstableApi::class)
    fun setGraphicEnabled(enabled: Boolean) = dspEngine.setGraphicEnabled(enabled)

    @OptIn(UnstableApi::class)
    fun setGraphicGains(gainsDb: List<Float>) = dspEngine.setGraphicGains(gainsDb)

    @OptIn(UnstableApi::class)
    fun setParametricEnabled(enabled: Boolean) = dspEngine.setParametricEnabled(enabled)

    @OptIn(UnstableApi::class)
    fun addParametricNode(frequencyHz: Float = 1000f): Long = dspEngine.addParametricNode(frequencyHz)

    @OptIn(UnstableApi::class)
    fun updateParametricNode(node: ParametricNode) = dspEngine.updateParametricNode(node)

    @OptIn(UnstableApi::class)
    fun removeParametricNode(id: Long) = dspEngine.removeParametricNode(id)

    @OptIn(UnstableApi::class)
    fun applyPreset(preset: DspPreset) = dspEngine.applyPreset(preset)

    @OptIn(UnstableApi::class)
    fun saveUserPreset(name: String): Boolean = dspEngine.saveUserPreset(name)

    @OptIn(UnstableApi::class)
    fun deleteUserPreset(name: String) = dspEngine.deleteUserPreset(name)

    @OptIn(UnstableApi::class)
    fun renameUserPreset(oldName: String, newName: String): Boolean =
        dspEngine.renameUserPreset(oldName, newName)

    @OptIn(UnstableApi::class)
    fun setTrackAssignment(trackId: Long, presetName: String?) =
        dspEngine.setTrackAssignment(trackId, presetName)

    @OptIn(UnstableApi::class)
    fun setAlbumAssignment(album: String, presetName: String?) =
        dspEngine.setAlbumAssignment(album, presetName)

    @OptIn(UnstableApi::class)
    fun setPlaylistAssignment(playlistId: Long, presetName: String?) =
        dspEngine.setPlaylistAssignment(playlistId, presetName)

    @OptIn(UnstableApi::class)
    fun setDeviceAssignment(kind: AssignedDeviceKind, presetName: String?) =
        dspEngine.setDeviceAssignment(kind, presetName)

    // Auto EQ

    private val _autoEqState = MutableStateFlow(AutoEqUiState())

    /** UI state of the Auto EQ flow inside the preset selection modal. */
    val autoEqState: StateFlow<AutoEqUiState> = _autoEqState.asStateFlow()

    fun loadAutoEqSites() {
        val current = _autoEqState.value
        if (current.sitesLoading || current.sites.isNotEmpty()) return
        _autoEqState.update { it.copy(sitesLoading = true, sitesError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = runCatching { SquigLink.sites() }
            _autoEqState.update { state ->
                loaded.fold(
                    onSuccess = { sites -> state.copy(sites = sites, sitesLoading = false) },
                    onFailure = { state.copy(sitesLoading = false, sitesError = it.autoEqMessage()) }
                )
            }
        }
    }

    /** Selects a site + form factor and loads its database (catalog + targets). */
    fun selectAutoEqDatabase(site: SquigSite, db: SquigDb) {
        _autoEqState.update {
            it.copy(
                selectedSite = site,
                selectedDb = db,
                databaseLoading = true,
                databaseError = null,
                database = null,
                fromDevice = null,
                targetDevice = null,
                generation = null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = runCatching { SquigLink.database(site, db) }
            _autoEqState.update { state ->
                if (state.selectedSite?.username != site.username) return@update state
                loaded.fold(
                    onSuccess = { database ->
                        state.copy(database = database, databaseLoading = false)
                    },
                    onFailure = {
                        state.copy(databaseLoading = false, databaseError = it.autoEqMessage())
                    }
                )
            }
        }
    }

    fun selectAutoEqFrom(device: SquigDevice) =
        _autoEqState.update { it.copy(fromDevice = device, generateError = null, generation = null) }

    fun selectAutoEqTarget(device: SquigDevice) =
        _autoEqState.update { it.copy(targetDevice = device, generateError = null, generation = null) }

    fun setAutoEqOutputMode(mode: AutoEqOutputMode) =
        _autoEqState.update { it.copy(outputMode = mode) }

    /** Fetches the measurement, fits the EQ from source → target, and stores the result. */
    fun generateAutoEq() {
        val current = _autoEqState.value
        val database = current.database
        val from = current.fromDevice
        val target = current.targetDevice
        if (database == null || from == null || target == null || current.generating) return
        _autoEqState.update { it.copy(generating = true, generateError = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val computed = runCatching {
                val source = SquigLink.measurement(database, from)
                val targetCurve = SquigLink.measurement(database, target)
                AutoEq.compute(source, targetCurve)
            }
            _autoEqState.update { state ->
                computed.fold(
                    onSuccess = { result ->
                        state.copy(
                            generating = false,
                            generation = AutoEqGeneration(
                                fromName = from.displayName,
                                targetName = target.displayName,
                                result = result
                            )
                        )
                    },
                    onFailure = {
                        state.copy(generating = false, generateError = it.autoEqMessage())
                    }
                )
            }
        }
    }

    /** Applies the generated EQ as a complete sound snapshot (parametric or graphic). */
    @OptIn(UnstableApi::class)
    fun applyAutoEq() {
        val generation = _autoEqState.value.generation ?: return
        val presetName = "Auto EQ · ${generation.fromName} → ${generation.targetName}"
        val result = generation.result
        val preset = when (_autoEqState.value.outputMode) {
            AutoEqOutputMode.PARAMETRIC -> DspPreset(
                name = presetName,
                kind = PresetKind.PARAMETRIC,
                parametricNodes = result.filters.mapIndexed { index, filter ->
                    ParametricNode(
                        id = 0L,
                        type = FilterType.PEAKING,
                        frequencyHz = filter.frequencyHz,
                        gainDb = filter.gainDb,
                        q = filter.q,
                        colorArgb = NodeColorPalette[index % NodeColorPalette.size]
                    )
                },
                preampDb = result.preampDb
            )
            AutoEqOutputMode.GRAPHIC -> {
                val count = dspState.value.graphicEq.count
                val gains = List(count) { band ->
                    result.correctionCurve
                        .interpolate(GraphicEqSettings.bandFrequency(band, count))
                        .coerceIn(GraphicEqSettings.MIN_GAIN_DB, GraphicEqSettings.MAX_GAIN_DB)
                }
                DspPreset(
                    name = presetName,
                    kind = PresetKind.GRAPHIC,
                    graphicGainsDb = gains,
                    preampDb = -(gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
                )
            }
        }
        dspEngine.applyPreset(preset)
    }

    private fun Throwable.autoEqMessage(): String =
        message?.takeIf(String::isNotBlank)?.lineSequence()?.first()
            ?: javaClass.simpleName
}
