package com.foamplayer.app.audio.engine

import android.content.Context
import android.util.Log
import android.view.Choreographer
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.foamplayer.app.audio.bitperfect.BitPerfectDetector
import com.foamplayer.app.audio.cast.ChromecastController
import com.foamplayer.app.audio.device.OutputDeviceManager
import com.foamplayer.app.audio.dsp.DspAudioProcessor
import com.foamplayer.app.audio.dsp.DspEngine
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.distant.DistantRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.R
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.toast.ToastCenter
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
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
    private val toastCenter: ToastCenter,
    private val fadeEngine: FadeEngine,
    private val queuePersistence: QueuePersistence,
    private val chromecast: ChromecastController
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
    val selectedPlayOnDeviceId: StateFlow<String?> =
        combine(
            outputDeviceManager.selectedPlayOnDeviceId,
            chromecast.connectedDeviceId
        ) { local, cast -> cast ?: local }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = outputDeviceManager.selectedPlayOnDeviceId.value
            )

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    /**
     * True while a plain seek is settling: buffering caused by the seek itself must not
     * show the loading indicator (only a genuinely new remote track does).
     */
    private var suppressLoadingUntilReady: Boolean = false

    /**
     * Set by [play] on a distant track: ExoPlayer may already report STATE_READY (it
     * buffers the item while paused) long before the decoder/sink actually render
     * audio, so the loader has to survive that first READY and drop on isPlaying
     * instead. Consumed by the first READY (or any stop), so it can never linger and
     * mask a later load.
     */
    private var playStartPending: Boolean = false

    /**
     * A Chromecast load (or play request) is in flight and expected to start playing
     * (see [onCastStatus]).
     */
    private var castLoadingExpected: Boolean = false

    /** Publishes [loading] into the playback snapshot, only when it actually changes. */
    private fun setLoading(loading: Boolean) {
        if (!loading) playStartPending = false
        if (_playbackState.value.isLoading == loading) return
        _playbackState.value = _playbackState.value.copy(isLoading = loading)
    }

    /**
     * Loading applies to remote sources only: a distant track still being fetched, or
     * (through [onCastStatus]) a receiver preparing the next item. Local files never
     * show it, and a plain seek opts out through [suppressLoadingUntilReady].
     */
    private fun updateLoadingForTrack(
        track: TrackEntity?,
        playbackState: Int = player.playbackState
    ) {
        if (chromecast.isCasting) return
        val requested = _playbackState.value.isPlaying || _playbackState.value.isPlaybackRequested
        setLoading(
            requested &&
                track != null &&
                DistantUris.isDistant(track.path) &&
                playbackState != Player.STATE_READY
        )
    }

    /** Playback position, updated every frame. Separate from [playbackState] to avoid recomposing its collectors. */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    /** Emits the new position on explicit seeks and track switches (not on frame ticks). */
    private val _seekEvents = MutableSharedFlow<Long>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val seekEvents: SharedFlow<Long> = _seekEvents.asSharedFlow()

    /** Emits once per playback occurrence when it reaches the listen threshold. */
    private val _listenedTrackEvents = MutableSharedFlow<ListenedTrackEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val listenedTrackEvents: SharedFlow<ListenedTrackEvent> =
        _listenedTrackEvents.asSharedFlow()

    private var onPlayerSwappedListener: (() -> Unit)? = null
    private var onDisplayPlayerChangedListener: ((Player) -> Unit)? = null

    /** Crossfade secondaries are built without focus handling; it is re-applied at each swap. */
    private val mediaAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** Output path subsystem: bit-perfect verdict, "Play on" pinning and volume (see [AudioOutputRouter]). */
    private val audioRouter = AudioOutputRouter(
        playerProvider = { activePlayerField },
        fadeEngine = fadeEngine,
        outputDeviceManager = outputDeviceManager,
        dspEngine = dspEngine,
        bitPerfectDetector = bitPerfectDetector,
        settingsRepository = settingsRepository,
        distantRepository = distantRepository,
        mediaAudioAttributes = mediaAudioAttributes,
        scope = scope,
        currentTrack = { _playbackState.value.currentTrack },
        latestSettings = { latestSettings },
        onOutputInfo = { info ->
            _playbackState.value = _playbackState.value.copy(audioOutputInfo = info)
        },
        onPlaybackResumed = {
            _playbackState.value = _playbackState.value.copy(isPlaying = true)
        }
    )

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

    /**
     * Queue shuffle bookkeeping. [preparedWrap] = (playing entry id, primary queue size, forward) once the
     * repeat-all wrap landing slot was settled for this boundary visit; [postWrapLandingEntryId] = the
     * entry the wrap lands on, whose arrival triggers the main-queue reshuffle ([trackLandedForQueueShuffle]).
     */
    private var preparedWrap: Triple<Long, Int, Boolean>? = null
    private var postWrapLandingEntryId: Long? = null
    private var postWrapLanded = false
    private var postWrapRestPending = false

    /** Only the newest window jump may write the timeline. */
    private var windowJumpId: Long = 0L

    private val windowAdjustMutex = Mutex()

    private var nextEntryId: Long = 0L

    private fun allocateEntryId(): Long = nextEntryId++

    private var allTracks: List<TrackEntity> = emptyList()

    private var refillScheduled: Boolean = false

    /** Counts the forward playback of each occurrence toward the listened threshold (see [ListenAccounting]). */
    private val listen = ListenAccounting()

    /** Maps database rows onto player media items (artwork rules included). */
    private val mediaItemMapper = MediaItemMapper { distantRepository.artworkUrlFor(it) }

    /** Guards `clear()` so an empty queue at process start never wipes the previous process's resumption snapshot. */
    private var hasSessionSaved: Boolean = false

    @Volatile
    private var listenedThresholdPercent: Int = AppSettings.DEFAULT_LISTENED_THRESHOLD_PERCENT

    /** Inter-track silence gap, in milliseconds. 0 disables it (default). */
    @Volatile
    private var silenceBetweenTracksMs: Long = 0L

    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    private var consecutiveLoadErrors: Int = 0

    /** Tracks that failed to load. Navigation skips them in both directions. */
    private val failedTrackIds = mutableSetOf<Long>()

    /** Chromecast picked while its session was still starting (see [castTo]). */
    private var pendingCastDeviceId: String? = null

    /** Queue index the receiver already reported as stopped (see [onCastStatus]). */
    private var castStoppedAt: Int = -1

    /** Set when a "Play on" pick supersedes the cast session: playback resumes on this device. */
    private var castHandoffResume: Boolean = false

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
        private const val FRAME_WAIT_TIMEOUT_MS = POSITION_POLL_SLOW_INTERVAL_MS
        private const val MAX_CONSECUTIVE_LOAD_ERRORS = 10
        private const val PERSIST_DEBOUNCE_MS = 1_000L
        private const val POSITION_SAVE_INTERVAL_MS = 15_000L

        /** Upcoming tracks pushed to a Chromecast in one queueLoad (the receiver's next/seek window). */
        private const val CAST_QUEUE_WINDOW = ChromecastController.QUEUE_WINDOW

        /**
         * Grace period for a receiver to start the next item of its own queue before
         * the app presumes it stopped and reloads (see [onCastStatus]).
         */
        private const val CAST_STOP_GRACE_MS = 800L
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (chromecast.isCasting) return
            when (playbackState) {
                Player.STATE_READY -> {
                    consecutiveLoadErrors = 0
                    suppressLoadingUntilReady = false
                    if (playStartPending) {
                        playStartPending = false
                    } else {
                        setLoading(false)
                    }
                }

                Player.STATE_BUFFERING -> {
                    if (!suppressLoadingUntilReady) {
                        updateLoadingForTrack(_playbackState.value.currentTrack, playbackState)
                    }
                }

                Player.STATE_IDLE -> {
                    suppressLoadingUntilReady = false
                    playStartPending = false
                    setLoading(false)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleTrackError(error, failedTrackId = null, syncCursorToPlayer = true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // While casting, the local player is parked: its state must not
            // overwrite the receiver's mirror in _playbackState.
            if (chromecast.isCasting) return
            _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            if (isPlaying) {
                playStartPending = false
                setLoading(false)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (chromecast.isCasting) return
            _playbackState.value =
                _playbackState.value.copy(isPlaybackRequested = playWhenReady)
            if (!playWhenReady) {
                playStartPending = false
                setLoading(false)
            }
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                maybeBeginSilenceGap()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Window maintenance on the parked player rewrites its timeline; the
            // cast status listener owns the queue position while casting.
            if (chromecast.isCasting) return
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
            audioRouter.applyTo(player)
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
            if (targetQueueIndex != _playbackState.value.queueIndex) {
                suppressLoadingUntilReady = false
            }
            pendingFadeWindowStart = windowStart
            pendingFadeWindowEnd = windowEnd

            // Listen counting moves to the incoming track; the outgoing progress is stashed in case the fade aborts.
            listen.stashOutgoing(targetTrackId)
            listen.begin(targetTrackId)

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

            playStartPending = false
            if (!suppressLoadingUntilReady) {
                updateLoadingForTrack(track, (displayPlayer ?: player).playbackState)
            }

            audioRouter.recompute()
            if (wasAuxiliary) scheduleAuxiliaryRefill()

            trackLandedForQueueShuffle()

            onDisplayPlayerChangedListener?.invoke(displayPlayer ?: activePlayerField)
        }

        override fun onCrossfadeSwapped(targetQueueIndex: Int, targetPositionMs: Long) {
            consecutiveLoadErrors = 0
            suppressLoadingUntilReady = false
            setLoading(false)
            pendingFadeWindowStart?.let { start ->
                loadedRange = start..(pendingFadeWindowEnd ?: start)
            }
            pendingFadeWindowStart = null
            pendingFadeWindowEnd = null
            listen.discardStash()

            // Session modes toggled mid-fade would otherwise be lost.
            player.repeatMode = playerRepeatMode(_playbackState.value.repeatMode)
            // Queue shuffle is app-level (see prepareWrapLanding), never ExoPlayer's shuffle mode.
            player.shuffleModeEnabled = false
            applyPauseAtEndOfMediaItems()

            activePlayerField.addListener(playerListener)
            _playbackState.value =
                _playbackState.value.copy(isPlaybackRequested = player.playWhenReady)

            // The new active timeline is committed: finish a repeat-all wrap's queue shuffle.
            trackLandedForQueueShuffle()

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
                audioRouter.recompute()
            }

            val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
            if (trackId != null) listen.resume(trackId) else listen.discardStash()

            suppressLoadingUntilReady = false

            _playbackState.value = _playbackState.value.copy(
                isPlaying = player.isPlaying,
                isPlaybackRequested = player.playWhenReady
            )
            updateLoadingForTrack(_playbackState.value.currentTrack)

            // The session may have been bound to the secondary, which is now released.
            onDisplayPlayerChangedListener?.invoke(activePlayerField)
        }

        override fun onFadeTargetError(targetTrackId: Long) {
            fadeEngine.cancel()
            handleTrackError(error = null, failedTrackId = targetTrackId, syncCursorToPlayer = false)
        }

        override fun playerBaseVolume(): Float {
            val dsp = dspEngine.state.value
            val dspGain = if (dsp.directVolumeControl) 1f else dsp.volumePercent / 100f
            // The phone-speaker gain only bites while Bluetooth owns the stream
            // volume (see AudioOutputRouter.phoneOutputGain).
            return dspGain * audioRouter.phoneOutputGain
        }
    }

    init {
        activePlayerField.addListener(playerListener)
        fadeEngine.bind(fadeHost)
        startProgressTracker()
        observeLibraryForAuxiliaryRefill()
        observeListenedThreshold()
        observeSessionForPersistence()
        audioRouter.start()
        chromecast.onStatus = ::onCastStatus
        chromecast.onSessionStarted = ::onCastSessionStarted
        chromecast.onSessionLost = ::onCastSessionLost
        observeVolumeForCast()
    }

    /**
     * Mirrors the app's volume onto the receiver: the DSP gain is local-only, so
     * without this the volume slider would do nothing while casting.
     */
    private fun observeVolumeForCast() {
        scope.launch {
            dspEngine.state.collect { state ->
                if (chromecast.isCasting) chromecast.setVolume(state.volumePercent / 100f)
            }
        }
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

    private fun TrackEntity.toMediaItem(): MediaItem = mediaItemMapper.toMediaItem(this)

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
        listen.clear()
        windowJumpJob?.cancel()
        failedTrackIds.clear()
        suppressLoadingUntilReady = false

        val clampedStart = startIndex.coerceIn(0, tracks.lastIndex)
        val restoredPosition = positionMs.coerceAtLeast(0L)

        val entryIds = List(tracks.size) { allocateEntryId() } +
                List(AUXILIARY_QUEUE_LENGTH) { allocateEntryId() }
        val newQueue = tracks + drawAuxiliaryTracks(queueTrackIds(tracks), AUXILIARY_QUEUE_LENGTH)

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
            isLoading = false,
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
        player.shuffleModeEnabled = false
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

        listen.begin(trackId)

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

        // A new song occurrence: any seek suppression from the previous track ends here.
        suppressLoadingUntilReady = false
        playStartPending = false
        updateLoadingForTrack(track)

        audioRouter.recompute()

        if (pendingWindowResync) {
            pendingWindowResync = false
            loadWindowAndPlay(currentQueue, index)
        }

        // Landed after a repeat-all wrap with shuffle: the main queue changes order shortly (see below).
        trackLandedForQueueShuffle()

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
        suppressLoadingUntilReady = false
        playStartPending = false
        setLoading(false)
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
            toastCenter.pushRes(R.string.toast_playback_stopped)
            return
        }
        trackId?.let { failedTrackIds.add(it) }

        // Tell the user what was skipped and why: a distant track whose provider
        // cannot serve it right now, or a local/distant load failure (decoder,
        // missing file, provider error...).
        failedTrack?.let(::pushTrackUnavailableToast)

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

    /** Pushes the "this track didn't play" toast for [track] into [toastCenter]. */
    private fun pushTrackUnavailableToast(track: TrackEntity) {
        val res = if (DistantUris.isDistant(track.path)) {
            R.string.toast_distant_track_unavailable
        } else {
            R.string.toast_failed_to_load
        }
        toastCenter.pushRes(res, track.title)
    }

    /** Explicit "Play on" routing from the device picker (see [AudioOutputRouter.routeTo]). */
    fun routeTo(deviceId: String): Boolean = audioRouter.routeTo(deviceId)

    // ------------------------------------------------------------------ casting

    /**
     * Sends the current queue to the Chromecast behind [deviceId]
     * (`"chromecast:<routeId>"`).
     *
     * Connecting is asynchronous: the queue is published once the receiver's
     * session reports in (see [onCastSessionStarted]). The phone's own playback is
     * parked first, so the sound never comes out of both at once.
     */
    fun castTo(deviceId: String): Boolean {
        if (!deviceId.startsWith(ChromecastController.ID_PREFIX)) return false
        val state = _playbackState.value
        if (state.currentTrack == null || state.queueIndex !in state.queue.indices) return false
        if (chromecast.connectedDeviceId.value == deviceId) {
            // Already on that receiver: re-publish the queue where the phone thinks it is.
            return castLoad(state.queue, state.queueIndex, _positionMs.value, state.isPlaybackRequested)
        }
        pendingCastDeviceId = deviceId
        detachLocalAudio()
        // Waiting for the receiver to come up and take the queue is a real load.
        castLoadingExpected = state.isPlaybackRequested
        setLoading(state.isPlaybackRequested)
        val routeId = deviceId.removePrefix(ChromecastController.ID_PREFIX)
        if (chromecast.connect(routeId)) return true
        // The route vanished before selection: give the phone its playback back.
        pendingCastDeviceId = null
        castLoadingExpected = false
        setLoading(false)
        toastCenter.pushRes(R.string.toast_cast_connect_failed)
        resumeLocalPlayback(state, play = state.isPlaybackRequested)
        return false
    }

    /** Ends the cast session; the queue stays paused on this device. */
    fun stopCasting(resumeOnDevice: Boolean = false) {
        pendingCastDeviceId = null
        castHandoffResume = resumeOnDevice
        castLoadingExpected = false
        setLoading(false)
        chromecast.disconnect()
    }

    /**
     * One hardware volume-key press, aimed at the device the "Play on" sheet has
     * selected rather than at the phone:
     *  - a Chromecast plays => the receiver's own (device) volume moves, so the TV
     *    shows its volume bar and its remote stays in sync;
     *  - the phone's output is pinned while a Bluetooth headset is attached =>
     *    Android would move the headset's volume, so the phone speaker's gain moves
     *    instead;
     *  - anything else (headset, wired, plain speaker) => returns false and the
     *    platform handles the key, which already targets the playing device.
     */
    fun adjustOutputVolume(up: Boolean): Boolean {
        val steps = if (up) 1 else -1
        if (chromecast.isCasting) return chromecast.adjustVolume(steps)
        return audioRouter.adjustPhoneOutputVolume(steps)
    }

    /** Silences the phone without touching the playback request, which the receiver inherits. */
    private fun detachLocalAudio() {
        cancelPendingTransitions()
        fadeEngine.completeCrossfadeNow()
        // The local listener is not guarded yet at this point (no pending route):
        // pausing would otherwise clear the request the receiver must inherit.
        val requested = _playbackState.value.isPlaybackRequested
        player.pause()
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            isPlaybackRequested = requested
        )
    }

    /** The receiver's queue: at most [CAST_QUEUE_WINDOW] upcoming tracks, starting at [index]. */
    private fun castLoad(
        queue: List<TrackEntity>,
        index: Int,
        positionMs: Long,
        play: Boolean
    ): Boolean {
        if (index !in queue.indices) return false
        val state = _playbackState.value
        // Repeat-all loops the primary queue: don't let the receiver run on into
        // the auxiliary pool the way a plain window would.
        val hardEnd = if (state.repeatMode == RepeatMode.ALL && queue === state.queue) {
            primaryQueueLastIndex(state) + 1
        } else {
            queue.size
        }
        val end = (index + CAST_QUEUE_WINDOW).coerceAtMost(hardEnd).coerceAtLeast(index + 1)
        val slice = queue.subList(index, end)
        castLoadingExpected = play
        setLoading(play)
        val loaded = chromecast.loadQueue(slice, index, positionMs, play)
        if (!loaded) {
            castLoadingExpected = false
            setLoading(false)
            toastCenter.pushRes(R.string.toast_cast_load_failed, queue[index].title)
        }
        return loaded
    }

    /** Moves the receiver to [index], re-publishing the queue when it is outside the loaded slice. */
    private fun castPlayIndex(
        index: Int,
        positionMs: Long,
        play: Boolean,
        emitSeekEvent: Boolean,
        reload: Boolean = false
    ) {
        val state = _playbackState.value
        if (index !in state.queue.indices) return
        // A jump only works while the receiver still holds live media: after it
        // went idle (end of a track/queue) the request is dropped, so [reload] --
        // and an idle receiver -- force a fresh load instead of leaving the app
        // "playing" silence.
        val jumped = !reload && !chromecast.isReceiverIdle &&
            chromecast.isIndexLoaded(index) &&
            chromecast.jumpTo(index, positionMs, play)
        if (!jumped && !castLoad(state.queue, index, positionMs, play)) return
        // A jump is a new item on the receiver too: keep the loader up until its
        // first non-buffering status arrives.
        castLoadingExpected = play
        setLoading(play)
        publishCastTrack(index, positionMs, emitSeekEvent)
    }

    /** next/previous on the receiver, wrapping at the primary queue like local playback. */
    private fun castAdvance(forward: Boolean, forcePlay: Boolean = false, reload: Boolean = false) {
        val state = _playbackState.value
        val index = state.queueIndex
        if (index !in state.queue.indices) return
        val play = forcePlay || state.isPlaybackRequested
        if (state.repeatMode == RepeatMode.ONE && forward) {
            castPlayIndex(index, 0L, play = true, emitSeekEvent = false, reload = reload)
            return
        }
        val first = primaryQueueFirstIndex(state).coerceAtLeast(0)
        val last = primaryQueueLastIndex(state)
        var target = if (forward) index + 1 else index - 1
        if (state.repeatMode == RepeatMode.ALL) {
            if (forward && target > last) target = first
            if (!forward && target < first) target = last
        }
        if (target !in state.queue.indices) return
        castPlayIndex(target, 0L, play, emitSeekEvent = false, reload = reload)
    }

    /**
     * Mirrors a position the receiver owns into the engine state. Deliberately
     * independent of [applyTrackTransition]: no ExoPlayer window work and
     * duplicate track ids can't resolve to the wrong queue slot.
     */
    private fun publishCastTrack(index: Int, positionMs: Long, emitSeekEvent: Boolean) {
        val state = _playbackState.value
        val track = state.queue.getOrNull(index) ?: return
        // Every call is a new playback occurrence: a receiver-driven track change
        // or a deliberate jump. Listen accounting starts over here.
        listen.begin(track.id)
        val flags = state.auxiliaryFlags
        val wasAuxiliary = flags.getOrNull(index) == true
        _playbackState.value = state.copy(
            currentTrack = track,
            queueIndex = index,
            durationMs = track.duration,
            auxiliaryFlags = if (wasAuxiliary) {
                flags.toMutableList().also { it[index] = false }
            } else flags
        )
        _positionMs.value = positionMs
        if (emitSeekEvent) _seekEvents.tryEmit(positionMs)
        audioRouter.recompute()
    }

    private fun onCastSessionStarted() {
        val state = _playbackState.value
        if (state.currentTrack == null || state.queueIndex !in state.queue.indices) return
        pendingCastDeviceId = null
        castStoppedAt = -1
        chromecast.setVolume(dspEngine.state.value.volumePercent / 100f)
        castLoad(state.queue, state.queueIndex, _positionMs.value, state.isPlaybackRequested)
    }

    private fun onCastSessionLost() {
        // A session that never reported in was a failed connect.
        val failedStart = pendingCastDeviceId != null
        pendingCastDeviceId = null
        castStoppedAt = -1
        castLoadingExpected = false
        setLoading(false)
        val resumeOnDevice = castHandoffResume
        castHandoffResume = false
        val state = _playbackState.value
        _playbackState.value = state.copy(isPlaying = false)
        if (failedStart) toastCenter.pushRes(R.string.toast_cast_connect_failed)
        // Give the phone its playback back on either hand-off (explicit stop or
        // failed connect): otherwise a refused connection leaves it silent.
        resumeLocalPlayback(state, play = resumeOnDevice || (failedStart && state.isPlaybackRequested))
    }

    /** The phone is the player again: rebuilds its timeline on the last cast position. */
    private fun resumeLocalPlayback(state: PlaybackState, play: Boolean) {
        if (state.currentTrack == null || state.queueIndex !in state.queue.indices) return
        loadWindowAndPlay(
            tracks = state.queue,
            centerIndex = state.queueIndex,
            forcePlay = play,
            startPositionMs = _positionMs.value
        )
    }

    private fun onCastStatus(status: ChromecastController.CastStatus) {
        if (!chromecast.isCasting) return
        if (status.finished) {
            castLoadingExpected = false
            setLoading(false)
            if (status.queueIndex == castStoppedAt) return
            castStoppedAt = status.queueIndex
            val stoppedAt = status.queueIndex
            val state = _playbackState.value
            if (stoppedAt in state.queue.indices && stoppedAt != state.queueIndex) {
                publishCastTrack(stoppedAt, status.positionMs, emitSeekEvent = false)
            }
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
            scope.launch {
                delay(CAST_STOP_GRACE_MS)
                if (!chromecast.isCasting || castStoppedAt != stoppedAt) return@launch
                Log.d("AudioEngine", "cast stopped at $stoppedAt; reloading from the next track")
                castAdvance(forward = true, forcePlay = true, reload = true)
            }
            return
        }
        castStoppedAt = -1
        if (castLoadingExpected && (status.isPlaying || !status.isPlaybackRequested)) {
            castLoadingExpected = false
        }
        setLoading(
            castLoadingExpected &&
                status.isPlaybackRequested &&
                (status.isBuffering || !status.isPlaying)
        )
        val state = _playbackState.value
        if (status.queueIndex in state.queue.indices && status.queueIndex != state.queueIndex) {
            publishCastTrack(status.queueIndex, status.positionMs, emitSeekEvent = false)
        } else {
            _positionMs.value = status.positionMs
        }
        _playbackState.value = _playbackState.value.copy(
            isPlaying = status.isPlaying,
            isPlaybackRequested = status.isPlaybackRequested
        )
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
     * jump or a gap over [ListenAccounting.MAX_TICK_GAP_MS] adds nothing.
     */
    private fun tickListenCount(displayPlayer: Player, positionMs: Long) {
        val pendingId = listen.pendingTrackId ?: return
        if (!displayPlayer.isPlaying) return
        if (displayPlayer.currentMediaItem?.mediaId?.toLongOrNull() != pendingId) return

        val playedMs = listen.accumulate(positionMs, ::listenThresholdMs) ?: return
        _listenedTrackEvents.tryEmit(ListenedTrackEvent(pendingId, playedMs))
        scope.launch {
            trackRepository.incrementPlayCount(pendingId, System.currentTimeMillis())
        }
    }

    /**
     * Listen accounting while a Chromecast plays: there is no local player to
     * sample, so the receiver's position drives the same accumulator.
     */
    private fun tickListenCountWhileCasting(positionMs: Long) {
        val pendingId = listen.pendingTrackId ?: return
        if (_playbackState.value.currentTrack?.id != pendingId) return

        val playedMs = listen.accumulate(positionMs, ::listenThresholdMs) ?: return
        _listenedTrackEvents.tryEmit(ListenedTrackEvent(pendingId, playedMs))
        scope.launch {
            trackRepository.incrementPlayCount(pendingId, System.currentTimeMillis())
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
                if (configChanged) audioRouter.recompute()
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
                        pruneMissingQueueTracks(allTracks.mapTo(HashSet()) { it.id })
                        if (_playbackState.value.queue.isNotEmpty()) {
                            refillAuxiliary()
                        }
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
        // O(queue) walk: kept off the Main dispatcher (the snapshot is immutable).
        val snapshot = withContext(Dispatchers.Default) { QueueSnapshot.from(state) }
        if (snapshot.trackIds.isEmpty()) return
        hasSessionSaved = true
        queuePersistence.save(
            PersistedPlayback(
                trackIds = snapshot.trackIds,
                sourceFlags = snapshot.sourceFlags,
                currentIndex = snapshot.currentIndex,
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
        listen.clear()
        windowJumpJob?.cancel()
        failedTrackIds.clear()
        suppressLoadingUntilReady = false

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
                isPlaying = false,
                isPlaybackRequested = false,
                isLoading = false,
                queueSourceId = null,
                queueSourceFlags = emptyList()
            )
            audioRouter.recompute()
            return
        }

        val clampedStart = startIndex.coerceIn(0, tracks.lastIndex)

        val entryIds = List(tracks.size) { allocateEntryId() } +
                List(AUXILIARY_QUEUE_LENGTH) { allocateEntryId() }
        val newQueue = tracks + drawAuxiliaryTracks(queueTrackIds(tracks), AUXILIARY_QUEUE_LENGTH)

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
        insertTracks(listOf(track), _playbackState.value.queueIndex + 1)
    }

    /** Appends [track] to the main queue, ahead of the auxiliary pool. */
    fun addToQueue(track: TrackEntity) {
        val current = _playbackState.value
        var index = current.queue.size
        while (index > 0 && current.auxiliaryFlags.getOrNull(index - 1) == true) {
            index--
        }
        insertTracks(listOf(track), index)
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
     * Plays [tracks] from a list pick. The incoming list replaces the current track and every
     * upcoming main-queue track, and becomes the only content of the main queue. Everything
     * before the current track is kept as history but flagged auxiliary, so it no longer
     * belongs to the main queue. The trailing auxiliary pool is kept.
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
        // Trailing auxiliary pool: kept, but never reaching back into the replaced current track.
        var auxStart = current.queue.size
        while (auxStart > current.queueIndex + 1 && flags.getOrNull(auxStart - 1) == true) {
            auxStart--
        }

        // Entries before the current track stay, demoted to the auxiliary queue.
        val historyCount = current.queueIndex
        val tailCount = current.queue.size - auxStart
        val newQueue = current.queue.take(historyCount) + tracks +
                current.queue.subList(auxStart, current.queue.size)

        val newIndex = historyCount + startIndex.coerceIn(0, tracks.lastIndex)
        val newTrack = newQueue[newIndex]

        _playbackState.value = current.copy(
            queue = newQueue,
            queueIndex = newIndex,
            currentTrack = newTrack,
            durationMs = newTrack.duration,
            queueEntryIds = current.queueEntryIds.take(historyCount) +
                    List(tracks.size) { allocateEntryId() } +
                    current.queueEntryIds.subList(auxStart, current.queueEntryIds.size),
            auxiliaryFlags = List(historyCount) { true } +
                    List(tracks.size) { false } +
                    List(tailCount) { true },
            queueSourceId = sourceCollectionId,
            queueSourceFlags = List(historyCount) { false } +
                    List(tracks.size) { sourceCollectionId != null } +
                    List(tailCount) { false }
        )

        jumpWindowTo(newIndex, forcePlay = true)
    }

    /** Inserts [track] at [requestedIndex] and patches the loaded timeline in place. */
    /**
     * Inserts [tracks] at [requestedIndex] (clamped), shifting the playing index and the loaded player
     * window. Single-track inserts (playNext, ddToQueue) go through here too.
     */
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
        forcePlay: Boolean = false,
        startPositionMs: Long = 0L
    ) {
        if (chromecast.isCasting) {
            castLoad(tracks, centerIndex, startPositionMs, play = forcePlay || _playbackState.value.isPlaybackRequested)
            return
        }
        windowJumpJob?.cancel()
        val jumpId = ++windowJumpId
        pendingWindowResync = false
        suppressLoadingUntilReady = false
        val playAfterPrepare = forcePlay || !switchStaysPaused()
        val targetTrack = tracks.getOrNull(centerIndex)
        setLoading(
            playAfterPrepare &&
                targetTrack != null &&
                DistantUris.isDistant(targetTrack.path)
        )

        val windowStart = (centerIndex - WINDOW_RADIUS).coerceAtLeast(0)
        val windowEnd = (centerIndex + WINDOW_RADIUS).coerceAtMost(tracks.lastIndex)

        windowJumpJob = scope.launch {
            val windowItems = withContext(Dispatchers.Default) {
                tracks.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
            }
            if (jumpId != windowJumpId) return@launch
            // Set BEFORE touching the player: ExoPlayer dispatches its listener callbacks
            // synchronously inside setMediaItems, and they resolve queue indices through loadedRange.
            loadedRange = windowStart..windowEnd
            player.setMediaItems(windowItems, centerIndex - windowStart, startPositionMs.coerceAtLeast(0L))
            if (playAfterPrepare) player.play() else player.pause()
            player.prepare()
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
            // The queue can be reordered while a chunk is being built (queue shuffle); a chunk
            // from the old order must not reach the timeline. A later transition re-adjusts.
            fun queueChanged() = _playbackState.value.queue !== tracks

            if (end < tracks.lastIndex && end - currentIndex < EDGE_TRIGGER_MARGIN) {
                val newEnd = (end + CHUNK_SIZE).coerceAtMost(tracks.lastIndex)
                val chunk = withContext(Dispatchers.Default) {
                    tracks.subList(end + 1, newEnd + 1).map { it.toMediaItem() }
                }
                awaitFrame()
                if (queueChanged()) return@withLock
                player.addMediaItems(chunk)
                end = newEnd
                loadedRange = start..end
            }

            if (start > 0 && currentIndex - start < EDGE_TRIGGER_MARGIN) {
                val newStart = (start - CHUNK_SIZE).coerceAtLeast(0)
                val chunk = withContext(Dispatchers.Default) {
                    tracks.subList(newStart, start).map { it.toMediaItem() }
                }
                awaitFrame()
                if (queueChanged()) return@withLock
                player.addMediaItems(0, chunk)
                start = newStart
                loadedRange = start..end
            }

            val overhang = WINDOW_RADIUS + CHUNK_SIZE
            if (currentIndex - start > overhang) {
                val newStart = currentIndex - WINDOW_RADIUS
                val removeCount = newStart - start
                awaitFrame()
                if (queueChanged()) return@withLock
                player.removeMediaItems(0, removeCount)
                start = newStart
                loadedRange = start..end
            }
            if (end - currentIndex > overhang) {
                val newEnd = currentIndex + WINDOW_RADIUS
                val keepCount = newEnd - start + 1
                awaitFrame()
                if (queueChanged()) return@withLock
                player.removeMediaItems(keepCount, player.mediaItemCount)
                end = newEnd
                loadedRange = start..end
            }

            loadedRange = start..end
        }
    }

    fun play() {
        if (chromecast.isCasting) {
            chromecast.play()
            // The receiver takes a moment to actually start (and may rebuffer first):
            // keep the loader up until its first playing status (see onCastStatus)
            // rather than assuming the request took effect.
            castLoadingExpected = true
            setLoading(true)
            _playbackState.value =
                _playbackState.value.copy(isPlaying = true, isPlaybackRequested = true)
            return
        }
        cancelPendingTransitions()
        suppressLoadingUntilReady = false

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
        val resumedTrack = _playbackState.value.currentTrack
        if (resumedTrack != null &&
            DistantUris.isDistant(resumedTrack.path) &&
            !player.isPlaying
        ) {
            playStartPending = true
            setLoading(true)
        }
    }

    fun pause() {
        if (chromecast.isCasting) {
            chromecast.pause()
            castLoadingExpected = false
            setLoading(false)
            _playbackState.value =
                _playbackState.value.copy(isPlaying = false, isPlaybackRequested = false)
            return
        }
        // Mid-crossfade, `player` is still the outgoing stream while the UI shows the incoming one.
        val settledCrossfade = fadeEngine.completeCrossfadeNow()
        cancelSilenceCountdown()
        suppressLoadingUntilReady = false
        setLoading(false)

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
        if (chromecast.isCasting) {
            castAdvance(forward = true)
            return
        }
        cancelPendingTransitions()
        suppressLoadingUntilReady = false
        advanceToNext(useFade = true)
    }

    fun previous() {
        if (chromecast.isCasting) {
            val state = _playbackState.value
            val position = chromecast.positionMs() ?: 0L
            if (position > 5_000L) {
                castPlayIndex(state.queueIndex, 0L, state.isPlaybackRequested, emitSeekEvent = true)
            } else {
                castAdvance(forward = false)
            }
            return
        }
        cancelPendingTransitions()
        suppressLoadingUntilReady = false
        val state = _playbackState.value
        if (state.repeatMode == RepeatMode.ALL &&
            state.queueIndex >= 0 &&
            state.queueIndex == primaryQueueFirstIndex(state) &&
            player.contentPosition <= 5000L
        ) {
            // Repeat-all + shuffle: settle the wrap landing (the queue's last slot) first.
            prepareWrapLanding(forward = false)
            val state = _playbackState.value
            var target = primaryQueueLastIndex(state)
            while (target > state.queueIndex && state.queue[target].id in failedTrackIds) {
                target--
            }
            if (target > state.queueIndex) {
                if (fadeEngine.quickFadeEnabled && player.isPlaying &&
                    fadeEngine.startCrossfade(target, 0L, fadeEngine.quickFadeDurationMs)
                ) {
                    return
                }
                loadWindowAndPlay(state.queue, target)
                return
            }
            // Sole primary track or all of them failed: fall through to the default behavior.
        }
        if (pendingWindowResync && state.queueIndex > 0 && player.contentPosition <= 5000L) {
            // The loaded timeline no longer matches the queue: step along the queue itself.
            loadWindowAndPlay(state.queue, state.queueIndex - 1)
            return
        }
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

        if (chromecast.isCasting) {
            castPlayIndex(
                index = index,
                positionMs = 0L,
                play = _playbackState.value.isPlaybackRequested,
                emitSeekEvent = true
            )
            return
        }

        val state = _playbackState.value
        val primaryFirst = primaryQueueFirstIndex(state)
        val primaryLast = primaryQueueLastIndex(state)
        var target = index
        // Known-failed tracks stepped over while resolving [target]; the player
        // never attempts them, so without this the skip is completely silent.
        val steppedOverFailed = mutableListOf<TrackEntity>()
        if (state.repeatMode == RepeatMode.ALL && primaryFirst < primaryLast && index in primaryFirst..primaryLast) {
            // Repeat-all: the primary queue is a loop, so stepping over failed tracks wraps
            // around its ends instead of leaking into the auxiliary pool.
            var guard = 0
            while (queue[target].id in failedTrackIds && guard++ <= primaryLast - primaryFirst) {
                steppedOverFailed.add(queue[target])
                target = if (forward) {
                    if (target >= primaryLast) primaryFirst else target + 1
                } else {
                    if (target <= primaryFirst) primaryLast else target - 1
                }
            }
            if (queue[target].id in failedTrackIds) target = index
        } else {
            while (target in queue.indices && queue[target].id in failedTrackIds) {
                steppedOverFailed.add(queue[target])
                target += if (forward) 1 else -1
            }
            if (target !in queue.indices) target = index
        }

        steppedOverFailed.distinctBy { it.id }.forEach(::pushTrackUnavailableToast)

        cancelPendingTransitions()
        // A real song switch is a new load; tapping the current entry (a restart) is not.
        if (target != state.queueIndex) suppressLoadingUntilReady = false

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

        if (chromecast.isCasting) {
            castLoad(
                queue = queue,
                index = centerIndex,
                positionMs = 0L,
                play = forcePlay || _playbackState.value.isPlaybackRequested
            )
            return
        }

        windowJumpId++
        windowJumpJob?.cancel()
        pendingWindowResync = false
        suppressLoadingUntilReady = false

        val windowStart = (centerIndex - WINDOW_RADIUS).coerceAtLeast(0)
        val windowEnd = (centerIndex + WINDOW_RADIUS).coerceAtMost(queue.lastIndex)
        val items = queue.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
        val playAfterPrepare = forcePlay || !switchStaysPaused()
        setLoading(playAfterPrepare && DistantUris.isDistant(queue[centerIndex].path))
        loadedRange = windowStart..windowEnd
        player.setMediaItems(items, centerIndex - windowStart, 0L)
        if (playAfterPrepare) player.play() else player.pause()
        player.prepare()
    }

    fun seekTo(positionMs: Long) {
        if (chromecast.isCasting) {
            _positionMs.value = positionMs
            _seekEvents.tryEmit(positionMs)
            chromecast.seekTo(positionMs)
            return
        }
        cancelPendingTransitions()

        // Seeking is not a new-song load: any buffering it causes must stay silent.
        suppressLoadingUntilReady = true

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
        // Repeat-all wrap, decided from the LOGICAL queue position rather than from the
        // player's timeline: on the last primary track, next always restarts the primary
        // queue and never flows on into the auxiliary pool, whatever the window holds.
        if (state.repeatMode == RepeatMode.ALL && state.queueIndex >= 0) {
            val primaryLast = primaryQueueLastIndex(state)
            if (primaryLast >= 0 && state.queueIndex >= primaryLast) {
                wrapToQueueStart(useFade)
                return
            }
        }
        if (pendingWindowResync && state.queueIndex + 1 in state.queue.indices) {
            // The loaded timeline no longer matches the queue: step along the queue itself.
            loadWindowAndPlay(state.queue, state.queueIndex + 1)
            return
        }
        if (player.hasNextMediaItem()) {
            val targetQueueIndex = player.nextMediaItemIndex + loadedRange.first
            // Safety net: a timeline next landing on the first auxiliary entry past
            // the primary queue restarts the queue instead.
            if (state.repeatMode == RepeatMode.ALL &&
                targetQueueIndex == primaryQueueLastIndex(state) + 1 &&
                state.auxiliaryFlags.getOrNull(targetQueueIndex) == true
            ) {
                wrapToQueueStart(useFade)
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
                    nextIndex = primaryQueueFirstIndex(state)
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

    private fun candidateIndexesFor(trackId: Long): List<Int> {
        val queue = _playbackState.value.queue
        var result: ArrayList<Int>? = null
        for (i in queue.indices) {
            if (queue[i].id == trackId) {
                if (result == null) result = ArrayList(2)
                result.add(i)
            }
        }
        return result ?: emptyList()
    }

    /**
     * Manual pick from the queue screen. In repeat-all it just moves the playhead to the
     * entry, keeping the queue order intact; otherwise it moves the entry next and plays
     * it, even over a paused session.
     */
    fun playQueueEntry(entryId: Long) {
        val fromIndex = entryIndexOf(entryId)
        if (fromIndex == -1) return
        if (chromecast.isCasting) {
            castPlayIndex(fromIndex, 0L, play = true, emitSeekEvent = true)
            return
        }
        if (_playbackState.value.repeatMode == RepeatMode.ALL) {
            skipToIndex(fromIndex)
            player.play()
            return
        }
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
     * queue (the pool refills right away). Entries from the played history (repeat-all's
     * full-queue view) are moved there too. The playing entry itself is left alone.
     */
    fun promoteQueueEntry(entryId: Long) {
        val fromIndex = entryIndexOf(entryId)
        if (fromIndex == -1) return
        val current = _playbackState.value
        if (fromIndex == current.queueIndex) return
        val wasAuxiliary = current.auxiliaryFlags.getOrNull(fromIndex) == true
        // moveQueueItem's target is the entry's FINAL index. From behind the playhead,
        // taking the entry out shifts the playing track down one slot, so the first
        // upcoming slot is queueIndex itself, not queueIndex + 1.
        val nextIndex = if (fromIndex < current.queueIndex) {
            current.queueIndex
        } else {
            (current.queueIndex + 1).coerceAtLeast(0)
        }

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
        // Auxiliary slots are being replaced, so only the rest of the queue (history included) counts.
        val replacedAuxiliary = upcomingRange.filter { flags.getOrNull(it) == true }
        val freshAuxiliary = drawAuxiliaryTracks(
            existing = queueTrackIds(queue, replacedAuxiliary.toSet()),
            count = replacedAuxiliary.size
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

        if (newQueue.isEmpty()) audioRouter.recompute()

        if (removedIsAuxiliary) {
            scheduleAuxiliaryRefill()
        }
    }

    /**
     * Drops every queue entry whose track no longer exists in the library (a
     * rescan removed its file), leaving the queue in the order it had.
     *
     * When the entry being played is one of them, playback cannot go on: the
     * queue is cleared instead, which stops the player and closes the mini
     * player (its presence keys off [PlaybackState.currentTrack]).
     */
    private fun pruneMissingQueueTracks(liveTrackIds: Set<Long>) {
        val state = _playbackState.value
        if (state.queue.isEmpty()) return

        val missing = state.queue.indices.filter { state.queue[it].id !in liveTrackIds }
        if (missing.isEmpty()) return

        if (state.queueIndex in missing) {
            setQueue(emptyList())
            return
        }

        // Descending order keeps the indices still to visit valid.
        missing.sortedDescending().forEach { removeQueueItem(it) }
    }

    /**
     * Toggles queue shuffle. Only effective in repeat-all: wrapping past the end (next) or the
     * start (previous) of the main queue reshuffles it (see [prepareWrapLanding]).
     */
    fun toggleShuffle() {
        val newShuffle = !_playbackState.value.isShuffle
        player.shuffleModeEnabled = false
        clearQueueShufflePending()
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
        clearQueueShufflePending()
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
        QueueShuffleMath.normalizeEntryIds(entryIds, queueSize, ::allocateEntryId)

    private fun normalizeAuxiliaryFlags(flags: List<Boolean>, queueSize: Int): List<Boolean> =
        QueueShuffleMath.normalizeFlags(flags, queueSize)

    /**
     * Repeat-all wrap: restarts the primary queue. Plays index 0 through the standard
     * window loader; the transition's listen bookkeeping fires from the player callback.
     */
    private fun wrapToQueueStart(useFade: Boolean = false) {
        // Repeat-all + shuffle: settle the wrap landing (the queue's first slot) first.
        prepareWrapLanding(forward = true)
        val state = _playbackState.value
        if (state.queue.isEmpty()) return
        crossfadeArmedForTrackId = null
        silenceGapArmedForTrackId = null

        // First primary track that did not fail to load (known-failed ones are stepped over).
        val first = primaryQueueFirstIndex(state)
        val last = primaryQueueLastIndex(state)
        var target = first
        while (target < last && state.queue[target].id in failedTrackIds) {
            target++
        }

        if (useFade && fadeEngine.quickFadeEnabled && player.isPlaying &&
            state.queue[target].id !in failedTrackIds &&
            fadeEngine.startCrossfade(target, 0L, fadeEngine.quickFadeDurationMs)
        ) {
            return
        }
        loadWindowAndPlay(state.queue, target)
    }

    /**
     * Queue shuffle, step 1 (repeat-all + shuffle enabled only). When the playing track is on the
     * last primary slot and the next move wraps forward (or on the first slot and it wraps
     * backward), settles which entry the wrap lands on: a random other main entry is swapped into
     * the landing slot (first slot going next, last slot going previous). The playing entry keeps
     * its slot, so nothing visible moves, yet the pager's ghost page already shows the real
     * upcoming track. Safe to call repeatedly; also called by the full player when a swipe starts
     * ([prepareQueueShuffle]). The actual reshuffle follows once the pager rests ([trackLandedForQueueShuffle]).
     */
    private fun prepareWrapLanding(forward: Boolean) {
        val state = _playbackState.value
        if (!state.isShuffle || state.repeatMode != RepeatMode.ALL) return
        if (pendingFadeWindowStart != null) return
        val current = state.queueIndex
        val queue = state.queue
        if (current !in queue.indices) return

        val first = primaryQueueFirstIndex(state)
        val last = primaryQueueLastIndex(state)
        if (last <= first) return
        if (current != (if (forward) last else first)) return

        val entryIds = state.queueEntryIds
        val sourceFlags = state.queueSourceFlags
        if (entryIds.size != queue.size || sourceFlags.size != queue.size) return
        val mark = Triple(entryIds[current], last - first + 1, forward)
        if (preparedWrap == mark) return

        val flags = state.auxiliaryFlags
        val from = QueueShuffleMath.pickRandomPrimarySlot(
            first = first,
            last = last,
            exclude = current,
            isAuxiliary = { flags.getOrNull(it) == true }
        )
        if (from < 0) return
        preparedWrap = mark

        val landingSlot = if (forward) first else last
        postWrapLandingEntryId = entryIds[from]
        if (from == landingSlot) return

        val newQueue = queue.toMutableList()
        val newEntryIds = entryIds.toMutableList()
        val newSourceFlags = sourceFlags.toMutableList()
        newQueue[landingSlot] = queue[from]
        newQueue[from] = queue[landingSlot]
        newEntryIds[landingSlot] = entryIds[from]
        newEntryIds[from] = entryIds[landingSlot]
        newSourceFlags[landingSlot] = sourceFlags[from]
        newSourceFlags[from] = sourceFlags[landingSlot]
        _playbackState.value = state.copy(
            queue = newQueue,
            queueEntryIds = newEntryIds,
            queueSourceFlags = newSourceFlags
        )
        patchTimelineAroundCurrent(newQueue, current)
    }

    /** Full player hook: a swipe started, so the wrap landing is settled before its ghost page is revealed. */
    fun prepareQueueShuffle() {
        val state = _playbackState.value
        val last = primaryQueueLastIndex(state)
        val first = primaryQueueFirstIndex(state)
        when (state.queueIndex) {
            last -> prepareWrapLanding(forward = true)
            first -> prepareWrapLanding(forward = false)
        }
    }

    private fun clearQueueShufflePending() {
        preparedWrap = null
        postWrapLandingEntryId = null
        postWrapLanded = false
        postWrapRestPending = false
    }

    /**
     * Called whenever a track becomes current (natural transition, window load, crossfade switch
     * and swap). Marks the wrap landing as reached; the reshuffle itself waits for the full
     * player's hero pager to come to rest ([onHeroPagerRested]), because reordering earlier would
     * change the outgoing page while it is still sliding away. If the user moves on before that
     * (or the full player isn't showing), the shuffle is applied here instead, keeping both the
     * left and the new track in place.
     */
    private fun trackLandedForQueueShuffle() {
        resolveLandedForQueueShuffle()
        settleBoundaryLandingEarly()
    }

    /**
     * Settles the wrap landing as soon as the playing track sits on a primary boundary slot,
     * instead of waiting for the next swipe to start. The pager's ghost page (and its artwork /
     * palette prefetch) then already targets the real landing track while the user is still
     * listening, so the art is loaded before the swipe reveals it. Skipped while a wrap's own
     * reshuffle is pending, so it can't overwrite that landing entry.
     */
    private fun settleBoundaryLandingEarly() {
        if (postWrapLandingEntryId != null) return
        prepareQueueShuffle()
    }

    private fun resolveLandedForQueueShuffle() {
        val landing = postWrapLandingEntryId ?: return
        val state = _playbackState.value
        val entry = state.queueEntryIds.getOrNull(state.queueIndex)
        if (entry == landing) {
            postWrapLanded = true
            applyShuffleIfPagerRested()
            return
        }
        if (postWrapLanded && entry != null) applyPostWrapShuffle(setOf(landing, entry))
        clearQueueShufflePending()
    }

    /** Full player hook: the hero pager rests on the playing page, so no outgoing page is left to disturb. */
    fun onHeroPagerRested() {
        val landing = postWrapLandingEntryId ?: return
        if (!postWrapLanded) return
        val state = _playbackState.value
        if (state.queueEntryIds.getOrNull(state.queueIndex) != landing) return
        postWrapRestPending = true
        applyShuffleIfPagerRested()
    }

    private fun applyShuffleIfPagerRested() {
        val landing = postWrapLandingEntryId ?: return
        if (!postWrapLanded || !postWrapRestPending) return
        // A crossfade still owns the timeline; the swap hook comes back here.
        if (pendingFadeWindowStart != null) return
        applyPostWrapShuffle(setOf(landing))
        clearQueueShufflePending()
        // The track we rest on may itself be a boundary slot (e.g. just wrapped onto slot 0).
        settleBoundaryLandingEarly()
    }

    /**
     * Queue shuffle, step 2: after a repeat-all wrap the main queue changes order. Entries in
     * [pinned] (the track now playing, and possibly the one just left) keep their slots; every
     * other main entry is shuffled among the primary slots. The auxiliary queue is never touched.
     */
    private fun applyPostWrapShuffle(pinned: Set<Long>) {
        val state = _playbackState.value
        if (pendingFadeWindowStart != null) return
        if (!state.isShuffle || state.repeatMode != RepeatMode.ALL) return
        val current = state.queueIndex
        val queue = state.queue
        val entryIds = state.queueEntryIds
        val sourceFlags = state.queueSourceFlags
        if (current !in queue.indices) return
        if (entryIds.size != queue.size || sourceFlags.size != queue.size) return

        val first = primaryQueueFirstIndex(state)
        val last = primaryQueueLastIndex(state)
        val flags = state.auxiliaryFlags
        // Primitive arrays: boxed List<Int> filter/shuffle was the main cost on queues of
        // thousands of tracks, and this runs on the Main dispatcher.
        val slotBuffer = IntArray((last - first + 1).coerceAtLeast(0))
        var slotCount = 0
        for (i in first..last) {
            if (i != current && flags.getOrNull(i) != true && entryIds[i] !in pinned) {
                slotBuffer[slotCount++] = i
            }
        }
        if (slotCount < 2) return
        val slots = slotBuffer.copyOf(slotCount)
        val order = QueueShuffleMath.shuffledSlots(slots)

        val newQueue = queue.toMutableList()
        val newEntryIds = entryIds.toMutableList()
        val newSourceFlags = sourceFlags.toMutableList()
        for (i in 0 until slotCount) {
            val from = order[i]
            val to = slots[i]
            newQueue[to] = queue[from]
            newEntryIds[to] = entryIds[from]
            newSourceFlags[to] = sourceFlags[from]
        }
        _playbackState.value = state.copy(
            queue = newQueue,
            queueEntryIds = newEntryIds,
            queueSourceFlags = newSourceFlags
        )
        patchTimelineAroundCurrent(newQueue, current)
    }

    /**
     * Rewrites every loaded media item except the playing one from [newQueue], without
     * interrupting playback. Falls back to a lazy window rebuild when the timeline can't be
     * trusted (the player is not on [currentIndex]).
     */
    private fun patchTimelineAroundCurrent(newQueue: List<TrackEntity>, currentIndex: Int) {
        if (pendingWindowResync || loadedRange == IntRange.EMPTY) return
        val currentPlayerIndex = currentIndex - loadedRange.first
        if (currentPlayerIndex !in 0 until player.mediaItemCount ||
            player.currentMediaItemIndex != currentPlayerIndex
        ) {
            windowJumpId++
            windowJumpJob?.cancel()
            pendingWindowResync = true
            return
        }
        val start = loadedRange.first
        val end = loadedRange.last.coerceAtMost(newQueue.lastIndex)
        // Each side is swapped with a single atomic replace: the playing item never leaves the
        // timeline, so listeners (MediaSession) never observe an index outside it.
        val afterCount = player.mediaItemCount - (currentPlayerIndex + 1)
        if (afterCount > 0 && currentIndex + 1 <= end &&
            end - currentIndex == afterCount
        ) {
            player.replaceMediaItems(
                currentPlayerIndex + 1,
                player.mediaItemCount,
                newQueue.subList(currentIndex + 1, end + 1).map { it.toMediaItem() }
            )
        } else if (afterCount > 0) {
            windowJumpId++
            windowJumpJob?.cancel()
            pendingWindowResync = true
            return
        }
        if (currentPlayerIndex > 0) {
            player.replaceMediaItems(
                0,
                currentPlayerIndex,
                newQueue.subList(start, currentIndex).map { it.toMediaItem() }
            )
        }
        loadedRange = start..end
    }

    /**
     * First index of the PRIMARY queue: auxiliary history demoted by a list pick can precede
     * it. Repeat-all wraps here. 0 when the queue has no primary tracks.
     */
    private fun primaryQueueFirstIndex(state: PlaybackState): Int {
        val index = state.auxiliaryFlags.indexOfFirst { !it }
        return if (index >= 0) index else 0
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

    /**
     * Ids of every track in the queue (played history, current track and upcoming entries),
     * optionally ignoring the slots in [ignoredIndices] (e.g. auxiliary slots about to be replaced).
     */
    private fun queueTrackIds(
        queue: List<TrackEntity>,
        ignoredIndices: Set<Int> = emptySet()
    ): Set<Long> {
        val ids = HashSet<Long>(queue.size * 2)
        queue.forEachIndexed { i, track -> if (i !in ignoredIndices) ids += track.id }
        return ids
    }

    /**
     * Draws [count] random tracks. Tracks not in [existing] are used first, each at most once
     * per call. Only when the library has too few such tracks are repeats allowed: the rest is
     * filled by cycling through reshuffled copies of the whole library, so a track repeats
     * only after every other one has been drawn.
     */
    private fun drawAuxiliaryTracks(existing: Collection<Long>, count: Int): List<TrackEntity> {
        val pool = allTracks
        if (pool.isEmpty() || count <= 0) return emptyList()

        val excluded = existing as? Set<Long> ?: existing.toHashSet()
        val draws = ArrayList<TrackEntity>(count)
        draws += pool.filter { it.id !in excluded }.shuffled().take(count)
        while (draws.size < count) {
            draws += pool.shuffled().take(count - draws.size)
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
     * Appends random tracks so the auxiliary pool after the current track is full. Tracks already
     * anywhere in the queue are avoided unless the library is too small.
     */
    private fun refillAuxiliary() {
        val current = _playbackState.value
        val flags = current.auxiliaryFlags
        if (current.queue.isEmpty() || flags.size != current.queue.size) return
        if (hasFullAuxiliaryPool(flags, current.queueIndex)) return

        val needed = AUXILIARY_QUEUE_LENGTH - flags.drop(current.queueIndex + 1).count { it }
        // Whole queue: played history, current track and upcoming entries.
        val additions = drawAuxiliaryTracks(queueTrackIds(current.queue), needed)
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
        prepareWrapLanding(forward = true)
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
                    nextIndex = primaryQueueFirstIndex(state)
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

        prepareWrapLanding(forward = true)
        val state = _playbackState.value
        if (state.repeatMode == RepeatMode.ONE) return

        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (silenceGapArmedForTrackId == trackId) return

        val primaryLast = primaryQueueLastIndex(state)
        val nextIndex = when {
            state.queueIndex + 1 <= primaryLast -> state.queueIndex + 1
            state.repeatMode == RepeatMode.ALL && primaryLast >= 0 -> primaryQueueFirstIndex(state)
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
     * position is dead-reckoned from an anchor that absorbs a share of each fresh sample's error
     * (see [PositionProjector]). Small backward corrections are held rather than published, so the
     * position never visibly steps back. The sampling loop itself lives in [PositionClock].
     */
    private fun startProgressTracker() {
        progressJob?.cancel()
        val clock = PositionClock(
            player = { activePlayerField },
            fadeDisplayPlayer = { fadeEngine.displayPlayer },
            isFading = { fadeEngine.isFading },
            onPosition = { _positionMs.value = it },
            onTick = ::tickListenCount,
            onPrimaryPlayingFrame = { maybeStartTrackCrossfade() },
            awaitFrame = ::awaitFrame,
            castPositionMs = {
                if (chromecast.isCasting) chromecast.positionMs() ?: _positionMs.value else null
            },
            onCastTick = ::tickListenCountWhileCasting
        )
        progressJob = scope.launch { clock.run() }
    }
}