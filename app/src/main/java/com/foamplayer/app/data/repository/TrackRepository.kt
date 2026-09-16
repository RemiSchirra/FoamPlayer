package com.foamplayer.app.data.repository

import com.foamplayer.app.data.db.dao.AlbumRecommendation
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.dao.CachedLyricsDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.scanner.LocalAudioScanner
import com.foamplayer.app.data.scanner.ScanStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val scanner: LocalAudioScanner,
    private val settingsRepository: SettingsRepository,
    private val cachedLyricsDao: CachedLyricsDao
) {
    private val _scanStatus = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    fun getAllTracks(): Flow<List<TrackEntity>> = trackDao.getAllTracks()

    suspend fun getAllTracksOnce(): List<TrackEntity> = trackDao.getAllTracksOnce()

    suspend fun getTrackById(trackId: Long): TrackEntity? = trackDao.getTrackById(trackId)

    fun searchTracks(query: String): Flow<List<TrackEntity>> = trackDao.searchTracks(query)

    fun searchArtists(query: String): Flow<List<String>> = trackDao.searchArtists(query).map { rawArtists ->
        rawArtists.flatMap { it.split("; ") }
            .filter { it.contains(query, ignoreCase = true) }
            .distinct()
            .sorted()
    }

    fun searchAlbums(query: String): Flow<List<AlbumSearchResult>> = trackDao.searchAlbums(query)

    fun getTracksByAlbum(album: String): Flow<List<TrackEntity>> = trackDao.getTracksByAlbum(album)

    fun getAlbumsByArtist(artist: String): Flow<List<AlbumSearchResult>> = trackDao.getAlbumsByArtist(artist)

    fun getAllAlbums(): Flow<List<AlbumSearchResult>> = trackDao.getAllAlbums()

    fun getAllArtists(): Flow<List<String>> = trackDao.getAllArtistNames().map { rawArtists ->
        rawArtists.flatMap { it.split("; ") }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun getAllGenres(): Flow<List<String>> = trackDao.getAllGenres().map { rawGenres ->
        rawGenres.flatMap { it.split("; ") }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun getTracksByGenre(genre: String): Flow<List<TrackEntity>> = trackDao.getTracksByGenre(genre)

    fun getAllYears(): Flow<List<Int>> = trackDao.getAllYears()

    fun getTracksByYear(year: Int): Flow<List<TrackEntity>> = trackDao.getTracksByYear(year)

    /** Most recently listened tracks (Home "Recent listens" row). */
    fun getRecentlyPlayed(): Flow<List<TrackEntity>> = trackDao.getRecentlyPlayed()

    /** Long-standing favorites (Home "Old favorites" row); [cutoff] bounds what counts as "not recent". */
    fun getOldFavorites(cutoff: Long): Flow<List<TrackEntity>> = trackDao.getOldFavorites(cutoff)

    /** Albums ranked by the aggregate play count + likes of their tracks (Home row + see-all page). */
    fun getRecommendedAlbums(): Flow<List<AlbumRecommendation>> = trackDao.getRecommendedAlbums()

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
        // stats and score: re-attach the existing ones to the freshly scanned entities.
        val existingStats = trackDao.getPlayStats().associateBy { it.path }
        val mergedTracks = scannedTracks.map { scanned ->
            existingStats[scanned.path]?.let {
                scanned.copy(playCount = it.playCount, lastPlayedAt = it.lastPlayedAt, score = it.score, instrumental = it.instrumental)
            } ?: scanned
        }

        trackDao.insertTracks(mergedTracks)
        val scannedPaths = mergedTracks.map { it.path }
        trackDao.deleteOrphanedTracks(scannedPaths)
        cachedLyricsDao.deleteOrphaned(mergedTracks.map { it.id })

        _scanStatus.value = ScanStatus.Finished(scannedTracks.size)
    }

    /**
     * Scans a single folder and merges the result into the library WITHOUT
     * deleting tracks from other folders (unlike [startScan], which cleans up
     * orphans across the whole library from the scanned set).
     */
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

    /** Marks [trackId] as listened once at [timestamp] (the threshold-crossing moment). */
    suspend fun incrementPlayCount(trackId: Long, timestamp: Long) =
        trackDao.incrementPlayCount(trackId, timestamp)

    /** Resets the listen counter to 0 and clears the last-listen date. */
    suspend fun resetPlayStats(trackId: Long) = trackDao.resetPlayStats(trackId)

    /** Stores the user score (-1 dislike / 0 not noted / 1 like / 2 super like). */
    suspend fun setScore(trackId: Long, score: Int) = trackDao.setScore(trackId, score)

    /** Stores the user-set instrumental flag (lyrics view shows a music note instead of lyrics). */
    suspend fun setInstrumental(trackId: Long, instrumental: Boolean) =
        trackDao.setInstrumental(trackId, instrumental)

    /** Emits the user-set instrumental flag for [trackId], updating whenever it changes. */
    fun observeInstrumental(trackId: Long): Flow<Boolean> = trackDao.observeInstrumental(trackId)
}
