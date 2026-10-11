package com.foamplayer.app.ui.screens.settings.lyricsmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.lyrics.LyricsSearchHelper
import com.foamplayer.app.data.lyrics.LyricsSourceStatus
import com.foamplayer.app.data.repository.LyricsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lyrics details for [LyricsTrackDetailsModal] hosted outside the lyrics
 * manager (the track modal's details page). Unlike [LyricsManagerViewModel] it
 * does no library-wide scan on creation; it resolves one track's lyrics on
 * demand, and keeps the last loaded details so the sheet can keep rendering
 * them while its exit animation plays.
 */
@HiltViewModel
class LyricsDetailsViewModel @Inject constructor(
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    private val _trackDetails = MutableStateFlow<LyricsTrackDetails?>(null)
    val trackDetails: StateFlow<LyricsTrackDetails?> = _trackDetails.asStateFlow()

    private val _isDetailsLoading = MutableStateFlow(false)
    val isDetailsLoading: StateFlow<Boolean> = _isDetailsLoading.asStateFlow()

    /**
     * Loads the track's lyrics for the details modal from local sources only
     * (persisted online cache, sidecar file, embedded tag) — never the network.
     * [LyricsTrackDetails.content] is null when no local source has lyrics.
     */
    fun loadTrackDetails(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            _isDetailsLoading.value = true
            val resolved = runCatching { lyricsRepository.resolveLocalLyrics(track) }.getOrNull()
            val status = runCatching { lyricsRepository.getSourceStatus(track) }.getOrDefault(
                LyricsSourceStatus(hasCached = false, hasFile = false, hasEmbedded = false)
            )
            _trackDetails.value = LyricsTrackDetails(
                track = track,
                content = resolved?.content,
                source = resolved?.source,
                providerName = resolved?.providerName,
                type = resolved?.let { LyricsSearchHelper.classify(it.content) },
                status = status
            )
            _isDetailsLoading.value = false
        }
    }

    /**
     * Removes every lyrics source from [track] (online cache, sidecar file,
     * embedded tag), then reloads its details so the open modal reflects the
     * deletion at once.
     */
    fun deleteLyrics(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { lyricsRepository.deleteLyrics(track) }
            loadTrackDetails(track)
        }
    }
}
