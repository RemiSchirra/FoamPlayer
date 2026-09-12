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
import com.foamplayer.app.data.repository.TrackRepository
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
import kotlin.random.Random

@Singleton
class AudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bitPerfectDetector: BitPerfectDetector,
    private val equalizerManager: EqualizerManager,
    private val trackRepository: TrackRepository
) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    // Kept separate from _playbackState: position ticks every frame, and a shared
    // state object would force full recompositions on every tick. Read positionMs
    // directly where live position is needed (seek bar, lyrics sync).
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private var progressJob: Job? = null

    // Queue indices currently loaded into the player (player index 0 == loadedRange.first).
    // Always a small window, never the whole queue - see adjustWindow().
    // Stale while pendingWindowResync is set: don't seek or patch against it.
    private var loadedRange: IntRange = IntRange.EMPTY

    // Set when a queue move invalidated the player timeline. The rebuild is deferred
    // to the next skip or transition: an async rebuild could land after a tap's seek
    // and reset playback to the cursor ("starts then reverts").
    private var pendingWindowResync: Boolean = false

    // In-flight "jump to a fresh window" job; cancelled when a new jump supersedes it.
    private var windowJumpJob: Job? = null

    // Generation counter: only the newest window jump may write the timeline and
    // loadedRange. Prevents stale jumps from racing with newer ones.
    private var windowJumpId: Long = 0L

    // Serializes adjustWindow() so rapid skips can't run overlapping player mutations.
    private val windowAdjustMutex = Mutex()

    // Monotonic allocator for queue entry ids (PlaybackState.queueEntryIds).
    private var nextEntryId: Long = 0L

    private fun allocateEntryId(): Long = nextEntryId++

    // Snapshot of the whole library, used to draw random auxiliary tracks from.
    private var allTracks: List<TrackEntity> = emptyList()

    // True while a refill is running; prevents overlapping refills from appending duplicates.
    private var refillScheduled: Boolean = false

    // Listen counting: a track counts as listened once it has been playing for
    // LISTEN_THRESHOLD_MS. The pending id marks the current play occurrence --
    // once counted it is cleared, so pause/resume never counts twice.
    private var listenTimerJob: Job? = null
    private var pendingListenTrackId: Long? = null

    companion object {
        // Tracks kept loaded on each side of the current track.
        private const val WINDOW_RADIUS = 15
        // Tracks fetched (or trimmed) per window shift.
        private const val CHUNK_SIZE = 10
        // Extend the window when playback is this close to either edge.
        private const val EDGE_TRIGGER_MARGIN = 5
        // Auxiliary (random) tracks always kept after the main queue.
        private const val AUXILIARY_QUEUE_LENGTH = PlaybackState.AUXILIARY_QUEUE_LENGTH
        // Playback time after which a track counts as listened (hardcoded 1s for now).
        private const val LISTEN_THRESHOLD_MS = 1000L
    }

    init {
        setupPlayerListeners()
        startProgressTracker()
        observeLibraryForAuxiliaryRefill()
    }

    /** Suspends until the next frame is rendered (uses Choreographer directly,
     *  since this scope has no Compose frame clock). */
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
                if (isPlaying) {
                    // (Re)start the listen timer from a clean threshold window.
                    val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
                    if (trackId != null && trackId == pendingListenTrackId) {
                        scheduleListenCount(trackId)
                    }
                } else {
                    // Pausing stops the count attempt; resuming restarts it.
                    listenTimerJob?.cancel()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val trackId = mediaItem?.mediaId?.toLongOrNull() ?: return
                val currentQueue = _playbackState.value.queue

                // New play occurrence: the previous track no longer counts toward
                // this one, so the timer restarts from scratch.
                listenTimerJob?.cancel()
                pendingListenTrackId = trackId
                if (player.isPlaying) {
                    scheduleListenCount(trackId)
                }

                // The same track can appear more than once (aux pool), so use the
                // player's timeline position to find the exact queue index, falling
                // back to the nearest matching occurrence.
                val index = resolveExactQueueIndex(trackId)
                if (index != null) {
                    val track = currentQueue[index]

                    // A played auxiliary track becomes a main track: unflag it,
                    // then refill the pool with fresh random picks.
                    val flags = _playbackState.value.auxiliaryFlags
                    val wasAuxiliary = flags.getOrNull(index) == true

                    _playbackState.value = _playbackState.value.copy(
                        currentTrack = track,
                        queueIndex = index,
                        durationMs = track.duration,
                        currentPositionMs = 0L,
                        auxiliaryFlags = if (wasAuxiliary) {
                            flags.toMutableList().also { it[index] = false }
                        } else flags
                    )
                    _positionMs.value = 0L

                    if (pendingWindowResync) {
                        pendingWindowResync = false
                        loadWindowAndPlay(currentQueue, index)
                    }

                    scope.launch { adjustWindow() }

                    if (wasAuxiliary) {
                        scheduleAuxiliaryRefill()
                    }
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizerManager.attachToAudioSession(audioSessionId)
            }
        })
    }

    /**
     * Counts [trackId] as listened once it survives [LISTEN_THRESHOLD_MS] of
     * playback. The check at fire time guards against a pause or a transition
     * landing right on the threshold.
     */
    private fun scheduleListenCount(trackId: Long) {
        listenTimerJob?.cancel()
        listenTimerJob = scope.launch {
            delay(LISTEN_THRESHOLD_MS)
            if (player.isPlaying && player.currentMediaItem?.mediaId?.toLongOrNull() == trackId) {
                pendingListenTrackId = null
                trackRepository.incrementPlayCount(trackId, System.currentTimeMillis())
            }
        }
    }

    private fun observeLibraryForAuxiliaryRefill() {
        scope.launch {
            trackRepository.getAllTracks().collect { tracks ->
                allTracks = tracks
                // The library changed, refresh the auxiliary pool from it.
                if (_playbackState.value.queue.isNotEmpty()) {
                    scope.launch { refillAuxiliary() }
                }
            }
        }
    }

    fun setQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        windowJumpJob?.cancel()

        if (tracks.isEmpty()) {
            loadedRange = IntRange.EMPTY
            pendingWindowResync = false
            player.clearMediaItems()
            _playbackState.value = _playbackState.value.copy(
                queue = emptyList(),
                queueIndex = -1,
                queueEntryIds = emptyList(),
                auxiliaryFlags = emptyList()
            )
            return
        }

        val clampedStart = startIndex.coerceIn(0, tracks.lastIndex)

        // Generate ids for the main queue, then the auxiliary queue
        val entryIds = List(tracks.size) { allocateEntryId() } +
                List(AUXILIARY_QUEUE_LENGTH) { allocateEntryId() }
        val newQueue = tracks + drawAuxiliaryTracks(emptySet(), AUXILIARY_QUEUE_LENGTH)

        pendingWindowResync = false
        _playbackState.value = _playbackState.value.copy(
            queue = newQueue,
            queueIndex = clampedStart,
            queueEntryIds = entryIds,
            auxiliaryFlags = List(entryIds.size) { i -> i >= tracks.size }
        )

        loadWindowAndPlay(newQueue, clampedStart)
    }

    /**
     * Inserts [track] right after the current track ("play next"). With no queue
     * active, starts a fresh queue with just this track.
     */
    fun playNext(track: TrackEntity) {
        insertTrack(track, _playbackState.value.queueIndex + 1)
    }

    /**
     * Appends [track] to the end of the MAIN queue — never behind the random
     * auxiliary pool. Starting from the end of the queue, walks back over any
     * trailing auxiliary tracks and inserts right after the last main-queue
     * item found. With no queue active, starts a fresh queue with just this
     * track.
     */
    fun addToQueue(track: TrackEntity) {
        val current = _playbackState.value
        var index = current.queue.size
        while (index > 0 && current.auxiliaryFlags.getOrNull(index - 1) == true) {
            index--
        }
        insertTrack(track, index)
    }

    /**
     * Plays [tracks] picked from a list (library, playlist, search results, ...):
     * keeps the played history up to and including the current track, drops the
     * upcoming main-queue tracks, queues [tracks] in their place and jumps to
     * [startIndex]. The auxiliary pool is left untouched, so no refill is needed.
     */
    fun playListReplacingUpcoming(tracks: List<TrackEntity>, startIndex: Int) {
        if (tracks.isEmpty()) return

        val current = _playbackState.value
        if (current.queue.isEmpty() || current.queueIndex !in current.queue.indices) {
            setQueue(tracks, startIndex)
            return
        }

        val flags = current.auxiliaryFlags
        // Walk back over the trailing auxiliary pool; only the upcoming
        // main-queue section in front of it gets dropped.
        var auxStart = current.queue.size
        while (auxStart > current.queueIndex + 1 && flags.getOrNull(auxStart - 1) == true) {
            auxStart--
        }

        val keptCount = current.queueIndex + 1
        val newQueue = current.queue.take(keptCount) + tracks +
                current.queue.subList(auxStart, current.queue.size)

        _playbackState.value = current.copy(
            queue = newQueue,
            queueEntryIds = current.queueEntryIds.take(keptCount) +
                    List(tracks.size) { allocateEntryId() } +
                    current.queueEntryIds.subList(auxStart, current.queueEntryIds.size),
            auxiliaryFlags = flags.take(keptCount) + List(tracks.size) { false } +
                    flags.subList(auxStart, flags.size)
        )

        jumpWindowTo(keptCount + startIndex.coerceIn(0, tracks.lastIndex))
    }

    /**
     * Shared implementation for [playNext] and [addToQueue]: inserts into the
     * queue state, then patches the player timeline in place when the insert
     * lands inside (or at the head of) the loaded window. Inserts past the
     * window need no timeline work — adjustWindow() loads them as playback
     * approaches, and a pending resync rebuilds over the updated state anyway.
     */
    private fun insertTrack(track: TrackEntity, requestedIndex: Int) {
        val current = _playbackState.value
        if (current.queue.isEmpty()) {
            setQueue(listOf(track), 0)
            return
        }

        val insertIndex = requestedIndex.coerceIn(0, current.queue.size)

        val newQueue = current.queue.toMutableList().apply {
            add(insertIndex, track)
        }

        val newEntryIds = current.queueEntryIds.toMutableList().apply {
            add(insertIndex.coerceAtMost(size), allocateEntryId())
        }

        // Inserted tracks are always main tracks, never part of the auxiliary pool.
        val newFlags = current.auxiliaryFlags.toMutableList().apply {
            add(insertIndex.coerceAtMost(size), false)
        }

        // Keep the cursor on the same track it was on: an insert at or before
        // it shifts every later index (including the cursor's) by one.
        val newQueueIndex =
            if (insertIndex <= current.queueIndex) current.queueIndex + 1 else current.queueIndex

        _playbackState.value = current.copy(
            queue = newQueue,
            queueEntryIds = newEntryIds,
            queueIndex = newQueueIndex,
            auxiliaryFlags = newFlags
        )

        val window = loadedRange
        if (pendingWindowResync || window == IntRange.EMPTY) return
        when {
            // Before the window: queue indices shift, the player timeline doesn't.
            insertIndex < window.first ->
                loadedRange = (window.first + 1)..(window.last + 1)
            // At or inside the window: slot it into the player at the same relative
            // position. At the head (insertIndex == window.first) that's index 0,
            // right before the playing item -- ExoPlayer keeps playback on the (now
            // shifted) current item. At the far edge (window.last + 1) it appends,
            // so playback never falls off the end of the loaded range (e.g. "play
            // next" while the queue's last track is the one playing).
            insertIndex <= window.last + 1 -> {
                player.addMediaItem(insertIndex - window.first, track.toMediaItem())
                loadedRange = window.first..(window.last + 1)
            }
            // Further past the window: nothing loaded needs to shift;
            // adjustWindow() loads the item as playback approaches.
        }
    }

    /**
     * Loads a window of tracks around [centerIndex] and starts playback.
     * The rest of the queue is loaded lazily by adjustWindow().
     */
    private fun loadWindowAndPlay(tracks: List<TrackEntity>, centerIndex: Int) {
        windowJumpJob?.cancel()
        val jumpId = ++windowJumpId
        // This jump writes the fresh timeline, resolving any deferred resync.
        pendingWindowResync = false

        val windowStart = (centerIndex - WINDOW_RADIUS).coerceAtLeast(0)
        val windowEnd = (centerIndex + WINDOW_RADIUS).coerceAtMost(tracks.lastIndex)

        windowJumpJob = scope.launch {
            // Loads the media items for a window
            val windowItems = withContext(Dispatchers.Default) {
                tracks.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
            }
            // A newer jump superseded this one, drop it
            if (jumpId != windowJumpId) return@launch
            player.setMediaItems(windowItems, centerIndex - windowStart, 0L)
            player.prepare()
            player.play()
            loadedRange = windowStart..windowEnd
        }
    }

    /**
     * Extends the loaded window.
     * Do nothing if a window jump is in running or a resync is pending.
     */
    private suspend fun adjustWindow() {
        if (windowJumpJob?.isActive == true) return
        if (pendingWindowResync) return
        windowAdjustMutex.withLock {
            val tracks = _playbackState.value.queue
            val currentIndex = _playbackState.value.queueIndex
            if (tracks.isEmpty() || loadedRange == IntRange.EMPTY) return@withLock
            if (currentIndex !in loadedRange) return@withLock

            var start = loadedRange.first
            var end = loadedRange.last

            // Extend ahead
            if (end < tracks.lastIndex && end - currentIndex < EDGE_TRIGGER_MARGIN) {
                val newEnd = (end + CHUNK_SIZE).coerceAtMost(tracks.lastIndex)
                val chunk = withContext(Dispatchers.Default) {
                    tracks.subList(end + 1, newEnd + 1).map { it.toMediaItem() }
                }
                awaitFrame()
                player.addMediaItems(chunk)
                end = newEnd
            }

            // Extend behind
            if (start > 0 && currentIndex - start < EDGE_TRIGGER_MARGIN) {
                val newStart = (start - CHUNK_SIZE).coerceAtLeast(0)
                val chunk = withContext(Dispatchers.Default) {
                    tracks.subList(newStart, start).map { it.toMediaItem() }
                }
                awaitFrame()
                player.addMediaItems(0, chunk)
                start = newStart
            }

            // Unload medias if they're too far from the window
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
            player.seekToNextMediaItem()
        } else {
            // Next track not loaded, load a window
            val queue = _playbackState.value.queue
            val nextIndex = _playbackState.value.queueIndex + 1
            if (nextIndex in queue.indices) {
                loadWindowAndPlay(queue, nextIndex)
            }
        }
    }

    fun previous() {
        if (player.hasPreviousMediaItem()) {
            // Only change track if we're within the first 5 seconds.
            // TODO: Make this an app parameter.
            if (player.contentPosition <= 5000L) {
                player.seekToPreviousMediaItem()
            } else {
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


        // Force a window jump
        if (pendingWindowResync) {
            jumpWindowTo(index)
            return
        }

        if (index in loadedRange) {
            player.seekTo(index - loadedRange.first, 0L)
            return
        }

        jumpWindowTo(index)
    }

    /**
     * Synchronously rebuilds the window around [centerIndex] from the current queue
     * and starts playing it. Override any in-flight jump and pending resync.
     */
    private fun jumpWindowTo(centerIndex: Int) {
        val queue = _playbackState.value.queue
        if (centerIndex !in queue.indices) return

        windowJumpId++
        windowJumpJob?.cancel()
        pendingWindowResync = false

        val windowStart = (centerIndex - WINDOW_RADIUS).coerceAtLeast(0)
        val windowEnd = (centerIndex + WINDOW_RADIUS).coerceAtMost(queue.lastIndex)
        val items = queue.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
        loadedRange = windowStart..windowEnd
        player.setMediaItems(items, centerIndex - windowStart, 0L)
        player.prepare()
        player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
        _positionMs.value = positionMs
    }

    /**
     * Resolves an entry id to its current index, derived from live state so it
     * never goes stale (unlike a cached map).
     */
    private fun entryIndexOf(entryId: Long): Int =
        _playbackState.value.queueEntryIds.indexOf(entryId)

    /**
     * All indices holding [trackId].
     */
    private fun candidateIndexesFor(trackId: Long): List<Int> =
        _playbackState.value.queue
            .withIndex()
            .filter { (_, t) -> t.id == trackId }
            .map { (i, _) -> i }

    /**
     * Moves the entry to be played next, jumps to it, and starts playback
     */
    fun playQueueEntry(entryId: Long) {
        val fromIndex = entryIndexOf(entryId)
        if (fromIndex == -1) return
        val nextIndex = (_playbackState.value.queueIndex + 1).coerceAtLeast(0)
        if (fromIndex != nextIndex) {
            moveQueueItem(fromIndex, nextIndex)
        }
        skipToIndex(nextIndex)
        player.play()
    }

    /**
     * Moves an entry to [toUpcomingPosition], relative to the current cursor.
     */
    fun moveQueueEntry(entryId: Long, toUpcomingPosition: Int) {
        val fromIndex = entryIndexOf(entryId)
        if (fromIndex == -1) return
        val queue = _playbackState.value.queue
        val offset = (_playbackState.value.queueIndex + 1).coerceAtLeast(0)
        val toIndex = (offset + toUpcomingPosition).coerceIn(0, queue.lastIndex)
        if (fromIndex == toIndex) return
        moveQueueItem(fromIndex, toIndex)
    }

    /**
     * Removes the occurrence identified by [entryId], wherever it currently sits.
     */
    fun removeQueueEntry(entryId: Long) {
        val index = entryIndexOf(entryId)
        if (index == -1) return
        removeQueueItem(index)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentQueue = _playbackState.value.queue
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) return

        val current = _playbackState.value

        val fromLoaded = fromIndex in loadedRange
        val toLoaded = toIndex in loadedRange

        if (fromLoaded && toLoaded) {
            // Move a media item in the player
            player.moveMediaItem(fromIndex - loadedRange.first, toIndex - loadedRange.first)
        }

        val newQueue = currentQueue.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }

        // Flags and entry ids move with their item, keeping each item's identity.
        val newEntryIds = current.queueEntryIds.toMutableList().apply {
            if (fromIndex < size) add(toIndex, removeAt(fromIndex))
        }

        val flags = current.auxiliaryFlags
        val movedIsAuxiliary = flags.getOrNull(fromIndex) == true
        val newFlags = flags.toMutableList().apply {
            if (fromIndex < size) add(toIndex, removeAt(fromIndex))
        }

        val oldPlayingIndex = current.queueIndex
        val newPlayingIndex = when {
            oldPlayingIndex == fromIndex -> toIndex
            oldPlayingIndex in (fromIndex + 1)..toIndex -> oldPlayingIndex - 1
            oldPlayingIndex in toIndex..<fromIndex -> oldPlayingIndex + 1
            else -> oldPlayingIndex
        }

        _playbackState.value = current.copy(
            queue = newQueue,
            queueEntryIds = normalizeEntryIds(newEntryIds, newQueue.size),
            queueIndex = newPlayingIndex,
            auxiliaryFlags = normalizeAuxiliaryFlags(newFlags, newQueue.size)
        )

        if (fromLoaded != toLoaded) {
            // Defer the rebuild (see pendingWindowResync) and invalidate in-flight jumps.
            windowJumpId++
            windowJumpJob?.cancel()
            pendingWindowResync = true
        }

        if (movedIsAuxiliary) {
            scheduleAuxiliaryRefill()
        }
    }

    fun removeQueueItem(index: Int) {
        val currentQueue = _playbackState.value.queue
        if (index !in currentQueue.indices) return

        val current = _playbackState.value
        val flags = current.auxiliaryFlags
        val removedIsAuxiliary = flags.getOrNull(index) == true

        when {
            index in loadedRange -> {
                player.removeMediaItem(index - loadedRange.first)
                loadedRange = loadedRange.first..<loadedRange.last
            }
            index < loadedRange.first -> {
                loadedRange = (loadedRange.first - 1)..<loadedRange.last
            }
            // index > loadedRange.last: nothing loaded needs to shift
        }

        val newQueue = currentQueue.toMutableList().apply {
            removeAt(index)
        }

        val newEntryIds = current.queueEntryIds.toMutableList().apply {
            if (index < size) removeAt(index)
        }

        // Remove the flag with the item, the pool refills below if needed
        val newFlags = flags.toMutableList().apply {
            if (index < size) removeAt(index)
        }

        val oldPlayingIndex = current.queueIndex
        val newPlayingIndex = when {
            index < oldPlayingIndex -> oldPlayingIndex - 1
            index == oldPlayingIndex -> if (newQueue.isEmpty()) -1 else index.coerceAtMost(newQueue.size - 1)
            else -> oldPlayingIndex
        }

        _playbackState.value = current.copy(
            queue = newQueue,
            queueEntryIds = normalizeEntryIds(newEntryIds, newQueue.size),
            queueIndex = newPlayingIndex,
            currentTrack = if (newPlayingIndex != -1) newQueue.getOrNull(newPlayingIndex) else null,
            auxiliaryFlags = normalizeAuxiliaryFlags(newFlags, newQueue.size)
        )

        if (removedIsAuxiliary) {
            scheduleAuxiliaryRefill()
        }
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

    /**
     * Finds the exact queue index of the playing track via the player's timeline
     * position (exact even with duplicate tracks). Falls back to the nearest match.
     */
    private fun resolveExactQueueIndex(trackId: Long): Int? {
        val queue = _playbackState.value.queue
        if (queue.isEmpty() || loadedRange == IntRange.EMPTY) return resolveQueueIndex(trackId)

        val playerIndex = player.currentMediaItemIndex + loadedRange.first
        if (playerIndex in queue.indices && queue[playerIndex].id == trackId) return playerIndex

        return resolveQueueIndex(trackId)
    }

    /** Nearest-occurrence fallback for [resolveExactQueueIndex]. */
    private fun resolveQueueIndex(trackId: Long): Int? {
        val candidates = candidateIndexesFor(trackId)
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]

        val queue = _playbackState.value.queue
        val playerIndex = player.currentMediaItemIndex + loadedRange.first
        if (playerIndex in queue.indices && queue[playerIndex].id == trackId) return playerIndex

        return candidates.minByOrNull { kotlin.math.abs(it - playerIndex) }
    }

    /** Regenerates entry ids if the list doesn't match the queue size. */
    private fun normalizeEntryIds(entryIds: List<Long>, queueSize: Int): List<Long> =
        if (entryIds.size == queueSize) entryIds else List(queueSize) { allocateEntryId() }

    /** Falls back to all-unflagged if the flag list doesn't match the queue size. */
    private fun normalizeAuxiliaryFlags(flags: List<Boolean>, queueSize: Int): List<Boolean> =
        if (flags.size == queueSize) flags else List(queueSize) { false }

    /**
     * True when AUXILIARY_QUEUE_LENGTH flagged tracks are queued after the current
     * index. Counts flags rather than comparing lengths, since the main section
     * shrinks as the cursor advances.
     */
    private fun hasFullAuxiliaryPool(flags: List<Boolean>, queueIndex: Int): Boolean {
        var count = 0
        for (i in (queueIndex + 1)..flags.lastIndex) {
            if (flags.getOrNull(i) == true) count++
        }
        return count >= AUXILIARY_QUEUE_LENGTH
    }

    /** Ids already queued after (or at) [queueIndex], for duplicate-aware drawing. */
    private fun upcomingTrackIds(queue: List<TrackEntity>, queueIndex: Int): Set<Long> =
        queue.drop(queueIndex + 1).mapTo(HashSet()) { it.id }

    /** Picks up to [count] random tracks, preferring ids not already queued up next. */
    private fun drawAuxiliaryTracks(existing: Collection<Long>, count: Int): List<TrackEntity> {
        val pool = allTracks
        if (pool.isEmpty() || count <= 0) return emptyList()

        val fresh = pool.filter { it.id !in existing }
        val draws = ArrayList<TrackEntity>(count)
        while (draws.size < count) {
            draws += if (fresh.isNotEmpty()) {
                fresh[Random.nextInt(fresh.size)]
            } else {
                // Everything is already queued, allow repeats
                pool[Random.nextInt(pool.size)]
            }
        }
        return draws
    }

    private fun scheduleAuxiliaryRefill() {
        if (refillScheduled) return
        refillScheduled = true
        scope.launch {
            try {
                refillAuxiliary()
            } finally {
                refillScheduled = false
            }
        }
    }

    /**
     * Tops the auxiliary pool back up after the upcoming section. Extends (never
     * rebuilds) flags and entry ids so existing auxiliary items keep their identity,
     * and only appends, new entries appear nowhere else outside setQueue.
     */
    private fun refillAuxiliary() {
        val current = _playbackState.value
        val flags = current.auxiliaryFlags
        if (current.queue.isEmpty() || flags.size != current.queue.size) return
        if (hasFullAuxiliaryPool(flags, current.queueIndex)) return

        val needed = AUXILIARY_QUEUE_LENGTH - flags.drop(current.queueIndex + 1).count { it }
        val additions = drawAuxiliaryTracks(
            upcomingTrackIds(current.queue, current.queueIndex),
            needed
        )
        if (additions.isEmpty()) return

        _playbackState.value = current.copy(
            queue = current.queue + additions,
            queueEntryIds = current.queueEntryIds + List(additions.size) { allocateEntryId() },
            auxiliaryFlags = flags + List(additions.size) { true }
        )
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                if (player.isPlaying) {
                    // Update the position flow every frame
                    _positionMs.value = player.currentPosition
                    awaitFrame()
                } else {
                    // Keep position in sync while paused (catches external seeks)
                    _positionMs.value = player.currentPosition
                    delay(200)
                }
            }
        }
    }
}