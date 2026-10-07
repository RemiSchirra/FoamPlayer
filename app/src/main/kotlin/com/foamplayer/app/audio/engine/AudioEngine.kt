package com.foamplayer.app.audio.engine

import android.content.Context
import android.util.Log
import android.view.Choreographer
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.foamplayer.app.audio.bitperfect.AudioOutputInfo
import com.foamplayer.app.audio.bitperfect.BitPerfectDetector
import com.foamplayer.app.audio.device.OutputDeviceManager
import com.foamplayer.app.audio.dsp.DspAudioProcessor
import com.foamplayer.app.audio.dsp.DspEngine
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.data.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Owns the ExoPlayer instance, the queue and playback state. All audio flows
 * through the [DspEngine]'s processor (a byte-for-byte passthrough when the
 * DSP state is neutral, which keeps output bit-perfect).
 *
 * The player loads a small window of the queue around the current track
 * ([adjustWindow] extends/trims it as playback moves). Crossfades build a
 * secondary player that is promoted here at the swap — see [FadeEngine] — so
 * [player] always points at the audible stream, and [onPlayerSwappedListener]
 * re-binds the MediaSession to it.
 */
@Singleton
@OptIn(UnstableApi::class)
class AudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bitPerfectDetector: BitPerfectDetector,
    private val dspEngine: DspEngine,
    private val outputDeviceManager: OutputDeviceManager,
    private val settingsRepository: SettingsRepository,
    private val trackRepository: TrackRepository,
    private val fadeEngine: FadeEngine,
    private val queuePersistence: QueuePersistence
) {
    /** The active player; replaced at each crossfade swap. */
    val player: ExoPlayer
        get() = activePlayerField

    private var activePlayerField: ExoPlayer = buildPlayer()

    /** Builds the primary player with the DSP processor in its audio sink. */
    private fun buildPlayer(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(dspEngine.processor))
                    // Float-capable AudioTrack for float-decoded sources.
                    .setEnableFloatOutput(enableFloatOutput)
                    .build()
        }
        return ExoPlayer.Builder(context, renderersFactory).build()
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    // Position ticks every frame; kept out of playbackState so its collectors
    // don't recompose on every tick.
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    // Emitted on explicit seeks only, not frame ticks.
    private val _seekEvents = MutableSharedFlow<Long>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val seekEvents: SharedFlow<Long> = _seekEvents.asSharedFlow()

    // Invoked after a crossfade promoted a new player (set by PlaybackService).
    private var onPlayerSwappedListener: (() -> Unit)? = null

    // Invoked whenever the player representing "now playing" changes mid-crossfade:
    // the logical switch hands the session over to the incoming stream at the fade's
    // switch point (halfway through the automatic crossfade), an abort hands it back
    // to the outgoing one. Lets the notification switch songs together with the
    // in-app UI instead of waiting for the swap.
    private var onDisplayPlayerChangedListener: ((Player) -> Unit)? = null

    // Session audio attributes, re-applied (with focus handling) at every swap:
    // crossfade secondaries are built WITHOUT focus handling.
    private val mediaAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // DSP mirror of the active player, when it was once a crossfade secondary.
    private var activeMirrorField: DspAudioProcessor? = null

    private var progressJob: Job? = null

    // Queue indices loaded into the player (player index 0 == loadedRange.first).
    // Stale while pendingWindowResync is set: don't seek or patch against it.
    private var loadedRange: IntRange = IntRange.EMPTY

    // Set when a queue move invalidated the timeline; rebuild is deferred to the
    // next skip/transition so it can't race a tap's seek.
    private var pendingWindowResync: Boolean = false

    // Crossfade secondary's window, committed to loadedRange when the swap promotes it.
    private var pendingFadeWindowStart: Int? = null
    private var pendingFadeWindowEnd: Int? = null

    // In-flight "jump to a fresh window" job; superseded by newer jumps.
    private var windowJumpJob: Job? = null

    // Generation counter: only the newest window jump may write the timeline.
    private var windowJumpId: Long = 0L

    // Serializes adjustWindow() so rapid skips can't overlap player mutations.
    private val windowAdjustMutex = Mutex()

    // Monotonic allocator for queue entry ids.
    private var nextEntryId: Long = 0L

    private fun allocateEntryId(): Long = nextEntryId++

    // Library snapshot used to draw random auxiliary tracks from.
    private var allTracks: List<TrackEntity> = emptyList()

    // True while a refill is running; prevents duplicate appends.
    private var refillScheduled: Boolean = false

    // A track counts as listened once [listenedElapsedMs] — forward playback
    // accumulated in the progress tracker — reaches listenedThresholdPercent of
    // its duration. [pendingListenTrackId] marks the current occurrence and is
    // cleared once counted, so a pause/resume or seek never counts twice.
    // Driven by the player's POSITION rather than a wall-clock timer: timers
    // stall in Doze and while the process is frozen, and a quick-fade seek
    // restarts them — both silently lose listens during background playback.
    private var pendingListenTrackId: Long? = null
    private var listenedElapsedMs: Long = 0L

    // Player position at the previous accumulation tick; -1 until the first
    // tick of an occurrence (its delta is dropped).
    private var lastListenedPositionMs: Long = -1L

    // Occurrence state stashed when a crossfade hands counting to the incoming
    // track, restored if that fade aborts and the outgoing track resumes.
    private var stashedListenOccurrence: Pair<Long, Long>? = null

    // True once THIS process has written a resumption snapshot; guards clear()
    // so the first (empty-queue) emission on process start never wipes the
    // snapshot a previous process wrote (it is what a media button resumes from).
    private var hasSessionSaved: Boolean = false

    @Volatile
    private var listenedThresholdPercent: Int = AppSettings.DEFAULT_LISTENED_THRESHOLD_PERCENT

    // Latest settings snapshot, read when evaluating the audio output path.
    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    // Last published AudioOutputInfo, to avoid re-emitting identical values.
    private var lastAudioOutputInfo: AudioOutputInfo? = null

    // Consecutive failed loads; past the cap, playback stops instead of racing
    // through a dead queue.
    private var consecutiveLoadErrors: Int = 0

    // Track ids that failed to load; navigation skips over them in both directions.
    private val failedTrackIds = mutableSetOf<Long>()

    // Per-track latch for the auto-crossfade trigger.
    private var crossfadeArmedForTrackId: Long? = null

    companion object {
        // Tracks kept loaded on each side of the current track.
        private const val WINDOW_RADIUS = 15
        // Tracks fetched (or trimmed) per window shift.
        private const val CHUNK_SIZE = 10
        // Extend the window when playback is this close to either edge.
        private const val EDGE_TRIGGER_MARGIN = 5
        // Auxiliary (random) tracks always kept after the main queue.
        private const val AUXILIARY_QUEUE_LENGTH = PlaybackState.AUXILIARY_QUEUE_LENGTH
        // Position poll rate when playing with no position collectors (background).
        private const val POSITION_POLL_SLOW_INTERVAL_MS = 250L
        // Position poll rate while paused.
        private const val PAUSED_POLL_INTERVAL_MS = 200L
        // Cap on waiting for a Choreographer frame; the wait itself becomes the
        // background poll cadence when no frames are delivered.
        private const val FRAME_WAIT_TIMEOUT_MS = POSITION_POLL_SLOW_INTERVAL_MS
        // Forward position jumps larger than this are seeks/jumps (or frames
        // missed while the process was frozen), not played audio: they don't
        // count toward the listen threshold.
        private const val MAX_LISTENED_TICK_GAP_MS = 2_000L
        // Max unreadable tracks skipped in a row before playback gives up.
        private const val MAX_CONSECUTIVE_LOAD_ERRORS = 10
        // Persistence: initial write delay collapsing queue-mutation bursts, and
        // the cadence the saved position is refreshed while playing.
        private const val PERSIST_DEBOUNCE_MS = 1_000L
        private const val POSITION_SAVE_INTERVAL_MS = 15_000L
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                consecutiveLoadErrors = 0
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleTrackError(error, failedTrackId = null, syncCursorToPlayer = true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            // Listen accounting needs no play-state hook: the progress tracker
            // accumulates only while a stream is audibly playing, so pauses and
            // focus losses freeze the accrual instead of discarding it.
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _playbackState.value =
                _playbackState.value.copy(isPlaybackRequested = playWhenReady)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val trackId = mediaItem?.mediaId?.toLongOrNull() ?: return
            applyTrackTransition(trackId, positionMs = 0L, emitSeekEvent = false)
        }
    }

    /** The [FadeEngine.Host] bridge: answers fade-time questions, reacts at fade moments. */
    private val fadeHost = object : FadeEngine.Host {
        override val primaryPlayer: ExoPlayer
            get() = activePlayerField

        override fun replacePrimaryPlayer(newPlayer: ExoPlayer, newMirror: DspAudioProcessor?) {
            // The previous mirror retires with its player; the new one stays registered.
            activeMirrorField?.let { dspEngine.removeMirrorProcessor(it) }
            activeMirrorField = newMirror
            activePlayerField = newPlayer
            // Focus handling transfers to the promoted player.
            newPlayer.setAudioAttributes(mediaAudioAttributes, /* handleAudioFocus = */ true)
            onPlayerSwappedListener?.invoke()
        }

        override fun buildFadeWindow(centerQueueIndex: Int): FadeEngine.FadeWindow? {
            val queue = _playbackState.value.queue
            if (centerQueueIndex !in queue.indices) return null
            val windowStart = (centerQueueIndex - WINDOW_RADIUS).coerceAtLeast(0)
            val windowEnd = (centerQueueIndex + WINDOW_RADIUS).coerceAtMost(queue.lastIndex)
            val items = queue.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
            return FadeEngine.FadeWindow(
                items = items,
                targetPlayerIndex = centerQueueIndex - windowStart,
                windowStart = windowStart,
                windowEnd = windowEnd
            )
        }

        override fun trackIdAt(queueIndex: Int): Long? =
            _playbackState.value.queue.getOrNull(queueIndex)?.id

        override fun onCrossfadeStarting(
            windowStart: Int,
            windowEnd: Int,
            targetQueueIndex: Int,
            targetTrackId: Long
        ) {
            // The fade's window IS the fresh timeline; resolve any pending resync.
            windowJumpId++
            windowJumpJob?.cancel()
            pendingWindowResync = false
            pendingFadeWindowStart = windowStart
            pendingFadeWindowEnd = windowEnd

            // Listen counting hands over to the incoming track, exactly like a
            // natural transition would; the outgoing occurrence is stashed so
            // an aborted fade can resume its accrued progress.
            val outgoingId = pendingListenTrackId
            if (outgoingId != null && outgoingId != targetTrackId) {
                stashedListenOccurrence = outgoingId to listenedElapsedMs
            }
            beginListenOccurrence(targetTrackId)

            // Detach the main listener so its events don't touch queue state mid-fade.
            activePlayerField.removeListener(playerListener)
        }

        override fun onCrossfadeLogicalSwitch(targetQueueIndex: Int, targetTrackId: Long) {
            // Publish the UI-facing switch now, while the outgoing stream fades out.
            val queue = _playbackState.value.queue
            val track = queue.getOrNull(targetQueueIndex) ?: return

            crossfadeArmedForTrackId = null

            val flags = _playbackState.value.auxiliaryFlags
            val wasAuxiliary = flags.getOrNull(targetQueueIndex) == true

            _playbackState.value = _playbackState.value.copy(
                currentTrack = track,
                queueIndex = targetQueueIndex,
                durationMs = track.duration,
                auxiliaryFlags = if (wasAuxiliary) {
                    flags.toMutableList().also { it[targetQueueIndex] = false }
                } else flags
            )

            // The incoming player's live position is what's shown, so an ongoing
            // fade doesn't reset the seek bar to 0.
            val positionMs = fadeEngine.displayPlayer?.currentPosition ?: 0L
            _positionMs.value = positionMs
            _seekEvents.tryEmit(positionMs)

            recomputeAudioOutputInfo()
            if (wasAuxiliary) scheduleAuxiliaryRefill()

            // The notification follows the in-app switch instead of waiting for
            // the swap: re-bind the session to the incoming stream at the fade's
            // switch point.
            onDisplayPlayerChangedListener?.invoke(
                fadeEngine.displayPlayer ?: activePlayerField
            )
        }

        override fun onCrossfadeSwapped(targetQueueIndex: Int, targetPositionMs: Long) {
            consecutiveLoadErrors = 0
            pendingFadeWindowStart?.let { start ->
                loadedRange = start..(pendingFadeWindowEnd ?: start)
            }
            pendingFadeWindowStart = null
            pendingFadeWindowEnd = null
            // The fade completed: the outgoing occurrence is gone for good.
            stashedListenOccurrence = null

            // Re-apply session modes: toggles landing mid-fade would otherwise be lost.
            player.repeatMode = when (_playbackState.value.repeatMode) {
                RepeatMode.NONE -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
            player.shuffleModeEnabled = _playbackState.value.isShuffle

            activePlayerField.addListener(playerListener)
            // Resync the pause-intent mirror to the promoted player: the main
            // listener was detached while the fade ran, so the secondary's
            // playWhenReady callbacks never reached the mirror.
            _playbackState.value =
                _playbackState.value.copy(isPlaybackRequested = player.playWhenReady)

            // No mutation can land mid-fade, so pendingWindowResync is false here.
            scope.launch { adjustWindow() }
        }

        override fun onCrossfadeAborted(logicalSwitchHadFired: Boolean) {
            pendingFadeWindowStart = null
            pendingFadeWindowEnd = null
            crossfadeArmedForTrackId = null

            // Re-attach the main listener (idempotent; remove first rules out dupes).
            activePlayerField.removeListener(playerListener)
            activePlayerField.addListener(playerListener)

            if (logicalSwitchHadFired) {
                // Revert the UI to the outgoing stream, which is still playing.
                val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
                val index = trackId?.let { resolveExactQueueIndex(it) }
                val track = index?.let { _playbackState.value.queue.getOrNull(it) }
                if (track != null) {
                    _playbackState.value = _playbackState.value.copy(
                        currentTrack = track,
                        queueIndex = index,
                        durationMs = track.duration
                    )
                }
                _positionMs.value = player.currentPosition
                _seekEvents.tryEmit(player.currentPosition)
                recomputeAudioOutputInfo()
            }

            // The incoming track never became current: restore the outgoing
            // occurrence's stashed progress, so its listen still lands once
            // enough of it has played in total.
            val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
            val stash = stashedListenOccurrence
            stashedListenOccurrence = null
            if (trackId != null) {
                if (stash != null && stash.first == trackId) {
                    pendingListenTrackId = trackId
                    listenedElapsedMs = stash.second
                    lastListenedPositionMs = -1L
                } else {
                    beginListenOccurrence(trackId)
                }
            }

            _playbackState.value = _playbackState.value.copy(
                isPlaying = player.isPlaying,
                isPlaybackRequested = player.playWhenReady
            )

            // The logical switch may have re-bound the session to the secondary,
            // which has just been released: hand the session back to the primary.
            onDisplayPlayerChangedListener?.invoke(activePlayerField)
        }

        override fun onFadeTargetError(targetTrackId: Long) {
            // Restore the single old stream, then run the error-advance path.
            fadeEngine.cancel()
            handleTrackError(error = null, failedTrackId = targetTrackId, syncCursorToPlayer = false)
        }

        override fun playerBaseVolume(): Float {
            val dsp = dspEngine.state.value
            return if (dsp.directVolumeControl) 1f else dsp.volumePercent / 100f
        }
    }

    init {
        activePlayerField.addListener(playerListener)
        fadeEngine.bind(fadeHost)
        startProgressTracker()
        observeLibraryForAuxiliaryRefill()
        observeListenedThreshold()
        observeSessionForPersistence()
        startAudioPathObservers()
    }

    /**
     * Suspends until the next frame is rendered (no Compose frame clock in this
     * scope), giving up after [FRAME_WAIT_TIMEOUT_MS]. Choreographer stops
     * delivering frames while the app is backgrounded or the screen is off — an
     * unbounded wait froze this whole loop there, killing position updates,
     * crossfade arming and listen counting during background playback.
     */
    private suspend fun awaitFrame() {
        withTimeoutOrNull(FRAME_WAIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = Choreographer.FrameCallback { cont.resumeWith(Result.success(Unit)) }
                Choreographer.getInstance().postFrameCallback(callback)
                cont.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
            }
        }
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

    /** Re-bind hook for the MediaSession (set by [PlaybackService]). */
    fun setOnPlayerSwappedListener(listener: (() -> Unit)?) {
        onPlayerSwappedListener = listener
    }

    /**
     * Mid-crossfade re-bind hook for the MediaSession (set by [PlaybackService]).
     *
     * Invoked with the player the session should advertise as "now playing":
     * the incoming secondary at the fade's logical switch (so the notification
     * switches songs together with the in-app UI instead of trailing it by the
     * fade's second half), the outgoing primary after an abort.
     */
    fun setOnDisplayPlayerChangedListener(listener: ((Player) -> Unit)?) {
        onDisplayPlayerChangedListener = listener
    }

    /**
     * Media items for [tracks]; the resumption path hands these back to Media3
     * for the session so the platform sees the same timeline the engine built.
     */
    fun mediaItemsFor(tracks: List<TrackEntity>): List<MediaItem> = tracks.map { it.toMediaItem() }

    /**
     * Rebuilds a paused session from a resumption snapshot: the full main queue
     * plus a redrawn auxiliary pool goes on the timeline, the cursor lands on
     * [startIndex] and the prepared stream waits (PAUSED) at [positionMs].
     * Playback is NOT started here — the triggering event (a media button play
     * or a session chip) decides that. Returns the media items of the built
     * timeline so the caller can hand the identical list back to Media3.
     */
    fun restoreQueue(
        tracks: List<TrackEntity>,
        startIndex: Int,
        positionMs: Long,
        sourceCollectionId: String?,
        sourceFlags: List<Boolean>?,
        isShuffle: Boolean,
        repeatMode: RepeatMode
    ): List<MediaItem> {
        if (tracks.isEmpty()) return emptyList()

        fadeEngine.cancel()
        crossfadeArmedForTrackId = null
        clearListenOccurrence()
        windowJumpJob?.cancel()
        failedTrackIds.clear()

        val clampedStart = startIndex.coerceIn(0, tracks.lastIndex)
        val restoredPosition = positionMs.coerceAtLeast(0L)

        // Ids for the main queue, then the auxiliary queue.
        val entryIds = List(tracks.size) { allocateEntryId() } +
                List(AUXILIARY_QUEUE_LENGTH) { allocateEntryId() }
        val newQueue = tracks + drawAuxiliaryTracks(emptySet(), AUXILIARY_QUEUE_LENGTH)

        pendingWindowResync = false
        _playbackState.value = _playbackState.value.copy(
            queue = newQueue,
            queueIndex = clampedStart,
            queueEntryIds = entryIds,
            auxiliaryFlags = List(entryIds.size) { i -> i >= tracks.size },
            currentTrack = newQueue[clampedStart],
            durationMs = newQueue[clampedStart].duration,
            isPlaying = false,
            // A rebuilt session waits paused until an explicit play request.
            isPlaybackRequested = false,
            isShuffle = isShuffle,
            repeatMode = repeatMode,
            queueSourceId = sourceCollectionId,
            queueSourceFlags = if (sourceFlags != null && sourceFlags.size == tracks.size) {
                sourceFlags + List(AUXILIARY_QUEUE_LENGTH) { false }
            } else {
                List(tracks.size) { sourceCollectionId != null } +
                        List(AUXILIARY_QUEUE_LENGTH) { false }
            }
        )

        player.repeatMode = when (repeatMode) {
            RepeatMode.NONE -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        player.shuffleModeEnabled = isShuffle

        // The whole queue goes on the timeline (adjustWindow trims the overhang
        // back as playback proceeds); prepared and PAUSED at the saved position —
        // the resumption flow decides when to press play.
        loadedRange = newQueue.indices
        val items = newQueue.map { it.toMediaItem() }
        player.setMediaItems(items, clampedStart, restoredPosition)
        player.prepare()
        player.pause()

        _positionMs.value = restoredPosition
        _seekEvents.tryEmit(restoredPosition)

        return items
    }

    /**
     * True when a gesture skip should leave the player paused: a current track
     * exists and playback wasn't requested. Manual picks start playback regardless.
     */
    private fun switchStaysPaused(): Boolean =
        _playbackState.value.currentTrack != null && !player.playWhenReady

    /**
     * Bookkeeping for a track becoming current — shared by the natural
     * transition and the crossfade swap so queue advance is identical.
     */
    private fun applyTrackTransition(
        trackId: Long,
        positionMs: Long,
        emitSeekEvent: Boolean,
        fallbackIndex: Int? = null
    ) {
        val currentQueue = _playbackState.value.queue

        // New play occurrence: restart listen accounting from zero. The count
        // itself lands in the progress tracker once enough of the track has
        // actually played — no isPlaying check needed here.
        beginListenOccurrence(trackId)

        // Duplicates possible (aux pool): use the player's timeline position.
        val index = resolveExactQueueIndex(trackId) ?: fallbackIndex ?: return
        val track = currentQueue[index]

        // A played auxiliary track becomes a main track, then the pool refills.
        val flags = _playbackState.value.auxiliaryFlags
        val wasAuxiliary = flags.getOrNull(index) == true

        _playbackState.value = _playbackState.value.copy(
            currentTrack = track,
            queueIndex = index,
            durationMs = track.duration,
            auxiliaryFlags = if (wasAuxiliary) {
                flags.toMutableList().also { it[index] = false }
            } else flags
        )
        _positionMs.value = positionMs
        if (emitSeekEvent) _seekEvents.tryEmit(positionMs)
        crossfadeArmedForTrackId = null

        // The new format may hit the output path differently: re-evaluate.
        recomputeAudioOutputInfo()

        if (pendingWindowResync) {
            pendingWindowResync = false
            loadWindowAndPlay(currentQueue, index)
        }

        scope.launch { adjustWindow() }

        if (wasAuxiliary) {
            scheduleAuxiliaryRefill()
        }
    }

    /**
     * Error handling for a failed track — shared by the natural error path and
     * a fade whose target failed. Never breaks the transition: it skips onward.
     */
    private fun handleTrackError(
        error: PlaybackException?,
        failedTrackId: Long?,
        syncCursorToPlayer: Boolean
    ) {
        // Remember the pause intent; only re-request playback after the error if it was playing.
        val resumeAfterError = player.playWhenReady
        val trackId = failedTrackId
            ?: player.currentMediaItem?.mediaId?.toLongOrNull()
        val failedTrack = trackId?.let { id ->
            _playbackState.value.queue.firstOrNull { it.id == id }
        }
        Log.w(
            "AudioEngine",
            "Playback error (${error?.errorCodeName ?: "unknown"}) on '${failedTrack?.path ?: "unknown"}'; skipping"
        )

        if (syncCursorToPlayer && trackId != null) {
            resolveExactQueueIndex(trackId)?.let { index ->
                val queue = _playbackState.value.queue
                _playbackState.value = _playbackState.value.copy(
                    currentTrack = queue.getOrNull(index),
                    queueIndex = index
                )
            }
        }

        consecutiveLoadErrors++
        if (consecutiveLoadErrors > MAX_CONSECUTIVE_LOAD_ERRORS) {
            // Too many dead tracks in a row (e.g. a deleted folder): stop.
            Log.w("AudioEngine", "Too many consecutive load errors; stopping playback")
            consecutiveLoadErrors = 0
            player.pause()
            return
        }
        trackId?.let { failedTrackIds.add(it) }

        val queue = _playbackState.value.queue
        val canAdvance = player.hasNextMediaItem() ||
                _playbackState.value.queueIndex + 1 in queue.indices
        if (!canAdvance) return

        // After an error the player sits in STATE_IDLE: the new item must be
        // prepared (and playback re-requested) explicitly. No crossfade here:
        // the old player is dead and fading into the same item would double-play it.
        advanceToNext(useFade = false)
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
            if (resumeAfterError) player.play()
        }
    }

    /** Re-evaluates the audio output path and publishes changes to [PlaybackState.audioOutputInfo]. */
    private fun recomputeAudioOutputInfo() {
        val track = _playbackState.value.currentTrack
        if (track == null) {
            if (lastAudioOutputInfo != null) {
                lastAudioOutputInfo = null
                _playbackState.value = _playbackState.value.copy(audioOutputInfo = null)
            }
            return
        }
        val info = bitPerfectDetector.evaluate(
            BitPerfectDetector.Input(
                sourceSampleRate = track.sampleRate,
                sourceBitDepth = track.bitDepth,
                sourceFormat = track.format,
                sourceChannels = track.channels,
                sourceBitrateKbps = track.bitrate,
                dspState = dspEngine.state.value,
                masterBitPerfectEnabled = latestSettings.bitPerfectEnabled,
                deviceConfig = latestSettings.deviceAudioConfig(outputDeviceManager.activeDevice.value?.id)
            )
        )
        if (info != lastAudioOutputInfo) {
            lastAudioOutputInfo = info
            _playbackState.value = _playbackState.value.copy(audioOutputInfo = info)
        }
    }

    /** Observes DSP state, output device and settings; re-evaluates the audio path on change. */
    private fun startAudioPathObservers() {
        outputDeviceManager.start()

        // Media attributes: routing + focus handling, unset by default construction.
        player.setAudioAttributes(mediaAudioAttributes, /* handleAudioFocus = */ true)

        scope.launch {
            dspEngine.state.collect { state ->
                applyVolumePath(state)
                recomputeAudioOutputInfo()
            }
        }
        scope.launch {
            outputDeviceManager.activeDevice.collect {
                recomputeAudioOutputInfo()
            }
        }
    }

    /**
     * Direct volume control : gain is applied in the float DSP
     * chain with the AudioTrack at full scale — no AudioFlinger re-quantization.
     * Skipped during a fade; the ramp re-reads the base volume itself.
     */
    private fun applyVolumePath(state: DspState) {
        if (fadeEngine.isFading) return
        player.volume = if (state.directVolumeControl) {
            1f
        } else {
            state.volumePercent / 100f
        }
    }

    /** Starts a fresh listen occurrence for [trackId] (a new play). */
    private fun beginListenOccurrence(trackId: Long) {
        pendingListenTrackId = trackId
        listenedElapsedMs = 0L
        lastListenedPositionMs = -1L
    }

    private fun clearListenOccurrence() {
        pendingListenTrackId = null
        listenedElapsedMs = 0L
        lastListenedPositionMs = -1L
        stashedListenOccurrence = null
    }

    /**
     * Playback time after which [trackId] counts as listened (user-configured % of duration).
     */
    private fun listenThresholdMs(trackId: Long): Long {
        val percent = listenedThresholdPercent
        if (percent <= 0) return 0L
        val durationMs = _playbackState.value.queue.firstOrNull { it.id == trackId }?.duration
            ?.takeIf { it > 0 }
            ?: player.duration.takeIf { it > 0 }
            ?: return 0L
        return durationMs * percent / 100
    }

    /**
     * Accumulates [positionMs] toward the listen threshold of the pending
     * occurrence; called from the progress tracker, which is why background
     * playback counts exactly like foreground playback. Only forward motion
     * actually played counts: pauses freeze the position (zero delta), a
     * backward seek replays (re-accumulates), and a forward jump — or a gap
     * wider than [MAX_LISTENED_TICK_GAP_MS] — adds nothing. The listen lands
     * the moment accrued playback reaches [listenThresholdMs].
     */
    private fun tickListenCount(displayPlayer: Player, positionMs: Long) {
        val pendingId = pendingListenTrackId ?: return
        if (!displayPlayer.isPlaying) return
        if (displayPlayer.currentMediaItem?.mediaId?.toLongOrNull() != pendingId) return

        val last = lastListenedPositionMs
        val delta = if (last >= 0L) {
            (positionMs - last).takeIf { it in 1..MAX_LISTENED_TICK_GAP_MS } ?: 0L
        } else {
            0L
        }
        lastListenedPositionMs = positionMs
        if (delta <= 0L) return

        listenedElapsedMs += delta
        if (listenedElapsedMs >= listenThresholdMs(pendingId)) {
            pendingListenTrackId = null
            scope.launch {
                trackRepository.incrementPlayCount(pendingId, System.currentTimeMillis())
            }
        }
    }

    private fun observeListenedThreshold() {
        scope.launch {
            settingsRepository.settings.collect { settings ->
                listenedThresholdPercent = settings.listenedThresholdPercent
                // Bit-perfect switch / per-device configs feed the path evaluation.
                val configChanged = latestSettings.bitPerfectEnabled != settings.bitPerfectEnabled ||
                        latestSettings.deviceAudioConfigs != settings.deviceAudioConfigs
                latestSettings = settings
                if (configChanged) recomputeAudioOutputInfo()
            }
        }
    }

    private fun observeLibraryForAuxiliaryRefill() {
        scope.launch {
            // One-shot snapshot + refresh on completed scans; the live Flow would
            // re-run the O(library) refill on every stat-only row write.
            allTracks = trackRepository.getAllTracksOnce()
            trackRepository.scanStatus.collect { status ->
                if (status is ScanStatus.Finished) {
                    allTracks = trackRepository.getAllTracksOnce()
                    // The library changed; refresh the auxiliary pool from it.
                    if (_playbackState.value.queue.isNotEmpty()) {
                        refillAuxiliary()
                    }
                }
            }
        }
    }

    /**
     * Persists the playing session for resumption. The first write collapses
     * queue-mutation bursts; while playing, the saved position keeps refreshing
     * so a killed process resumes near the mark. A queue that empties clears
     * THIS process's snapshot (a previous process's snapshot is preserved).
     */
    private fun observeSessionForPersistence() {
        scope.launch {
            playbackState.collectLatest { state ->
                if (state.currentTrack == null) {
                    if (hasSessionSaved) {
                        queuePersistence.clear()
                        hasSessionSaved = false
                    }
                    return@collectLatest
                }
                delay(PERSIST_DEBOUNCE_MS)
                saveSessionSnapshot(state)
                while (state.isPlaying) {
                    delay(POSITION_SAVE_INTERVAL_MS)
                    saveSessionSnapshot(state)
                }
            }
        }
    }

    private suspend fun saveSessionSnapshot(state: PlaybackState) {
        val ids = ArrayList<Long>(state.queue.size)
        val flags = ArrayList<Boolean>(state.queue.size)
        state.queue.forEachIndexed { i, track ->
            if (state.auxiliaryFlags.getOrNull(i) != true) {
                ids.add(track.id)
                flags.add(state.queueSourceFlags.getOrNull(i) == true)
            }
        }
        if (ids.isEmpty()) return
        hasSessionSaved = true
        queuePersistence.save(
            PersistedPlayback(
                trackIds = ids,
                sourceFlags = flags,
                currentIndex = state.queueIndex.coerceIn(0, ids.lastIndex),
                positionMs = _positionMs.value,
                isShuffle = state.isShuffle,
                repeatMode = state.repeatMode,
                queueSourceId = state.queueSourceId
            )
        )
    }

    /**
     * Starts a fresh queue at [startIndex] and plays it (manual pick).
     * [sourceCollectionId], when set, marks the queue as started from that collection.
     */
    fun setQueue(tracks: List<TrackEntity>, startIndex: Int = 0, sourceCollectionId: String? = null) {
        fadeEngine.cancel()
        crossfadeArmedForTrackId = null
        clearListenOccurrence()
        windowJumpJob?.cancel()
        failedTrackIds.clear()

        if (tracks.isEmpty()) {
            loadedRange = IntRange.EMPTY
            pendingWindowResync = false
            player.clearMediaItems()
            _playbackState.value = _playbackState.value.copy(
                queue = emptyList(),
                queueIndex = -1,
                queueEntryIds = emptyList(),
                auxiliaryFlags = emptyList(),
                currentTrack = null,
                queueSourceId = null,
                queueSourceFlags = emptyList()
            )
            recomputeAudioOutputInfo()
            return
        }

        val clampedStart = startIndex.coerceIn(0, tracks.lastIndex)

        // Ids for the main queue, then the auxiliary queue.
        val entryIds = List(tracks.size) { allocateEntryId() } +
                List(AUXILIARY_QUEUE_LENGTH) { allocateEntryId() }
        val newQueue = tracks + drawAuxiliaryTracks(emptySet(), AUXILIARY_QUEUE_LENGTH)

        pendingWindowResync = false
        _playbackState.value = _playbackState.value.copy(
            queue = newQueue,
            queueIndex = clampedStart,
            queueEntryIds = entryIds,
            auxiliaryFlags = List(entryIds.size) { i -> i >= tracks.size },
            queueSourceId = sourceCollectionId,
            queueSourceFlags = List(tracks.size) { sourceCollectionId != null } +
                    List(AUXILIARY_QUEUE_LENGTH) { false }
        )

        loadWindowAndPlay(newQueue, clampedStart, forcePlay = true)
    }

    /** Inserts [track] after the current track; with no queue, starts a fresh one. */
    fun playNext(track: TrackEntity) {
        insertTrack(track, _playbackState.value.queueIndex + 1)
    }

    /** Appends [track] at the end of the MAIN queue, never behind the auxiliary pool. */
    fun addToQueue(track: TrackEntity) {
        val current = _playbackState.value
        var index = current.queue.size
        while (index > 0 && current.auxiliaryFlags.getOrNull(index - 1) == true) {
            index--
        }
        insertTrack(track, index)
    }

    /** Inserts [tracks] after the current track, keeping order; fresh queue when empty. */
    fun playNextAll(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        val current = _playbackState.value
        if (current.queue.isEmpty()) {
            setQueue(tracks, 0)
            return
        }
        insertTracks(tracks, current.queueIndex + 1)
    }

    /** Appends [tracks] to the MAIN queue, keeping order (same boundary rule as [addToQueue]). */
    fun addAllToQueue(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        val current = _playbackState.value
        if (current.queue.isEmpty()) {
            setQueue(tracks, 0)
            return
        }
        var index = current.queue.size
        while (index > 0 && current.auxiliaryFlags.getOrNull(index - 1) == true) {
            index--
        }
        insertTracks(tracks, index)
    }

    /**
     * Plays [tracks] from a list pick: keeps played history, drops upcoming
     * main-queue tracks, queues [tracks] in their place and jumps to [startIndex].
     * The auxiliary pool is left untouched. Manual pick: playback always starts.
     */
    fun playListReplacingUpcoming(
        tracks: List<TrackEntity>,
        startIndex: Int,
        sourceCollectionId: String? = null
    ) {
        if (tracks.isEmpty()) return

        val current = _playbackState.value
        if (current.queue.isEmpty() || current.queueIndex !in current.queue.indices) {
            setQueue(tracks, startIndex, sourceCollectionId)
            return
        }

        fadeEngine.cancel()

        val flags = current.auxiliaryFlags
        // Only the upcoming main-queue section in front of the pool gets dropped.
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
                    flags.subList(auxStart, flags.size),
            queueSourceId = sourceCollectionId,
            // Kept history entries are past occurrences and never highlighted.
            queueSourceFlags = List(keptCount) { false } +
                    List(tracks.size) { sourceCollectionId != null } +
                    List(current.queue.size - auxStart) { false }
        )

        jumpWindowTo(keptCount + startIndex.coerceIn(0, tracks.lastIndex), forcePlay = true)
    }

    /** Shared implementation for [playNext]/[addToQueue]; patches the timeline in place. */
    private fun insertTrack(track: TrackEntity, requestedIndex: Int) {
        // A queue mutation shifts the indices a fade captured: cancel first.
        fadeEngine.cancel()

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

        val newFlags = current.auxiliaryFlags.toMutableList().apply {
            add(insertIndex.coerceAtMost(size), false)
        }

        val newSourceFlags = current.queueSourceFlags.toMutableList().apply {
            add(insertIndex.coerceAtMost(size), false)
        }

        // Keep the cursor on the same track: inserts at/before it shift later indices.
        val newQueueIndex =
            if (insertIndex <= current.queueIndex) current.queueIndex + 1 else current.queueIndex

        _playbackState.value = current.copy(
            queue = newQueue,
            queueEntryIds = newEntryIds,
            queueIndex = newQueueIndex,
            auxiliaryFlags = newFlags,
            queueSourceFlags = newSourceFlags
        )

        val window = loadedRange
        if (pendingWindowResync || window == IntRange.EMPTY) return
        when {
            insertIndex < window.first ->
                loadedRange = (window.first + 1)..(window.last + 1)
            insertIndex <= window.last + 1 -> {
                player.addMediaItem(insertIndex - window.first, track.toMediaItem())
                loadedRange = window.first..(window.last + 1)
            }
            // Past the window: adjustWindow() loads it as playback approaches.
        }
    }

    /** Bulk variant of [insertTrack]; one state publish and one addMediaItems call. */
    private fun insertTracks(tracks: List<TrackEntity>, requestedIndex: Int) {
        if (tracks.isEmpty()) return

        fadeEngine.cancel()

        val current = _playbackState.value
        if (current.queue.isEmpty()) {
            setQueue(tracks, 0)
            return
        }

        val insertIndex = requestedIndex.coerceIn(0, current.queue.size)
        val count = tracks.size

        val newQueue = current.queue.toMutableList().apply {
            addAll(insertIndex, tracks)
        }

        val newEntryIds = current.queueEntryIds.toMutableList().apply {
            addAll(insertIndex.coerceAtMost(size), List(count) { allocateEntryId() })
        }

        val newFlags = current.auxiliaryFlags.toMutableList().apply {
            addAll(insertIndex.coerceAtMost(size), List(count) { false })
        }

        val newSourceFlags = current.queueSourceFlags.toMutableList().apply {
            addAll(insertIndex.coerceAtMost(size), List(count) { false })
        }

        val newQueueIndex =
            if (insertIndex <= current.queueIndex) current.queueIndex + count else current.queueIndex

        _playbackState.value = current.copy(
            queue = newQueue,
            queueEntryIds = newEntryIds,
            queueIndex = newQueueIndex,
            auxiliaryFlags = newFlags,
            queueSourceFlags = newSourceFlags
        )

        val window = loadedRange
        if (pendingWindowResync || window == IntRange.EMPTY) return
        when {
            insertIndex < window.first ->
                loadedRange = (window.first + count)..(window.last + count)
            insertIndex <= window.last + 1 -> {
                player.addMediaItems(insertIndex - window.first, tracks.map { it.toMediaItem() })
                loadedRange = window.first..(window.last + count)
            }
            // Past the window: adjustWindow() loads the items as playback approaches.
        }
    }

    /**
     * Loads a window of tracks around [centerIndex] and starts playback; the
     * rest loads lazily via [adjustWindow]. [forcePlay] marks a manual pick.
     */
    private fun loadWindowAndPlay(
        tracks: List<TrackEntity>,
        centerIndex: Int,
        forcePlay: Boolean = false
    ) {
        windowJumpJob?.cancel()
        val jumpId = ++windowJumpId
        // This jump writes the fresh timeline, resolving any deferred resync.
        pendingWindowResync = false
        val playAfterPrepare = forcePlay || !switchStaysPaused()

        val windowStart = (centerIndex - WINDOW_RADIUS).coerceAtLeast(0)
        val windowEnd = (centerIndex + WINDOW_RADIUS).coerceAtMost(tracks.lastIndex)

        windowJumpJob = scope.launch {
            val windowItems = withContext(Dispatchers.Default) {
                tracks.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
            }
            // A newer jump superseded this one.
            if (jumpId != windowJumpId) return@launch
            player.setMediaItems(windowItems, centerIndex - windowStart, 0L)
            if (playAfterPrepare) player.play() else player.pause()
            player.prepare()
            loadedRange = windowStart..windowEnd
        }
    }

    /** Extends/trims the loaded window. No-op while a jump runs or a resync is pending. */
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

            // Unload media too far from the window
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

    fun play() {
        // Cancel any in-flight quick fade (e.g. a fade-out-to-pause the user reversed).
        fadeEngine.cancel()

        // After a playback error the player stays in STATE_IDLE; prepare again.
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        if (fadeEngine.quickFadeEnabled && !player.isPlaying) {
            // Ramp up from silence: no click at the resume point.
            fadeEngine.fadeIn()
            player.play()
        } else {
            player.play()
        }
        // Publish last so the button reacts to the tap immediately.
        _playbackState.value = _playbackState.value.copy(isPlaying = true)
    }

    fun pause() {
        // Mid-crossfade, `player` is still the outgoing stream while the UI already shows
        // the incoming one: settle on the incoming stream first, then pause it.
        val settledCrossfade = fadeEngine.completeCrossfadeNow()

        if (fadeEngine.quickFadeEnabled && player.isPlaying) {
            // fadeOutAndPause() starts from player.volume, i.e. the ramp's current value.
            fadeEngine.fadeOutAndPause()
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                isPlaybackRequested = false
            )
        } else {
            player.pause()
            // The promoted player may still carry a partial ramp volume.
            if (settledCrossfade) player.volume = fadeHost.playerBaseVolume()
        }
    }

    fun next() {
        fadeEngine.cancel()
        advanceToNext(useFade = true)
    }

    fun previous() {
        fadeEngine.cancel()
        val state = _playbackState.value
        if (player.hasPreviousMediaItem()) {
            if (player.contentPosition <= 5000L) {
                // TODO: Make this an app parameter.
                val targetQueueIndex = player.previousMediaItemIndex + loadedRange.first
                val targetId = state.queue.getOrNull(targetQueueIndex)?.id
                // Paused skips never crossfade: there's no audible stream to fade from.
                if (fadeEngine.quickFadeEnabled && player.isPlaying && targetId != null &&
                    targetId !in failedTrackIds &&
                    fadeEngine.startCrossfade(
                        targetQueueIndex,
                        0L,
                        fadeEngine.quickFadeDurationMs
                    )
                ) {
                    return
                }
                player.seekToPreviousMediaItem()
                skipBackwardOverFailedTracks()
            } else {
                // Rewind the current track: crossfade to its own start when possible.
                if (fadeEngine.quickFadeEnabled && player.isPlaying &&
                    fadeEngine.startCrossfade(
                        state.queueIndex,
                        0L,
                        fadeEngine.quickFadeDurationMs
                    )
                ) {
                    return
                }
                seekTo(0)
            }
        } else if (loadedRange.first > 0) {
            val queue = state.queue
            var previousIndex = loadedRange.first - 1
            // Never land back on a track that already failed to load.
            while (previousIndex >= 0 && queue[previousIndex].id in failedTrackIds) {
                previousIndex--
            }
            if (previousIndex >= 0) {
                if (fadeEngine.quickFadeEnabled && player.isPlaying &&
                    fadeEngine.startCrossfade(
                        previousIndex,
                        0L,
                        fadeEngine.quickFadeDurationMs
                    )
                ) {
                    return
                }
                loadWindowAndPlay(queue, previousIndex)
            }
        }
    }

    /** After a backward seek, keeps moving back past tracks that failed to load. */
    private fun skipBackwardOverFailedTracks() {
        var guard = 0
        while (guard++ < MAX_CONSECUTIVE_LOAD_ERRORS) {
            val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
            if (trackId !in failedTrackIds) return
            if (!player.hasPreviousMediaItem()) return
            player.seekToPreviousMediaItem()
        }
    }

    /**
     * Gesture skip: jumps to [index] without un-pausing a paused session.
     * Skips over known-failed tracks in the skip direction.
     */
    fun skipToIndex(index: Int, forward: Boolean = true) {
        val queue = _playbackState.value.queue
        if (index !in queue.indices) return

        var target = index
        while (target in queue.indices && queue[target].id in failedTrackIds) {
            target += if (forward) 1 else -1
        }
        if (target !in queue.indices) target = index

        fadeEngine.cancel()

        if (pendingWindowResync) {
            jumpWindowTo(target)
            return
        }

        if (fadeEngine.quickFadeEnabled && player.isPlaying) {
            val targetId = queue.getOrNull(target)?.id
            if (targetId != null &&
                targetId !in failedTrackIds &&
                fadeEngine.startCrossfade(target, 0L, fadeEngine.quickFadeDurationMs)
            ) {
                return
            }
        }

        if (target in loadedRange) {
            player.seekTo(target - loadedRange.first, 0L)
            return
        }

        jumpWindowTo(target)
    }

    /** Synchronously rebuilds the window around [centerIndex] and plays it, overriding any jump. */
    private fun jumpWindowTo(centerIndex: Int, forcePlay: Boolean = false) {
        val queue = _playbackState.value.queue
        if (centerIndex !in queue.indices) return

        windowJumpId++
        windowJumpJob?.cancel()
        pendingWindowResync = false

        val windowStart = (centerIndex - WINDOW_RADIUS).coerceAtLeast(0)
        val windowEnd = (centerIndex + WINDOW_RADIUS).coerceAtMost(queue.lastIndex)
        // Deliberately synchronous: must atomically override in-flight jumps.
        val items = queue.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
        loadedRange = windowStart..windowEnd
        player.setMediaItems(items, centerIndex - windowStart, 0L)
        if (forcePlay || !switchStaysPaused()) player.play() else player.pause()
        player.prepare()
    }

    fun seekTo(positionMs: Long) {
        fadeEngine.cancel()

        // The seek bar jumps immediately; a same-track crossfade smooths the
        // audio underneath. Only the dedicated position flow is updated here to
        // avoid recomposing playbackState collectors per drag pixel.
        _positionMs.value = positionMs
        _seekEvents.tryEmit(positionMs)

        val duration = player.duration
        val tailRoom = duration > 0L && duration != C.TIME_UNSET &&
                (duration - positionMs) > fadeEngine.quickFadeDurationMs * 2
        if (fadeEngine.quickFadeEnabled && player.isPlaying && tailRoom &&
            fadeEngine.startCrossfade(
                _playbackState.value.queueIndex,
                positionMs,
                fadeEngine.quickFadeDurationMs
            )
        ) {
            return
        }

        player.seekTo(positionMs)
    }

    /** Advances to the next item, crossfading when [useFade] and quick fades are on. */
    private fun advanceToNext(useFade: Boolean) {
        val state = _playbackState.value
        if (player.hasNextMediaItem()) {
            if (useFade) {
                val targetQueueIndex = player.nextMediaItemIndex + loadedRange.first
                val targetId = state.queue.getOrNull(targetQueueIndex)?.id
                // Paused skips seek instead and stay paused.
                if (fadeEngine.quickFadeEnabled && player.isPlaying && targetId != null &&
                    targetId !in failedTrackIds &&
                    fadeEngine.startCrossfade(targetQueueIndex, 0L, fadeEngine.quickFadeDurationMs)
                ) {
                    return
                }
            }
            player.seekToNextMediaItem()
        } else {
            val nextIndex = state.queueIndex + 1
            if (nextIndex in state.queue.indices) {
                if (useFade) {
                    val targetId = state.queue[nextIndex].id
                    if (fadeEngine.quickFadeEnabled && player.isPlaying && targetId !in failedTrackIds &&
                        fadeEngine.startCrossfade(nextIndex, 0L, fadeEngine.quickFadeDurationMs)
                    ) {
                        return
                    }
                }
                loadWindowAndPlay(state.queue, nextIndex)
            }
        }
    }

    /** Resolves an entry id to its current index (derived from live state, never stale). */
    private fun entryIndexOf(entryId: Long): Int =
        _playbackState.value.queueEntryIds.indexOf(entryId)

    private fun candidateIndexesFor(trackId: Long): List<Int> =
        _playbackState.value.queue
            .withIndex()
            .filter { (_, t) -> t.id == trackId }
            .map { (i, _) -> i }

    /**
     * Manual pick from the queue screen: moves the entry next, jumps to it and
     * starts playback — even over a paused session.
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

    /** Moves an entry to [toUpcomingPosition], relative to the current cursor. */
    fun moveQueueEntry(entryId: Long, toUpcomingPosition: Int) {
        val fromIndex = entryIndexOf(entryId)
        if (fromIndex == -1) return
        val queue = _playbackState.value.queue
        val offset = (_playbackState.value.queueIndex + 1).coerceAtLeast(0)
        val toIndex = (offset + toUpcomingPosition).coerceIn(0, queue.lastIndex)
        if (fromIndex == toIndex) return
        moveQueueItem(fromIndex, toIndex)
    }

    /** Removes the occurrence identified by [entryId]. */
    fun removeQueueEntry(entryId: Long) {
        val index = entryIndexOf(entryId)
        if (index == -1) return
        removeQueueItem(index)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentQueue = _playbackState.value.queue
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) return

        fadeEngine.cancel()

        val current = _playbackState.value

        val fromLoaded = fromIndex in loadedRange
        val toLoaded = toIndex in loadedRange

        if (fromLoaded && toLoaded) {
            player.moveMediaItem(fromIndex - loadedRange.first, toIndex - loadedRange.first)
        }

        val newQueue = currentQueue.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }

        // Flags and entry ids move with their item.
        val newEntryIds = current.queueEntryIds.toMutableList().apply {
            if (fromIndex < size) add(toIndex, removeAt(fromIndex))
        }

        val flags = current.auxiliaryFlags
        val movedIsAuxiliary = flags.getOrNull(fromIndex) == true
        val newFlags = flags.toMutableList().apply {
            if (fromIndex < size) add(toIndex, removeAt(fromIndex))
        }

        val newSourceFlags = current.queueSourceFlags.toMutableList().apply {
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
            auxiliaryFlags = normalizeAuxiliaryFlags(newFlags, newQueue.size),
            queueSourceFlags = normalizeQueueSourceFlags(newSourceFlags, newQueue.size)
        )

        if (fromLoaded != toLoaded) {
            // Defer the rebuild and invalidate in-flight jumps.
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

        fadeEngine.cancel()

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

        val newFlags = flags.toMutableList().apply {
            if (index < size) removeAt(index)
        }

        val newSourceFlags = current.queueSourceFlags.toMutableList().apply {
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
            auxiliaryFlags = normalizeAuxiliaryFlags(newFlags, newQueue.size),
            queueSourceFlags = normalizeQueueSourceFlags(newSourceFlags, newQueue.size)
        )

        if (newQueue.isEmpty()) recomputeAudioOutputInfo()

        if (removedIsAuxiliary) {
            scheduleAuxiliaryRefill()
        }
    }

    fun toggleShuffle() {
        val newShuffle = !_playbackState.value.isShuffle
        player.shuffleModeEnabled = newShuffle
        _playbackState.value = _playbackState.value.copy(isShuffle = newShuffle)
    }

    /** Cycles repeat mode NONE -> ALL -> ONE. */
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

    /** Exact queue index of the playing track via timeline position; nearest-occurrence fallback. */
    private fun resolveExactQueueIndex(trackId: Long): Int? {
        val queue = _playbackState.value.queue
        if (queue.isEmpty() || loadedRange == IntRange.EMPTY) return resolveQueueIndex(trackId)

        val playerIndex = player.currentMediaItemIndex + loadedRange.first
        if (playerIndex in queue.indices && queue[playerIndex].id == trackId) return playerIndex

        return resolveQueueIndex(trackId)
    }

    private fun resolveQueueIndex(trackId: Long): Int? {
        val candidates = candidateIndexesFor(trackId)
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]

        val queue = _playbackState.value.queue
        val playerIndex = player.currentMediaItemIndex + loadedRange.first
        if (playerIndex in queue.indices && queue[playerIndex].id == trackId) return playerIndex

        return candidates.minByOrNull { kotlin.math.abs(it - playerIndex) }
    }

    private fun normalizeEntryIds(entryIds: List<Long>, queueSize: Int): List<Long> =
        if (entryIds.size == queueSize) entryIds else List(queueSize) { allocateEntryId() }

    private fun normalizeAuxiliaryFlags(flags: List<Boolean>, queueSize: Int): List<Boolean> =
        if (flags.size == queueSize) flags else List(queueSize) { false }

    private fun normalizeQueueSourceFlags(flags: List<Boolean>, queueSize: Int): List<Boolean> =
        if (flags.size == queueSize) flags else List(queueSize) { false }

    /** True when the full auxiliary pool sits after [queueIndex] (counts flags). */
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

    /** Tops up the auxiliary pool after the upcoming section (append-only). */
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
            auxiliaryFlags = flags + List(additions.size) { true },
            queueSourceFlags = current.queueSourceFlags + List(additions.size) { false }
        )
    }

    /**
     * Auto-crossfade at a natural track transition, armed by the progress
     * tracker near the track's end. Skipped for repeat-one, queue end and
     * known-broken next tracks.
     */
    private fun maybeStartTrackCrossfade() {
        if (fadeEngine.isFading) return
        if (!fadeEngine.normalFadeEnabled) return
        val state = _playbackState.value
        if (state.repeatMode == RepeatMode.ONE) return

        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (crossfadeArmedForTrackId == trackId) return

        val duration = player.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return

        val nextIndex = state.queueIndex + 1
        if (nextIndex !in state.queue.indices) return
        if (state.queue[nextIndex].id in failedTrackIds) return

        val normalFadeMs = fadeEngine.normalFadeDurationMs
        if (!FadeMath.shouldStartCrossfade(duration - player.currentPosition, normalFadeMs, duration)) return

        crossfadeArmedForTrackId = trackId
        fadeEngine.startCrossfade(
            targetQueueIndex = nextIndex,
            targetPositionMs = 0L,
            durationMs = FadeMath.clampedFadeDurationMs(normalFadeMs, duration),
            // The UI switches at the ramp's midpoint, matching how far the
            // incoming stream has already played into itself.
            switchAtT = 0.5f
        )
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var lastEmitted = Long.MIN_VALUE
            while (true) {
                // Before the fade's logical switch the outgoing player is shown;
                // after it, the incoming one — the UI ticks through the whole fade.
                val displayPlayer = fadeEngine.displayPlayer ?: player
                val position = displayPlayer.currentPosition
                // Skip redundant emissions while paused/between gaps.
                if (position != lastEmitted) {
                    lastEmitted = position
                    _positionMs.value = position
                }
                // Auto-crossfade arming is always evaluated on the real primary.
                if (player.isPlaying) {
                    maybeStartTrackCrossfade()
                }
                // Listen accounting rides the same tick, so background playback
                // counts identically to foreground playback.
                tickListenCount(displayPlayer, position)
                // While a fade runs the incoming player may still be preparing (isPlaying
                // false) right after its logical switch; keep ticking per frame so its
                // position is published the moment audio starts, instead of waiting out
                // the slow paused poll (which left lyrics projecting from a stale value).
                if (displayPlayer.isPlaying || fadeEngine.isFading) {
                    if (_positionMs.subscriptionCount.value > 0) {
                        // UI listening: per-frame ticks keep the seek bar smooth.
                        // Bounded (see awaitFrame): Choreographer stops ticking
                        // while the app is backgrounded.
                        awaitFrame()
                    } else {
                        delay(POSITION_POLL_SLOW_INTERVAL_MS)
                    }
                } else {
                    // While paused, keep position in sync (catches external seeks).
                    delay(PAUSED_POLL_INTERVAL_MS)
                }
            }
        }
    }
}