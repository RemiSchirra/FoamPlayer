package com.foamplayer.app.data.repository

import com.foamplayer.app.data.db.dao.AlbumSearchResult
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
    private val settingsRepository: SettingsRepository
) {
    private val _scanStatus = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    fun getAllTracks(): Flow<List<TrackEntity>> = trackDao.getAllTracks()

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

    suspend fun rescanLibrary() {
        val folders = settingsRepository.settings.first().libraryFolders
        startScan(folders)
    }

    suspend fun startScan(folders: Set<String>) {
        if (folders.isEmpty()) {
            _scanStatus.value = ScanStatus.Finished(0)
            return
        }

        val settings = settingsRepository.settings.first()
        val artistSeps = settings.artistSeparators
        val genreSeps = settings.genreSeparators

        _scanStatus.value = ScanStatus.Scanning(0f, "Starting...")
        val scannedTracks = scanner.scanFoldersSync(folders, artistSeps, genreSeps) { progress, currentFile ->
            _scanStatus.value = ScanStatus.Scanning(progress, currentFile)
        }
        
        trackDao.insertTracks(scannedTracks)
        val scannedPaths = scannedTracks.map { it.path }
        trackDao.deleteOrphanedTracks(scannedPaths)
        
        _scanStatus.value = ScanStatus.Finished(scannedTracks.size)
    }
}
