package com.foamplayer.app.data.repository

import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.foamplayer.app.data.db.AppDatabase
import com.foamplayer.app.data.db.dao.AlbumRecommendation
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.dao.ArtistAddRow
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
import com.foamplayer.app.data.db.dao.TrackPlayStatsRow
import com.foamplayer.app.data.db.entities.ListenHistoryEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.db.entities.toDeletionLogEntry
import com.foamplayer.app.data.scanner.AudioTagWriter
import com.foamplayer.app.data.scanner.LocalAudioScanner
import com.foamplayer.app.data.scanner.RemoteLibraryScanner
import com.foamplayer.app.data.scanner.ScanStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** One track's generated stats block, staged in memory before the atomic write. */
private data class TrackStatsUpdate(
    val trackId: Long,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val score: Int
)

/** Store format of multi-value text columns (genres, credited artists, composers): values "; "-joined. */
const val MULTI_VALUE_SEPARATOR = "; "

/**
 * A track's user-editable metadata, as gathered by the track modal's edit page.
 * Blank optional fields are stored as null; [genres] is stored "; "-joined in the
 * genre column (see [MULTI_VALUE_SEPARATOR]).
 */
data class TrackMetadataEdit(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val genres: List<String>,
    val composer: String?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?
) {
    /** The genre string as stored in the genre column (empty list → empty string). */
    val genre: String get() = genres.joinToString(MULTI_VALUE_SEPARATOR)
}

/**
 * Library data layer: Room access, library scanning and listening-stats
 * persistence. Keeps the main browse listings (tracks, albums, artists,
 * genres, years, composers) as process-warm shared flows so screens open
 * instantly with the last snapshot instead of a cold Room round-trip.
 */
