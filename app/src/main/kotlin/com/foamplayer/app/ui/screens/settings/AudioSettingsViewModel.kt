package com.foamplayer.app.ui.screens.settings

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.foamplayer.app.audio.device.AudioOutputDevice
import com.foamplayer.app.audio.device.DeviceAudioConfig
import com.foamplayer.app.audio.device.DeviceBitPerfectMode
import com.foamplayer.app.audio.device.OutputDeviceManager
import com.foamplayer.app.audio.dsp.DspEngine
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.audio.dsp.EqBandLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Audio settings screen and its subscreens (playback, devices,
 * equalizer layout): fidelity score, pipeline visualization, global engine
 * switches and the ISO band-layout choice of the graphic EQ.
 */
@HiltViewModel
class AudioSettingsViewModel @OptIn(UnstableApi::class)
@Inject constructor(
    private val settingsRepository: SettingsRepository,
    outputDeviceManager: OutputDeviceManager,
    private val dspEngine: DspEngine,
    audioEngine: AudioEngine
) : ViewModel() {

    init {
        // Idempotent: covers the settings-before-any-playback case. The engine
        // normally starts it as soon as the playback service comes up.
        outputDeviceManager.start()
    }

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    /** Every audio output currently attached to the system. */
    val devices: StateFlow<List<AudioOutputDevice>> = outputDeviceManager.devices

    /** The device the engine currently routes to. */
    val activeDevice: StateFlow<AudioOutputDevice?> = outputDeviceManager.activeDevice

    /** Carries PlaybackState.audioOutputInfo (bit-perfect verdict, score, pipeline). */
    val playbackState: StateFlow<PlaybackState> = audioEngine.playbackState

    val dspState: StateFlow<DspState> = dspEngine.state

    @OptIn(UnstableApi::class)
    fun setEqBandLayout(layout: EqBandLayout) = launch {
        dspEngine.setGraphicBandCount(layout.bands)
    }

    fun setBitPerfectEnabled(enabled: Boolean) = launch {
        settingsRepository.setBitPerfectEnabled(enabled)
    }

    fun setNormalFadeSeconds(seconds: Float) = launch {
        settingsRepository.setNormalFadeSeconds(seconds)
    }

    fun setQuickFadeSeconds(seconds: Float) = launch {
        settingsRepository.setQuickFadeSeconds(seconds)
    }

    fun setSilenceBetweenTracksSeconds(seconds: Float) = launch {
        settingsRepository.setSilenceBetweenTracksSeconds(seconds)
    }

    fun setResumeOnStartup(enabled: Boolean) = launch {
        settingsRepository.setResumeOnStartup(enabled)
    }

    fun setResumeOnDeviceConnected(enabled: Boolean) = launch {
        settingsRepository.setResumeOnDeviceConnected(enabled)
    }

    fun setPauseOnDeviceDisconnected(enabled: Boolean) = launch {
        settingsRepository.setPauseOnDeviceDisconnected(enabled)
    }

    @OptIn(UnstableApi::class)
    fun setDirectVolumeControl(enabled: Boolean) = launch {
        dspEngine.setDirectVolumeControl(enabled)
    }

    fun setDeviceBitPerfectMode(deviceId: String, mode: DeviceBitPerfectMode) = launch {
        val current = settings.value.deviceAudioConfigs[deviceId] ?: DeviceAudioConfig()
        settingsRepository.setDeviceAudioConfig(deviceId, current.copy(bitPerfectMode = mode))
    }

    fun setDeviceSampleRateOverride(deviceId: String, sampleRateHz: Int) = launch {
        val current = settings.value.deviceAudioConfigs[deviceId] ?: DeviceAudioConfig()
        settingsRepository.setDeviceAudioConfig(
            deviceId,
            current.copy(sampleRateOverrideHz = sampleRateHz)
        )
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
