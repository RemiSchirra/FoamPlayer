package com.foamplayer.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.LyricsRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val artists: List<String> = emptyList(),
    val albums: List<AlbumSearchResult> = emptyList(),
    val history: List<TrackEntity> = emptyList(),
    /** Tracks whose locally available lyrics contain the query (streams in as the scan progresses). */
    val lyricsMatches: List<TrackEntity> = emptyList(),
    /** True while the background lyrics scan is still running. */
    val lyricsSearchInProgress: Boolean = false
)

/** Progressive result of the background lyrics text scan. */
private data class LyricsSearchState(
    val matches: List<TrackEntity> = emptyList(),
    val inProgress: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val settingsRepository: SettingsRepository,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Metadata matches (tracks/artists/albums + history): fast Room queries, no debounce. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val metadataState: Flow<SearchUiState> = _query
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

    /**
     * Lyrics scan: after a short debounce the whole library is inspected in
     * parallel on background threads; matches stream into the UI as they are
     * found. Local sources only — nothing is fetched online. Switching to a
     * new query cancels the previous scan.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val lyricsState: Flow<LyricsSearchState> = _query
        .debounce { if (it.isBlank()) 0L else LYRICS_SEARCH_DEBOUNCE_MS }
        .flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(LyricsSearchState())
            } else {
                flow {
                    emit(LyricsSearchState(inProgress = true))
                    val matches = mutableListOf<TrackEntity>()
                    val allTracks = repository.getAllTracksOnce()
                    lyricsRepository.searchLyricsText(query, allTracks).collect { match ->
                        matches += match
                        emit(LyricsSearchState(matches = matches.toList(), inProgress = true))
                    }
                    emit(LyricsSearchState(matches = matches.toList(), inProgress = false))
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SearchUiState> = combine(metadataState, lyricsState) { metadata, lyrics ->
        metadata.copy(
            lyricsMatches = lyrics.matches,
            lyricsSearchInProgress = lyrics.inProgress
        )
    }.stateIn(
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

    private companion object {
        /** Grace period after the last keystroke before the (heavier) lyrics scan starts. */
        const val LYRICS_SEARCH_DEBOUNCE_MS = 400L
    }
}
