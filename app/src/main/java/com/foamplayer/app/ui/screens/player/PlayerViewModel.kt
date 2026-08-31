package com.foamplayer.app.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val audioEngine: AudioEngine,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = audioEngine.playbackState

    val isImmersiveModeEnabled: StateFlow<Boolean> = settingsRepository.isImmersiveModeEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val isAnimateSeekBarEnabled: StateFlow<Boolean> = settingsRepository.isAnimateSeekBarEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    private val _dynamicAccentColor = MutableStateFlow<Color?>(null)
    val dynamicAccentColor: StateFlow<Color?> = _dynamicAccentColor.asStateFlow()

    fun setDynamicAccentColor(color: Color?) {
        _dynamicAccentColor.value = color
    }

    fun playQueue(tracks: List<TrackEntity>, startIndex: Int) {
        audioEngine.setQueue(tracks, startIndex)
    }

    fun play() = audioEngine.play()
    fun pause() = audioEngine.pause()
    fun next() = audioEngine.next()
    fun previous() = audioEngine.previous()
    fun skipToIndex(index: Int) = audioEngine.skipToIndex(index)
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = audioEngine.moveQueueItem(fromIndex, toIndex)
    fun removeQueueItem(index: Int) = audioEngine.removeQueueItem(index)
    fun seekTo(positionMs: Long) = audioEngine.seekTo(positionMs)
    fun toggleShuffle() = audioEngine.toggleShuffle()
    fun toggleRepeat() = audioEngine.toggleRepeat()
}