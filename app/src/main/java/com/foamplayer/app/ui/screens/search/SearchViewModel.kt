package com.foamplayer.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val artists: List<String> = emptyList(),
    val albums: List<AlbumSearchResult> = emptyList(),
    val history: List<TrackEntity> = emptyList()
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SearchUiState> = _query
        .flatMapLatest { query ->
            if (query.isBlank()) {
                combine(
                    repository.getAllTracks(),
                    settingsRepository.settings
                ) { allTracks, settings ->
                    val history = settings.searchHistory.mapNotNull { path ->
                        allTracks.find { it.path == path }
                    }
                    SearchUiState(history = history)
                }
            } else {
                combine(
                    repository.searchTracks(query),
                    repository.searchArtists(query),
                    repository.searchAlbums(query),
                    repository.getAllTracks(),
                    settingsRepository.settings
                ) { tracks, artists, albums, allTracks, settings ->
                    val history = settings.searchHistory.mapNotNull { path ->
                        allTracks.find { it.path == path }
                    }
                    SearchUiState(tracks, artists, albums, history)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SearchUiState()
        )

    fun search(query: String) {
        _query.value = query
    }

    fun addToHistory(track: TrackEntity) {
        viewModelScope.launch {
            val currentSettings = settingsRepository.settings.first()
            val newHistory = (listOf(track.path) + currentSettings.searchHistory)
                .distinct()
                .take(20)
            settingsRepository.setSearchHistory(newHistory)
        }
    }

    fun removeFromHistory(track: TrackEntity) {
        viewModelScope.launch {
            val currentSettings = settingsRepository.settings.first()
            val newHistory = currentSettings.searchHistory.filter { it != track.path }
            settingsRepository.setSearchHistory(newHistory)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            settingsRepository.setSearchHistory(emptyList())
        }
    }
}
