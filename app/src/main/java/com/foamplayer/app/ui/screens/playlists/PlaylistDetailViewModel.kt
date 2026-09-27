package com.foamplayer.app.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One playlist's detail state: tracks, playlist entity and edit mode. */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: PlaylistRepository
) : ViewModel() {

    private val _tracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val tracks: StateFlow<List<TrackEntity>> = _tracks

    private val _playlist = MutableStateFlow<PlaylistEntity?>(null)
    val playlist: StateFlow<PlaylistEntity?> = _playlist

    /** Edit mode: rows gain swipe-to-remove and a drag handle for reordering. */
    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode

    fun setEditMode(enabled: Boolean) {
        _editMode.value = enabled
    }

    fun removeTrack(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    /** Persists a completed edit-mode drag: [orderedTrackIds] is the final displayed order. */
    fun persistTrackOrder(playlistId: Long, orderedTrackIds: List<Long>) {
        viewModelScope.launch {
            repository.reorderPlaylistTracks(playlistId, orderedTrackIds)
        }
    }

    fun loadPlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.getTracksForPlaylist(playlistId).collect { list ->
                _tracks.value = list
            }
        }

        viewModelScope.launch {
            repository.getAllPlaylists().collect { playlists ->
                _playlist.value = playlists.find { it.playlistId == playlistId }
            }
        }
    }
}