@Singleton
class TrackRepository @Inject constructor(
    private val db: AppDatabase,
    private val trackDao: TrackDao,
    private val audioTagWriter: AudioTagWriter,
    private val scanner: LocalAudioScanner,
    private val remoteScanner: RemoteLibraryScanner,
    private val settingsRepository: SettingsRepository,
    private val cachedLyricsDao: CachedLyricsDao,
    private val listenHistoryDao: ListenHistoryDao
) {
    private val _scanStatus = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    // Track scores (-1 dislike / 0 not noted / 1 like / 2 super like): the engine's
    // in-memory queue entities go stale the moment a score hits the DB, and TWO
    // surfaces write scores (the app UI and the media-notification service).
    // This id→score overlay is the shared source of truth both write through
    // [setScore] and both readers (the player UI's heart, the notification icon
    // cache) observe, so a score changed on one surface is instantly visible on
    // the other.
    private val _scoreOverrides = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val scoreOverrides: StateFlow<Map<Long, Int>> = _scoreOverrides.asStateFlow()

    // Warm process-lifetime shared flows (replay 1): browse ViewModels are
    // per-navigation-destination, so without these every screen open would pay
    // a cold Room round-trip. Aggregation runs off the main thread.
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Latest detached scan ([rescanLibraryDetached] / [scanServerDetached]); guards double-starts. */
    private var scanJob: Job? = null

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
        rawArtists.flatMap { it.split(MULTI_VALUE_SEPARATOR) }
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

    /** All credited composer names, kept warm process-wide and replayed to new subscribers. */
    private val allComposersShared = trackDao.getAllComposerNames().map { rawComposers ->
        rawComposers.flatMap { it.split(MULTI_VALUE_SEPARATOR) }
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
        rawGenres.flatMap { it.split(MULTI_VALUE_SEPARATOR) }
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
        repositoryScope.launch { allComposersShared.collect { } }
        repositoryScope.launch { allGenresShared.collect { } }
        repositoryScope.launch { allYearsShared.collect { } }
    }

    /** Latest all-tracks snapshot, suspending until Room's first emission lands. */
    suspend fun allTracksSnapshot(): List<TrackEntity> = allTracksShared.first()

    /** Latest all-albums snapshot, suspending until Room's first emission lands. */
    suspend fun allAlbumsSnapshot(): List<AlbumSearchResult> = allAlbumsShared.first()

    /** Latest credited-artist list snapshot, suspending until the first emission lands. */
    suspend fun allArtistsSnapshot(): List<String> = allArtistsShared.first()

    /** Latest credited-composer list snapshot, suspending until the first emission lands. */
    suspend fun allComposersSnapshot(): List<String> = allComposersShared.first()

    /** The all-tracks stream with the last snapshot replayed to new subscribers. */
    fun getAllTracks(): Flow<List<TrackEntity>> = allTracksShared

    fun getAllAlbums(): Flow<List<AlbumSearchResult>> = allAlbumsShared

    fun getAllArtists(): Flow<List<String>> = allArtistsShared

    fun getAllComposers(): Flow<List<String>> = allComposersShared

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

    /**
     * Radio-qualified track ids for the "; "-multi-value seeds of a track:
     * the union of ids sharing any of its genre values plus ids crediting any
     * of its artist / album-artist credits, deduplicated. Id-only projection
     * queries keep a radio build from materializing the whole track table.
     */
    suspend fun getSimilarTrackIds(
        excludeId: Long,
        genreSeeds: List<String>,
        artistSeeds: List<String>
    ): List<Long> {
        val matched = buildList {
            genreSeeds.forEach { addAll(trackDao.getTrackIdsMatchingGenre(excludeId, it)) }
            artistSeeds.forEach { addAll(trackDao.getTrackIdsMatchingArtist(excludeId, it)) }
        }
        return matched.distinct()
    }

    fun searchTracks(query: String): Flow<List<TrackEntity>> = trackDao.searchTracks(escapeLike(query))

    /** Searches credited artists (the "; " convention split apart), matching filtered. */
    fun searchArtists(query: String): Flow<List<String>> = trackDao.searchArtists(escapeLike(query)).map { rawArtists ->
        rawArtists.flatMap { it.split(MULTI_VALUE_SEPARATOR) }
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

    /** All tracks crediting [composer], alphabetical by title. */
    fun getTracksByComposer(composer: String): Flow<List<TrackEntity>> = trackDao.getTracksByComposer(composer)

    fun getTracksByGenre(genre: String): Flow<List<TrackEntity>> = trackDao.getTracksByGenre(genre)

    fun getTracksByYear(year: Int): Flow<List<TrackEntity>> = trackDao.getTracksByYear(year)

    /** Most recently listened tracks (Home "Recent listens" row). */
    fun getRecentlyPlayed(): Flow<List<TrackEntity>> = trackDao.getRecentlyPlayed()

    /** Home "Favorite tracks": aggregate play count + likes, any last-played date. */
    fun getFavoriteTracks(): Flow<List<TrackEntity>> = trackDao.getFavoriteTracks()

    /** Long-standing favorites (Home "Old favorites" row); [cutoff] bounds what counts as "not recent". */
    fun getOldFavorites(cutoff: Long): Flow<List<TrackEntity>> = trackDao.getOldFavorites(cutoff)

    /** Albums ranked by the aggregate play count + likes of their tracks (Home row + see-all page). */
    fun getRecommendedAlbums(): Flow<List<AlbumRecommendation>> = trackDao.getRecommendedAlbums()

    /**
     * Albums whose latest listen is older than [cutoff], ranked by aggregate
     * play count + likes (Home "Old favorite albums" row).
     */
    fun getOldFavoriteAlbums(cutoff: Long): Flow<List<AlbumRecommendation>> = trackDao.getOldFavoriteAlbums(cutoff)

    /** Albums newest-first by the latest file modification date among their tracks. */
    fun getRecentlyAddedAlbums(): Flow<List<AlbumRecommendation>> = trackDao.getRecentlyAddedAlbums()

    /** Credited artists newest-first by the latest file modification date among their tracks. */
    fun getRecentlyAddedArtistRows(): Flow<List<ArtistAddRow>> = trackDao.getRecentlyAddedArtistRows()

    /** Rescans the configured library folders. */
    suspend fun rescanLibrary(automatic: Boolean = false) {
        val folders = settingsRepository.settings.first().libraryFolders
        startScan(folders, automatic)
    }

    /**
     * Launches [rescanLibrary] on the repository's process-lifetime scope. A
     * scan must outlive the screen that started it: launched from a ViewModel
     * scope, leaving Settings (or finishing onboarding) cancelled the scan
     * mid-flight and left [scanStatus] stuck on "Scanning". No-op while another
     * detached scan is still running.
     */
    fun rescanLibraryDetached() {
        if (scanJob?.isActive == true) return
        scanJob = repositoryScope.launch { rescanLibrary() }
    }

    /** Detached variant of [scanServerAndMerge]; see [rescanLibraryDetached]. */
    fun scanServerDetached(serverId: String) {
        if (scanJob?.isActive == true) return
        scanJob = repositoryScope.launch { scanServerAndMerge(serverId) }
    }

    /**
     * Scans the library on app open: the same full rescan as the manual one,
     * on the process-lifetime scope, flagged automatic so the scan-progress
     * notification is skipped (in-app indicators still update). Skipped when
     * no source is configured yet (fresh install / pending onboarding) —
     * there is nothing to scan.
     */
    fun autoScanOnOpen() {
        repositoryScope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.libraryFolders.isNotEmpty() || settings.distantServers.isNotEmpty()) {
                rescanLibrary(automatic = true)
            }
        }
    }

    /**
     * Removes [folder] from the configured library folders and immediately
     * drops every track stored under it, plus their cached lyrics — removed
     * folders' tracks used to linger until the next full rescan's orphan
     * cleanup. Deletions run chunked and transactional like [startScan].
     * (Removing a folder while a scan is in flight can re-add its tracks;
     * the next full rescan prunes them again.)
     */
    suspend fun removeFolderAndPruneTracks(folder: String) {
        settingsRepository.removeFolder(folder)

        val root = treeDocumentIdOf(folder) ?: return
        val prefix = "$root/"
        val removedPaths = trackDao.getAllTrackPaths()
            .filter { documentIdOf(it).startsWith(prefix) }

        db.withTransaction {
            val deletedAt = System.currentTimeMillis()
            removedPaths.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
                val doomed = trackDao.getTracksByPaths(chunk)
                if (doomed.isNotEmpty()) {
                    trackDao.insertDeletionLogEntries(doomed.map { it.toDeletionLogEntry(deletedAt) })
                }
                trackDao.deleteTracksByPaths(chunk)
            }

            // Mirrors startScan's orphan cleanup: drop cached lyrics whose
            // track row no longer exists.
            val liveTrackIds = trackDao.getAllTrackIds().toHashSet()
            val orphanedCachedIds = cachedLyricsDao.getCachedTrackIds().filterNot { it in liveTrackIds }
            orphanedCachedIds.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
                cachedLyricsDao.deleteByTrackIds(chunk)
            }
        }
    }

    /** Detached variant of [removeFolderAndPruneTracks]: pruning survives leaving Settings. */
    fun removeFolderDetached(folder: String) {
        repositoryScope.launch { removeFolderAndPruneTracks(folder) }
    }

    /** Document id of a track path (content document id, file path, or the raw string). */
    private fun documentIdOf(trackPath: String): String = try {
        val uri = trackPath.toUri()
        when (uri.scheme) {
            "content" -> DocumentsContract.getDocumentId(uri)
            "file" -> uri.path ?: trackPath
            else -> trackPath
        }
    } catch (_: Exception) {
        trackPath
    }

    /** Tree document id of a picked library folder (content tree URI or file path). */
    private fun treeDocumentIdOf(folderUri: String): String? = try {
        val uri = folderUri.toUri()
        if (uri.scheme == "content") DocumentsContract.getTreeDocumentId(uri) else uri.path
    } catch (_: Exception) {
        null
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

    /**
     * Re-attaches a file's persisted stats to a freshly scanned entity, KEEPING
     * the existing row id. INSERT OR REPLACE then updates that same row instead
     * of deleting + re-inserting it — the latter would re-assign the
     * autoGenerate id and orphan every listen_history row pointing at the old
     * id, leaving the stats screens (built on listen_history joins) empty even
     * though the tracks table still shows the play counts.
     */
    private fun mergeStats(
        scanned: TrackEntity,
        existing: TrackPlayStatsRow?
    ): TrackEntity {
        return if (existing != null) {
            scanned.copy(
                id = existing.id,
                playCount = existing.playCount,
                lastPlayedAt = existing.lastPlayedAt,
                score = existing.score,
                instrumental = existing.instrumental
            )
        } else {
            scanned
        }
    }

    /**
     * Scans [folders] (local) plus every connected distant server, replacing
     * the library with the results (orphans removed). [automatic] is carried
     * on the emitted [ScanStatus] so notification observers can skip it.
     */
    suspend fun startScan(folders: Set<String>, automatic: Boolean = false) {
        val settings = settingsRepository.settings.first()
        val servers = settings.distantServers
        if (folders.isEmpty() && servers.isEmpty()) {
            _scanStatus.value = ScanStatus.Finished(0, automatic)
            return
        }

        val artistSeps = settings.artistSeparators
        val genreSeps = settings.genreSeparators
        val knownTracks = reusableTracks(artistSeps, genreSeps)

        _scanStatus.value = ScanStatus.Scanning(0f, "Starting...", automatic)
        remoteScanner.refreshTokens()

        val localTracks = if (folders.isEmpty()) {
            emptyList()
        } else {
            scanner.scanFoldersSync(folders, artistSeps, genreSeps, knownTracks) { progress, currentFile ->
                _scanStatus.value = ScanStatus.Scanning(progress, currentFile, automatic)
            }
        }
        settingsRepository.markScanSeparators(artistSeps, genreSeps)

        val remoteTracks = remoteScanner.scan(servers) { progress, label ->
            _scanStatus.value = ScanStatus.Scanning(progress, label, automatic)
        }

        val scannedTracks = localTracks + remoteTracks

        // insertTracks() replaces rows on conflict, which would wipe the listen
        // stats and score: re-attach the existing ones (with their row id, so
        // the row is updated in place rather than re-assigned) to the fresh
        // entities.
        val existingStats = trackDao.getPlayStats().associateBy { it.path }
        val mergedTracks = scannedTracks.map { scanned ->
            mergeStats(scanned, existingStats[scanned.path])
        }

        db.withTransaction {
            trackDao.insertTracks(mergedTracks)
            // Chunked orphan cleanup: a single NOT IN (...) crashes past SQLite's
            // 999 host-variable limit on API 29.
            val scannedPathSet = mergedTracks.mapTo(HashSet()) { it.path }
            val stalePaths = trackDao.getAllTrackPaths().filterNot { it in scannedPathSet }
            val deletedAt = System.currentTimeMillis()
            stalePaths.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
                val doomed = trackDao.getTracksByPaths(chunk)
                if (doomed.isNotEmpty()) {
                    trackDao.insertDeletionLogEntries(doomed.map { it.toDeletionLogEntry(deletedAt) })
                }
                trackDao.deleteTracksByPaths(chunk)
            }

            val liveTrackIds = trackDao.getAllTrackIds().toHashSet()
            val orphanedCachedIds = cachedLyricsDao.getCachedTrackIds().filterNot { it in liveTrackIds }
            orphanedCachedIds.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
                cachedLyricsDao.deleteByTrackIds(chunk)
            }
        }

        _scanStatus.value = ScanStatus.Finished(scannedTracks.size, automatic)
    }

    /**
     * Scans one folder and merges the result without deleting tracks from
     * other folders. Unlike the detached scans this runs on the caller's
     * scope (the playlist-import flow needs its result), so a cancellation
     * (its ViewModel dying mid-import) resets the status to Idle instead of
     * leaving a "Scanning" value spinning every indicator forever.
     */
    suspend fun scanFolderAndMerge(folder: String) {
        try {
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
                mergeStats(scanned, existingStats[scanned.path])
            }

            trackDao.insertTracks(mergedTracks)
            _scanStatus.value = ScanStatus.Finished(mergedTracks.size)
        } catch (cancellation: CancellationException) {
            _scanStatus.value = ScanStatus.Idle
            throw cancellation
        }
    }

    /** Scans one distant server's folders and merges the result without touching other sources. */
    suspend fun scanServerAndMerge(serverId: String) {
        val server = settingsRepository.settings.first().distantServers.firstOrNull { it.id == serverId } ?: return
        remoteScanner.refreshTokens()

        _scanStatus.value = ScanStatus.Scanning(0f, "Starting...")
        val scannedTracks = remoteScanner.scan(listOf(server)) { progress, label ->
            _scanStatus.value = ScanStatus.Scanning(progress, label)
        }

        val existingStats = trackDao.getPlayStats().associateBy { it.path }
        val mergedTracks = scannedTracks.map { scanned ->
            mergeStats(scanned, existingStats[scanned.path])
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

    /**
     * Stores the user score (-1 dislike / 0 not noted / 1 like / 2 super like).
     * The overlay map is updated BEFORE the DB write so every reader sees the
     * new score on the very next frame, without waiting for Room.
     */
    suspend fun setScore(trackId: Long, score: Int) {
        _scoreOverrides.value += (trackId to score)
        trackDao.setScore(trackId, score)
    }

    /** Stores the user-set instrumental flag (lyrics view shows a music note instead). */
    suspend fun setInstrumental(trackId: Long, instrumental: Boolean) =
        trackDao.setInstrumental(trackId, instrumental)

    /**
     * Applies the track modal's edit page: writes [edit] into the track's file
     * tags (so a rescan reads the same values back), then overwrites the
     * database row. The file write is best-effort — when it fails (unsupported
     * container, read-only document…) the database still updates, but the next
     * scan will read the old tags back.
     */
    suspend fun updateMetadata(track: TrackEntity, edit: TrackMetadataEdit) {
        withContext(Dispatchers.IO) {
            audioTagWriter.writeTags(track.path.toUri(), track.format, edit)
        }
        trackDao.updateMetadata(
            trackId = track.id,
            title = edit.title,
            artist = edit.artist,
            album = edit.album,
            albumArtist = edit.albumArtist,
            genre = edit.genre.ifEmpty { null },
            composer = edit.composer,
            year = edit.year,
            trackNumber = edit.trackNumber,
            discNumber = edit.discNumber
        )
    }

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

    // ---- Developer tools (only reachable from the DEV_MODE test screen) ----

    /** Clears every track's play count, last-listen date, score and history atomically. */
    suspend fun clearAllPlayStats() {
        db.withTransaction {
            trackDao.resetAllPlayStats()
            listenHistoryDao.clearAll()
        }
        _scoreOverrides.value = emptyMap()
    }

    /**
     * Fills the whole library with random listening stats over the last
     * [years] years: per-track play count / last-listen date / score, plus one
     * listen_history row per generated listen so the stats screen's period
     * analysis and charts are populated too.
     *
     * The whole dataset is committed in ONE transaction: either the tracks'
     * aggregate columns and the full listen history land together, or nothing
     * changes. (Without that, a cancellation or mid-write error left the
     * tracks rewritten while the history table — the stats screen's only data
     * source — stayed cleared, i.e. "stats show nothing".)
     *
     * The generator skews toward realism: most tracks get few plays, a handful
     * become heavy favorites; recent days are busier than older ones; listen
     * times span the whole day (mornings, afternoons, late night) with an
     * evening peak.
     */
    suspend fun generateRandomPlayStats(years: Int = 3) {
        val trackIds = trackDao.getAllTrackIds()
        Log.i(TAG, "generateRandomPlayStats: ${trackIds.size} tracks in library")
        if (trackIds.isEmpty()) return

        // The generation is CPU-bound; compute everything in memory first, then
        // commit atomically (see the transaction comment above).
        withContext(Dispatchers.Default) {
            val zone = ZoneId.systemDefault()
            // Year arithmetic must happen on a calendar type: Instant has no
            // YEARS unit (no calendar context) and throws
            // UnsupportedTemporalTypeException. LocalDate handles leap years.
            val today = LocalDate.now(zone)
            val windowStart = today.minusYears(years.toLong())
            val windowDays = ChronoUnit.DAYS.between(windowStart, today).coerceAtLeast(1)

            val random = Random.Default
            val updates = ArrayList<TrackStatsUpdate>(trackIds.size)
            val listens = ArrayList<ListenHistoryEntity>()

            // Day picker biased toward "now": multiplying two uniform draws
            // concentrates mass near 1, so 1 - product clusters near zero (today).
            fun randomRecentDay(): Long {
                val biased = 1.0 - random.nextDouble() * random.nextDouble()
                return (biased * windowDays).toLong().coerceIn(0, windowDays - 1)
            }

            // Hour-of-day picker covering the WHOLE day, weighted like real
            // listening: an evening peak (17:00-23:00), a solid daytime block
            // (8:00-16:00) and a sparser night band (0:00-7:00). The previous
            // 17..23-only window made every generated listen land after 5pm.
            fun randomListenHour(): Int {
                return when (random.nextInt(100)) {
                    in 0 until 15 -> random.nextInt(8) // 0:00-7:59 night
                    in 15 until 55 -> 8 + random.nextInt(9) // 8:00-16:59 daytime
                    else -> 17 + random.nextInt(7) // 17:00-23:59 evening peak
                }
            }

            trackIds.forEach { trackId ->
                val plays = when {
                    random.nextInt(100) < 60 -> random.nextInt(3) // quiet tracks: 0..2
                    random.nextInt(100) < 85 -> 3 + random.nextInt(10) // regular: 3..12
                    else -> 15 + random.nextInt(40) // favorites: 15..54
                }
                if (plays == 0) {
                    updates += TrackStatsUpdate(trackId, 0, null, 0)
                    return@forEach
                }

                val listenTimestamps = LongArray(plays) {
                    val day = windowStart.plusDays(randomRecentDay())
                    LocalDateTime.of(day, LocalTime.of(randomListenHour(), random.nextInt(60)))
                        .atZone(zone)
                        .toInstant()
                        .toEpochMilli()
                }
                val lastPlayedAt = listenTimestamps.max()
                val score = when (random.nextInt(100)) {
                    in 0 until 55 -> 0
                    in 55 until 75 -> -1
                    in 75 until 90 -> 1
                    else -> 2
                }

                updates += TrackStatsUpdate(trackId, plays, lastPlayedAt, score)
                listenTimestamps.forEach { at ->
                    listens += ListenHistoryEntity(trackId = trackId, listenedAt = at)
                }
            }

            // One atomic commit: tracks' aggregate columns + full history.
            db.withTransaction {
                updates.forEach { update ->
                    trackDao.setPlayStats(
                        update.trackId,
                        update.playCount,
                        update.lastPlayedAt,
                        update.score
                    )
                }
                listenHistoryDao.clearAll()
                // Chunked inserts: every row binds 3 host variables and the
                // SQLite shipped on minSdk 29 caps statements at 999 of them.
                listens.chunked(LISTEN_INSERT_CHUNK).forEach { chunk ->
                    listenHistoryDao.insertListens(chunk)
                }
            }
            // The dev tool rewrote every score behind the overlay's back: drop
            // the stale overrides so readers fall back to the fresh DB values.
            _scoreOverrides.value = emptyMap()
            Log.i(TAG, "generateRandomPlayStats: committed ${updates.size} tracks, ${listens.size} listens")
        }
    }

    private companion object {
        private const val TAG = "FoamTrackRepository"

        /** Bound below SQLite's 999 host-variable limit (API 29) per statement. */
        const val SQL_VARIABLE_CHUNK = 900

        /**
         * listen_history rows per insertListens statement: 3 host variables per
         * row (id, trackId, listenedAt) × 300 = 900, safely under the 999 cap.
         */
        const val LISTEN_INSERT_CHUNK = 300

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
