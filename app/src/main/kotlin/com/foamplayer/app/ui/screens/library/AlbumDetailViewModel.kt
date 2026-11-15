package com.foamplayer.app.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ArtistImageScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Loads one album's tracks (disc/track order) with an instant snapshot seed. */
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val artistImageScanner: ArtistImageScanner
) : ViewModel() {

    private val _tracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val tracks: StateFlow<List<TrackEntity>> = _tracks

    /**
     * Cached artist picture, used to seed the artist page opened from this
     * album so its hero can show the real picture from the first frame. When
     * absent, the album art is used as the seed instead.
     */
    private val _artistImage = MutableStateFlow<String?>(null)
    val artistImage: StateFlow<String?> = _artistImage

    fun loadAlbumTracks(albumName: String) {
        viewModelScope.launch {
            // Seed from the process-wide snapshot so revisits render on the
            // first frame; the live Room query then takes over.
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

    /** Reads the already-cached artist picture; never resolves or previews the fallback. */
    fun loadArtistImage(artistName: String) {
        viewModelScope.launch {
            _artistImage.value = artistImageScanner.imageFor(artistName)
        }
    }
}
