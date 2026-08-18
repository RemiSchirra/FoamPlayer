package com.foamplayer.app.ui.screens.equalizer

import androidx.lifecycle.ViewModel
import com.foamplayer.app.audio.equalizer.EqualizerManager
import com.foamplayer.app.audio.equalizer.EqualizerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val equalizerManager: EqualizerManager
) : ViewModel() {

    val state: StateFlow<EqualizerState> = equalizerManager.state

    fun toggleEqualizer(enabled: Boolean) {
        equalizerManager.setEnabled(enabled)
    }

    fun setBandGain(bandIndex: Int, gainMillibels: Int) {
        equalizerManager.setBandGain(bandIndex, gainMillibels)
    }

    fun selectPreset(preset: String) {
        equalizerManager.applyPreset(preset)
    }
}