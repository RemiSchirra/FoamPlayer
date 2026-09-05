package com.foamplayer.app.ui.screens.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.data.repository.LyricsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val audioEngine: AudioEngine,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    private val _lyrics = MutableStateFlow<String?>(null)
    val lyrics: StateFlow<String?> = _lyrics.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val currentPositionMs: StateFlow<Long> = audioEngine.playbackState
        .map { it.currentPositionMs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val durationMs: StateFlow<Long> = audioEngine.playbackState
        .map { it.durationMs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val isPlaying: StateFlow<Boolean> = audioEngine.playbackState
        .map { it.isPlaying }
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

                    val result = lyricsRepository.getLyrics(track)
                    _lyrics.value = result
                    _isLoading.value = false
                }
        }
    }

    fun seekTo(positionMs: Long) {
        audioEngine.seekTo(positionMs)
    }
}