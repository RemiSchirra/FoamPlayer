package com.foamplayer.app.ui.screens.equalizer

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import com.foamplayer.app.audio.dsp.DspEngine
import com.foamplayer.app.audio.dsp.DspPreset
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.audio.dsp.ParametricNode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
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
    private val dspEngine: DspEngine
) : ViewModel() {

    val dspState: StateFlow<DspState> = dspEngine.state

    val userPresets: StateFlow<List<DspPreset>> = dspEngine.userPresets

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
}
