package com.foamplayer.app.audio.equalizer

import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class EqualizerState(
    val enabled: Boolean = false,
    val bands: List<Int> = listOf(0, 0, 0, 0, 0),
    val bandFrequencies: List<Int> = listOf(60, 230, 910, 3600, 14000),
    val preampGain: Float = 0f,
    val presets: List<String> = listOf("Flat", "Bass Boost", "Vocal", "Acoustic", "Custom"),
    val selectedPreset: String = "Flat"
)

@Singleton
class EqualizerManager @Inject constructor() {

    private var equalizer: Equalizer? = null
    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state

    fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId == 0) return
        try {
            equalizer?.release()
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = _state.value.enabled
            }
            fetchBandSettings()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fetchBandSettings() {
        val eq = equalizer ?: return
        val bandsCount = eq.numberOfBands.toInt()
        val frequencies = mutableListOf<Int>()
        val gains = mutableListOf<Int>()

        for (i in 0 until bandsCount) {
            frequencies.add(eq.getCenterFreq(i.toShort()) / 1000)
            gains.add(eq.getBandLevel(i.toShort()).toInt())
        }

        _state.value = _state.value.copy(
            bands = gains,
            bandFrequencies = frequencies
        )
    }

    fun setEnabled(enabled: Boolean) {
        equalizer?.enabled = enabled
        _state.value = _state.value.copy(enabled = enabled)
    }

    fun setBandGain(bandIndex: Int, gainMillibels: Int) {
        val eq = equalizer ?: return
        if (bandIndex in 0 until eq.numberOfBands) {
            eq.setBandLevel(bandIndex.toShort(), gainMillibels.toShort())
            val updatedBands = _state.value.bands.toMutableList()
            if (bandIndex < updatedBands.size) {
                updatedBands[bandIndex] = gainMillibels
                _state.value = _state.value.copy(
                    bands = updatedBands,
                    selectedPreset = "Custom"
                )
            }
        }
    }

    fun applyPreset(presetName: String) {
        val newGains = when (presetName) {
            "Bass Boost" -> listOf(600, 400, 0, 0, 200)
            "Vocal" -> listOf(-200, 100, 500, 300, -100)
            "Acoustic" -> listOf(300, 200, 100, 300, 400)
            else -> listOf(0, 0, 0, 0, 0)
        }

        // Apply all bands to the native effect first and publish a single state
        // update; emitting once per band caused one recomposition of the whole
        // equalizer screen per band on every preset tap.
        val eq = equalizer
        if (eq != null) {
            newGains.forEachIndexed { index, gain ->
                if (index in 0 until eq.numberOfBands) {
                    eq.setBandLevel(index.toShort(), gain.toShort())
                }
            }
        }
        _state.value = _state.value.copy(
            bands = newGains,
            selectedPreset = presetName
        )
    }

    fun release() {
        equalizer?.release()
        equalizer = null
    }
}