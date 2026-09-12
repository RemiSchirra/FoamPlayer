package com.foamplayer.app.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val audioEngine: AudioEngine,
    private val settingsRepository: SettingsRepository,
    private val trackRepository: TrackRepository
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = audioEngine.playbackState

    val positionMs: StateFlow<Long> = audioEngine.positionMs

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppSettings()
    )

    private val _dynamicAccentColor = MutableStateFlow<Color?>(null)
    val dynamicAccentColor: StateFlow<Color?> = _dynamicAccentColor.asStateFlow()

    private val _openPlayerRequested = MutableStateFlow(false)
    val openPlayerRequested: StateFlow<Boolean> = _openPlayerRequested.asStateFlow()

    fun setDynamicAccentColor(color: Color?) {
        _dynamicAccentColor.value = color
    }

    fun requestOpenPlayer() {
        _openPlayerRequested.value = true
    }

    fun consumeOpenPlayerRequest() {
        _openPlayerRequested.value = false
    }

    fun playQueue(tracks: List<TrackEntity>, startIndex: Int) {
        audioEngine.setQueue(tracks, startIndex)
    }

    // Long-press track actions from the library lists: queue right after the
    // current track, or append to the end of the main queue.
    fun playNext(track: TrackEntity) = audioEngine.playNext(track)
    fun addToQueue(track: TrackEntity) = audioEngine.addToQueue(track)

    // Clicking a track from search results: keep the played history, drop the
    // upcoming queue, and play the picked track next.
    fun playTrackReplacingUpcoming(track: TrackEntity) =
        audioEngine.playTrackReplacingUpcoming(track)

    fun play() = audioEngine.play()
    fun pause() = audioEngine.pause()
    fun next() = audioEngine.next()
    fun previous() = audioEngine.previous()
    fun skipToIndex(index: Int) = audioEngine.skipToIndex(index)

    // Queue mutations are occurrence-addressed (entry ids), resolved inside the engine
    // against current state — never by indices captured in a possibly-stale UI snapshot.
    fun playQueueEntry(entryId: Long) = audioEngine.playQueueEntry(entryId)
    fun moveQueueEntry(entryId: Long, toUpcomingPosition: Int) =
        audioEngine.moveQueueEntry(entryId, toUpcomingPosition)
    fun removeQueueEntry(entryId: Long) = audioEngine.removeQueueEntry(entryId)

    fun seekTo(positionMs: Long) = audioEngine.seekTo(positionMs)
    fun toggleShuffle() = audioEngine.toggleShuffle()
    fun toggleRepeat() = audioEngine.toggleRepeat()

    // Track details modal: reset the listen counter and last-listen date.
    fun resetPlayStats(trackId: Long) {
        viewModelScope.launch { trackRepository.resetPlayStats(trackId) }
    }

    // Track scores (-1 dislike / 0 not noted / 1 like / 2 super like): the in-memory
    // queue entities go stale the moment a score is written to the DB, so the UI
    // overlays this id→score map on top of the entity values.
    private val _scoreOverrides = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val scoreOverrides: StateFlow<Map<Long, Int>> = _scoreOverrides.asStateFlow()

    fun setTrackScore(trackId: Long, score: Int) {
        _scoreOverrides.value = _scoreOverrides.value + (trackId to score)
        viewModelScope.launch { trackRepository.setScore(trackId, score) }
    }
}
