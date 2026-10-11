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
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Owns the ExoPlayer, the queue and the playback state.
 *
 * Only a window of the queue around the current track is loaded into the player
 * ([loadedRange]); [adjustWindow] extends and trims it as playback moves. Crossfades run on a
 * secondary player owned by [FadeEngine] and promoted at the swap, so [player] always
 * points at the audible stream.
 *
 * Audio goes through the [DspEngine] processor, a byte-for-byte passthrough (bit-perfect)
 * when the DSP state is neutral.
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
    /** The active player. Replaced at each crossfade swap. */
    val player: ExoPlayer
        get() = activePlayerField

    private var activePlayerField: ExoPlayer = buildPlayer()

    private fun buildPlayer(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(dspEngine.processor))
                    .setEnableFloatOutput(enableFloatOutput)
                    .build()
        }
        return ExoPlayer.Builder(context, renderersFactory).build()
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    /** Playback position, updated every frame. Separate from [playbackState] to avoid recomposing its collectors. */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    /** Emits the new position on explicit seeks and track switches (not on frame ticks). */
    private val _seekEvents = MutableSharedFlow<Long>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val seekEvents: SharedFlow<Long> = _seekEvents.asSharedFlow()

    private var onPlayerSwappedListener: (() -> Unit)? = null
    private var onDisplayPlayerChangedListener: ((Player) -> Unit)? = null

    /** Crossfade secondaries are built without focus handling; it is re-applied at each swap. */
    private val mediaAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** DSP mirror of the active player when it was promoted from a crossfade secondary. */
    private var activeMirrorField: DspAudioProcessor? = null

    private var progressJob: Job? = null

    /** Queue indices loaded in the player (player index 0 == `loadedRange.first`). Stale while [pendingWindowResync] is set. */
    private var loadedRange: IntRange = IntRange.EMPTY

    /** Set when a queue move invalidated the timeline. The rebuild waits for the next skip or transition so it can't race a tap's seek. */
    private var pendingWindowResync: Boolean = false

    /** Window of the in-flight crossfade, committed to [loadedRange] at the swap. */
    private var pendingFadeWindowStart: Int? = null
    private var pendingFadeWindowEnd: Int? = null

    private var windowJumpJob: Job? = null

    /** Only the newest window jump may write the timeline. */
    private var windowJumpId: Long = 0L

    private val windowAdjustMutex = Mutex()

    private var nextEntryId: Long = 0L

    private fun allocateEntryId(): Long = nextEntryId++

    private var allTracks: List<TrackEntity> = emptyList()

    private var refillScheduled: Boolean = false

    /**
     * Listen accounting: a track counts as listened once [listenedElapsedMs] (forward playback
     * accumulated by [tickListenCount]) reaches the configured share of its duration.
     * [pendingListenTrackId] is cleared once counted, so pauses and seeks never count twice.
     * It is position-driven rather than timer-driven: timers stall in Doze.
     */
    private var pendingListenTrackId: Long? = null
    private var listenedElapsedMs: Long = 0L

    /** Position at the previous accumulation tick, -1 until the first tick of an occurrence. */
    private var lastListenedPositionMs: Long = -1L

    /** (trackId, elapsed) of the outgoing occurrence, restored if a crossfade aborts. */
    private var stashedListenOccurrence: Pair<Long, Long>? = null

    /** Guards `clear()` so an empty queue at process start never wipes the previous process's resumption snapshot. */
    private var hasSessionSaved: Boolean = false

    @Volatile
    private var listenedThresholdPercent: Int = AppSettings.DEFAULT_LISTENED_THRESHOLD_PERCENT

    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    private var lastAudioOutputInfo: AudioOutputInfo? = null

    private var consecutiveLoadErrors: Int = 0

    /** Tracks that failed to load. Navigation skips them in both directions. */
    private val failedTrackIds = mutableSetOf<Long>()

    /** Latch so the auto-crossfade triggers once per track. */
    private var crossfadeArmedForTrackId: Long? = null

    companion object {
        /** Tracks kept loaded on each side of the current one. */
        private const val WINDOW_RADIUS = 15
        /** Tracks added or removed per window shift. */
        private const val CHUNK_SIZE = 10
        /** The window is extended when playback gets this close to an edge. */
        private const val EDGE_TRIGGER_MARGIN = 5
        private const val AUXILIARY_QUEUE_LENGTH = PlaybackState.AUXILIARY_QUEUE_LENGTH
        /** Poll interval when no frames are delivered (background). */
        private const val POSITION_POLL_SLOW_INTERVAL_MS = 250L
        /** Poll interval while paused; bounds the latency to notice a play or an external seek. */
        private const val PAUSED_POLL_INTERVAL_MS = 50L
        private const val FRAME_WAIT_TIMEOUT_MS = POSITION_POLL_SLOW_INTERVAL_MS
        /** Residual error beyond which a position sample is a discontinuity and re-anchors immediately. */
        private const val POSITION_SNAP_MS = 120.0
        /** Share of each sample's residual error absorbed by the dead-reckoning anchor. */
        private const val POSITION_ERROR_SLEW = 0.25
        /** Max wait for a stream clock to start moving before dead-reckoning resumes anyway. */
        private const val SETTLE_TIMEOUT_MS = 300L
        /** Largest backward correction absorbed by holding the position instead of stepping back. */
        private const val MONOTONIC_HOLD_MAX_MS = 60L
        /** Larger forward jumps are seeks, not played audio, and don't count as listened. */
        private const val MAX_LISTENED_TICK_GAP_MS = 2_000L
        private const val MAX_CONSECUTIVE_LOAD_ERRORS = 10
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

    private val fadeHost = object : FadeEngine.Host {
        override val primaryPlayer: ExoPlayer
            get() = activePlayerField

        override fun replacePrimaryPlayer(newPlayer: ExoPlayer, newMirror: DspAudioProcessor?) {
            activeMirrorField?.let { dspEngine.removeMirrorProcessor(it) }
            activeMirrorField = newMirror
            activePlayerField = newPlayer
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
            // The fade window is a fresh timeline, which resolves any pending resync.
            windowJumpId++
            windowJumpJob?.cancel()
            pendingWindowResync = false
            pendingFadeWindowStart = windowStart
            pendingFadeWindowEnd = windowEnd

            // Listen counting moves to the incoming track; the outgoing progress is stashed in case the fade aborts.
            val outgoingId = pendingListenTrackId
            if (outgoingId != null && outgoingId != targetTrackId) {
                stashedListenOccurrence = outgoingId to listenedElapsedMs
            }
            beginListenOccurrence(targetTrackId)

            activePlayerField.removeListener(playerListener)
        }

        override fun onCrossfadeLogicalSwitch(targetQueueIndex: Int, targetTrackId: Long) {
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

            // Media3 reports the prospective position (the fade target) until the secondary renders.
            val displayPlayer = fadeEngine.displayPlayer
            val positionMs = (displayPlayer ?: player).currentPosition
            _positionMs.value = positionMs
            _seekEvents.tryEmit(positionMs)

            recomputeAudioOutputInfo()
            if (wasAuxiliary) scheduleAuxiliaryRefill()

            onDisplayPlayerChangedListener?.invoke(displayPlayer ?: activePlayerField)
        }

        override fun onCrossfadeSwapped(targetQueueIndex: Int, targetPositionMs: Long) {
            consecutiveLoadErrors = 0
            pendingFadeWindowStart?.let { start ->
                loadedRange = start..(pendingFadeWindowEnd ?: start)
            }
            pendingFadeWindowStart = null
            pendingFadeWindowEnd = null
            stashedListenOccurrence = null

            // Session modes toggled mid-fade would otherwise be lost.
            player.repeatMode = when (_playbackState.value.repeatMode) {
                RepeatMode.NONE -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
            player.shuffleModeEnabled = _playbackState.value.isShuffle

            activePlayerField.addListener(playerListener)
            _playbackState.value =
                _playbackState.value.copy(isPlaybackRequested = player.playWhenReady)

            scope.launch { adjustWindow() }
        }

        override fun onCrossfadeAborted(logicalSwitchHadFired: Boolean) {
            pendingFadeWindowStart = null
            pendingFadeWindowEnd = null
            crossfadeArmedForTrackId = null

            activePlayerField.removeListener(playerListener)
            activePlayerField.addListener(playerListener)

            if (logicalSwitchHadFired) {
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

            // The session may have been bound to the secondary, which is now released.
            onDisplayPlayerChangedListener?.invoke(activePlayerField)
        }

        override fun onFadeTargetError(targetTrackId: Long) {
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
     * Suspends until the next frame, or [FRAME_WAIT_TIMEOUT_MS] at most. Choreographer delivers
     * no frames in the background, so an unbounded wait would freeze the progress loop.
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
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(path.toUri())
                    .build()
            )
            .build()

    /** Called after a crossfade promoted a new player, so the MediaSession can re-bind. */
    fun setOnPlayerSwappedListener(listener: (() -> Unit)?) {
        onPlayerSwappedListener = listener
    }

    /**
     * Called with the player the session should present as "now playing": the incoming
     * secondary at a crossfade's logical switch, the outgoing primary on abort.
     */
    fun setOnDisplayPlayerChangedListener(listener: ((Player) -> Unit)?) {
        onDisplayPlayerChangedListener = listener
    }

    /** Media items for [tracks], matching the timeline the engine builds. */
    fun mediaItemsFor(tracks: List<TrackEntity>): List<MediaItem> = tracks.map { it.toMediaItem() }

    /**
     * Rebuilds a paused session from a resumption snapshot, positioned at [startIndex] / [positionMs].
     * Does not start playback. Returns the timeline's media items.
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

        // The whole queue is loaded here; adjustWindow() trims it as playback proceeds.
        loadedRange = newQueue.indices
        val items = newQueue.map { it.toMediaItem() }
        player.setMediaItems(items, clampedStart, restoredPosition)
        player.prepare()
        player.pause()

        _positionMs.value = restoredPosition
        _seekEvents.tryEmit(restoredPosition)

        return items
    }

    /** True when a gesture skip must not un-pause the session. Manual picks always play. */
    private fun switchStaysPaused(): Boolean =
        _playbackState.value.currentTrack != null && !player.playWhenReady

    /** Bookkeeping for a track becoming current. Shared by natural transitions and crossfade swaps. */
    private fun applyTrackTransition(
        trackId: Long,
        positionMs: Long,
        emitSeekEvent: Boolean,
        fallbackIndex: Int? = null
    ) {
        val currentQueue = _playbackState.value.queue

        beginListenOccurrence(trackId)

        val index = resolveExactQueueIndex(trackId) ?: fallbackIndex ?: return
        val track = currentQueue[index]

        // A played auxiliary track becomes a main track.
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

    /** Skips past a track that failed to load, giving up after [MAX_CONSECUTIVE_LOAD_ERRORS] in a row. */
    private fun handleTrackError(
        error: PlaybackException?,
        failedTrackId: Long?,
        syncCursorToPlayer: Boolean
    ) {
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

        // No crossfade: the failed player is dead and fading into the same item would double-play it.
        advanceToNext(useFade = false)
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
            if (resumeAfterError) player.play()
        }
    }

    /** Re-evaluates the output path and publishes it to [PlaybackState.audioOutputInfo] when it changed. */
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

    private fun startAudioPathObservers() {
        outputDeviceManager.start()

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
     * With direct volume control the gain is applied in the float DSP chain and the AudioTrack
     * stays at full scale. Skipped during a fade, which reads the base volume itself.
     */
    private fun applyVolumePath(state: DspState) {
        if (fadeEngine.isFading) return
        player.volume = if (state.directVolumeControl) {
            1f
        } else {
            state.volumePercent / 100f
        }
    }

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

    /** Playback time after which [trackId] counts as listened. */
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
     * Accumulates played time toward the listen threshold of the pending occurrence.
     * Only forward motion counts: pauses add nothing, a backward seek replays, and a forward
     * jump or a gap over [MAX_LISTENED_TICK_GAP_MS] adds nothing.
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
                val configChanged = latestSettings.bitPerfectEnabled != settings.bitPerfectEnabled ||
                        latestSettings.deviceAudioConfigs != settings.deviceAudioConfigs
                latestSettings = settings
                if (configChanged) recomputeAudioOutputInfo()
            }
        }
    }

    private fun observeLibraryForAuxiliaryRefill() {
        scope.launch {
            // Snapshot refreshed on completed scans: the live Flow would re-run the refill on every row write.
            allTracks = trackRepository.getAllTracksOnce()
            trackRepository.scanStatus.collect { status ->
                if (status is ScanStatus.Finished) {
                    allTracks = trackRepository.getAllTracksOnce()
                    if (_playbackState.value.queue.isNotEmpty()) {
                        refillAuxiliary()
                    }
                }
            }
        }
    }

    /**
     * Persists the session for resumption: debounced on queue changes, then refreshed every
     * [POSITION_SAVE_INTERVAL_MS] while playing. An emptied queue clears only a snapshot
     * written by this process.
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
     * Replaces the queue with [tracks] and plays from [startIndex].
     * [sourceCollectionId] marks the collection the queue was started from.
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

    /** Inserts [track] after the current one. Starts a new queue if empty. */
    fun playNext(track: TrackEntity) {
        insertTrack(track, _playbackState.value.queueIndex + 1)
    }

    /** Appends [track] to the main queue, ahead of the auxiliary pool. */
    fun addToQueue(track: TrackEntity) {
        val current = _playbackState.value
        var index = current.queue.size
        while (index > 0 && current.auxiliaryFlags.getOrNull(index - 1) == true) {
            index--
        }
        insertTrack(track, index)
    }

    /** Inserts [tracks] after the current one, in order. Starts a new queue if empty. */
    fun playNextAll(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        val current = _playbackState.value
        if (current.queue.isEmpty()) {
            setQueue(tracks, 0)
            return
        }
        insertTracks(tracks, current.queueIndex + 1)
    }

    /** Appends [tracks] to the main queue, ahead of the auxiliary pool. */
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
     * Plays [tracks] from a list pick: keeps played history, replaces the upcoming main-queue
     * tracks with [tracks] and jumps to [startIndex]. The auxiliary pool is kept.
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
            queueSourceFlags = List(keptCount) { false } +
                    List(tracks.size) { sourceCollectionId != null } +
                    List(current.queue.size - auxStart) { false }
        )

        jumpWindowTo(keptCount + startIndex.coerceIn(0, tracks.lastIndex), forcePlay = true)
    }

    /** Inserts [track] at [requestedIndex] and patches the loaded timeline in place. */
    private fun insertTrack(track: TrackEntity, requestedIndex: Int) {
        // Queue mutations shift the indices a fade captured.
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
            // Past the window: adjustWindow() loads it when playback gets close.
        }
    }

    /** Bulk variant of [insertTrack]. */
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
        }
    }

    /**
     * Loads a window around [centerIndex] and starts playback. The rest is loaded lazily by
     * [adjustWindow]. [forcePlay] marks a manual pick.
     */
    private fun loadWindowAndPlay(
        tracks: List<TrackEntity>,
        centerIndex: Int,
        forcePlay: Boolean = false
    ) {
        windowJumpJob?.cancel()
        val jumpId = ++windowJumpId
        pendingWindowResync = false
        val playAfterPrepare = forcePlay || !switchStaysPaused()

        val windowStart = (centerIndex - WINDOW_RADIUS).coerceAtLeast(0)
        val windowEnd = (centerIndex + WINDOW_RADIUS).coerceAtMost(tracks.lastIndex)

        windowJumpJob = scope.launch {
            val windowItems = withContext(Dispatchers.Default) {
                tracks.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
            }
            if (jumpId != windowJumpId) return@launch
            player.setMediaItems(windowItems, centerIndex - windowStart, 0L)
            if (playAfterPrepare) player.play() else player.pause()
            player.prepare()
            loadedRange = windowStart..windowEnd
        }
    }

    /** Extends the loaded window near its edges and unloads media far from it. No-op during a jump or a pending resync. */
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

            if (end < tracks.lastIndex && end - currentIndex < EDGE_TRIGGER_MARGIN) {
                val newEnd = (end + CHUNK_SIZE).coerceAtMost(tracks.lastIndex)
                val chunk = withContext(Dispatchers.Default) {
                    tracks.subList(end + 1, newEnd + 1).map { it.toMediaItem() }
                }
                awaitFrame()
                player.addMediaItems(chunk)
                end = newEnd
            }

            if (start > 0 && currentIndex - start < EDGE_TRIGGER_MARGIN) {
                val newStart = (start - CHUNK_SIZE).coerceAtLeast(0)
                val chunk = withContext(Dispatchers.Default) {
                    tracks.subList(newStart, start).map { it.toMediaItem() }
                }
                awaitFrame()
                player.addMediaItems(0, chunk)
                start = newStart
            }

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
        fadeEngine.cancel()

        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        if (fadeEngine.quickFadeEnabled && !player.isPlaying) {
            fadeEngine.fadeIn()
            player.play()
        } else {
            player.play()
        }
        // Published last so the button reacts to the tap immediately.
        _playbackState.value = _playbackState.value.copy(isPlaying = true)
    }

    fun pause() {
        // Mid-crossfade, `player` is still the outgoing stream while the UI shows the incoming one.
        val settledCrossfade = fadeEngine.completeCrossfadeNow()

        if (fadeEngine.quickFadeEnabled && player.isPlaying) {
            fadeEngine.fadeOutAndPause()
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                isPlaybackRequested = false
            )
        } else {
            player.pause()
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
                // Paused skips never crossfade: there is no audible stream to fade from.
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
     * Gesture skip to [index]. Doesn't un-pause a paused session and steps over known-failed
     * tracks in the skip direction.
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

    /** Synchronously rebuilds the window around [centerIndex], superseding any in-flight jump. */
    private fun jumpWindowTo(centerIndex: Int, forcePlay: Boolean = false) {
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
        if (forcePlay || !switchStaysPaused()) player.play() else player.pause()
        player.prepare()
    }

    fun seekTo(positionMs: Long) {
        fadeEngine.cancel()

        // Only the position flow is updated: playbackState collectors shouldn't recompose per drag pixel.
        _positionMs.value = positionMs
        _seekEvents.tryEmit(positionMs)

        val duration = player.duration
        val tailRoom = duration > 0L && duration != C.TIME_UNSET &&
                (duration - positionMs) > fadeEngine.quickFadeDurationMs * 2
        if (fadeEngine.quickFadeEnabled && player.isPlaying && tailRoom) {
            // Same-track crossfade; the progress tracker holds the position at the target until the secondary renders.
            if (fadeEngine.startCrossfade(
                    _playbackState.value.queueIndex,
                    positionMs,
                    fadeEngine.quickFadeDurationMs
                )
            ) {
                return
            }
        }

        player.seekTo(positionMs)
    }

    /** Advances to the next item, crossfading when [useFade] and quick fades are enabled. */
    private fun advanceToNext(useFade: Boolean) {
        val state = _playbackState.value
        if (player.hasNextMediaItem()) {
            if (useFade) {
                val targetQueueIndex = player.nextMediaItemIndex + loadedRange.first
                val targetId = state.queue.getOrNull(targetQueueIndex)?.id
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

    private fun entryIndexOf(entryId: Long): Int =
        _playbackState.value.queueEntryIds.indexOf(entryId)

    private fun candidateIndexesFor(trackId: Long): List<Int> =
        _playbackState.value.queue
            .withIndex()
            .filter { (_, t) -> t.id == trackId }
            .map { (i, _) -> i }

    /** Manual pick from the queue screen: moves the entry next and plays it, even over a paused session. */
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

    /** Moves an entry to [toUpcomingPosition], counted from the track after the current one. */
    fun moveQueueEntry(entryId: Long, toUpcomingPosition: Int) {
        val fromIndex = entryIndexOf(entryId)
        if (fromIndex == -1) return
        val queue = _playbackState.value.queue
        val offset = (_playbackState.value.queueIndex + 1).coerceAtLeast(0)
        val toIndex = (offset + toUpcomingPosition).coerceIn(0, queue.lastIndex)
        if (fromIndex == toIndex) return
        moveQueueItem(fromIndex, toIndex)
    }

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

        // Moving an item across the window edge can't be patched in place: rebuild lazily.
        if (fromLoaded != toLoaded) {
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

    /** Cycles repeat mode: NONE -> ALL -> ONE. */
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

    /** Queue index of the playing track from the player's timeline position, falling back to the nearest occurrence. */
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

        return candidates.minByOrNull { abs(it - playerIndex) }
    }

    private fun normalizeEntryIds(entryIds: List<Long>, queueSize: Int): List<Long> =
        if (entryIds.size == queueSize) entryIds else List(queueSize) { allocateEntryId() }

    private fun normalizeAuxiliaryFlags(flags: List<Boolean>, queueSize: Int): List<Boolean> =
        if (flags.size == queueSize) flags else List(queueSize) { false }

    private fun normalizeQueueSourceFlags(flags: List<Boolean>, queueSize: Int): List<Boolean> =
        if (flags.size == queueSize) flags else List(queueSize) { false }

    private fun hasFullAuxiliaryPool(flags: List<Boolean>, queueIndex: Int): Boolean {
        var count = 0
        for (i in (queueIndex + 1)..flags.lastIndex) {
            if (flags.getOrNull(i) == true) count++
        }
        return count >= AUXILIARY_QUEUE_LENGTH
    }

    private fun upcomingTrackIds(queue: List<TrackEntity>, queueIndex: Int): Set<Long> =
        queue.drop(queueIndex + 1).mapTo(HashSet()) { it.id }

    /** Draws [count] random tracks, preferring ids not in [existing]. Repeats only when the library is exhausted. */
    private fun drawAuxiliaryTracks(existing: Collection<Long>, count: Int): List<TrackEntity> {
        val pool = allTracks
        if (pool.isEmpty() || count <= 0) return emptyList()

        val fresh = pool.filter { it.id !in existing }
        val draws = ArrayList<TrackEntity>(count)
        while (draws.size < count) {
            draws += if (fresh.isNotEmpty()) {
                fresh[Random.nextInt(fresh.size)]
            } else {
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

    /** Appends random tracks so the auxiliary pool after the current track is full. */
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
     * Starts the automatic crossfade into the next track when the current one nears its end.
     * Skipped for repeat-one, at the queue end, and when the next track failed to load.
     * Re-evaluated on every progress tick.
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
            switchAtT = 0.5f
        )
    }

    /**
     * Publishes [positionMs] once per frame and arms the auto-crossfade.
     *
     * The ExoPlayer clock is quantized (AudioTrack timestamps step ~16-30 ms), so the published
     * position is dead-reckoned from an anchor that absorbs [POSITION_ERROR_SLEW] of each fresh
     * sample's error. Errors above [POSITION_SNAP_MS] are discontinuities and re-anchor at once.
     *
     * While a stream is priming (fade switch, seek, buffering) its clock sits frozen at the
     * target, so the position is held there and dead-reckoning resumes only once the clock
     * really moves. Small backward corrections are held rather than published, so the position
     * never visibly steps back.
     */
    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var lastPublishedMs = Long.MIN_VALUE
            var lastSampleMs = -1L
            var anchorMs = 0.0
            var anchorNanos = 0L
            var wasPriming = false
            // Non-zero while waiting for the incoming clock to start moving.
            var settlingSinceNanos = 0L
            while (true) {
                val displayPlayer = fadeEngine.displayPlayer ?: player
                val sampleMs = displayPlayer.currentPosition.coerceAtLeast(0L)
                val audible = displayPlayer.isPlaying || fadeEngine.isFading
                val loading = fadeEngine.displayPlayer == null &&
                        displayPlayer.playWhenReady &&
                        displayPlayer.playbackState == Player.STATE_BUFFERING
                val priming = loading ||
                        (fadeEngine.displayPlayer != null && !displayPlayer.isPlaying)
                // True on ticks where the position may legitimately step backward.
                var allowBackward = false

                // Settled pause: freeze the published clock. Publishing the raw sample here
                // could step it backward at the end of a pause fade-out.
                if (!audible && !loading && sampleMs == lastSampleMs) {
                    val publishedMs = maxOf(sampleMs, lastPublishedMs)
                    if (publishedMs != lastPublishedMs) {
                        lastPublishedMs = publishedMs
                        _positionMs.value = publishedMs
                    }
                    anchorMs = sampleMs.toDouble()
                    anchorNanos = 0L
                    lastSampleMs = sampleMs
                    settlingSinceNanos = 0L
                    delay(PAUSED_POLL_INTERVAL_MS)
                    continue
                }

                if (priming) {
                    allowBackward = true
                    anchorMs = sampleMs.toDouble()
                    anchorNanos = 0L
                    lastSampleMs = sampleMs
                    wasPriming = true
                } else if (wasPriming) {
                    // isPlaying only means READY + playWhenReady: the sink needs longer before
                    // the clock really starts. Keep holding until it moves.
                    wasPriming = false
                    allowBackward = true
                    settlingSinceNanos = System.nanoTime()
                    anchorMs = sampleMs.toDouble()
                    anchorNanos = 0L
                    lastSampleMs = sampleMs
                } else if (settlingSinceNanos != 0L) {
                    if (sampleMs != lastSampleMs) {
                        val jumpMs = abs(sampleMs - anchorMs)
                        anchorMs = sampleMs.toDouble()
                        lastSampleMs = sampleMs
                        if (jumpMs > POSITION_SNAP_MS) {
                            // Another seek while settling: re-anchor and keep waiting.
                            allowBackward = true
                            anchorNanos = 0L
                            settlingSinceNanos = System.nanoTime()
                        } else {
                            anchorNanos = System.nanoTime()
                            settlingSinceNanos = 0L
                        }
                    } else if ((System.nanoTime() - settlingSinceNanos) / 1_000_000L >
                        SETTLE_TIMEOUT_MS
                    ) {
                        anchorNanos = System.nanoTime()
                        settlingSinceNanos = 0L
                    }
                } else if (sampleMs != lastSampleMs) {
                    // Fresh sample: slew the anchor toward it, or snap on a discontinuity.
                    val elapsedMs =
                        if (anchorNanos == 0L) 0.0 else (System.nanoTime() - anchorNanos) / 1_000_000.0
                    val errorMs = sampleMs - (anchorMs + elapsedMs)
                    val snapped = abs(errorMs) > POSITION_SNAP_MS
                    if (snapped) allowBackward = true
                    anchorMs =
                        if (snapped) {
                            sampleMs.toDouble()
                        } else {
                            anchorMs + elapsedMs + errorMs * POSITION_ERROR_SLEW
                        }
                    if (snapped && audible) {
                        // Seek while playing: hold at the target until the clock moves.
                        anchorNanos = 0L
                        settlingSinceNanos = System.nanoTime()
                    } else {
                        // Dead-reckon only while audible, otherwise the position would run
                        // ahead of a frozen clock and be pulled back later.
                        anchorNanos = if (audible) System.nanoTime() else 0L
                    }
                    lastSampleMs = sampleMs
                } else if (!audible) {
                    // The sample moved while paused: external seek, the raw clock is the truth.
                    allowBackward = true
                    anchorMs = sampleMs.toDouble()
                    anchorNanos = 0L
                    lastSampleMs = sampleMs
                }
                // Audible with a frozen sample (quantization gap): keep dead-reckoning from the anchor.

                val rawProjectedMs =
                    (if (anchorNanos == 0L) {
                        anchorMs
                    } else {
                        anchorMs + (System.nanoTime() - anchorNanos) / 1_000_000.0
                    }).roundToLong().coerceAtLeast(0L)
                // Monotonic guard: hold the published value on small backward corrections.
                val deficitMs = lastPublishedMs - rawProjectedMs
                val projectedMs =
                    if (!allowBackward && lastPublishedMs != Long.MIN_VALUE &&
                        deficitMs in 1..MONOTONIC_HOLD_MAX_MS
                    ) {
                        lastPublishedMs
                    } else {
                        rawProjectedMs
                    }
                if (projectedMs != lastPublishedMs) {
                    lastPublishedMs = projectedMs
                    _positionMs.value = projectedMs
                }

                tickListenCount(displayPlayer, projectedMs)

                // Gated on the real primary: mid-fade the display player is a priming secondary.
                if (player.isPlaying) {
                    maybeStartTrackCrossfade()
                }

                // Per frame while audible. awaitFrame() falls back to a slow poll in the background.
                if (audible) {
                    awaitFrame()
                } else {
                    delay(PAUSED_POLL_INTERVAL_MS)
                }
            }
        }
    }
}