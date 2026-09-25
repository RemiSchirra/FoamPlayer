package com.foamplayer.app.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.ArtistImageStore
import com.foamplayer.app.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val artistImageStore: ArtistImageStore
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

    /** Cached artist picture (Spotify, downloaded to the app cache). */
    private val _artistImage = MutableStateFlow<String?>(null)
    val artistImage: StateFlow<String?> = _artistImage

    fun loadArtistAlbums(artistName: String) {
        viewModelScope.launch {
            // Seed from the process-wide snapshot so revisits render on the
            // first frame; the live query then corrects/updates the list.
            val credited = repository.allTracksSnapshot()
                .filter { it.artist == artistName || it.albumArtist == artistName }
            if (_albums.value.isEmpty()) {
                _albums.value = credited
                    .groupBy { it.album }
                    .map { (album, tracks) ->
                        AlbumSearchResult(
                            album = album,
                            artist = tracks.firstNotNullOfOrNull { it.albumArtist ?: it.artist } ?: artistName,
                            albumArtPath = tracks.firstNotNullOf { it.albumArtPath }
                        )
                    }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.album })
            }
            if (_tracks.value.isEmpty()) {
                _tracks.value = credited.sortedWith(compareBy { it.title })
                _topTracks.value = computeTopTracks(_tracks.value)
            }
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

    fun loadArtistImage(artistName: String) {
        viewModelScope.launch {
            // Local cache file (re-downloaded from the stored CDN URL if the
            // cache was cleared/evicted); null when the artist was never resolved.
            _artistImage.value = artistImageStore.imagePath(ArtistImageStore.key(artistName))
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
