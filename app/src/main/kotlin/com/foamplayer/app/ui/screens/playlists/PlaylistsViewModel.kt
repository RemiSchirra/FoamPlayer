package com.foamplayer.app.ui.screens.playlists

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.R
import com.foamplayer.app.data.backup.PlaylistExporter
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.PlaylistImporter
import com.foamplayer.app.data.repository.PlaylistInsertPosition
import com.foamplayer.app.data.repository.PlaylistRepairResult
import com.foamplayer.app.data.repository.PlaylistRepairer
import com.foamplayer.app.data.repository.PlaylistRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.PlaylistCover
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.modals.M3uSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State machine of a playlist creation that may produce a missing-songs report. */
@Immutable
sealed interface CreatePlaylistState {
    data object Idle : CreatePlaylistState

    /** M3U import finished; the playlists exist (found tracks were added) and [missing] could not be matched. */
    data class M3uSongsMissing(
        val playlistIds: List<Long>,
        val playlistName: String,
        val missing: List<String>,
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
    private val exporter: PlaylistExporter,
    private val playlistRepairer: PlaylistRepairer,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _playlists = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    val playlists: StateFlow<List<PlaylistEntity>> = _playlists

    /** Track count per playlist id, for list-row subtitles. */
    private val _trackCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val trackCounts: StateFlow<Map<Long, Int>> = _trackCounts

    /**
     * Resolved covers per playlist id (custom image first, else the first
     * track in custom order carrying embedded art, else absent = letter
     * fallback), mirroring how artist rows resolve their pictures.
     */
    val playlistCovers: StateFlow<Map<Long, PlaylistCover>> =
        playlistRepository.getPlaylistCovers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _createState = MutableStateFlow<CreatePlaylistState>(CreatePlaylistState.Idle)
    val createState: StateFlow<CreatePlaylistState> = _createState

    val scanStatus: StateFlow<ScanStatus> = trackRepository.scanStatus

    /** Where tracks land inside a playlist when added from the add-to-playlist picker. */
    private val _addTrackInsertPosition = MutableStateFlow(PlaylistInsertPosition.END)
    val addTrackInsertPosition: StateFlow<PlaylistInsertPosition> = _addTrackInsertPosition

    private val _exportRunning = MutableStateFlow(false)
    val exportRunning: StateFlow<Boolean> = _exportRunning.asStateFlow()

    /** True while a playlist repair is running; guards against double taps. */
    private val _repairRunning = MutableStateFlow(false)
    val repairRunning: StateFlow<Boolean> = _repairRunning.asStateFlow()

    /** Result of the last repair run; consumed by the result sheet. */
    private val _repairResult = MutableStateFlow<PlaylistRepairResult?>(null)
    val repairResult: StateFlow<PlaylistRepairResult?> = _repairResult.asStateFlow()

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

    /**
     * Creates a playlist, optionally importing from M3U file(s) or a folder
     * first. One picked .m3u file is imported as the named playlist the name
     * field and cover describe; several files import one playlist per file,
     * each named after its file and without a cover.
     */
    fun createPlaylistFromSource(
        name: String,
        m3uFiles: List<M3uSelection>,
        folderUri: String?,
        imageUri: String? = null
    ) {
        viewModelScope.launch {
            val resolvedName = name.trim().ifEmpty { context.getString(R.string.new_playlist_fallback) }
            when {
                m3uFiles.size > 1 -> createFromM3uFiles(m3uFiles)
                m3uFiles.size == 1 -> createFromM3u(resolvedName, m3uFiles.first().uri, imageUri)
                folderUri != null -> createFromFolder(resolvedName, folderUri, imageUri)
                else -> playlistRepository.createPlaylist(resolvedName, imageUri)
            }
        }
    }

    /** Creates an empty playlist (the picker's + button); blank names fall back to "New playlist". */
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(
                name.trim().ifEmpty { context.getString(R.string.new_playlist_fallback) }
            )
        }
    }

    /** Sets where tracks land inside a playlist when added from the add-to-playlist picker. */
    fun setAddTrackInsertPosition(position: PlaylistInsertPosition) {
        _addTrackInsertPosition.value = position
    }

    /** Adds a track to a playlist at the picker's chosen insertion position. */
    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            playlistRepository.addTrackToPlaylist(playlistId, trackId, _addTrackInsertPosition.value)
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

    /** Tracks of a playlist in its custom order; feeds the modal's Next/Queue actions. */
    suspend fun playlistTracks(playlistId: Long): List<TrackEntity> =
        playlistRepository.getTracksForPlaylistOnce(playlistId).firstOrNull().orEmpty()

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

    /** Cancels the pending creation: deletes the just-imported M3U playlist(s), or discards the folder selection. */
    fun cancelMissingSongs() {
        val state = _createState.value
        _createState.value = CreatePlaylistState.Idle
        if (state is CreatePlaylistState.M3uSongsMissing) {
            viewModelScope.launch {
                state.playlistIds.forEach { playlistRepository.deletePlaylist(it) }
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

    /** Exports every playlist as .m3u8 files into the picked folder. */
    fun exportAllPlaylists(folderUri: String) {
        runExport { exporter.exportAll(folderUri) }
    }

    /**
     * Re-links playlist entries whose track ids a rescan invalidated (moved
     * files); the run's outcome lands in [repairResult] for the result sheet.
     */
    fun repairPlaylists() {
        if (_repairRunning.value) return
        viewModelScope.launch {
            _repairRunning.value = true
            try {
                _repairResult.value = playlistRepairer.repair()
            } finally {
                _repairRunning.value = false
            }
        }
    }

    fun consumeRepairResult() {
        _repairResult.value = null
    }

    private fun runExport(export: suspend () -> Unit) {
        if (_exportRunning.value) return
        viewModelScope.launch {
            _exportRunning.value = true
            try {
                export()
            } finally {
                _exportRunning.value = false
            }
        }
    }

    private suspend fun createFromM3u(name: String, m3uUri: String, imageUrl: String?) {
        val result = importer.importM3u(m3uUri, trackRepository.getAllTracksOnce())
        val playlistId = playlistRepository.createPlaylist(name, imageUrl)
        result.matched.forEach { track ->
            playlistRepository.appendTrackToPlaylist(playlistId, track.id)
        }
        if (result.missing.isNotEmpty()) {
            _createState.value = CreatePlaylistState.M3uSongsMissing(listOf(playlistId), name, result.missing)
        }
    }

    /** Imports every picked .m3u file as its own playlist, each named after its file. */
    private suspend fun createFromM3uFiles(files: List<M3uSelection>) {
        val fallback = context.getString(R.string.new_playlist_fallback)
        val tracks = trackRepository.getAllTracksOnce()
        val playlistIds = mutableListOf<Long>()
        val missing = mutableListOf<String>()
        files.forEach { file ->
            val result = importer.importM3u(file.uri, tracks)
            val name = file.name.substringBeforeLast('.').trim().ifEmpty { fallback }
            val playlistId = playlistRepository.createPlaylist(name, null)
            playlistIds += playlistId
            result.matched.forEach { track ->
                playlistRepository.appendTrackToPlaylist(playlistId, track.id)
            }
            missing += result.missing
        }
        if (missing.isNotEmpty()) {
            _createState.value = CreatePlaylistState.M3uSongsMissing(playlistIds, "", missing)
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

    /** Live ids of the playlists that contain a track; drives the playlist picker's checkboxes. */
    fun playlistIdsContainingTrack(trackId: Long?): Flow<Set<Long>> =
        if (trackId == null) flowOf(emptySet())
        else playlistRepository.playlistIdsContainingTrack(trackId)

    /**
     * Live ids of the playlists that contain EVERY track of [trackIds];
     * drives the picker's checkboxes when a whole collection is added.
     */
    fun playlistIdsContainingAllTracks(trackIds: List<Long>): Flow<Set<Long>> =
        playlistRepository.playlistIdsContainingAllTracks(trackIds)

    /** Adds a whole list of tracks (a collection) to a playlist, skipping ones it already has. */
    fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>) {
        viewModelScope.launch {
            playlistRepository.addTracksToPlaylist(playlistId, trackIds, _addTrackInsertPosition.value)
        }
    }

    /** Removes a whole list of tracks from a playlist (unchecking a row in a batch add). */
    fun removeTracksFromPlaylist(playlistId: Long, trackIds: List<Long>) {
        viewModelScope.launch {
            playlistRepository.removeTracksFromPlaylist(playlistId, trackIds)
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            playlistRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }
}
