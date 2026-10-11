package com.foamplayer.app.ui.screens.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.data.repository.LyricsRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.lyrics.isInstrumentalOnlyLyrics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val audioEngine: AudioEngine,
    private val lyricsRepository: LyricsRepository,
    private val settingsRepository: SettingsRepository,
    private val trackRepository: TrackRepository
) : ViewModel() {

    private val _lyrics = MutableStateFlow<String?>(null)
    val lyrics: StateFlow<String?> = _lyrics.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // NOT sourced from playbackState — that field only updates on track transitions and
    // manual seeks now (see AudioEngine.positionMs), so mapping it here would freeze the
    // karaoke sync loop in LyricsEnhanced almost immediately (it drifts, gets clamped,
    // and keeps snapping back to a stale value instead of advancing). audioEngine.positionMs
    // is the fast, always-ticking flow; forward it directly.
    val currentPositionMs: StateFlow<Long> = audioEngine.positionMs

    val durationMs: StateFlow<Long> = audioEngine.playbackState
        .map { it.durationMs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val isPlaying: StateFlow<Boolean> = audioEngine.playbackState
        .map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Blur of non-active lyric lines, as chosen in Settings > Lyrics > Display.
    val lyricsLineBlur: StateFlow<Boolean> = settingsRepository.settings
        .map { it.lyricsLineBlur }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val lyricsWordSyncDisabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.lyricsWordSyncDisabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Instrumental flag of the current track, read straight from the DB so marking a
    // track instrumental flips the lyrics subview to the music-note state immediately.
    @OptIn(ExperimentalCoroutinesApi::class)
    val isInstrumental: StateFlow<Boolean> = audioEngine.playbackState
        .map { it.currentTrack }
        .distinctUntilChanged { old, new -> old?.id == new?.id }
        .flatMapLatest { track ->
            if (track == null) flowOf(false) else trackRepository.observeInstrumental(track.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        observeCurrentTrack()
    }

    @OptIn(FlowPreview::class)
    private fun observeCurrentTrack() {
        viewModelScope.launch {
            audioEngine.playbackState
                .map { it.currentTrack }
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { track ->
                    if (track == null) {
                        _lyrics.value = null
                        return@collectLatest
                    }

                    _isLoading.value = true
                    _lyrics.value = null

                    // User-marked instrumental tracks never go out to the providers.
                    val instrumental = withContext(Dispatchers.IO) {
                        trackRepository.observeInstrumental(track.id).first()
                    }
                    if (instrumental) {
                        _lyrics.value = null
                        _isLoading.value = false
                        return@collectLatest
                    }

                    val result = withContext(Dispatchers.IO) { lyricsRepository.getLyrics(track) }

                    if (result != null && isInstrumentalOnlyLyrics(result)) {
                        withContext(Dispatchers.IO) {
                            trackRepository.setInstrumental(track.id, true)
                        }
                        trackRepository.observeInstrumental(track.id).first { it }
                        _lyrics.value = null
                        _isLoading.value = false
                        return@collectLatest
                    }

                    _lyrics.value = result
                    _isLoading.value = false
                }
        }
    }

    fun markCurrentTrackInstrumental() {
        val track = audioEngine.playbackState.value.currentTrack ?: return
        viewModelScope.launch { trackRepository.setInstrumental(track.id, true) }
    }

    fun seekTo(positionMs: Long) {
        audioEngine.seekTo(positionMs)
    }
}
