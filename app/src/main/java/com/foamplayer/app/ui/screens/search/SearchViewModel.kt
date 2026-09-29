package com.foamplayer.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.LyricsRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ArtistCover
import com.foamplayer.app.data.scanner.ArtistCoverProvider
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.data.settings.SearchHistoryEntry
import com.foamplayer.app.data.settings.identityKey
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

/** A search scope the user can toggle on/off (long-press solos it). */
enum class SearchFilter { LYRICS, ALBUMS, ARTISTS, TRACKS }

/** Which scopes are included in the search. */
data class SearchFilters(
    val lyrics: Boolean = true,
    val albums: Boolean = true,
    val artists: Boolean = true,
    val tracks: Boolean = true
) {
    val allEnabled: Boolean get() = lyrics && albums && artists && tracks
}

/** Everything the search screen renders. */
data class SearchUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val artists: List<String> = emptyList(),
    val albums: List<AlbumSearchResult> = emptyList(),
    val history: List<RecentSearch> = emptyList(),
    /** Tracks whose locally available lyrics contain the query (streams in as the scan progresses). */
    val lyricsMatches: List<TrackEntity> = emptyList(),
    /** True while the background lyrics scan is still running. */
    val lyricsSearchInProgress: Boolean = false,
    val filters: SearchFilters = SearchFilters()
)

/** A persisted history entry resolved against the current library for display. */
sealed interface RecentSearch {
    data class Track(val track: TrackEntity) : RecentSearch
    data class Album(val album: AlbumSearchResult) : RecentSearch
    data class Artist(val name: String) : RecentSearch
}

/** Progressive result of the background lyrics text scan. */
private data class LyricsSearchState(
    val matches: List<TrackEntity> = emptyList(),
    val inProgress: Boolean = false
)

/**
 * Search state: debounced metadata queries (tracks/artists/albums), a
 * background lyrics text scan streaming matches in, persisted history
 * resolution, and scope filters mirrored in memory for instant toggles.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val settingsRepository: SettingsRepository,
    private val lyricsRepository: LyricsRepository,
    artistCoverProvider: ArtistCoverProvider
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Resolved pictures per artist name: a cached Spotify image when the scan
     * found one, else the artist's first track with embedded art (both are
     * automatic — no scan has to run for the fallback), for the artist result
     * rows.
     */
    val artistImages: StateFlow<Map<String, ArtistCover>> =
        artistCoverProvider.coversFlow(repository.getAllArtists())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

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

    /** Metadata matches (tracks/artists/albums): fast Room queries, short debounce. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val metadataState: Flow<SearchUiState> = _query
        .debounce { if (it.isBlank()) 0L else METADATA_SEARCH_DEBOUNCE_MS }
        .flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(SearchUiState())
            } else {
                combine(
                    repository.searchTracks(query),
                    repository.searchArtists(query),
                    repository.searchAlbums(query)
                ) { tracks, artists, albums ->
                    SearchUiState(tracks, artists, albums)
                }
            }
        }

    private val historyState: Flow<List<RecentSearch>> = combine(
        settingsRepository.settings.map { it.searchHistory },
        repository.getAllTracks(),
        repository.getAllAlbums(),
        repository.getAllArtists()
    ) { history, allTracks, allAlbums, allArtists ->
        resolveHistory(history, allTracks, allAlbums, allArtists)
    }

    /** Resolves persisted history entries to displayable items; missing entries are dropped. */
    private fun resolveHistory(
        history: List<SearchHistoryEntry>,
        allTracks: List<TrackEntity>,
        allAlbums: List<AlbumSearchResult>,
        allArtists: List<String>
    ): List<RecentSearch> {
        val tracksByPath = HashMap<String, TrackEntity>(allTracks.size)
        for (track in allTracks) tracksByPath[track.path] = track
        val albumsByName = HashMap<String, AlbumSearchResult>(allAlbums.size)
        for (album in allAlbums) albumsByName[album.album] = album
        val artists = allArtists.toHashSet()
        return history.mapNotNull { entry ->
            when (entry) {
                is SearchHistoryEntry.Track -> tracksByPath[entry.path]?.let(RecentSearch::Track)
                is SearchHistoryEntry.Album -> albumsByName[entry.album]?.let(RecentSearch::Album)
                is SearchHistoryEntry.Artist -> entry.name.takeIf { it in artists }?.let(RecentSearch::Artist)
            }
        }
    }

    /**
     * Lyrics scan: after a debounce the whole library is inspected in parallel;
     * matches stream in as they are found (local sources only, never online).
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
                        // Batched emissions keep the UI streaming while bounding
                        // the recomposition rate.
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
    val uiState: StateFlow<SearchUiState> = combine(metadataState, lyricsState, historyState, _filters) { metadata, lyrics, history, filters ->
        metadata.copy(
            tracks = if (filters.tracks) metadata.tracks else emptyList(),
            artists = if (filters.artists) metadata.artists else emptyList(),
            albums = if (filters.albums) metadata.albums else emptyList(),
            history = history,
            // Gated on the in-memory flag so toggling off hides the section in
            // this same emission, without waiting for the scan's cancellation.
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

    fun addToHistory(track: TrackEntity) = addToHistory(SearchHistoryEntry.Track(track.path))

    fun addToHistory(album: AlbumSearchResult) =
        addToHistory(SearchHistoryEntry.Album(album.album, album.artist))

    fun addToHistory(artist: String) = addToHistory(SearchHistoryEntry.Artist(artist))

    private fun addToHistory(entry: SearchHistoryEntry) {
        viewModelScope.launch {
            val currentSettings = settingsRepository.settings.first()
            val newHistory = (listOf(entry) + currentSettings.searchHistory)
                .distinctBy { it.identityKey }
                .take(20)
            settingsRepository.setSearchHistory(newHistory)
        }
    }

    fun removeFromHistory(entry: RecentSearch) {
        val persisted = when (entry) {
            is RecentSearch.Track -> SearchHistoryEntry.Track(entry.track.path)
            is RecentSearch.Album -> SearchHistoryEntry.Album(entry.album.album, entry.album.artist)
            is RecentSearch.Artist -> SearchHistoryEntry.Artist(entry.name)
        }
        viewModelScope.launch {
            val currentSettings = settingsRepository.settings.first()
            val newHistory = currentSettings.searchHistory.filterNot { it.identityKey == persisted.identityKey }
            settingsRepository.setSearchHistory(newHistory)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            settingsRepository.setSearchHistory(emptyList())
        }
    }

    /** One album's tracks in disc/track order (mirrors TrackDao.getTracksByAlbum). */
    suspend fun tracksForAlbum(album: String): List<TrackEntity> =
        repository.getTracksByAlbum(album).first()

    /** Tracks naming [artist] as artist or album artist (mirrors TrackDao.getTracksByArtist). */
    suspend fun tracksForArtist(artist: String): List<TrackEntity> =
        repository.getTracksByArtist(artist).first()

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
