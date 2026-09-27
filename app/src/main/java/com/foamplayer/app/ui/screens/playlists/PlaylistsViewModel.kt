package com.foamplayer.app.ui.screens.playlists

import android.content.Context
import androidx.compose.runtime.Immutable
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State machine of a playlist creation that may produce a missing-songs report. */
@Immutable
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
    @Immutable
    data class ImportingFolder(
        val name: String,
        val folderUri: String,
        val missing: List<String>
    ) : CreatePlaylistState
}

/**
 * Playlists CRUD plus M3U/folder import flows, including the missing-songs
 * report state machine that lets the user import unmatched folders wholesale.
 */
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

    /** Track count per playlist id, for list-row subtitles. */
    private val _trackCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val trackCounts: StateFlow<Map<Long, Int>> = _trackCounts

    private val _createState = MutableStateFlow<CreatePlaylistState>(CreatePlaylistState.Idle)
    val createState: StateFlow<CreatePlaylistState> = _createState

    val scanStatus: StateFlow<ScanStatus> = trackRepository.scanStatus

    init {
        viewModelScope.launch {
            playlistRepository.getAllPlaylists().collect { list ->
                _playlists.value = list
            }
        }
        viewModelScope.launch {
            playlistRepository.getPlaylistTrackCounts().collect { counts ->
                _trackCounts.value = counts.associate { it.playlistId to it.trackCount }
            }
        }
    }

    /** Creates a playlist, optionally importing from an M3U file or folder first. */
    fun createPlaylistFromSource(name: String, m3uUri: String?, folderUri: String?, imageUri: String? = null) {
        viewModelScope.launch {
            val resolvedName = name.trim().ifEmpty { context.getString(R.string.new_playlist_fallback) }
            when {
                m3uUri != null -> createFromM3u(resolvedName, m3uUri, imageUri)
                folderUri != null -> createFromFolder(resolvedName, folderUri, imageUri)
                else -> playlistRepository.createPlaylist(resolvedName, imageUri)
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlistId)
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch {
            playlistRepository.renamePlaylist(playlistId, name)
        }
    }

    fun setPlaylistImage(playlistId: Long, imageUrl: String?) {
        viewModelScope.launch {
            playlistRepository.setPlaylistImage(playlistId, imageUrl)
        }
    }

    /** Persists a drag-and-drop reordering done in edit mode. */
    fun reorderPlaylists(orderedPlaylistIds: List<Long>) {
        viewModelScope.launch {
            playlistRepository.reorderPlaylists(orderedPlaylistIds)
        }
    }

    /** Track count + total duration of a playlist, for the modal's details page. */
    suspend fun playlistTrackStats(playlistId: Long): Pair<Int, Long> {
        val tracks = playlistRepository.getTracksForPlaylistOnce(playlistId).firstOrNull().orEmpty()
        return tracks.size to tracks.sumOf { it.duration }
    }

    /** Dismisses the missing-songs report; a pending folder playlist is created with the songs found so far. */
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

    private suspend fun createFromM3u(name: String, m3uUri: String, imageUrl: String?) {
        val result = importer.importM3u(m3uUri, trackRepository.getAllTracksOnce())
        val playlistId = playlistRepository.createPlaylist(name, imageUrl)
        result.matched.forEach { track ->
            playlistRepository.appendTrackToPlaylist(playlistId, track.id)
        }
        if (result.missing.isNotEmpty()) {
            _createState.value = CreatePlaylistState.M3uSongsMissing(playlistId, name, result.missing)
        }
    }

    private suspend fun createFromFolder(name: String, folderUri: String, imageUrl: String?) {
        val result = importer.matchFolder(folderUri, trackRepository.getAllTracksOnce())
        if (result.missing.isEmpty()) {
            createPlaylistWithTracks(name, result.matched.map { it.id }, imageUrl)
        } else {
            _createState.value = CreatePlaylistState.FolderSongsMissing(
                name = name,
                folderUri = folderUri,
                foundTrackIds = result.matched.map { it.id },
                missing = result.missing
            )
        }
    }

    private suspend fun createPlaylistWithTracks(name: String, trackIds: List<Long>, imageUrl: String? = null) {
        val playlistId = playlistRepository.createPlaylist(name, imageUrl)
        trackIds.forEach { trackId ->
            playlistRepository.appendTrackToPlaylist(playlistId, trackId)
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            playlistRepository.appendTrackToPlaylist(playlistId, trackId)
        }
    }

    /** Live ids of the playlists that contain a track; drives the playlist picker's checkboxes. */
    fun playlistIdsContainingTrack(trackId: Long?): Flow<Set<Long>> =
        if (trackId == null) flowOf(emptySet())
        else playlistRepository.playlistIdsContainingTrack(trackId)

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            playlistRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }
}
