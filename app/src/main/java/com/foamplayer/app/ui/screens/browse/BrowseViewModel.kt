package com.foamplayer.app.ui.screens.browse

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A browsable folder: its storage path, display name and recursive track count. */
data class FolderEntry(
    val path: String,
    val name: String,
    val trackCount: Int
)

/** The content of one folder level: subfolders plus the tracks directly inside. */
data class FolderContent(
    val path: String?,
    val folders: List<FolderEntry>,
    val tracks: List<TrackEntity>
)

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val albums: StateFlow<List<AlbumSearchResult>> = trackRepository.getAllAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTracks: StateFlow<List<TrackEntity>> = trackRepository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists: StateFlow<List<String>> = trackRepository.getAllArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val genres: StateFlow<List<String>> = trackRepository.getAllGenres()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val years: StateFlow<List<Int>> = trackRepository.getAllYears()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _genreTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val genreTracks: StateFlow<List<TrackEntity>> = _genreTracks.asStateFlow()

    private val _yearTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val yearTracks: StateFlow<List<TrackEntity>> = _yearTracks.asStateFlow()

    private val _folderContent = MutableStateFlow<FolderContent?>(null)
    val folderContent: StateFlow<FolderContent?> = _folderContent.asStateFlow()

    fun loadGenreTracks(genre: String) {
        viewModelScope.launch {
            trackRepository.getTracksByGenre(genre).collect { _genreTracks.value = it }
        }
    }

    fun loadYearTracks(year: Int) {
        viewModelScope.launch {
            trackRepository.getTracksByYear(year).collect { _yearTracks.value = it }
        }
    }

    fun loadFolderContent(path: String?) {
        viewModelScope.launch {
            val tracks = trackRepository.getAllTracks().first()
            val libraryFolders = settingsRepository.settings.first().libraryFolders
            _folderContent.value = buildFolderContent(path, tracks, libraryFolders)
        }
    }

    private fun buildFolderContent(
        path: String?,
        tracks: List<TrackEntity>,
        libraryFolders: Set<String>
    ): FolderContent {
        if (path == null) {
            val roots = libraryFolders.mapNotNull { treeDocumentId(it) }
                .ifEmpty {
                    tracks.map { parentDocumentIdOf(it.path) }
                        .filter { it.isNotBlank() }
                        .distinct()
                }
            val entries = roots
                .map { root ->
                    FolderEntry(
                        path = root,
                        name = folderDisplayName(root),
                        trackCount = tracks.count { documentIdOf(it.path).startsWith("$root/") }
                    )
                }
                .filter { it.trackCount > 0 }
                .sortedBy { it.name.lowercase() }
            return FolderContent(path = null, folders = entries, tracks = emptyList())
        }

        val prefix = "$path/"
        val directTracks = mutableListOf<TrackEntity>()
        val subFolderCounts = mutableMapOf<String, Int>()

        tracks.forEach { track ->
            val docId = documentIdOf(track.path)
            if (!docId.startsWith(prefix)) return@forEach
            val remainder = docId.removePrefix(prefix)
            val slashIndex = remainder.indexOf('/')
            if (slashIndex == -1) {
                directTracks.add(track)
            } else {
                val subFolder = remainder.take(slashIndex)
                subFolderCounts[subFolder] = (subFolderCounts[subFolder] ?: 0) + 1
            }
        }

        val subFolders = subFolderCounts
            .map { (name, count) -> FolderEntry(path = "$prefix$name", name = name, trackCount = count) }
            .sortedBy { it.name.lowercase() }

        return FolderContent(path = path, folders = subFolders, tracks = directTracks)
    }

    private fun documentIdOf(trackPath: String): String {
        return try {
            val uri = Uri.parse(trackPath)
            when (uri.scheme) {
                "content" -> DocumentsContract.getDocumentId(uri)
                "file" -> uri.path ?: trackPath
                else -> trackPath
            }
        } catch (_: Exception) {
            trackPath
        }
    }

    private fun parentDocumentIdOf(trackPath: String): String =
        documentIdOf(trackPath).substringBeforeLast('/', missingDelimiterValue = "")

    private fun treeDocumentId(folderUri: String): String? {
        return try {
            val uri = Uri.parse(folderUri)
            if (uri.scheme == "content") DocumentsContract.getTreeDocumentId(uri) else uri.path
        } catch (_: Exception) {
            null
        }
    }

    private fun folderDisplayName(path: String): String =
        path.substringAfterLast('/').ifEmpty { path.substringAfterLast(':') }
}
