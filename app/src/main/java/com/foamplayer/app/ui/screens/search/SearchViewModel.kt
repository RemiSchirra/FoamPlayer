package com.foamplayer.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.LyricsRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.settings.AppSettings
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchFilter { LYRICS, ALBUMS, ARTISTS, TRACKS }

data class SearchFilters(
    val lyrics: Boolean = true,
    val albums: Boolean = true,
    val artists: Boolean = true,
    val tracks: Boolean = true
) {
    val allEnabled: Boolean get() = lyrics && albums && artists && tracks
}

data class SearchUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val artists: List<String> = emptyList(),
    val albums: List<AlbumSearchResult> = emptyList(),
    val history: List<TrackEntity> = emptyList(),
    /** Tracks whose locally available lyrics contain the query (streams in as the scan progresses). */
    val lyricsMatches: List<TrackEntity> = emptyList(),
    /** True while the background lyrics scan is still running. */
    val lyricsSearchInProgress: Boolean = false,
    val filters: SearchFilters = SearchFilters()
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

    /**
     * In-memory mirror of the scope filters: toggles land here first so results
     * react instantly, while the write to DataStore happens in the background.
     */
    private val _filters = MutableStateFlow(SearchFilters())

    init {
        viewModelScope.launch {
            _filters.value = settingsRepository.settings.first().toSearchFilters()
        }
    }

    /** Metadata matches (tracks/artists/albums + history): fast Room queries, short debounce. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val metadataState: Flow<SearchUiState> = _query
        .debounce { if (it.isBlank()) 0L else METADATA_SEARCH_DEBOUNCE_MS }
        .flatMapLatest { query ->
            if (query.isBlank()) {
                combine(
                    repository.getAllTracks(),
                    settingsRepository.settings
                ) { allTracks, settings ->
                    SearchUiState(history = resolveHistory(settings.searchHistory, allTracks))
                }
            } else {
                combine(
                    repository.searchTracks(query),
                    repository.searchArtists(query),
                    repository.searchAlbums(query),
                    repository.getAllTracks(),
                    settingsRepository.settings
                ) { tracks, artists, albums, allTracks, settings ->
                    SearchUiState(tracks, artists, albums, resolveHistory(settings.searchHistory, allTracks))
                }
            }
        }

    /**
     * Resolves search-history paths to tracks in one pass. The old
     * `searchHistory.mapNotNull { path -> allTracks.find { ... } }` was
     * O(history × library) and re-ran on the main dispatcher for every
     * keystroke / table / settings emission.
     */
    private fun resolveHistory(historyPaths: List<String>, allTracks: List<TrackEntity>): List<TrackEntity> {
        val byPath = HashMap<String, TrackEntity>(allTracks.size)
        for (track in allTracks) byPath[track.path] = track
        return historyPaths.mapNotNull(byPath::get)
    }

    /**
     * Lyrics scan: after a short debounce the whole library is inspected in
     * parallel on background threads; matches stream into the UI as they are
     * found. Local sources only — nothing is fetched online. Switching to a
     * new query cancels the previous scan.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val lyricsState: Flow<LyricsSearchState> = combine(
        // Debounce only the keystrokes: toggling the scope must cancel the running
        // scan and drop its results immediately, without waiting out the grace period.
        _query.debounce { if (it.isBlank()) 0L else LYRICS_SEARCH_DEBOUNCE_MS },
        _filters.map { it.lyrics }.distinctUntilChanged()
    ) { query, lyricsEnabled -> query to lyricsEnabled }
        .flatMapLatest { (query, lyricsEnabled) ->
            if (query.isBlank() || !lyricsEnabled) {
                flowOf(LyricsSearchState())
            } else {
                flow {
                    emit(LyricsSearchState(inProgress = true))
                    val matches = mutableListOf<TrackEntity>()
                    val allTracks = repository.getAllTracksOnce()
                    var lastEmitNanos = System.nanoTime()
                    lyricsRepository.searchLyricsText(query, allTracks).collect { match ->
                        matches += match
                        // Emission per match used to copy the whole list (O(n²)
                        // cumulative) and rebuild the combined UI state once per
                        // matched track; batching to a fixed cadence keeps the UI
                        // streaming while bounding the recomposition rate.
                        val now = System.nanoTime()
                        if (now - lastEmitNanos >= LYRICS_MATCH_EMIT_INTERVAL_MS) {
                            lastEmitNanos = now
                            emit(LyricsSearchState(matches = matches.toList(), inProgress = true))
                        }
                    }
                    emit(LyricsSearchState(matches = matches.toList(), inProgress = false))
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SearchUiState> = combine(metadataState, lyricsState, _filters) { metadata, lyrics, filters ->
        metadata.copy(
            tracks = if (filters.tracks) metadata.tracks else emptyList(),
            artists = if (filters.artists) metadata.artists else emptyList(),
            albums = if (filters.albums) metadata.albums else emptyList(),
            // Gated on the in-memory flag (not the scan flow) so toggling off hides
            // the section in this same emission, without waiting for the scan's
            // cancellation to round-trip back as an empty result.
            lyricsMatches = if (filters.lyrics) lyrics.matches else emptyList(),
            lyricsSearchInProgress = if (filters.lyrics) lyrics.inProgress else false,
            filters = filters
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SearchUiState()
    )

    fun search(query: String) {
        _query.value = query
    }

    fun setLyricsFilterEnabled(enabled: Boolean) {
        _filters.value = _filters.value.copy(lyrics = enabled)
        persistFilters()
    }

    fun setAlbumsFilterEnabled(enabled: Boolean) {
        _filters.value = _filters.value.copy(albums = enabled)
        persistFilters()
    }

    fun setArtistsFilterEnabled(enabled: Boolean) {
        _filters.value = _filters.value.copy(artists = enabled)
        persistFilters()
    }

    fun setTracksFilterEnabled(enabled: Boolean) {
        _filters.value = _filters.value.copy(tracks = enabled)
        persistFilters()
    }

    /** Long-press: enable only [filter], turning every other scope off. */
    fun soloSearchFilter(filter: SearchFilter) {
        _filters.value = SearchFilters(
            lyrics = filter == SearchFilter.LYRICS,
            albums = filter == SearchFilter.ALBUMS,
            artists = filter == SearchFilter.ARTISTS,
            tracks = filter == SearchFilter.TRACKS
        )
        persistFilters()
    }

    /** "All" chip: enable every scope, or turn everything off when tapped while fully on. */
    fun setAllFiltersEnabled(enabled: Boolean) {
        _filters.value = SearchFilters(
            lyrics = enabled,
            albums = enabled,
            artists = enabled,
            tracks = enabled
        )
        persistFilters()
    }

    private fun persistFilters() {
        val current = _filters.value
        viewModelScope.launch {
            settingsRepository.setSearchFilters(
                lyrics = current.lyrics,
                albums = current.albums,
                artists = current.artists,
                tracks = current.tracks
            )
        }
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

        /** Grace period after the last keystroke before the (cheap) metadata queries run. */
        const val METADATA_SEARCH_DEBOUNCE_MS = 120L

        /** Minimum interval between streamed lyrics-scan progress emissions. */
        const val LYRICS_MATCH_EMIT_INTERVAL_MS = 100L * 1_000_000L
    }
}

private fun AppSettings.toSearchFilters(): SearchFilters =
    SearchFilters(
        lyrics = searchInLyrics,
        albums = searchInAlbums,
        artists = searchInArtists,
        tracks = searchInTracks
    )
