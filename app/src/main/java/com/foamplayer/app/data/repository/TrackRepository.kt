package com.foamplayer.app.data.repository

import com.foamplayer.app.data.db.dao.AlbumRecommendation
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.dao.CachedLyricsDao
import com.foamplayer.app.data.db.dao.DailyPlayBucket
import com.foamplayer.app.data.db.dao.GenrePlayRow
import com.foamplayer.app.data.db.dao.HourBucket
import com.foamplayer.app.data.db.dao.ListenHistoryDao
import com.foamplayer.app.data.db.dao.ListenOverview
import com.foamplayer.app.data.db.dao.TopAlbumStat
import com.foamplayer.app.data.db.dao.TopTrackStat
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.dao.ArtistPlayRow
import com.foamplayer.app.data.db.entities.ListenHistoryEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.scanner.LocalAudioScanner
import com.foamplayer.app.data.scanner.ScanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Library data layer: Room access, library scanning and listening-stats
 * persistence. Keeps the main browse listings (tracks, albums, artists, genres,
 * years) as process-warm shared flows so screens open instantly with the last
 * snapshot instead of a cold Room round-trip.
 */
@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val scanner: LocalAudioScanner,
    private val settingsRepository: SettingsRepository,
    private val cachedLyricsDao: CachedLyricsDao,
    private val listenHistoryDao: ListenHistoryDao
) {
    private val _scanStatus = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    // Warm process-lifetime shared flows (replay 1): browse ViewModels are
    // per-navigation-destination, so without these every screen open would pay
    // a cold Room round-trip. Aggregation runs off the main thread.
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** All tracks, kept warm process-wide and replayed to new subscribers. */
    private val allTracksShared = trackDao.getAllTracks()
        .flowOn(Dispatchers.Default)
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            replay = 1
        )

    /** All albums, kept warm process-wide and replayed to new subscribers. */
    private val allAlbumsShared = trackDao.getAllAlbums()
        .flowOn(Dispatchers.Default)
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            replay = 1
        )

    /** All credited artist names, kept warm process-wide and replayed to new subscribers. */
    private val allArtistsShared = trackDao.getAllArtistNames().map { rawArtists ->
        rawArtists.flatMap { it.split("; ") }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
        .flowOn(Dispatchers.Default)
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            replay = 1
        )

    /** All credited genre names, kept warm process-wide and replayed to new subscribers. */
    private val allGenresShared = trackDao.getAllGenres().map { rawGenres ->
        rawGenres.flatMap { it.split("; ") }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
        .flowOn(Dispatchers.Default)
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            replay = 1
        )

    /** All years, kept warm process-wide and replayed to new subscribers. */
    private val allYearsShared = trackDao.getAllYears()
        .flowOn(Dispatchers.Default)
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.Lazily,
            replay = 1
        )

    init {
        // Pre-warm at app start, off the main thread, instead of on first navigation.
        repositoryScope.launch { allTracksShared.collect { } }
        repositoryScope.launch { allAlbumsShared.collect { } }
        repositoryScope.launch { allArtistsShared.collect { } }
        repositoryScope.launch { allGenresShared.collect { } }
        repositoryScope.launch { allYearsShared.collect { } }
    }

    /** Latest all-tracks snapshot, suspending until Room's first emission lands. */
    suspend fun allTracksSnapshot(): List<TrackEntity> = allTracksShared.first()

    /** Latest all-albums snapshot, suspending until Room's first emission lands. */
    suspend fun allAlbumsSnapshot(): List<AlbumSearchResult> = allAlbumsShared.first()

    /** Latest credited-artist list snapshot, suspending until the first emission lands. */
    suspend fun allArtistsSnapshot(): List<String> = allArtistsShared.first()

    /** The all-tracks stream with the last snapshot replayed to new subscribers. */
    fun getAllTracks(): Flow<List<TrackEntity>> = allTracksShared

    fun getAllAlbums(): Flow<List<AlbumSearchResult>> = allAlbumsShared

    fun getAllArtists(): Flow<List<String>> = allArtistsShared

    fun getAllGenres(): Flow<List<String>> = allGenresShared

    fun getAllYears(): Flow<List<Int>> = allYearsShared

    suspend fun getAllTracksOnce(): List<TrackEntity> = trackDao.getAllTracksOnce()

    suspend fun getTrackById(trackId: Long): TrackEntity? = trackDao.getTrackById(trackId)

    /**
     * Tracks for [trackIds], in [trackIds] order, dropping ids that no longer
     * exist in the library (a rescan re-assigns ids when files are re-scanned).
     * [trackIds] may exceed SQLite's host-variable limit, so it is chunked.
     */
    suspend fun getTracksByIds(trackIds: List<Long>): List<TrackEntity> {
        if (trackIds.isEmpty()) return emptyList()
        val byId = trackIds.chunked(SQL_VARIABLE_CHUNK)
            .flatMap { trackDao.getTracksByIds(it) }
            .associateBy { it.id }
        return trackIds.mapNotNull { byId[it] }
    }

    fun searchTracks(query: String): Flow<List<TrackEntity>> = trackDao.searchTracks(escapeLike(query))

    /** Searches credited artists (the "; " convention split apart), matching filtered. */
    fun searchArtists(query: String): Flow<List<String>> = trackDao.searchArtists(escapeLike(query)).map { rawArtists ->
        rawArtists.flatMap { it.split("; ") }
            .filter { it.contains(query, ignoreCase = true) }
            .distinct()
            .sorted()
    }
        .flowOn(Dispatchers.Default)

    fun searchAlbums(query: String): Flow<List<AlbumSearchResult>> = trackDao.searchAlbums(escapeLike(query))

    fun getTracksByAlbum(album: String): Flow<List<TrackEntity>> = trackDao.getTracksByAlbum(album)

    fun getAlbumsByArtist(artist: String): Flow<List<AlbumSearchResult>> = trackDao.getAlbumsByArtist(artist)

    /** All tracks naming [artist] as artist or album artist, alphabetical by title. */
    fun getTracksByArtist(artist: String): Flow<List<TrackEntity>> = trackDao.getTracksByArtist(artist)

    fun getTracksByGenre(genre: String): Flow<List<TrackEntity>> = trackDao.getTracksByGenre(genre)

    fun getTracksByYear(year: Int): Flow<List<TrackEntity>> = trackDao.getTracksByYear(year)

    /** Most recently listened tracks (Home "Recent listens" row). */
    fun getRecentlyPlayed(): Flow<List<TrackEntity>> = trackDao.getRecentlyPlayed()

    /** Long-standing favorites (Home "Old favorites" row); [cutoff] bounds what counts as "not recent". */
    fun getOldFavorites(cutoff: Long): Flow<List<TrackEntity>> = trackDao.getOldFavorites(cutoff)

    /** Albums ranked by the aggregate play count + likes of their tracks (Home row + see-all page). */
    fun getRecommendedAlbums(): Flow<List<AlbumRecommendation>> = trackDao.getRecommendedAlbums()

    /** Rescans the configured library folders. */
    suspend fun rescanLibrary() {
        val folders = settingsRepository.settings.first().libraryFolders
        startScan(folders)
    }

    /**
     * Previous scan results that are still valid: only when the separator
     * settings are unchanged (they transform the extracted strings) can files
     * with an unchanged size skip metadata re-extraction entirely.
     */
    private suspend fun reusableTracks(artistSeps: Set<String>, genreSeps: Set<String>): Map<String, TrackEntity> {
        val lastSeps = settingsRepository.getLastScanSeparators()
        if (lastSeps == null || lastSeps.first != artistSeps || lastSeps.second != genreSeps) {
            return emptyMap()
        }
        return trackDao.getAllTracksOnce().associateBy { it.path }
    }

    /** Scans [folders], replacing the library with the results (orphans removed). */
    suspend fun startScan(folders: Set<String>) {
        if (folders.isEmpty()) {
            _scanStatus.value = ScanStatus.Finished(0)
            return
        }

        val settings = settingsRepository.settings.first()
        val artistSeps = settings.artistSeparators
        val genreSeps = settings.genreSeparators
        val knownTracks = reusableTracks(artistSeps, genreSeps)

        _scanStatus.value = ScanStatus.Scanning(0f, "Starting...")
        val scannedTracks = scanner.scanFoldersSync(folders, artistSeps, genreSeps, knownTracks) { progress, currentFile ->
            _scanStatus.value = ScanStatus.Scanning(progress, currentFile)
        }
        settingsRepository.markScanSeparators(artistSeps, genreSeps)

        // insertTracks() replaces rows on conflict, which would wipe the listen
        // stats and score: re-attach the existing ones to the fresh entities.
        val existingStats = trackDao.getPlayStats().associateBy { it.path }
        val mergedTracks = scannedTracks.map { scanned ->
            existingStats[scanned.path]?.let {
                scanned.copy(playCount = it.playCount, lastPlayedAt = it.lastPlayedAt, score = it.score, instrumental = it.instrumental)
            } ?: scanned
        }

        trackDao.insertTracks(mergedTracks)
        // Chunked orphan cleanup: a single NOT IN (...) crashes past SQLite's
        // 999 host-variable limit on API 29.
        val scannedPathSet = mergedTracks.mapTo(HashSet()) { it.path }
        val stalePaths = trackDao.getAllTrackPaths().filterNot { it in scannedPathSet }
        stalePaths.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
            trackDao.deleteTracksByPaths(chunk)
        }

        val liveTrackIds = trackDao.getAllTrackIds().toHashSet()
        val orphanedCachedIds = cachedLyricsDao.getCachedTrackIds().filterNot { it in liveTrackIds }
        orphanedCachedIds.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
            cachedLyricsDao.deleteByTrackIds(chunk)
        }

        _scanStatus.value = ScanStatus.Finished(scannedTracks.size)
    }

    /** Scans one folder and merges the result without deleting tracks from other folders. */
    suspend fun scanFolderAndMerge(folder: String) {
        val settings = settingsRepository.settings.first()
        val artistSeps = settings.artistSeparators
        val genreSeps = settings.genreSeparators
        val knownTracks = reusableTracks(artistSeps, genreSeps)

        _scanStatus.value = ScanStatus.Scanning(0f, "Starting...")
        val scannedTracks = scanner.scanFoldersSync(setOf(folder), artistSeps, genreSeps, knownTracks) { progress, currentFile ->
            _scanStatus.value = ScanStatus.Scanning(progress, currentFile)
        }
        settingsRepository.markScanSeparators(artistSeps, genreSeps)

        val existingStats = trackDao.getPlayStats().associateBy { it.path }
        val mergedTracks = scannedTracks.map { scanned ->
            existingStats[scanned.path]?.let {
                scanned.copy(playCount = it.playCount, lastPlayedAt = it.lastPlayedAt, score = it.score, instrumental = it.instrumental)
            } ?: scanned
        }

        trackDao.insertTracks(mergedTracks)
        _scanStatus.value = ScanStatus.Finished(mergedTracks.size)
    }

    /**
     * Marks [trackId] as listened at [timestamp]: the tracks-table counters plus
     * one listen_history row for the stats screen's period analysis.
     */
    suspend fun incrementPlayCount(trackId: Long, timestamp: Long) {
        trackDao.incrementPlayCount(trackId, timestamp)
        listenHistoryDao.insertListen(ListenHistoryEntity(trackId = trackId, listenedAt = timestamp))
    }

    /** Resets the listen counter and clears the last-listen date and history rows. */
    suspend fun resetPlayStats(trackId: Long) {
        trackDao.resetPlayStats(trackId)
        listenHistoryDao.deleteByTrackId(trackId)
    }

    /** Stores the user score (-1 dislike / 0 not noted / 1 like / 2 super like). */
    suspend fun setScore(trackId: Long, score: Int) = trackDao.setScore(trackId, score)

    /** Stores the user-set instrumental flag (lyrics view shows a music note instead). */
    suspend fun setInstrumental(trackId: Long, instrumental: Boolean) =
        trackDao.setInstrumental(trackId, instrumental)

    /** Emits the user-set instrumental flag for [trackId], updating whenever it changes. */
    fun observeInstrumental(trackId: Long): Flow<Boolean> = trackDao.observeInstrumental(trackId)

    // ---- Listening stats: [since] = period start (epoch ms) or NULL for all time ----

    fun observeListenOverview(since: Long?): Flow<ListenOverview> =
        listenHistoryDao.observeOverview(since)

    fun observeListenedArtists(since: Long?): Flow<List<String>> =
        listenHistoryDao.observeDistinctArtists(since)

    /** Uncapped: every track listened to in the period, most-played first. */
    fun observeTopListenedTracks(since: Long?): Flow<List<TopTrackStat>> =
        listenHistoryDao.observeTopTracks(since)

    fun observeListenedArtistRows(since: Long?): Flow<List<ArtistPlayRow>> =
        listenHistoryDao.observeArtistPlayRows(since)

    fun observeTopListenedAlbums(since: Long?, limit: Int): Flow<List<TopAlbumStat>> =
        listenHistoryDao.observeTopAlbums(since, limit)

    fun observeDailyListenBuckets(since: Long?, tzOffsetMs: Long): Flow<List<DailyPlayBucket>> =
        listenHistoryDao.observeDailyPlays(since, tzOffsetMs)

    fun observeListenHourHistogram(since: Long?, tzOffsetMs: Long): Flow<List<HourBucket>> =
        listenHistoryDao.observeHourHistogram(since, tzOffsetMs)

    fun observeListenedGenreRows(since: Long?): Flow<List<GenrePlayRow>> =
        listenHistoryDao.observeGenrePlayRows(since)

    private companion object {
        /** Bound below SQLite's 999 host-variable limit (API 29) per statement. */
        const val SQL_VARIABLE_CHUNK = 900

        /**
         * Neutralizes LIKE's wildcard characters (%, _) and the ESCAPE character
         * itself, so a typed query such as "0% Angel" or "_" matches literally.
         * Pairs with the ESCAPE '\' clause in the search* queries.
         */
        fun escapeLike(query: String): String =
            query.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
    }
}
