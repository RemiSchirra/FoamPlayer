package com.foamplayer.app.ui.screens.playlists

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.repository.PlaylistImporter
import com.foamplayer.app.data.repository.PlaylistRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ScanStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CreatePlaylistState {
    data object Idle : CreatePlaylistState

    /** M3U import finished; the playlist exists (found tracks were added) and [missing] could not be matched. */
    data class M3uSongsMissing(
        val playlistId: Long,
        val playlistName: String,
        val missing: List<String>
    ) : CreatePlaylistState

    /** Folder import would miss [missing] files; playlist creation waits for the user's decision. */
    data class FolderSongsMissing(
        val name: String,
        val folderUri: String,
        val foundTrackIds: List<Long>,
        val missing: List<String>
    ) : CreatePlaylistState

    /** The folder is being added to the library and scanned; the playlist is created once it completes. */
    data class ImportingFolder(
        val name: String,
        val folderUri: String,
        val missing: List<String>
    ) : CreatePlaylistState
}

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val trackRepository: TrackRepository,
    private val settingsRepository: SettingsRepository,
    private val importer: PlaylistImporter,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _playlists = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    val playlists: StateFlow<List<PlaylistEntity>> = _playlists

    private val _createState = MutableStateFlow<CreatePlaylistState>(CreatePlaylistState.Idle)
    val createState: StateFlow<CreatePlaylistState> = _createState

    val scanStatus: StateFlow<ScanStatus> = trackRepository.scanStatus

    init {
        viewModelScope.launch {
            playlistRepository.getAllPlaylists().collect { list ->
                _playlists.value = list
            }
        }
    }

    fun createPlaylistFromSource(name: String, m3uUri: String?, folderUri: String?) {
        viewModelScope.launch {
            val resolvedName = name.trim().ifEmpty { context.getString(R.string.new_playlist_fallback) }
            when {
                m3uUri != null -> createFromM3u(resolvedName, m3uUri)
                folderUri != null -> createFromFolder(resolvedName, folderUri)
                else -> playlistRepository.createPlaylist(resolvedName)
            }
        }
    }

    /** Dismisses the missing-songs report; for a pending folder playlist, creates it with the songs found so far. */
    fun dismissMissingSongs() {
        val state = _createState.value
        _createState.value = CreatePlaylistState.Idle
        if (state is CreatePlaylistState.FolderSongsMissing) {
            viewModelScope.launch {
                createPlaylistWithTracks(state.name, state.foundTrackIds)
            }
        }
    }

    /** Cancels the pending creation: deletes the just-imported M3U playlist, or discards the folder selection. */
    fun cancelMissingSongs() {
        val state = _createState.value
        _createState.value = CreatePlaylistState.Idle
        if (state is CreatePlaylistState.M3uSongsMissing) {
            viewModelScope.launch {
                playlistRepository.deletePlaylist(state.playlistId)
            }
        }
    }

    /** Adds the folder to the library, scans it, then creates the playlist with everything in it. */
    fun importMissingFolderAndCreate() {
        val state = _createState.value as? CreatePlaylistState.FolderSongsMissing ?: return
        viewModelScope.launch {
            _createState.value = CreatePlaylistState.ImportingFolder(state.name, state.folderUri, state.missing)
            settingsRepository.addFolder(state.folderUri)
            trackRepository.scanFolderAndMerge(state.folderUri)
            val matched = importer.matchFolder(state.folderUri, trackRepository.getAllTracksOnce()).matched
            createPlaylistWithTracks(state.name, matched.map { it.id })
            _createState.value = CreatePlaylistState.Idle
        }
    }

    private suspend fun createFromM3u(name: String, m3uUri: String) {
        val result = importer.importM3u(m3uUri, trackRepository.getAllTracksOnce())
        val playlistId = playlistRepository.createPlaylist(name)
        result.matched.forEach { track ->
            playlistRepository.appendTrackToPlaylist(playlistId, track.id)
        }
        if (result.missing.isNotEmpty()) {
            _createState.value = CreatePlaylistState.M3uSongsMissing(playlistId, name, result.missing)
        }
    }

    private suspend fun createFromFolder(name: String, folderUri: String) {
        val result = importer.matchFolder(folderUri, trackRepository.getAllTracksOnce())
        if (result.missing.isEmpty()) {
            createPlaylistWithTracks(name, result.matched.map { it.id })
        } else {
            _createState.value = CreatePlaylistState.FolderSongsMissing(
                name = name,
                folderUri = folderUri,
                foundTrackIds = result.matched.map { it.id },
                missing = result.missing
            )
        }
    }

    private suspend fun createPlaylistWithTracks(name: String, trackIds: List<Long>) {
        val playlistId = playlistRepository.createPlaylist(name)
        trackIds.forEach { trackId ->
            playlistRepository.appendTrackToPlaylist(playlistId, trackId)
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            playlistRepository.appendTrackToPlaylist(playlistId, trackId)
        }
    }
}
