package com.foamplayer.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumRecommendation
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.dao.PlaylistRecommendation
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.PlaylistRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ArtistCover
import com.foamplayer.app.data.scanner.ArtistCoverProvider
import com.foamplayer.app.data.scanner.PlaylistCover
import com.foamplayer.app.data.scanner.ScanStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An artist plus the total plays of its songs (Home "Favorite artists" row). */
data class FavoriteArtist(
    val name: String,
    val totalPlayCount: Int
)

/**
 * Covers for the "Random artists" grid, plus the names the latest resolution
 * pass was built for: a name inside [resolved] without a cover genuinely has
 * no art (letter fallback), while one outside it is still being resolved
 * (placeholder, so a reshuffle never flashes letters).
 */
data class RandomArtistCovers(
    val covers: Map<String, ArtistCover> = emptyMap(),
    val resolved: Set<String> = emptySet()
)

/**
 * Data for the Home screen's rows and their "See all" pages. Every flow
 * holds the full sorted list (DAO-capped at 50); the rows render the first
 * few entries and the see-all pages all fifty, so both ends of a "See all"
 * always agree on the ordering.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val playlistRepository: PlaylistRepository,
    artistCoverProvider: ArtistCoverProvider
) : ViewModel() {

    /** Live library scan status (Idle / Scanning / Finished), for the header's scanning indicator. */
    val scanStatus: StateFlow<ScanStatus> = trackRepository.scanStatus

    /**
     * Sources that must have delivered their first database snapshot before the
     * Home screen can be shown. The app holds its first UI frame until this is
     * true, so rows never pop into place one by one after launch.
     */
    private object HomeSource {
        const val LIBRARY = "library"
        const val RECOMMENDED_PLAYLISTS = "recommended_playlists"
        const val RECOMMENDED_ALBUMS = "recommended_albums"
        const val RECENT_TRACKS = "recent_tracks"
        const val RECOMMENDED_TRACKS = "recommended_tracks"
        const val FAVORITE_TRACKS = "favorite_tracks"
        const val OLD_FAVORITE_TRACKS = "old_favorite_tracks"
        const val OLD_FAVORITE_ALBUMS = "old_favorite_albums"
        const val FAVORITE_ARTISTS = "favorite_artists"
        const val RECENTLY_ADDED_ALBUMS = "recently_added_albums"
        const val RECENTLY_ADDED_ARTISTS = "recently_added_artists"

        val all = setOf(
            LIBRARY,
            RECOMMENDED_PLAYLISTS,
            RECOMMENDED_ALBUMS,
            RECENT_TRACKS,
            RECOMMENDED_TRACKS,
            FAVORITE_TRACKS,
            OLD_FAVORITE_TRACKS,
            OLD_FAVORITE_ALBUMS,
            FAVORITE_ARTISTS,
            RECENTLY_ADDED_ALBUMS,
            RECENTLY_ADDED_ARTISTS
        )
    }

    private val loadedSources = MutableStateFlow<Set<String>>(emptySet())

    private val _contentReady = MutableStateFlow(false)

    /**
     * True once every Home data source has emitted its first snapshot. The
     * launch gate waits on this so the whole page appears in one piece.
     */
    val contentReady: StateFlow<Boolean> = _contentReady.asStateFlow()

    /**
     * Marks [key] loaded AFTER the value has been handed to the downstream
     * [stateIn] collector, so readiness can never run ahead of the page's data.
     */
    private fun <T> Flow<T>.trackSource(key: String): Flow<T> = transform { value ->
        emit(value)
        markSourceLoaded(key)
    }

    private fun markSourceLoaded(key: String) {
        val loaded = loadedSources.updateAndGet { it + key }
        if (loaded.containsAll(HomeSource.all)) _contentReady.value = true
    }

    /**
     * Re-reads a track from the DB right before the track modal opens: home rows
     * are collected snapshots, so an edited track's metadata can be stale in the
     * row list. Falls back to the snapshot when the row can't be found.
     */
    suspend fun freshTrack(track: TrackEntity): TrackEntity =
        trackRepository.getTrackById(track.id) ?: track

    /** All playlists, for resolving a recommendation into its real entity (long-press modal). */
    val playlists: StateFlow<List<PlaylistEntity>> =
        playlistRepository.getAllPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Playlists ranked by the aggregate play count + likes of the songs they contain. */
    val recommendedPlaylists: StateFlow<List<PlaylistRecommendation>> =
        playlistRepository.getRecommendedPlaylists()
            .trackSource(HomeSource.RECOMMENDED_PLAYLISTS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Resolved covers per playlist id (custom image first, else first-track embedded art). */
    val playlistCovers: StateFlow<Map<Long, PlaylistCover>> =
        playlistRepository.getPlaylistCovers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Albums ranked by the aggregate play count + likes of their tracks. */
    val recommendedAlbums: StateFlow<List<AlbumRecommendation>> =
        trackRepository.getRecommendedAlbums()
            .trackSource(HomeSource.RECOMMENDED_ALBUMS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Tracks with at least one listen, newest listen first. */
    val recentTracks: StateFlow<List<TrackEntity>> =
        trackRepository.getRecentlyPlayed()
            .trackSource(HomeSource.RECENT_TRACKS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Most-listened tracks, descending by listen count. */
    val recommendedTracks: StateFlow<List<TrackEntity>> =
        trackRepository.getRecommendedTracks()
            .trackSource(HomeSource.RECOMMENDED_TRACKS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Liked tracks (score >= 1), ranked by score then listen count. */
    val favoriteTracks: StateFlow<List<TrackEntity>> =
        trackRepository.getFavoriteTracks()
            .trackSource(HomeSource.FAVORITE_TRACKS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Long-standing favorites: listening history whose last listen is older than 14 days. */
    val oldFavoriteTracks: StateFlow<List<TrackEntity>> =
        trackRepository.getOldFavorites(System.currentTimeMillis() - OldFavoritesCutoffMs)
            .trackSource(HomeSource.OLD_FAVORITE_TRACKS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Albums whose latest listen is older than 14 days, ranked by aggregate play/like. */
    val oldFavoriteAlbums: StateFlow<List<AlbumRecommendation>> =
        trackRepository.getOldFavoriteAlbums(System.currentTimeMillis() - OldFavoritesCutoffMs)
            .trackSource(HomeSource.OLD_FAVORITE_ALBUMS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Albums newest-first by their most recently added (first-seen) track. */
    val recentlyAddedAlbums: StateFlow<List<AlbumRecommendation>> =
        trackRepository.getRecentlyAddedAlbums()
            .trackSource(HomeSource.RECENTLY_ADDED_ALBUMS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Artists newest-first by the latest file modification date among their
     * tracks. Credited names are split on the app-wide "; " convention so a
     * multi-artist track counts toward each of its artists (matching the
     * favorite-artists rows and the cover resolution keys).
     */
    val recentlyAddedArtists: StateFlow<List<FavoriteArtist>> =
        trackRepository.getAllTracks()
            .map { tracks ->
                val lastAdded = HashMap<String, Long>()
                for (track in tracks) {
                    track.artist.split("; ")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .forEach { artist -> lastAdded.merge(artist, track.dateAdded, ::maxOf) }
                }
                lastAdded.entries.asSequence()
                    .filter { it.value > 0 }
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Long>> { it.value }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key }
                    )
                    .map { FavoriteArtist(it.key, 0) }
                    .toList()
            }
            .flowOn(Dispatchers.Default)
            .trackSource(HomeSource.RECENTLY_ADDED_ARTISTS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Artists ranked by the total plays of their songs. Names split on the
     * app-wide "; " convention with each credited share counted once; artists
     * without any play are not "favorites".
     */
    val favoriteArtists: StateFlow<List<FavoriteArtist>> =
        trackRepository.getAllTracks()
            .map { tracks ->
                val totals = HashMap<String, Int>()
                for (track in tracks) {
                    track.artist.split("; ")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .forEach { artist -> totals.merge(artist, track.playCount, Int::plus) }
                }
                totals.entries.asSequence()
                    .filter { it.value > 0 }
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Int>> { it.value }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key }
                    )
                    .map { FavoriteArtist(it.key, it.value) }
                    .toList()
            }
            .flowOn(Dispatchers.Default)
            .trackSource(HomeSource.FAVORITE_ARTISTS)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Resolved pictures per artist name: a Spotify image or first-track embedded art. */
    val artistImages: StateFlow<Map<String, ArtistCover>> =
        artistCoverProvider.coversFlow(favoriteArtists.map { artists -> artists.map { it.name } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Resolved pictures per recently-added artist name (same resolution chain). */
    val recentlyAddedArtistImages: StateFlow<Map<String, ArtistCover>> =
        artistCoverProvider.coversFlow(recentlyAddedArtists.map { artists -> artists.map { it.name } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Library snapshot backing the random sections' grids. */
    private var libraryTracks: List<TrackEntity> = emptyList()

    private val _libraryLoaded = MutableStateFlow(false)

    /** True once the first library snapshot has arrived; Home's empty state waits for it. */
    val libraryLoaded: StateFlow<Boolean> = _libraryLoaded.asStateFlow()

    private val _libraryEmpty = MutableStateFlow(true)

    /** True when the library holds no tracks at all. */
    val libraryEmpty: StateFlow<Boolean> = _libraryEmpty.asStateFlow()

    private val _randomTracks = MutableStateFlow<List<TrackEntity>>(emptyList())

    /** The "Random tracks" grid: six random songs, repicked by [reshuffleRandomTracks]. */
    val randomTracks: StateFlow<List<TrackEntity>> = _randomTracks.asStateFlow()

    private val _randomAlbums = MutableStateFlow<List<AlbumSearchResult>>(emptyList())

    /** The "Random albums" grid: six random albums, repicked by [reshuffleRandomAlbums]. */
    val randomAlbums: StateFlow<List<AlbumSearchResult>> = _randomAlbums.asStateFlow()

    private val _randomArtists = MutableStateFlow<List<FavoriteArtist>>(emptyList())

    /** The "Random artists" grid: six random artists, repicked by [reshuffleRandomArtists]. */
    val randomArtists: StateFlow<List<FavoriteArtist>> = _randomArtists.asStateFlow()

    /**
     * Covers for the "Random artists" grid. Keyed on the picked artists rather
     * than the favorites: the grid draws from the whole library, including
     * never-played artists that the favorites' cover map doesn't carry.
     *
     * [flatMapLatest] pairs each pick's names with ITS OWN resolution result:
     * the stored value only updates once the new pick's covers have finished
     * resolving, so the frames in between see a stale [RandomArtistCovers]
     * whose [RandomArtistCovers.resolved] doesn't contain the fresh names —
     * cards hold the person placeholder instead of flashing the letter.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val randomArtistCovers: StateFlow<RandomArtistCovers> =
        randomArtists
            .map { artists -> artists.map { it.name } }
            .flatMapLatest { names ->
                artistCoverProvider.coversFlow(flowOf(names))
                    .map { covers -> RandomArtistCovers(covers, names.toSet()) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RandomArtistCovers())

    init {
        viewModelScope.launch {
            trackRepository.getAllTracks().collect { tracks ->
                libraryTracks = tracks
                _libraryEmpty.value = tracks.isEmpty()
                _libraryLoaded.value = true
                // First load seeds the grids; later library changes don't
                // silently replace the items the user is looking at.
                if (_randomTracks.value.isEmpty()) reshuffleRandomTracks()
                if (_randomAlbums.value.isEmpty()) reshuffleRandomAlbums()
                if (_randomArtists.value.isEmpty()) reshuffleRandomArtists()
                // Marked last: the gate must not reveal Home before the
                // library-backed grids have been seeded.
                markSourceLoaded(HomeSource.LIBRARY)
            }
        }
        viewModelScope.launch {
            var previous: ScanStatus? = null
            trackRepository.scanStatus.collect { status ->
                val scanCompleted = status is ScanStatus.Finished && previous is ScanStatus.Scanning
                previous = status
                if (scanCompleted) refreshRandomSections()
            }
        }
    }

    /**
     * Repicks every random grid from a fresh DB snapshot. Reads the DAO
     * directly (instead of the warm flow) so the repick can't race the Room
     * invalidation that follows the scan's commit.
     */
    private suspend fun refreshRandomSections() {
        libraryTracks = trackRepository.getAllTracksOnce()
        _libraryEmpty.value = libraryTracks.isEmpty()
        _libraryLoaded.value = true
        reshuffleRandomTracks()
        reshuffleRandomAlbums()
        reshuffleRandomArtists()
    }

    /** Repicks the "Random tracks" grid's six songs from the whole library. */
    fun reshuffleRandomTracks() {
        _randomTracks.value = libraryTracks.shuffled().take(StartPlayingTrackCount)
    }

    /** Repicks the "Random albums" grid's six albums from the whole library. */
    fun reshuffleRandomAlbums() {
        _randomAlbums.value = libraryTracks.groupBy { it.album }
            .entries
            .shuffled()
            .take(StartPlayingTrackCount)
            .map { (album, tracks) ->
                AlbumSearchResult(
                    album = album,
                    artist = tracks.first().albumArtist ?: tracks.first().artist,
                    albumArtPath = tracks.firstOrNull { it.albumArtPath != null }?.albumArtPath
                )
            }
    }

    /** Repicks the "Random artists" grid's six artists from the whole library. */
    fun reshuffleRandomArtists() {
        val artists = libraryTracks.flatMap { track ->
            (track.artist.split("; ") + track.albumArtist.orEmpty().split("; "))
                .map(String::trim)
                .filter(String::isNotEmpty)
        }.distinct()
        _randomArtists.value = artists.shuffled().take(StartPlayingTrackCount)
            .map { name -> FavoriteArtist(name, 0) }
    }

    /** One album's tracks in disc/track order. */
    suspend fun tracksForAlbum(album: String): List<TrackEntity> =
        trackRepository.getTracksByAlbum(album).first()

    /** Tracks naming [artist] as artist or album artist, in album order. */
    suspend fun tracksForArtist(artist: String): List<TrackEntity> = withContext(Dispatchers.Default) {
        trackRepository.getAllTracks().first()
            .filter { it.artist == artist || it.albumArtist == artist }
            .sortedWith(compareBy({ it.album }, { it.discNumber }, { it.trackNumber }, { it.title }))
    }

    companion object {
        /** Listens older than this count as "long-standing" for the Old favorites rows (14 days). */
        private const val OldFavoritesCutoffMs = 14L * 24 * 60 * 60 * 1000

        /** Songs shown in the "Random tracks" grid (2 columns x 3 rows). */
        const val StartPlayingTrackCount = 6
    }
}
