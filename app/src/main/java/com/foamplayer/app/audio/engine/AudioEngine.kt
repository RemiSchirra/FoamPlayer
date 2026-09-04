package com.foamplayer.app.audio.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.foamplayer.app.audio.bitperfect.BitPerfectDetector
import com.foamplayer.app.audio.equalizer.EqualizerManager
import com.foamplayer.app.data.db.entities.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri

@Singleton
class AudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bitPerfectDetector: BitPerfectDetector,
    private val equalizerManager: EqualizerManager
) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private var progressJob: Job? = null

    // Window index range
    private var loadedRange: IntRange = IntRange.EMPTY
    private var lazyLoadJob: Job? = null

    // Mirrors _playbackState.value.queue: maps track id -> its index in that queue
    private var trackIndexById: Map<Long, Int> = emptyMap()

    companion object {
        // Number of tracks loaded around the current track
        private const val INITIAL_WINDOW_RADIUS = 15
        // Number of tracks get added per chunk
        private const val LAZY_CHUNK_SIZE = 25
        // Delay between chunk loads
        private const val LAZY_CHUNK_DELAY_MS = 40L
    }

    init {
        setupPlayerListeners()
        startProgressTracker()
    }

    private fun TrackEntity.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(path.toUri())
            .setMediaId(id.toString())
            .build()

    private fun setupPlayerListeners() {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val trackId = mediaItem?.mediaId?.toLongOrNull() ?: return
                val currentQueue = _playbackState.value.queue
                val index = trackIndexById[trackId]
                if (index != null) {
                    val track = currentQueue[index]
                    val outputInfo = bitPerfectDetector.evaluateOutput(
                        sourceSampleRate = track.sampleRate,
                        sourceBitDepth = track.bitDepth,
                        isEqualizerActive = equalizerManager.state.value.enabled
                    )
                    _playbackState.value = _playbackState.value.copy(
                        currentTrack = track,
                        queueIndex = index,
                        durationMs = track.duration,
                        currentPositionMs = 0L,
                        audioOutputInfo = outputInfo,
                        currentLyrics = track.lyrics
                    )
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizerManager.attachToAudioSession(audioSessionId)
            }
        })
    }

    fun setQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        lazyLoadJob?.cancel()

        if (tracks.isEmpty()) {
            loadedRange = IntRange.EMPTY
            trackIndexById = emptyMap()
            player.clearMediaItems()
            _playbackState.value = _playbackState.value.copy(queue = emptyList(), queueIndex = -1)
            return
        }

        val clampedStart = startIndex.coerceIn(0, tracks.lastIndex)

        trackIndexById = tracks.withIndex().associate { (i, t) -> t.id to i }
        _playbackState.value = _playbackState.value.copy(
            queue = tracks,
            queueIndex = clampedStart
        )

        loadWindowAndPlay(tracks, clampedStart)
    }

    /**
     * Loads a window of tracks around [centerIndex] into the player, starts playback,
     * then starts lazy loading of everything else.
     */
    private fun loadWindowAndPlay(tracks: List<TrackEntity>, centerIndex: Int) {
        lazyLoadJob?.cancel()

        val windowStart = (centerIndex - INITIAL_WINDOW_RADIUS).coerceAtLeast(0)
        val windowEnd = (centerIndex + INITIAL_WINDOW_RADIUS).coerceAtMost(tracks.lastIndex)
        loadedRange = windowStart..windowEnd

        val windowItems = tracks.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
        player.setMediaItems(windowItems, centerIndex - windowStart, 0L)
        player.prepare()
        player.play()

        lazyLoadRemaining()
    }

    /**
     * Loads the rest of the queue.
     */
    private fun lazyLoadRemaining() {
        lazyLoadJob = scope.launch {
            while (true) {
                val tracks = _playbackState.value.queue
                if (tracks.isEmpty() || loadedRange == IntRange.EMPTY) break
                if (loadedRange.first <= 0 && loadedRange.last >= tracks.lastIndex) break

                if (loadedRange.first > 0) {
                    val newStart = (loadedRange.first - LAZY_CHUNK_SIZE).coerceAtLeast(0)
                    val chunk = tracks.subList(newStart, loadedRange.first).map { it.toMediaItem() }
                    player.addMediaItems(0, chunk)
                    loadedRange = newStart..loadedRange.last
                    delay(LAZY_CHUNK_DELAY_MS)
                }

                if (loadedRange.last < tracks.lastIndex) {
                    val newEnd = (loadedRange.last + LAZY_CHUNK_SIZE).coerceAtMost(tracks.lastIndex)
                    val chunk = tracks.subList(loadedRange.last + 1, newEnd + 1).map { it.toMediaItem() }
                    player.addMediaItems(chunk)
                    loadedRange = loadedRange.first..newEnd
                    delay(LAZY_CHUNK_DELAY_MS)
                }
            }
        }
    }

    fun play() = player.play()
    fun pause() = player.pause()

    fun next() {
        if (player.hasNextMediaItem()) {
            seekTo(0)
            player.seekToNextMediaItem()
        } else {
            // The next track exists in the full queue but hasn't been lazily loaded yet
            // (e.g. rapid skipping outran the background loader) — jump a fresh window there.
            val queue = _playbackState.value.queue
            val nextIndex = _playbackState.value.queueIndex + 1
            if (nextIndex in queue.indices) {
                loadWindowAndPlay(queue, nextIndex)
            }
        }
    }

    fun previous() {
        if (player.hasPreviousMediaItem()) {
            // Skip to previous media only if the cursor is in the first 5 seconds of the track
            // TODO : This should be an app parameter
            if (player.contentPosition <= 5000L) {
                player.seekToPreviousMediaItem()
            }
            seekTo(0)
        } else if (loadedRange.first > 0) {
            val queue = _playbackState.value.queue
            val previousIndex = loadedRange.first - 1
            if (previousIndex in queue.indices) {
                loadWindowAndPlay(queue, previousIndex)
            }
        }
    }

    fun skipToIndex(index: Int) {
        val queue = _playbackState.value.queue
        if (index !in queue.indices) return

        if (index in loadedRange) {
            player.seekTo(index - loadedRange.first, 0L)
        } else {
            // Tapped a track outside the currently loaded window — load a fresh window
            // around it instead of paying to insert everything in between.
            loadWindowAndPlay(queue, index)
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentQueue = _playbackState.value.queue
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) return

        val fromLoaded = fromIndex in loadedRange
        val toLoaded = toIndex in loadedRange

        if (fromLoaded && toLoaded) {
            // Both endpoints are in the loaded window — reflect the move directly in the player.
            player.moveMediaItem(fromIndex - loadedRange.first, toIndex - loadedRange.first)
        }
        // If only one endpoint is loaded, the move crosses the loaded/unloaded boundary; we
        // resync the window below (after the queue list itself is updated) rather than trying
        // to patch a partially-loaded player timeline in place.
        val needsWindowResync = fromLoaded != toLoaded

        val newQueue = currentQueue.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }

        val oldPlayingIndex = _playbackState.value.queueIndex
        val newPlayingIndex = when {
            oldPlayingIndex == fromIndex -> toIndex
            oldPlayingIndex in (fromIndex + 1)..toIndex -> oldPlayingIndex - 1
            oldPlayingIndex in toIndex..<fromIndex -> oldPlayingIndex + 1
            else -> oldPlayingIndex
        }

        trackIndexById = newQueue.withIndex().associate { (i, t) -> t.id to i }
        _playbackState.value = _playbackState.value.copy(
            queue = newQueue,
            queueIndex = newPlayingIndex
        )

        if (needsWindowResync) {
            lazyLoadJob?.cancel()
            loadWindowAndPlay(newQueue, newPlayingIndex)
        }
    }

    fun removeQueueItem(index: Int) {
        val currentQueue = _playbackState.value.queue
        if (index !in currentQueue.indices) return

        when {
            index in loadedRange -> {
                player.removeMediaItem(index - loadedRange.first)
                loadedRange = loadedRange.first..<loadedRange.last
            }
            index < loadedRange.first -> {
                loadedRange = (loadedRange.first - 1)..<loadedRange.last
            }
            // index > loadedRange.last: nothing loaded needs to shift.
        }

        val newQueue = currentQueue.toMutableList().apply {
            removeAt(index)
        }

        val oldPlayingIndex = _playbackState.value.queueIndex
        val newPlayingIndex = when {
            index < oldPlayingIndex -> oldPlayingIndex - 1
            index == oldPlayingIndex -> if (newQueue.isEmpty()) -1 else index.coerceAtMost(newQueue.size - 1)
            else -> oldPlayingIndex
        }

        trackIndexById = newQueue.withIndex().associate { (i, t) -> t.id to i }
        _playbackState.value = _playbackState.value.copy(
            queue = newQueue,
            queueIndex = newPlayingIndex,
            currentTrack = if (newPlayingIndex != -1) newQueue.getOrNull(newPlayingIndex) else null
        )
    }

    fun toggleShuffle() {
        val newShuffle = !_playbackState.value.isShuffle
        player.shuffleModeEnabled = newShuffle
        _playbackState.value = _playbackState.value.copy(isShuffle = newShuffle)
    }

    fun toggleRepeat() {
        val nextMode = when (_playbackState.value.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        player.repeatMode = when (nextMode) {
            RepeatMode.NONE -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        _playbackState.value = _playbackState.value.copy(repeatMode = nextMode)
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                if (player.isPlaying) {
                    _playbackState.value = _playbackState.value.copy(
                        currentPositionMs = player.currentPosition
                    )
                }
                delay(200)
            }
        }
    }
}