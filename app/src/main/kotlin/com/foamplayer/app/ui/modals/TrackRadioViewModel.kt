package com.foamplayer.app.ui.modals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Max number of similar tracks offered around the seed track. */
private const val RadioTrackLimit = 50

/** Content of the radio sheet for one seed track. [sourceTrack] null = closed. */
data class TrackRadioState(
    val sourceTrack: TrackEntity? = null,
    /** The radio list: the seed track first, then similar tracks. */
    val tracks: List<TrackEntity> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * Similar-track ("radio") list behind [TrackRadioModal]. Builds the list from
 * the library on demand: the seed track first, then every other track sharing
 * at least one genre or one artist credit with it, in random order. It keeps
 * the last built state after dismissal so the sheet keeps rendering its
 * content while its exit animation plays, and a new [openRadioFor] call
 * rebuilds from scratch.
 */
@HiltViewModel
class TrackRadioViewModel @Inject constructor(
    private val trackRepository: TrackRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TrackRadioState())
    val state: StateFlow<TrackRadioState> = _state.asStateFlow()

    private var requestGeneration = 0

    /** Opens the radio sheet for [track], (re)building its similar list. */
    fun openRadioFor(track: TrackEntity) {
        requestGeneration++
        val generation = requestGeneration
        _state.value = TrackRadioState(sourceTrack = track, isLoading = true)
        viewModelScope.launch(Dispatchers.Default) {
            val tracks = buildRadioList(track)
            if (generation == requestGeneration) {
                _state.value = TrackRadioState(sourceTrack = track, tracks = tracks)
            }
        }
    }

    /** Closes the sheet; the built list stays for the exit animation. */
    fun dismissRadio() {
        _state.value = _state.value.copy(sourceTrack = null, isLoading = false)
    }

    /**
     * Builds the radio list: the seed track first, then up to [RadioTrackLimit]
     * other library tracks sharing at least one genre or one artist credit
     * (artist / album artist) with it, shuffled. Matching happens in SQL over
     * "; "-multi-value convention patterns ([TrackRepository.getSimilarTrackIds]),
     * then only the picked rows are fetched by id — neither step materializes
     * the whole track table, which keeps opening the sheet fast.
     */
    private suspend fun buildRadioList(source: TrackEntity): List<TrackEntity> {
        val genreSeeds = valuesOf(source.genre).toList()
        val artistSeeds = creditsOf(source).toList()
        if (genreSeeds.isEmpty() && artistSeeds.isEmpty()) return listOf(source)

        val similarIds = trackRepository.getSimilarTrackIds(source.id, genreSeeds, artistSeeds)
        if (similarIds.isEmpty()) return listOf(source)

        val pickedIds = similarIds.shuffled().take(RadioTrackLimit)
        val similar = trackRepository.getTracksByIds(pickedIds)
        return listOf(source) + similar
    }
}

/** Splits a "; "-joined multi-value field (genre, artist, album artist) into its values. */
private fun valuesOf(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(';')
        .map { it.trim() }
        .filterTo(mutableSetOf()) { it.isNotEmpty() }
}

/** All artist credits of a track: its artist and album-artist values. */
private fun creditsOf(track: TrackEntity): Set<String> =
    valuesOf(track.artist) + valuesOf(track.albumArtist)
