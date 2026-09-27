package com.foamplayer.app.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    private val _tracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val tracks: StateFlow<List<TrackEntity>> = _tracks

    fun loadAlbumTracks(albumName: String) {
        viewModelScope.launch {
            // Seed from the process-wide tracks snapshot so revisits render on
            // the first frame; the live query then corrects/updates the list.
            // The seed filter+sort used to run on the main dispatcher (a fresh
            // StateFlow forces the destination screen's first composition to
            // wait on it), stalling the open frame on a full-library pass for
            // large libraries. Off-main keeps the open frame free; the flow's
            // first emission is the same list instance as the snapshot, so no
            // extra pass is paid.
            val seeded = withContext(Dispatchers.Default) {
                repository.allTracksSnapshot()
                    .filter { it.album == albumName }
                    .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title }))
            }
            if (_tracks.value.isEmpty()) _tracks.value = seeded
            repository.getTracksByAlbum(albumName).collect { list ->
                _tracks.value = list
            }
        }
    }
}