package com.foamplayer.app.audio.engine

import android.content.Context
import android.view.Choreographer
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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

    // Deliberately separate from _playbackState. Position ticks every rendered frame
    // while playing; if it lived on the same object as queue/currentTrack/etc, every tick
    // would emit a brand-new PlaybackState and force recomposition of every composable
    // that reads ANY field off `state` (not just position) — including screens far from
    // anything that actually needs live position, like the lyrics/queue subviews.
    // Consumers that need a live, per-frame position (seek bar, karaoke lyrics sync)
    // should read `positionMs.value` directly inside a leaf composable/provider lambda,
    // the same way LyricsScreen already does — not via collectAsState() at a screen's
    // top level.
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private var progressJob: Job? = null

    // The range of queue indices currently loaded into the player, as absolute indices
    // into _playbackState.value.queue (player index 0 == queue index loadedRange.first).
    // This is always kept to a small bounded window, never the whole queue — see
    // adjustWindow() below, which is the thing that actually enforces that bound.
    private var loadedRange: IntRange = IntRange.EMPTY

    // Guards a full "jump to a fresh window" (setQueue / loadWindowAndPlay — used when
    // starting a new queue or skipping somewhere outside the currently loaded window).
    // Cancelling this is how a new jump discards an in-flight one.
    private var windowJumpJob: Job? = null

    // Serializes the small, reactive window-shift mutations triggered by
    // onMediaItemTransition, so a burst of rapid skips can't run two adjustWindow()
    // player mutations concurrently against each other.
    private val windowAdjustMutex = Mutex()

    // Mirrors _playbackState.value.queue: maps track id -> its index in that queue
    private var trackIndexById: Map<Long, Int> = emptyMap()

    companion object {
        // Steady-state number of tracks kept loaded on each side of the current track.
        // Combined with CHUNK_SIZE below, the player never holds more than roughly
        // 2 * WINDOW_RADIUS + CHUNK_SIZE tracks at once, no matter how large the
        // underlying queue is.
        private const val WINDOW_RADIUS = 15
        // Tracks fetched (or trimmed) per window shift.
        private const val CHUNK_SIZE = 10
        // Start extending the loaded window once the current track is this close to
        // either edge of it, so the next chunk is already loaded before playback
        // actually reaches that edge.
        private const val EDGE_TRIGGER_MARGIN = 5
    }

    init {
        setupPlayerListeners()
        startProgressTracker()
    }

    /**
     * Suspends until just after the next display frame is rendered. Used to pace
     * Main-thread player mutations during window loading so they land right after
     * a frame commits instead of on an arbitrary wall-clock timer that can fire mid-frame.
     * AudioEngine's own `scope` isn't running under a composition, so it has no
     * MonotonicFrameClock available and can't use Compose's `withFrameNanos` directly —
     * this goes straight to the platform Choreographer instead.
     */
    private suspend fun awaitFrame() = suspendCancellableCoroutine { cont ->
        val callback = Choreographer.FrameCallback { cont.resumeWith(Result.success(Unit)) }
        Choreographer.getInstance().postFrameCallback(callback)
        cont.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
    }

    private fun TrackEntity.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(path.toUri())
            .setMediaId(id.toString())
            // Media data used by the notification
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(path.toUri())
                    .build()
            )
            .build()

    @OptIn(UnstableApi::class)
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

                    _playbackState.value = _playbackState.value.copy(
                        currentTrack = track,
                        queueIndex = index,
                        durationMs = track.duration,
                        currentPositionMs = 0L
                    )
                    _positionMs.value = 0L

                    // Reactively keep the loaded window centered on the new current
                    // track. This — not a background loop that loads the rest of the
                    // queue — is what keeps the player's footprint small even when the
                    // queue itself is huge.
                    scope.launch { adjustWindow() }
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizerManager.attachToAudioSession(audioSessionId)
            }
        })
    }

    fun setQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        windowJumpJob?.cancel()

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
     * Loads *only* a window of up to 2*WINDOW_RADIUS+1 tracks around [centerIndex]
     * into the player and starts playback. Nothing else in the queue is touched here:
     * the rest is loaded lazily, on demand, by adjustWindow() as playback moves
     * through it — so a 50,000-track queue costs exactly the same as a 50-track one.
     */
    private fun loadWindowAndPlay(tracks: List<TrackEntity>, centerIndex: Int) {
        windowJumpJob?.cancel()

        val windowStart = (centerIndex - WINDOW_RADIUS).coerceAtLeast(0)
        val windowEnd = (centerIndex + WINDOW_RADIUS).coerceAtMost(tracks.lastIndex)

        windowJumpJob = scope.launch {
            // Building MediaItems is pure CPU work with no player interaction (title/
            // artist metadata, artwork URI parsing), so it belongs on Default. This used
            // to run synchronously on Main, which meant every song switch that needed a
            // fresh window (starting a new queue, skipping past the loaded range, tapping
            // a track in the Queue subview) blocked Main building up to 2*WINDOW_RADIUS+1
            // MediaItems right on the thread Compose needs for that same switch's own
            // animations.
            val windowItems = withContext(Dispatchers.Default) {
                tracks.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
            }
            player.setMediaItems(windowItems, centerIndex - windowStart, 0L)
            player.prepare()
            player.play()
            loadedRange = windowStart..windowEnd
        }
    }

    /**
     * Keeps the player's loaded window small and centered around the current track,
     * regardless of how large the underlying queue is:
     *  - extends the window by one chunk on whichever edge playback is approaching, so
     *    that edge is loaded before playback actually reaches it, and
     *  - trims tracks off the *opposite* edge once the window has grown well past the
     *    target radius, so the player never accumulates the whole queue behind/ahead of
     *    where playback actually is.
     *
     * A no-op while a fresh window jump (loadWindowAndPlay) is in flight — that jump
     * establishes its own window when it completes, so adjusting the *old* window here
     * would just be wasted, and potentially wrong, work.
     */
    private suspend fun adjustWindow() {
        if (windowJumpJob?.isActive == true) return
        windowAdjustMutex.withLock {
            val tracks = _playbackState.value.queue
            val currentIndex = _playbackState.value.queueIndex
            if (tracks.isEmpty() || loadedRange == IntRange.EMPTY) return@withLock
            if (currentIndex !in loadedRange) return@withLock

            var start = loadedRange.first
            var end = loadedRange.last

            // Extend ahead if we're approaching the trailing edge.
            if (end < tracks.lastIndex && end - currentIndex < EDGE_TRIGGER_MARGIN) {
                val newEnd = (end + CHUNK_SIZE).coerceAtMost(tracks.lastIndex)
                val chunk = withContext(Dispatchers.Default) {
                    tracks.subList(end + 1, newEnd + 1).map { it.toMediaItem() }
                }
                awaitFrame()
                player.addMediaItems(chunk)
                end = newEnd
            }

            // Extend behind if we're approaching the leading edge.
            if (start > 0 && currentIndex - start < EDGE_TRIGGER_MARGIN) {
                val newStart = (start - CHUNK_SIZE).coerceAtLeast(0)
                val chunk = withContext(Dispatchers.Default) {
                    tracks.subList(newStart, start).map { it.toMediaItem() }
                }
                awaitFrame()
                player.addMediaItems(0, chunk)
                start = newStart
            }

            // Trim the far side once it's grown well past the target radius. This is
            // the step that actually bounds memory/timeline size for a huge queue —
            // without it, repeatedly extending ahead as playback advances would
            // eventually load the entire queue into the player, one chunk at a time.
            val overhang = WINDOW_RADIUS + CHUNK_SIZE
            if (currentIndex - start > overhang) {
                val newStart = currentIndex - WINDOW_RADIUS
                val removeCount = newStart - start
                awaitFrame()
                player.removeMediaItems(0, removeCount)
                start = newStart
            }
            if (end - currentIndex > overhang) {
                val newEnd = currentIndex + WINDOW_RADIUS
                val keepCount = newEnd - start + 1
                awaitFrame()
                player.removeMediaItems(keepCount, player.mediaItemCount)
                end = newEnd
            }

            loadedRange = start..end
        }
    }

    fun play() = player.play()
    fun pause() = player.pause()

    fun next() {
        if (player.hasNextMediaItem()) {
            // No seekTo(0) here: onMediaItemTransition already resets
            // currentPositionMs/_positionMs to 0 for the new track. Calling seekTo(0)
            // first would allocate and emit an extra PlaybackState (with the old
            // track's metadata but a zeroed position) right before the transition's
            // own PlaybackState emission overwrites it -- a wasted full-screen
            // recomposition, and a one-frame position/track mismatch, on every skip.
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
                // Actually changing tracks -- onMediaItemTransition will reset the
                // position itself. Calling seekTo(0) here too would double-emit
                // PlaybackState for the same reason described in next().
                player.seekToPreviousMediaItem()
            } else {
                // Staying on the current track (just restarting it) -- this is the
                // only case that needs an explicit reset, since no transition fires.
                seekTo(0)
            }
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
        _positionMs.value = positionMs
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
            windowJumpJob?.cancel()
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
                    // Updates the position flow every frame
                    _positionMs.value = player.currentPosition
                    awaitFrame()
                } else {
                    // Even when paused, keep position in sync to catch seeks from notification/external
                    _positionMs.value = player.currentPosition
                    // Loops every 200ms
                    delay(200)
                }
            }
        }
    }
}