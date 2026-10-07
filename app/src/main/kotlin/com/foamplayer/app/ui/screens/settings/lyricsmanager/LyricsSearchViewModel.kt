package com.foamplayer.app.ui.screens.settings.lyricsmanager

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.lyrics.LyricsResultType
import com.foamplayer.app.data.lyrics.LyricsSearchResult
import com.foamplayer.app.data.repository.LyricsRepository
import com.foamplayer.app.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Loading state of the multi-provider lyrics search. */
sealed interface LyricsSearchUiState {
    data object Loading : LyricsSearchUiState
    data class Loaded(val results: List<LyricsSearchResult>) : LyricsSearchUiState
    data object NotFound : LyricsSearchUiState
}

/** Outcome of a save/embed action, surfaced to the user as a snackbar. */
sealed interface LyricsWriteEvent {
    data object SavedToFile : LyricsWriteEvent
    data object Embedded : LyricsWriteEvent
    data class SaveFailed(val reason: String) : LyricsWriteEvent
    data class EmbedFailed(val reason: String) : LyricsWriteEvent
}

@HiltViewModel
class LyricsSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val trackRepository: TrackRepository,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    private val trackId: Long = savedStateHandle.get<Long>("trackId") ?: -1L

    private val _track = MutableStateFlow<TrackEntity?>(null)
    val track: StateFlow<TrackEntity?> = _track.asStateFlow()

    private val _uiState = MutableStateFlow<LyricsSearchUiState>(LyricsSearchUiState.Loading)
    val uiState: StateFlow<LyricsSearchUiState> = _uiState.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _events = Channel<LyricsWriteEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadAndSearch()
    }

    fun search() {
        val currentTrack = _track.value ?: return
        _uiState.value = LyricsSearchUiState.Loading
        viewModelScope.launch {
            val results = lyricsRepository.searchAllProviders(currentTrack)
            _uiState.value =
                if (results.isEmpty()) LyricsSearchUiState.NotFound
                else LyricsSearchUiState.Loaded(results)
        }
    }

    private fun loadAndSearch() {
        _uiState.value = LyricsSearchUiState.Loading
        viewModelScope.launch {
            val loadedTrack = trackRepository.getTrackById(trackId)
            _track.value = loadedTrack
            if (loadedTrack == null) {
                _uiState.value = LyricsSearchUiState.NotFound
                return@launch
            }
            val results = lyricsRepository.searchAllProviders(loadedTrack)
            _uiState.value =
                if (results.isEmpty()) LyricsSearchUiState.NotFound
                else LyricsSearchUiState.Loaded(results)
        }
    }

    /** Writes the result as a .lrc/.ttml sidecar next to the audio file. */
    fun saveAsFile(trackMetadata: TrackEntity, result: LyricsSearchResult) {
        performWrite(
            onSuccess = LyricsWriteEvent.SavedToFile,
            onFailure = LyricsWriteEvent::SaveFailed
        ) { repository ->
            repository.saveLyricsFile(
                track = trackMetadata,
                content = result.content,
                wordSynced = result.type == LyricsResultType.WORD_SYNCED
            )
        }
    }

    /** Writes the result into the audio file's embedded lyrics tag. */
    fun embed(trackMetadata: TrackEntity, result: LyricsSearchResult) {
        performWrite(
            onSuccess = LyricsWriteEvent.Embedded,
            onFailure = LyricsWriteEvent::EmbedFailed
        ) { repository -> repository.embedLyrics(trackMetadata, result.content) }
    }

    private fun performWrite(
        onSuccess: LyricsWriteEvent,
        onFailure: (String) -> LyricsWriteEvent,
        block: suspend (LyricsRepository) -> Result<Unit>
    ) {
        if (_isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            block(lyricsRepository).fold(
                onSuccess = { _events.send(onSuccess) },
                onFailure = { error -> _events.send(onFailure(error.message ?: "")) }
            )
            _isSaving.value = false
        }
    }
}
