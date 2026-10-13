package com.foamplayer.app.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.constants.MediaListView
import com.foamplayer.app.data.canvas.CanvasProvider
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.canvas.CanvasArtwork
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val audioEngine: AudioEngine,
    private val settingsRepository: SettingsRepository,
    private val trackRepository: TrackRepository,
    private val canvasProvider: CanvasProvider
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = audioEngine.playbackState

    val positionMs: StateFlow<Long> = audioEngine.positionMs

    // Narrow projections of playbackState for the composition roots (FoamApp,
    // FoamNavHost). Collecting these instead of the whole state keeps emissions like
    // play/pause or queue mutations from recomposing the entire navigation body when
    // only the presence of a track (or the source-collection highlight) matters.
    val hasCurrentTrack: StateFlow<Boolean> = audioEngine.playbackState
        .map { it.currentTrack != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val activeTrackId: StateFlow<Long?> = audioEngine.playbackState
        .map { it.currentTrack?.id }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentAlbumArtPath: StateFlow<String?> = audioEngine.playbackState
        .map { it.currentTrack?.albumArtPath }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Narrow play/pause bit: feeds the paused-art shrink of track rows that act as the
    // shared-element endpoint of a player-dismiss flight (see rowFlightArtwork). Kept
    // narrow so the nav root can collect it without subscribing to the whole state.
    val isPlaying: StateFlow<Boolean> = audioEngine.playbackState
        .map { it.isPlaying }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Pause INTENT, reactive, distinct from "not audibly playing": [isPlaying] goes
     * false while the player is buffering (e.g. between tracks) even though the user
     * never pressed pause — the playWhenReady mirror in [PlaybackState] stays true
     * through those gaps. The lock-display session must only close on a real pause
     * request, and the paused-art shrink must only apply to one (the combined
     * isPlaying || isPlaybackRequested bit feeds rememberPausedArtScale). Synchronous
     * readers use [isPlaybackRequested.value].
     */
    val isPlaybackRequested: StateFlow<Boolean> = audioEngine.playbackState
        .map { it.isPlaybackRequested }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Id of the collection the queue was started from (null = bare track list),
    // highlighted only while the cursor is still inside that collection's own
    // section of the queue.
    val activeCollectionId: StateFlow<String?> = audioEngine.playbackState
        .map {
            it.queueSourceId?.takeIf { sourceId ->
                it.queueSourceFlags.getOrNull(it.queueIndex) == true
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Seeded with the SAVED settings, not AppSettings() defaults: with a default seed the
    // first frame drew the default nav-bar order (and mode, background, ...) and then
    // jumped to the stored one once DataStore emitted. The first DataStore read is a few
    // milliseconds; waiting for it once here means every collector starts on real values.
    private val initialSettings: AppSettings = runBlocking { settingsRepository.settings.first() }

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = initialSettings
    )

    private val _dynamicAccentColor = MutableStateFlow<Color?>(null)
    val dynamicAccentColor: StateFlow<Color?> = _dynamicAccentColor.asStateFlow()

    private val _openPlayerRequested = MutableStateFlow(false)
    val openPlayerRequested: StateFlow<Boolean> = _openPlayerRequested.asStateFlow()

    /**
     * True until the first startup queue restore attempt settles: the saved playing
     * session is either back in the engine (paused at the saved position) or there
     * was nothing to restore. UI gates (mini player, service start) wait for it so
     * a restored session doesn't flash as absent for a frame.
     */
    private val _sessionRestoreSettled = MutableStateFlow(false)
    val sessionRestoreSettled: StateFlow<Boolean> = _sessionRestoreSettled.asStateFlow()

    init {
        // Close-and-reopen resumption: rebuild the persisted session (played history,
        // current track, upcoming queue) PAUSED at the saved position. Playback is
        // never started here; the user presses play. The engine no-ops when the
        // process survived (queue still live) or nothing was saved.
        viewModelScope.launch {
            audioEngine.restoreSession()
            _sessionRestoreSettled.value = true
        }
    }

    fun setDynamicAccentColor(color: Color?) {
        _dynamicAccentColor.value = color
    }

    /**
     * Animated-canvas artwork for [track]: first available provider wins
     * (AppleMusic -> Tidal -> ViviMusic -> EchoMusic). Network access is gated
     * by [allowMobileData] inside [CanvasProvider]; null when disabled, offline
     * on a disallowed network, or when no provider has a canvas.
     */
    suspend fun resolveAnimatedCover(track: TrackEntity, allowMobileData: Boolean): CanvasArtwork? =
        canvasProvider.getCanvas(
            song = track.title,
            artist = track.artist,
            album = track.album,
            allowMobileData = allowMobileData
        )

    fun requestOpenPlayer() {
        _openPlayerRequested.value = true
    }

    fun consumeOpenPlayerRequest() {
        _openPlayerRequested.value = false
    }

    // Starting a list (library, playlist, search results, ...): keep the played
    // history, drop the upcoming main queue, and play the picked list next.
    // [sourceCollectionId] marks the queue as started from that collection
    // (PlaybackState.queueSourceId), which drives the active-collection highlight.
    fun playQueueReplacingUpcoming(
        tracks: List<TrackEntity>,
        startIndex: Int,
        sourceCollectionId: String? = null
    ) = audioEngine.playListReplacingUpcoming(tracks, startIndex, sourceCollectionId)

    fun playTrackReplacingUpcoming(track: TrackEntity) =
        audioEngine.playListReplacingUpcoming(listOf(track), 0)

    // Long-press track actions from the library lists: queue right after the
    // current track, or append to the end of the main queue.
    fun playNext(track: TrackEntity) = audioEngine.playNext(track)
    fun addToQueue(track: TrackEntity) = audioEngine.addToQueue(track)

    // Long-press collection actions (album/artist/genre/year/folder): queue the
    // whole collection right after the current track, or append it to the end of
    // the main queue, keeping the collection's track order.
    fun playNext(tracks: List<TrackEntity>) = audioEngine.playNextAll(tracks)
    fun addToQueue(tracks: List<TrackEntity>) = audioEngine.addAllToQueue(tracks)

    fun play() = audioEngine.play()
    fun pause() = audioEngine.pause()
    fun next() = audioEngine.next()
    fun previous() = audioEngine.previous()
    fun skipToIndex(index: Int, forward: Boolean = true) = audioEngine.skipToIndex(index, forward)

    // Queue mutations are occurrence-addressed (entry ids), resolved inside the engine
    // against current state — never by indices captured in a possibly-stale UI snapshot.
    fun playQueueEntry(entryId: Long) = audioEngine.playQueueEntry(entryId)
    fun moveQueueEntry(entryId: Long, toUpcomingPosition: Int) =
        audioEngine.moveQueueEntry(entryId, toUpcomingPosition)
    fun removeQueueEntry(entryId: Long) = audioEngine.removeQueueEntry(entryId)

    fun shuffleUpcoming() = audioEngine.shuffleUpcoming()
    fun clearUpcoming() = audioEngine.clearUpcoming()

    fun seekTo(positionMs: Long) = audioEngine.seekTo(positionMs)
    fun toggleShuffle() = audioEngine.toggleShuffle()
    fun toggleRepeat() = audioEngine.toggleRepeat()

    // Track details modal: the queue entities held in PlaybackState are snapshots and go
    // stale as soon as the DB row changes (play count, last-listen date, ...). Re-read the
    // row when the modal opens; fall back to the snapshot if it can't be found.
    suspend fun freshTrack(track: TrackEntity): TrackEntity =
        trackRepository.getTrackById(track.id) ?: track

    // Track details modal: reset the listen counter and last-listen date.
    fun resetPlayStats(trackId: Long) {
        viewModelScope.launch { trackRepository.resetPlayStats(trackId) }
    }

    // Track details modal: toggle the user-set instrumental flag (lyrics view override).
    fun setTrackInstrumental(trackId: Long, instrumental: Boolean) {
        viewModelScope.launch { trackRepository.setInstrumental(trackId, instrumental) }
    }

    // Track scores (-1 dislike / 0 not noted / 1 like / 2 super like): the in-memory
    // queue entities go stale the moment a score is written to the DB, so the UI
    // overlays a shared id→score map on top of the entity values. The map is owned
    // by [TrackRepository] because the media-notification service writes scores too —
    // its toggle must be reflected in this UI instantly, and vice versa.
    val scoreOverrides: StateFlow<Map<Long, Int>> = trackRepository.scoreOverrides

    fun setTrackScore(trackId: Long, score: Int) {
        viewModelScope.launch { trackRepository.setScore(trackId, score) }
    }

    // Per-view media list density (organisation modal). Every view remembers its own.
    fun setMediaDensity(view: MediaListView, density: MediaDensity) {
        viewModelScope.launch { settingsRepository.setMediaDensity(view, density) }
    }

    // Per-view media list sort (organisation modal): the mode and its "Inverse" toggle.
    fun setMediaSortMode(view: MediaListView, mode: CollectionSortMode) {
        viewModelScope.launch { settingsRepository.setMediaSortMode(view, mode) }
    }

    fun setMediaSortInverted(view: MediaListView, inverted: Boolean) {
        viewModelScope.launch { settingsRepository.setMediaSortInverted(view, inverted) }
    }
}
