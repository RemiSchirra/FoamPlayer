package com.foamplayer.app.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.settings.AppSettings
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

    // Live position, updated 5x/sec while playing. Deliberately kept separate from
    // playbackState — see the comment on AudioEngine.positionMs. Read this via
    // `.value` from a leaf composable/provider lambda (as FullPlayerScreen's
    // seekBarProgress and LyricsScreen's currentPositionMsProvider already do),
    // not via collectAsState() at a screen's top level, or you'll reintroduce the
    // same tick-rate recomposition this was added to avoid.
    val positionMs: StateFlow<Long> = audioEngine.positionMs

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppSettings()
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