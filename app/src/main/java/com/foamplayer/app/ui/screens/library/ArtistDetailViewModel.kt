package com.foamplayer.app.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    private val _albums = MutableStateFlow<List<AlbumSearchResult>>(emptyList())
    val albums: StateFlow<List<AlbumSearchResult>> = _albums

    /** All of the artist's tracks, alphabetical by title. */
    private val _tracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val tracks: StateFlow<List<TrackEntity>> = _tracks

    /**
     * The [TopTracksCount] most listened tracks; when fewer than that have
     * listens, the remainder is padded with random tracks from the artist.
     */
    private val _topTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val topTracks: StateFlow<List<TrackEntity>> = _topTracks

    fun loadArtistAlbums(artistName: String) {
        viewModelScope.launch {
            repository.getAlbumsByArtist(artistName).collect { list ->
                _albums.value = list
            }
        }
    }

    fun loadArtistTracks(artistName: String) {
        viewModelScope.launch {
            repository.getTracksByArtist(artistName).collect { list ->
                _tracks.value = list
                _topTracks.value = computeTopTracks(list)
            }
        }
    }

    private fun computeTopTracks(tracks: List<TrackEntity>): List<TrackEntity> {
        if (tracks.size <= TopTracksCount) return tracks
        val listened = tracks.filter { it.playCount > 0 }.sortedByDescending { it.playCount }
        val top = listened.take(TopTracksCount)
        if (top.size < TopTracksCount) {
            val chosenIds = top.mapTo(HashSet()) { it.id }
            val fill = tracks
                .filterNot { it.id in chosenIds }
                .shuffled()
                .take(TopTracksCount - top.size)
            return top + fill
        }
        return top
    }

    private companion object {
        const val TopTracksCount = 5
    }
}
