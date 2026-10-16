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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.foamplayer.app.audio.bitperfect.AudioOutputInfo
import com.foamplayer.app.audio.bitperfect.AudioStreamInfo
import com.foamplayer.app.audio.bitperfect.BitPerfectDetector
import com.foamplayer.app.audio.device.OutputDeviceManager
import com.foamplayer.app.audio.dsp.DspAudioProcessor
import com.foamplayer.app.audio.dsp.DspEngine
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.distant.DistantRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.distant.DistantUris
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
import kotlinx.coroutines.flow.first
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
import kotlin.time.Duration.Companion.milliseconds

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
    private val distantRepository: DistantRepository,
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
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(DistantDataSourceFactory(context) { distantRepository.streamFor(it) })
        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    /** The user's "Play on" routing pick (null = no explicit pick yet). */
    val preferredOutputDeviceId: StateFlow<String?> = outputDeviceManager.preferredDeviceId

    /** The routing target folded to a "Play on" sheet id (what the UI shows as selected). */
    val selectedPlayOnDeviceId: StateFlow<String?> = outputDeviceManager.selectedPlayOnDeviceId

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

    /** Inter-track silence gap, in milliseconds. 0 disables it (default). */
    @Volatile
    private var silenceBetweenTracksMs: Long = 0L

    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    private var lastAudioOutputInfo: AudioOutputInfo? = null

    private var consecutiveLoadErrors: Int = 0

    /** Tracks that failed to load. Navigation skips them in both directions. */
    private val failedTrackIds = mutableSetOf<Long>()

    /** Latch so the auto-crossfade triggers once per track. */
    private var crossfadeArmedForTrackId: Long? = null

    /** Latch so the inter-track silence gap fires once per track. */
    private var silenceGapArmedForTrackId: Long? = null

    /** Pending "keep holding the finished track, then advance" job (see [maybeBeginSilenceGap]). */
    private var silenceCountdownJob: Job? = null

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
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                maybeBeginSilenceGap()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val trackId = mediaItem?.mediaId?.toLongOrNull() ?: return

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                val state = _playbackState.value
                if (state.repeatMode == RepeatMode.ALL) {
                    val index = resolveExactQueueIndex(trackId)
                    if (index != null &&
                        index == primaryQueueLastIndex(state) + 1 &&
                        state.auxiliaryFlags.getOrNull(index) == true
                    ) {
                        wrapToQueueStart()
                        return
                    }
                }
            }

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

        @androidx.annotation.OptIn(UnstableApi::class)
        override fun applyOutputRouting(player: ExoPlayer) {
            applyPreferredAudioDeviceTo(player)
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
            player.repeatMode = playerRepeatMode(_playbackState.value.repeatMode)
            player.shuffleModeEnabled = _playbackState.value.isShuffle
            applyPauseAtEndOfMediaItems()

            activePlayerField.addListener(playerListener)
            _playbackState.value =
                _playbackState.value.copy(isPlaybackRequested = player.playWhenReady)

            scope.launch { adjustWindow() }
        }

        override fun onCrossfadeAborted(logicalSwitchHadFired: Boolean) {
            pendingFadeWindowStart = null
            pendingFadeWindowEnd = null
            crossfadeArmedForTrackId = null
            silenceGapArmedForTrackId = null

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
        withTimeoutOrNull(FRAME_WAIT_TIMEOUT_MS.milliseconds) {
            suspendCancellableCoroutine { cont ->
                val callback = Choreographer.FrameCallback { cont.resumeWith(Result.success(Unit)) }
                Choreographer.getInstance().postFrameCallback(callback)
                cont.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
            }
        }
    }

    private fun TrackEntity.toMediaItem(): MediaItem {
        val artwork = if (com.foamplayer.distant.DistantUris.isDistant(path)) {
            (albumArtPath ?: path).toUri()
        } else {
            path.toUri()
        }
        return MediaItem.Builder()
            .setUri(path.toUri())
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artwork)
                    .build()
            )
            .build()
    }

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

        cancelPendingTransitions()
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

        player.repeatMode = playerRepeatMode(repeatMode)
        player.shuffleModeEnabled = isShuffle
        applyPauseAtEndOfMediaItems()

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

    /** True once [restoreSession] has run for this process (restored, or nothing to restore). */
    private var sessionRestoreAttempted = false

    /**
     * Restores the persisted playing session when the app process starts fresh: the saved
     * queue is resolved against the library and rebuilt PAUSED at the saved position, so the
     * played history, the current track and the upcoming queue all survive a close-and-reopen
     * without playback resuming on its own (the user presses play).
     *
     * No-op once a queue is already live (the process never died, or a media-button resumption
     * rebuilt the session first); returns false when there was nothing to restore. Engine
     * mutations all run on the main dispatcher, and [sessionRestoreAttempted] latches on the
     * first call, so re-entries can't race a user pick.
     */
    suspend fun restoreSession(): Boolean {
        if (sessionRestoreAttempted) return false
        sessionRestoreAttempted = true
        if (_playbackState.value.currentTrack != null) return false

        val saved = queuePersistence.current() ?: return false
        val byId = trackRepository.getTracksByIds(saved.trackIds).associateBy { it.id }
        val tracks = saved.trackIds.mapNotNull { byId[it] }
        if (tracks.isEmpty() || saved.currentIndex !in tracks.indices) return false

        // The auxiliary pool was never persisted; the library snapshot may not be
        // loaded yet this early in the process, so fetch it before drawing a new pool.
        if (allTracks.isEmpty()) allTracks = trackRepository.getAllTracksOnce()

        restoreQueue(
            tracks = tracks,
            startIndex = saved.currentIndex,
            positionMs = saved.positionMs,
            sourceCollectionId = saved.queueSourceId,
            sourceFlags = saved.sourceFlags.takeIf { it.size == tracks.size },
            isShuffle = saved.isShuffle,
            repeatMode = saved.repeatMode
        )
        scheduleAuxiliaryRefill()
        return true
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
        silenceGapArmedForTrackId = null

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
                isStreaming = com.foamplayer.distant.DistantUris.isDistant(track.path),
                stream = streamInfoFor(track),
                deviceConfig = latestSettings.deviceAudioConfig(outputDeviceManager.activeDevice.value?.id)
            )
        )
        if (info != lastAudioOutputInfo) {
            lastAudioOutputInfo = info
            _playbackState.value = _playbackState.value.copy(audioOutputInfo = info)
        }
    }

    /** Resolves the transport facts of a distant track for the pipeline's Transport stage. */
    private fun streamInfoFor(track: TrackEntity): AudioStreamInfo? {
        if (!DistantUris.isDistant(track.path)) return null
        val parsed = DistantUris.parse(track.path) ?: return null
        val endpoint = runCatching { distantRepository.streamFor(track.path) }
            .getOrNull()
            ?.url
            ?.let(::endpointOf)
            .orEmpty()
        return AudioStreamInfo(provider = parsed.type.displayName, endpoint = endpoint)
    }

    /** Scheme + authority of a stream URL (drops the path/query so no token leaks). */
    private fun endpointOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val rest = url.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        return url.substring(0, schemeEnd + 3) + authority
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
        // User-picked routing ("Play on" sheet): re-resolve and re-apply the
        // preferred device whenever the override changes OR the device set changes
        // (a detached headset must drop the override; a re-attached one must
        // reclaim it). setPreferredAudioDevice pins the sink and every AudioTrack
        // it creates afterwards, so both players and future tracks follow. The
        // persisted pick is restored first (before the collectors can act on a
        // null it would briefly read), then the observers take over.
        scope.launch {
            val persisted = settingsRepository.settings.first().preferredOutputDeviceId
            if (persisted != null &&
                outputDeviceManager.preferredDeviceId.value == null
            ) {
                outputDeviceManager.setPreferredDevice(persisted)
            }
        }
        scope.launch {
            outputDeviceManager.devices.collect {
                applyPreferredAudioDevice()
                recomputeAudioOutputInfo()
            }
        }
        scope.launch {
            outputDeviceManager.preferredDeviceId.collect {
                applyPreferredAudioDevice()
                recomputeAudioOutputInfo()
            }
        }
    }

    /**
     * Applies the resolved routing target to the active player's audio sink.
     * Three cases:
     *  - no explicit pick yet -> clear the pin, the platform routes by its own
     *    priority (BT attached => BT, matching pre-"Play on" behavior);
     *  - "this device" pick -> pin the best LOCAL output (USB, wired, speaker) so
     *    an attached BT headset does NOT steal the route;
     *  - a concrete device pick -> pin that device.
     * A target that detached between the state flip and this call leaves the
     * previous route untouched (the override state itself self-corrects in
     * [OutputDeviceManager.refresh]).
     */
    private fun applyPreferredAudioDevice() {
        applyPreferredAudioDeviceTo(activePlayerField)
        // Mid-crossfade the AUDIBLE stream is the not-yet-promoted secondary: pin
        // it too, or the incoming stream stays on the platform's default route.
        fadeEngine.activeSecondaryPlayer?.let { applyPreferredAudioDeviceTo(it) }
    }

    /**
     * Pins [target]'s audio sink to the resolved routing target. Three cases:
     *  - no explicit pick yet -> clear the pin, the platform routes by its own
     *    priority (BT attached => BT, matching pre-"Play on" behavior);
     *  - "this device" pick -> pin the best LOCAL output (USB, wired, speaker) so
     *    an attached BT headset does NOT steal the route;
     *  - a concrete device pick -> pin that device.
     * A target that detached between the state flip and this call leaves the
     * previous route untouched (the override state itself self-corrects in
     * [OutputDeviceManager.refresh]).
     */
    private fun applyPreferredAudioDeviceTo(target: ExoPlayer) {
        val preferredId = outputDeviceManager.preferredDeviceId.value
        if (preferredId == null) {
            target.setPreferredAudioDevice(null)
            return
        }
        val resolvedId = if (preferredId == OutputDeviceManager.ThisDevicePickId) {
            // "This device" is a virtual target: the best local output stands in
            // for it. No local output attached -> nothing to pin.
            outputDeviceManager.localDevice?.id ?: return
        } else {
            preferredId
        }
        val deviceInfo = outputDeviceManager.systemDeviceInfoForId(resolvedId) ?: return
        target.setPreferredAudioDevice(deviceInfo)
    }

    /**
     * Explicit "Play on" routing from the device picker. Returns false when the
     * target is not (or no longer) attached, so the UI can keep its previous
     * selection. The pick persists across process restarts.
     *
     * After pinning the sink the player is flushed and re-prepared at the current
     * position: several OEM AudioFlinger implementations only honor
     * AudioTrack.setPreferredDevice at track CREATION, silently ignoring a live
     * re-route of a playing track (which is why the switch otherwise only "works"
     * after the next track change creates a fresh AudioTrack). Recreating the
     * track makes the first switch deterministic; the brief gap is the same one a
     * seek produces.
     */
    fun routeTo(deviceId: String): Boolean {
        if (fadeEngine.isFading) fadeEngine.completeCrossfadeNow()
        val applied = outputDeviceManager.setPreferredDevice(deviceId)
        if (!applied) {
            recomputeAudioOutputInfo()
            return false
        }
        applyPreferredAudioDevice()
        scope.launch { settingsRepository.setPreferredOutputDeviceId(deviceId) }
        recomputeAudioOutputInfo()

        // Force the fresh AudioTrack unless playback is idle anyway (nothing to
        // re-create; the pin applies when the next track is prepared). stop()
        // tears the renderer's AudioTrack down (STATE_IDLE) and prepare() rebuilds
        // it with the just-pinned preferred device, at the same position.
        if (player.playbackState != Player.STATE_IDLE) {
            val resume = player.playWhenReady
            val positionMs = player.currentPosition
            player.stop()
            player.seekTo(positionMs)
            player.prepare()
            if (resume) {
                player.play()
                _playbackState.value = _playbackState.value.copy(isPlaying = true)
            }
        }
        return true
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
                val silenceChanged = silenceBetweenTracksMs !=
                        (settings.silenceBetweenTracksSeconds * 1000).toLong()
                silenceBetweenTracksMs =
                    (settings.silenceBetweenTracksSeconds * 1000).toLong()
                val configChanged = latestSettings.bitPerfectEnabled != settings.bitPerfectEnabled ||
                        latestSettings.deviceAudioConfigs != settings.deviceAudioConfigs
                latestSettings = settings
                if (silenceChanged) applyPauseAtEndOfMediaItems()
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
        cancelPendingTransitions()
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

        applyPauseAtEndOfMediaItems()
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

        cancelPendingTransitions()

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
        cancelPendingTransitions()

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

        cancelPendingTransitions()

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
        cancelPendingTransitions()

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
        cancelSilenceCountdown()

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
        cancelPendingTransitions()
        advanceToNext(useFade = true)
    }

    fun previous() {
        cancelPendingTransitions()
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

        cancelPendingTransitions()

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
        cancelPendingTransitions()

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

    /**
     * Advances to the next item, crossfading when [useFade] and quick fades are enabled.
     * Repeat-all wraps at the primary queue's end (the auxiliary pool is only entered by
     * manual navigation); repeat-off also advances into the pool.
     */
    private fun advanceToNext(useFade: Boolean) {
        val state = _playbackState.value
        if (player.hasNextMediaItem()) {
            val targetQueueIndex = player.nextMediaItemIndex + loadedRange.first
            // Repeat-all wrap: a timeline next landing on the first auxiliary entry past
            // the primary queue restarts the queue instead.
            if (state.repeatMode == RepeatMode.ALL &&
                targetQueueIndex == primaryQueueLastIndex(state) + 1 &&
                state.auxiliaryFlags.getOrNull(targetQueueIndex) == true
            ) {
                wrapToQueueStart()
                return
            }
            if (useFade) {
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
            var nextIndex = state.queueIndex + 1
            val primaryLast = primaryQueueLastIndex(state)
            if (nextIndex > primaryLast) {
                if (state.repeatMode == RepeatMode.ALL && primaryLast >= 0) {
                    nextIndex = 0
                } else if (nextIndex >= state.queue.size) {
                    return
                }
                // Else: repeat-off flows on into the auxiliary pool (up-next content).
            }
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

    /**
     * The queue screen's swipe-right action: moves the entry to the first upcoming slot
     * and, when it came from the auxiliary pool, clears its flag so it joins the primary
     * queue (the pool refills right away).
     */
    fun promoteQueueEntry(entryId: Long) {
        val fromIndex = entryIndexOf(entryId)
        if (fromIndex == -1) return
        val current = _playbackState.value
        if (fromIndex <= current.queueIndex) return
        val wasAuxiliary = current.auxiliaryFlags.getOrNull(fromIndex) == true
        val nextIndex = (current.queueIndex + 1).coerceAtLeast(0)

        if (fromIndex != nextIndex) {
            moveQueueItem(fromIndex, nextIndex)
        }

        if (!wasAuxiliary) return

        val index = entryIndexOf(entryId)
        if (index != -1) {
            val flags = _playbackState.value.auxiliaryFlags.toMutableList()
            if (index < flags.size) {
                flags[index] = false
                _playbackState.value = _playbackState.value.copy(auxiliaryFlags = flags)
            }
        }
        scheduleAuxiliaryRefill()
    }

    /**
     * Shuffles the upcoming main-queue occurrences among their slots and replaces every
     * auxiliary slot after them with a fresh random track.
     */
    fun shuffleUpcoming() {
        val current = _playbackState.value
        val queue = current.queue
        val fromIndex = (current.queueIndex + 1).coerceAtLeast(0)
        if (queue.isEmpty() || fromIndex > queue.lastIndex) return

        cancelPendingTransitions()

        val flags = current.auxiliaryFlags
        val entryIds = current.queueEntryIds
        val sourceFlags = current.queueSourceFlags
        val upcomingRange = fromIndex..queue.lastIndex

        val shuffledMain = upcomingRange
            .filter { flags.getOrNull(it) != true }
            .map { i ->
                Triple(
                    queue[i],
                    entryIds.getOrNull(i) ?: allocateEntryId(),
                    sourceFlags.getOrNull(i) == true
                )
            }
            .shuffled()
        val freshAuxiliary = drawAuxiliaryTracks(
            existing = upcomingTrackIds(queue, current.queueIndex),
            count = upcomingRange.count { flags.getOrNull(it) == true }
        )

        val newQueue = queue.toMutableList()
        val newEntryIds = entryIds.toMutableList()
        val newFlags = flags.toMutableList()
        val newSourceFlags = sourceFlags.toMutableList()
        var mainCursor = 0
        var auxiliaryCursor = 0
        for (index in upcomingRange) {
            if (flags.getOrNull(index) == true) {
                if (auxiliaryCursor < freshAuxiliary.size) {
                    newQueue[index] = freshAuxiliary[auxiliaryCursor]
                    newEntryIds[index] = allocateEntryId()
                    auxiliaryCursor++
                }
            } else {
                if (mainCursor < shuffledMain.size) {
                    val (track, entryId, sourceFlag) = shuffledMain[mainCursor]
                    newQueue[index] = track
                    newEntryIds[index] = entryId
                    newFlags[index] = false
                    newSourceFlags[index] = sourceFlag
                    mainCursor++
                }
            }
        }

        _playbackState.value = current.copy(
            queue = newQueue,
            queueEntryIds = normalizeEntryIds(newEntryIds, newQueue.size),
            auxiliaryFlags = normalizeAuxiliaryFlags(newFlags, newQueue.size),
            queueSourceFlags = normalizeQueueSourceFlags(newSourceFlags, newQueue.size)
        )

        // Patch the loaded timeline in place: everything after the current item is replaced;
        // the current item and the played history keep their player indices.
        if (pendingWindowResync || loadedRange == IntRange.EMPTY) return
        val playerStart = fromIndex - loadedRange.first
        if (playerStart >= player.mediaItemCount) return
        player.removeMediaItems(playerStart, player.mediaItemCount)
        val windowEnd = loadedRange.last.coerceAtMost(newQueue.lastIndex)
        if (fromIndex <= windowEnd) {
            player.addMediaItems(newQueue.subList(fromIndex, windowEnd + 1).map { it.toMediaItem() })
        }
        loadedRange = loadedRange.first..windowEnd
    }

    /**
     * Drops every upcoming entry, main and auxiliary, keeping the played history and the
     * current track. The auxiliary pool refills immediately; its tracks are loaded into the
     * player lazily, like any refill.
     */
    fun clearUpcoming() {
        val current = _playbackState.value
        val queue = current.queue
        if (queue.isEmpty()) return
        val keep = (current.queueIndex + 1).coerceIn(0, queue.size)

        cancelPendingTransitions()

        _playbackState.value = current.copy(
            queue = queue.take(keep),
            queueEntryIds = current.queueEntryIds.take(keep),
            auxiliaryFlags = current.auxiliaryFlags.take(keep),
            queueSourceFlags = current.queueSourceFlags.take(keep)
        )

        if (!pendingWindowResync && loadedRange != IntRange.EMPTY) {
            val playerStart = (keep - loadedRange.first).coerceAtLeast(0)
            if (playerStart < player.mediaItemCount) {
                player.removeMediaItems(playerStart, player.mediaItemCount)
            }
            loadedRange = if (keep > 0) {
                loadedRange.first..(keep - 1)
            } else {
                IntRange.EMPTY
            }
        }

        scheduleAuxiliaryRefill()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentQueue = _playbackState.value.queue
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) return

        cancelPendingTransitions()

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

        val newPlayingIndex = when (val oldPlayingIndex = current.queueIndex) {
            fromIndex -> toIndex
            in (fromIndex + 1)..toIndex -> oldPlayingIndex - 1
            in toIndex..<fromIndex -> oldPlayingIndex + 1
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

        cancelPendingTransitions()

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

    /** Cycles repeat mode: NONE -> ONE -> ALL. */
    fun toggleRepeat() {
        val nextMode = when (_playbackState.value.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.NONE
        }
        player.repeatMode = playerRepeatMode(nextMode)
        // Repeat-one loops the item itself, so the pause-at-end signal must stay armed off.
        applyPauseAtEndOfMediaItems()
        _playbackState.value = _playbackState.value.copy(repeatMode = nextMode)
    }

    /**
     * Session repeat mode as an ExoPlayer mode. ALL deliberately maps to OFF: the app-level
     * loop wraps only the primary queue (see [maybeStartTrackCrossfade] and the STATE_ENDED
     * wrap in [startProgressTracker]), while the player's REPEAT_MODE_ALL would also loop the
     * random auxiliary pool appended after the user's queue.
     */
    private fun playerRepeatMode(mode: RepeatMode): Int = when (mode) {
        RepeatMode.NONE -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
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

    /**
     * Repeat-all wrap: restarts the primary queue. Plays index 0 through the standard
     * window loader; the transition's listen bookkeeping fires from the player callback.
     */
    private fun wrapToQueueStart() {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        crossfadeArmedForTrackId = null
        silenceGapArmedForTrackId = null
        loadWindowAndPlay(state.queue, 0)
    }

    /**
     * Last index of the PRIMARY queue (the user's own tracks), i.e. before the random
     * auxiliary pool. -1 when the queue has no primary tracks. Repeat-all wraps at this
     * boundary; the auxiliary tail is only ever reached by manual navigation.
     */
    private fun primaryQueueLastIndex(state: PlaybackState): Int {
        var index = state.queue.lastIndex
        while (index >= 0 && state.auxiliaryFlags.getOrNull(index) == true) {
            index--
        }
        return index
    }

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
     * Skipped for repeat-one and when the next track failed to load. At the primary queue's
     * end, repeat-all wraps to its start (the auxiliary pool is only entered by manual
     * navigation); repeat-off keeps flowing into the pool. Re-evaluated on every progress tick.
     */
    private fun maybeStartTrackCrossfade() {
        if (silenceBetweenTracksMs > 0L) return
        if (fadeEngine.isFading) return
        if (!fadeEngine.normalFadeEnabled) return
        val state = _playbackState.value
        if (state.repeatMode == RepeatMode.ONE) return

        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (crossfadeArmedForTrackId == trackId) return

        val duration = player.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return

        var nextIndex = state.queueIndex + 1
        val primaryLast = primaryQueueLastIndex(state)
        if (nextIndex > primaryLast) {
            when {
                state.repeatMode == RepeatMode.ALL && primaryLast >= 0 &&
                        (nextIndex == primaryLast + 1 || nextIndex >= state.queue.size) ->
                    nextIndex = 0
                nextIndex >= state.queue.size -> return
                // Else: repeat-off flows on into the auxiliary pool (up-next content).
            }
        }
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
     * Applies [ExoPlayer.setPauseAtEndOfMediaItems] to the active player: the flag makes the
     * player pause at the end of the current media item instead of auto-advancing, which is the
     * hook the inter-track silence gap needs. Repeat-one loops the item itself, so the flag is
     * never set there (it would pause after every loop).
     */
    private fun applyPauseAtEndOfMediaItems() {
        val enabled = silenceBetweenTracksMs > 0L && _playbackState.value.repeatMode != RepeatMode.ONE
        player.pauseAtEndOfMediaItems = enabled
    }

    /**
     * The inter-track silence gap: when [silenceBetweenTracksMs] > 0, the outgoing track plays
     * to its very end, the player pauses there ([applyPauseAtEndOfMediaItems] makes ExoPlayer
     * report it via PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM), the engine holds on the
     * finished track for the configured gap, then advances to the next one. The automatic
     * crossfade is disabled while a gap is configured ([maybeStartTrackCrossfade]).
     */
    private fun maybeBeginSilenceGap() {
        val silenceMs = silenceBetweenTracksMs
        if (silenceMs <= 0L) return
        if (silenceCountdownJob != null) return
        if (fadeEngine.isFading) return

        val state = _playbackState.value
        if (state.repeatMode == RepeatMode.ONE) return

        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (silenceGapArmedForTrackId == trackId) return

        val primaryLast = primaryQueueLastIndex(state)
        val nextIndex = when {
            state.queueIndex + 1 <= primaryLast -> state.queueIndex + 1
            state.repeatMode == RepeatMode.ALL && primaryLast >= 0 -> 0
            state.queueIndex + 1 < state.queue.size -> state.queueIndex + 1
            else -> return
        }
        if (state.queue[nextIndex].id in failedTrackIds) return

        silenceGapArmedForTrackId = trackId
        silenceCountdownJob = scope.launch {
            delay(silenceMs)
            silenceCountdownJob = null
            val current = _playbackState.value
            val currentTrackId = player.currentMediaItem?.mediaId?.toLongOrNull()
            // The user may have interacted during the gap (play/pause/seek/skip, queue edits).
            // Any of those cancels or invalidates the pending advance; re-verify before switching.
            if (currentTrackId != trackId || current.queueIndex != state.queueIndex) return@launch
            if (fadeEngine.isFading || player.isPlaying || player.playWhenReady) return@launch
            advanceAfterSilence(current, nextIndex)
        }
    }

    /** Cancels a pending silence gap without touching the fade engine. */
    private fun cancelSilenceCountdown() {
        silenceCountdownJob?.cancel()
        silenceCountdownJob = null
    }

    /**
     * Cancels every pending track transition (fades and the silence gap). Callers are the user
     * actions and queue mutations that must supersede an in-flight transition.
     */
    private fun cancelPendingTransitions() {
        cancelSilenceCountdown()
        silenceGapArmedForTrackId = null
        fadeEngine.cancel()
    }

    /**
     * Leaves the finished track behind: switches to [nextIndex] at position 0 and resumes
     * playback, mirroring the queue-order convention of the auto-crossfade's target.
     */
    private fun advanceAfterSilence(state: PlaybackState, nextIndex: Int) {
        if (nextIndex in loadedRange) {
            player.seekTo(nextIndex - loadedRange.first, 0L)
        } else {
            loadWindowAndPlay(state.queue, nextIndex)
        }
        player.play()
        _playbackState.value = _playbackState.value.copy(isPlaybackRequested = true)
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
