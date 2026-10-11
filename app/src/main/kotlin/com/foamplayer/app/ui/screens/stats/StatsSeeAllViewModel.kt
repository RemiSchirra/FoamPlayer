package com.foamplayer.app.ui.screens.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.TopAlbumStat
import com.foamplayer.app.data.db.dao.TopTrackStat
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ArtistCover
import com.foamplayer.app.data.scanner.ArtistCoverProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Backing data of a stats "see all" page: one ranked list (kind fixed by the
 * route), scoped to the period the overview was showing when opened. Top
 * tracks are uncapped; albums cap at [SeeAllLimit], matching the Home rows.
 */
@HiltViewModel
class StatsSeeAllViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    artistCoverProvider: ArtistCoverProvider,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val kind: StatsTopKind = savedStateHandle.get<String>("kind")
        ?.let { target -> StatsTopKind.entries.find { it.name == target } }
        ?: StatsTopKind.TRACKS

    val period: StatsPeriod = savedStateHandle.get<String>("period")
        ?.let { name -> StatsPeriod.entries.find { it.name == name } }
        ?: StatsPeriod.WEEK

    private val since = period.durationMs?.let { System.currentTimeMillis() - it }

    /** The page's full ranked list, typed per [kind] (others stay empty). */
    val tracks: StateFlow<List<TopTrackStat>> =
        if (kind == StatsTopKind.TRACKS) {
            trackRepository.observeTopListenedTracks(since)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        } else {
            emptyStateFlow()
        }

    val artists: StateFlow<List<ArtistStat>> =
        if (kind == StatsTopKind.ARTISTS) {
            trackRepository.observeListenedArtistRows(since)
                .map(::aggregateArtistRows)
                // Crediting aggregation runs per emission; off the main thread.
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        } else {
            emptyStateFlow()
        }

    val albums: StateFlow<List<TopAlbumStat>> =
        if (kind == StatsTopKind.ALBUMS) {
            trackRepository.observeTopListenedAlbums(since, SeeAllLimit)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        } else {
            emptyStateFlow()
        }

    /** Resolved pictures per artist name, for the top-artist rows. */
    val artistImages: StateFlow<Map<String, ArtistCover>> =
        artistCoverProvider.coversFlow(trackRepository.getAllArtists())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** One album's tracks in disc/track order. */
    suspend fun tracksForAlbum(album: String): List<TrackEntity> =
        trackRepository.getTracksByAlbum(album).first()

    /** Tracks naming [artist] as artist or album artist, in album order. */
    suspend fun tracksForArtist(artist: String): List<TrackEntity> = withContext(Dispatchers.Default) {
        trackRepository.getAllTracks().first()
            .filter { it.artist == artist || it.albumArtist == artist }
            .sortedWith(compareBy({ it.album }, { it.discNumber }, { it.trackNumber }, { it.title }))
    }

    private fun <T> emptyStateFlow(): StateFlow<List<T>> =
        MutableStateFlow(emptyList())

    companion object {
        const val SeeAllLimit = 50
    }
}
