package com.foamplayer.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val artists: List<String> = emptyList(),
    val albums: List<AlbumSearchResult> = emptyList()
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            combine(
                repository.getAllTracks(),
                _query
            ) { _, query -> query }.collect { query ->
                if (query.isBlank()) {
                    _uiState.value = SearchUiState()
                    return@collect
                }

                launch {
                    repository.searchTracks(query).collect { tracks ->
                        _uiState.value = _uiState.value.copy(tracks = tracks)
                    }
                }
                launch {
                    repository.searchArtists(query).collect { artists ->
                        _uiState.value = _uiState.value.copy(artists = artists)
                    }
                }
                launch {
                    repository.searchAlbums(query).collect { albums ->
                        _uiState.value = _uiState.value.copy(albums = albums)
                    }
                }
            }
        }
    }

    fun search(query: String) {
        _query.value = query
    }
}
