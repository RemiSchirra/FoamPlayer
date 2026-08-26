package com.foamplayer.app.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: PlaylistRepository
) : ViewModel() {

    private val _tracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val tracks: StateFlow<List<TrackEntity>> = _tracks

    private val _playlist = MutableStateFlow<PlaylistEntity?>(null)
    val playlist: StateFlow<PlaylistEntity?> = _playlist

    fun loadPlaylist(playlistId: Long) {
        viewModelScope.launch {
            // In a real app, you'd probably want to observe the playlist name too
            // For now, we'll just get the tracks. We might need a getPlaylistById in repository.
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
