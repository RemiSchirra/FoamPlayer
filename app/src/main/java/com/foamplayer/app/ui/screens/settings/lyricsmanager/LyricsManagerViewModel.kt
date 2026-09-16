package com.foamplayer.app.ui.screens.settings.lyricsmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.constants.LyricsSource
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.lyrics.LyricsResultType
import com.foamplayer.app.data.lyrics.LyricsSearchHelper
import com.foamplayer.app.data.lyrics.LyricsSourceStatus
import com.foamplayer.app.data.repository.LyricsRepository
import com.foamplayer.app.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Library-wide lyrics counters powering the manager's header stats row. */
data class LyricsManagerStats(
    val cached: Int = 0,
    val embedded: Int = 0,
    val file: Int = 0,
    val noLyrics: Int = 0,
    val total: Int = 0,
    val isScanning: Boolean = false
)

/** Outcome of a delete-lyrics action, surfaced to the user as a snackbar. */
sealed interface LyricsDeleteEvent {
    /** [embeddedRemained] = embedded lyrics survived (unsupported container). */
    data class Deleted(val embeddedRemained: Boolean) : LyricsDeleteEvent
    data class Failed(val reason: String) : LyricsDeleteEvent
}

/**
 * Full lyrics picture of one track, powering the long-press details modal.
 * [content]/[source]/[type] are null when the track has no lyrics at all.
 */
data class LyricsTrackDetails(
    val track: TrackEntity,
    val content: String?,
    val source: LyricsSource?,
    val providerName: String?,
    val type: LyricsResultType?,
    val status: LyricsSourceStatus
)

@HiltViewModel
class LyricsManagerViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    val tracks: StateFlow<List<TrackEntity>> = trackRepository.getAllTracks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** Track ids with online-fetched lyrics persisted in the Room cache. */
    val cachedTrackIds: StateFlow<Set<Long>> = lyricsRepository.observeCachedTrackIds()
        .map { it.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet()
        )

    private val _statusMap = MutableStateFlow<Map<Long, LyricsSourceStatus>>(emptyMap())
    val statusMap: StateFlow<Map<Long, LyricsSourceStatus>> = _statusMap.asStateFlow()

    private val isScanning = MutableStateFlow(false)

    private val _deleteEvents = Channel<LyricsDeleteEvent>(Channel.BUFFERED)
    val deleteEvents = _deleteEvents.receiveAsFlow()

    private val _trackDetails = MutableStateFlow<LyricsTrackDetails?>(null)
    val trackDetails: StateFlow<LyricsTrackDetails?> = _trackDetails.asStateFlow()

    private val _isDetailsLoading = MutableStateFlow(false)
    val isDetailsLoading: StateFlow<Boolean> = _isDetailsLoading.asStateFlow()

    val stats: StateFlow<LyricsManagerStats> = combine(
        tracks,
        cachedTrackIds,
        statusMap,
        isScanning
    ) { allTracks, cachedIds, statuses, scanning ->
        val cachedIdSet = cachedIds.toSet()
        LyricsManagerStats(
            cached = allTracks.count { it.id in cachedIdSet },
            embedded = allTracks.count { statuses[it.id]?.hasEmbedded == true },
            file = allTracks.count { statuses[it.id]?.hasFile == true },
            noLyrics = allTracks.count { track ->
                track.id !in cachedIdSet &&
                        statuses[track.id]?.let { !it.hasFile && !it.hasEmbedded } == true
            },
            total = allTracks.size,
            isScanning = scanning
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LyricsManagerStats()
    )

    init {
        refresh()
    }

    /**
     * Re-analyzes every track's lyrics sources (cache / sidecar file / embedded
     * tag). Results stream in as they resolve, so the counters and filters fill
     * in progressively while the scan runs. Called on first load and again
     * whenever the screen resumes, so saves made on the results screen are
     * reflected when the user comes back. Repeat scans are cheap: the online
     * cache is read with a single query, one directory listing serves each
     * folder, and tracks whose audio file did not change skip the embedded-tag
     * parse entirely.
     */
    fun refresh() {
        if (!isScanning.compareAndSet(expect = false, update = true)) return

        viewModelScope.launch {
            try {
                val allTracks = withContext(Dispatchers.IO) { currentTracks() }
                val liveIds = allTracks.map { it.id }.toSet()
                _statusMap.update { current -> current.filterKeys { it in liveIds } }

                lyricsRepository.scanSourceStatuses(allTracks) { trackId, status ->
                    _statusMap.update { current -> current + (trackId to status) }
                }
            } finally {
                isScanning.value = false
            }
        }
    }

    /**
     * Removes every lyrics source from [track] (online cache, sidecar file,
     * embedded tag), then re-checks that track's status so its row and the
     * counters update immediately.
     */
    fun deleteLyrics(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { lyricsRepository.deleteLyrics(track) }
            _statusMap.update { current ->
                current + (track.id to runCatching { lyricsRepository.getSourceStatus(track) }.getOrDefault(
                    current[track.id] ?: LyricsSourceStatus(false, false, false)
                ))
            }
            result.fold(
                onSuccess = { deleteResult ->
                    if (deleteResult.deletedSomething) {
                        _deleteEvents.send(LyricsDeleteEvent.Deleted(deleteResult.embeddedStillPresent))
                    }
                },
                onFailure = { error -> _deleteEvents.send(LyricsDeleteEvent.Failed(error.message ?: "")) }
            )
        }
    }

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
                statusMap.value[track.id] ?: LyricsSourceStatus(false, false, false)
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

    private suspend fun currentTracks(): List<TrackEntity> =
        tracks.value.ifEmpty { trackRepository.getAllTracksOnce() }
}
