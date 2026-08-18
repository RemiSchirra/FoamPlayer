package com.foamplayer.app.data.repository

import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.scanner.LocalAudioScanner
import com.foamplayer.app.data.scanner.ScanStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    suspend fun rescanLibrary() {
        val folders = settingsRepository.selectedFolders.first()
        startScan(folders)
    }

    suspend fun startScan(folders: Set<String>) {
        if (folders.isEmpty()) {
            _scanStatus.value = ScanStatus.Finished(0)
            return
        }

        _scanStatus.value = ScanStatus.Scanning(0f, "Starting...")
        val scannedTracks = scanner.scanFoldersSync(folders) { progress, currentFile ->
            _scanStatus.value = ScanStatus.Scanning(progress, currentFile)
        }
        
        trackDao.insertTracks(scannedTracks)
        val scannedPaths = scannedTracks.map { it.path }
        trackDao.deleteOrphanedTracks(scannedPaths)
        
        _scanStatus.value = ScanStatus.Finished(scannedTracks.size)
    }
}