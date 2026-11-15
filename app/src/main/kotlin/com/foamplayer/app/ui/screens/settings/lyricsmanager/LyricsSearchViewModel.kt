package com.foamplayer.app.ui.screens.settings.lyricsmanager

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.lyrics.LyricsResultType
import com.foamplayer.app.data.lyrics.LyricsSearchResult
import com.foamplayer.app.data.lyrics.SavedLyricsEntry
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

/** Loading state of the track's saved lyrics (cache / sidecar file / embedded tag). */
sealed interface SavedLyricsUiState {
    data object Loading : SavedLyricsUiState
    data class Loaded(val entries: List<SavedLyricsEntry>) : SavedLyricsUiState
}

/** Outcome of a save/embed/delete action, surfaced to the user as a toast. */
sealed interface LyricsEvent {
    data object SavedToFile : LyricsEvent
    data object Embedded : LyricsEvent
    data object Deleted : LyricsEvent
    data class SaveFailed(val reason: String) : LyricsEvent
    data class EmbedFailed(val reason: String) : LyricsEvent
    data class DeleteFailed(val reason: String) : LyricsEvent
}

/**
 * View model of the per-track lyrics details screen: the saved-lyrics tab
 * (every payload stored in the online cache, the sidecar file and the embedded
 * tag, each deletable) and the search tab (results of the multi-provider
 * search started on load with the track's own tags, with save-as-file / embed
 * actions on the results).
 */
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

    private val _savedLyrics = MutableStateFlow<SavedLyricsUiState>(SavedLyricsUiState.Loading)
    val savedLyrics: StateFlow<SavedLyricsUiState> = _savedLyrics.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _events = Channel<LyricsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadSavedLyrics()
        loadAndSearch()
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

    /**
     * Loads every lyrics payload stored for the track (persisted online cache,
     * sidecar file, embedded tag) — never the network.
     */
    fun loadSavedLyrics() {
        viewModelScope.launch {
            val loadedTrack = _track.value ?: trackRepository.getTrackById(trackId)
            _track.value = loadedTrack
            if (loadedTrack == null) {
                _savedLyrics.value = SavedLyricsUiState.Loaded(emptyList())
                return@launch
            }
            val entries = runCatching { lyricsRepository.getSavedLyrics(loadedTrack) }
                .getOrDefault(emptyList())
            _savedLyrics.value = SavedLyricsUiState.Loaded(entries)
        }
    }

    /**
     * Removes only the lyrics stored in [entry]'s source (cache entry, sidecar
     * files, or embedded tag), then reloads the saved list.
     */
    fun deleteSavedLyrics(entry: SavedLyricsEntry) {
        val currentTrack = _track.value ?: return
        if (_isDeleting.value) return
        _isDeleting.value = true
        viewModelScope.launch {
            lyricsRepository.deleteSavedLyrics(currentTrack, entry.source).fold(
                onSuccess = {
                    _events.send(LyricsEvent.Deleted)
                    loadSavedLyrics()
                },
                onFailure = { error -> _events.send(LyricsEvent.DeleteFailed(error.message ?: "")) }
            )
            _isDeleting.value = false
        }
    }

    /** Writes the result as a .lrc/.ttml sidecar next to the audio file. */
    fun saveAsFile(trackMetadata: TrackEntity, result: LyricsSearchResult) {
        performWrite(
            onSuccess = LyricsEvent.SavedToFile,
            onFailure = LyricsEvent::SaveFailed
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
            onSuccess = LyricsEvent.Embedded,
            onFailure = LyricsEvent::EmbedFailed
        ) { repository -> repository.embedLyrics(trackMetadata, result.content) }
    }

    private fun performWrite(
        onSuccess: LyricsEvent,
        onFailure: (String) -> LyricsEvent,
        block: suspend (LyricsRepository) -> Result<Unit>
    ) {
        if (_isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            block(lyricsRepository).fold(
                onSuccess = {
                    _events.send(onSuccess)
                    // A write changes the file/embedded sources the saved-lyrics tab mirrors.
                    loadSavedLyrics()
                },
                onFailure = { error -> _events.send(onFailure(error.message ?: "")) }
            )
            _isSaving.value = false
        }
    }
}
