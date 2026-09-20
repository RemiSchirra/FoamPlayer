package com.foamplayer.app.ui.screens.equalizer

import androidx.lifecycle.ViewModel
import com.foamplayer.app.audio.dsp.DspEngine
import com.foamplayer.app.audio.dsp.DspPreset
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.audio.dsp.ParametricNode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Drives the whole DSP chain from the Equalizer screen: state, section toggles,
 * tone/control knobs, graphic bands, parametric nodes, presets and volume all
 * forward straight into [DspEngine], whose publication also feeds the player's
 * audio sink and the audio-path evaluation.
 */
@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val dspEngine: DspEngine
) : ViewModel() {

    val dspState: StateFlow<DspState> = dspEngine.state

    val userPresets: StateFlow<List<DspPreset>> = dspEngine.userPresets

    fun setEnabled(enabled: Boolean) = dspEngine.setEnabled(enabled)

    fun setPreampDb(db: Float) = dspEngine.setPreampDb(db)

    fun setBassDb(db: Float) = dspEngine.setBassDb(db)

    fun setMidDb(db: Float) = dspEngine.setMidDb(db)

    fun setTrebleDb(db: Float) = dspEngine.setTrebleDb(db)

    fun setBalance(balance: Float) = dspEngine.setBalance(balance)

    fun setStereoWidth(width: Float) = dspEngine.setStereoWidth(width)

    fun setLimiterEnabled(enabled: Boolean) = dspEngine.setLimiterEnabled(enabled)

    fun setAutoHeadroom(enabled: Boolean) = dspEngine.setAutoHeadroom(enabled)

    fun setDirectVolumeControl(enabled: Boolean) = dspEngine.setDirectVolumeControl(enabled)

    fun setVolumePercent(percent: Int) = dspEngine.setVolumePercent(percent)

    fun setGraphicEnabled(enabled: Boolean) = dspEngine.setGraphicEnabled(enabled)

    fun setGraphicGains(gainsDb: List<Float>) = dspEngine.setGraphicGains(gainsDb)

    fun setParametricEnabled(enabled: Boolean) = dspEngine.setParametricEnabled(enabled)

    fun addParametricNode(frequencyHz: Float = 1000f): Long = dspEngine.addParametricNode(frequencyHz)

    fun updateParametricNode(node: ParametricNode) = dspEngine.updateParametricNode(node)

    fun removeParametricNode(id: Long) = dspEngine.removeParametricNode(id)

    fun applyPreset(preset: DspPreset) = dspEngine.applyPreset(preset)

    fun saveUserPreset(name: String): Boolean = dspEngine.saveUserPreset(name)

    fun deleteUserPreset(name: String) = dspEngine.deleteUserPreset(name)
}
