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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
@OptIn(UnstableApi::class)
class AudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bitPerfectDetector: BitPerfectDetector,
    private val dspEngine: DspEngine,
    private val outputDeviceManager: OutputDeviceManager,
    private val settingsRepository: SettingsRepository,
    private val trackRepository: TrackRepository,
    private val fadeEngine: FadeEngine
) {
    /**
     * ExoPlayer built with a custom audio sink that carries the [DspEngine]'s
     * processor: every decoded PCM buffer flows through the float DSP chain before
     * reaching the AudioTrack. When the DSP state is neutral the processor is a
     * byte-for-byte passthrough, which is what keeps the output bit-perfect.
     *
     * This is the ACTIVE player. A crossfade builds a secondary player that is
     * promoted here at the swap — see [FadeEngine] — so [player] always points at
     * the stream that is actually audible, and the MediaSession is re-bound to it
     * through [onPlayerSwappedListener] (set by [PlaybackService]).
     */
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
                    // Float-capable AudioTrack for float-decoded sources; the DSP
                    // processor itself always emits the stream's original encoding.
                    .setEnableFloatOutput(enableFloatOutput)
                    .build()
        }
        return ExoPlayer.Builder(context, renderersFactory).build()
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    // Kept separate from _playbackState: position ticks every frame, and a shared
    // state object would force full recompositions on every tick. Read positionMs
    // directly where live position is needed (seek bar, lyrics sync).
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    // Emitted on explicit seeks (scrub bar / jump), NOT on frame ticks. Lets
    // observers react to position jumps without subscribing to positionMs,
    // whose subscription would keep the progress tracker in its per-frame
    // polling mode even in background.
    private val _seekEvents = MutableSharedFlow<Long>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val seekEvents: SharedFlow<Long> = _seekEvents.asSharedFlow()

    // Invoked after a crossfade promoted a new player, so the session owner can
    // re-bind the MediaSession (and refresh its notification) without glitches.
    private var onPlayerSwappedListener: (() -> Unit)? = null

    // Session-level audio attributes, re-applied (with focus handling) at every
    // player swap: the secondary player is built WITHOUT focus handling so two
    // overlapping streams never fight over it.
    private val mediaAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // Mirror processor belonging to the active player, when the active player was
    // once a crossfade secondary (its DSP chain is a mirror, not the original).
    private var activeMirrorField: DspAudioProcessor? = null

    private var progressJob: Job? = null

    // Queue indices currently loaded into the player (player index 0 == loadedRange.first).
    // Always a small window, never the whole queue - see adjustWindow().
    // Stale while pendingWindowResync is set: don't seek or patch against it.
    private var loadedRange: IntRange = IntRange.EMPTY

    // Set when a queue move invalidated the player timeline. The rebuild is deferred
    // to the next skip or transition: an async rebuild could land after a tap's seek
    // and reset playback to the cursor ("starts then reverts").
    private var pendingWindowResync: Boolean = false

    // Window of the crossfade's secondary player, recorded at fade start and
    // committed to loadedRange when the swap promotes it.
    private var pendingFadeWindowStart: Int? = null
    private var pendingFadeWindowEnd: Int? = null

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
    // listenedThresholdPercent of its duration. The pending id marks the current
    // play occurrence -- once counted it is cleared, so pause/resume never counts twice.
    private var listenTimerJob: Job? = null
    private var pendingListenTrackId: Long? = null

    // Last known user setting; kept off the settings Flow so the timer path stays
    // synchronous. Seeded with the default until the first snapshot arrives.
    @Volatile
    private var listenedThresholdPercent: Int = AppSettings.DEFAULT_LISTENED_THRESHOLD_PERCENT

    // Latest settings snapshot, read when evaluating the audio output path.
    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    // Last published AudioOutputInfo; used to avoid re-emitting identical values.
    private var lastAudioOutputInfo: AudioOutputInfo? = null

    // Consecutive tracks that failed to load (missing/corrupt file). Reset as soon
    // as a track prepares successfully; past the cap, playback stops instead of
    // spinning through a whole dead queue.
    private var consecutiveLoadErrors: Int = 0

    // Track ids that failed to load, remembered so navigation skips over them in
    // both directions (previous must not land back on a broken track either).
    // Cleared whenever a fresh queue is set.
    private val failedTrackIds = mutableSetOf<Long>()

    // Per-track latch for the auto-crossfade trigger: a failed start attempt
    // (short window, unknown duration) is not retried every frame.
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
        // Max unreadable tracks skipped in a row before playback gives up.
        private const val MAX_CONSECUTIVE_LOAD_ERRORS = 10
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // A track prepared successfully: clear the consecutive-failure cap
            // so later isolated errors can skip again.
            if (playbackState == Player.STATE_READY) {
                consecutiveLoadErrors = 0
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleTrackError(error, failedTrackId = null, syncCursorToPlayer = true)
        }

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
            applyTrackTransition(trackId, positionMs = 0L, emitSeekEvent = false)
        }
    }

    /**
     * The [FadeEngine.Host] bridge: the fade engine asks the queue owner
     * (this class) for windows, ids and volumes, and notifies it at the two
     * stateful moments of a crossfade — start and swap.
     */
    private val fadeHost = object : FadeEngine.Host {
        override val primaryPlayer: ExoPlayer
            get() = activePlayerField

        override fun replacePrimaryPlayer(newPlayer: ExoPlayer, newMirror: DspAudioProcessor?) {
            // The previously promoted mirror (if any) retires together with the
            // player it was attached to; the new one stays registered.
            activeMirrorField?.let { dspEngine.removeMirrorProcessor(it) }
            activeMirrorField = newMirror
            activePlayerField = newPlayer
            // Focus handling transfers to the promoted player: the secondary was
            // built with handleAudioFocus=false so both streams never request it.
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
            // Invalidate in-flight window jumps: the fade's window IS the fresh
            // timeline, same as a loadWindowAndPlay resolving the resync.
            windowJumpId++
            windowJumpJob?.cancel()
            pendingWindowResync = false
            pendingFadeWindowStart = windowStart
            pendingFadeWindowEnd = windowEnd

            // Listen counting restarts for the incoming track, exactly like a
            // natural transition would.
            listenTimerJob?.cancel()
            pendingListenTrackId = targetTrackId
            scheduleListenCount(targetTrackId)

            // Detach the main listener from the outgoing stream: its events
            // (especially the auto-advance transition when the track ends)
            // must not touch queue state mid-fade.
            activePlayerField.removeListener(playerListener)
        }

        override fun onCrossfadeLogicalSwitch(targetQueueIndex: Int, targetTrackId: Long) {
            // Publish the UI-facing switch right now — title/artist/queue index/
            // duration and the seek bar jump to the incoming track — while the
            // outgoing stream is still audibly fading out underneath. The actual
            // player promotion (session rebind, old player release, window
            // commit) still waits for the ramp to finish, in onCrossfadeSwapped.
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

            // The incoming (secondary) player already started at the target
            // position and has been playing since the ramp began: its live
            // position — not a value captured at fade start — is what the
            // switch publishes, so a fade that has already run for a while
            // (the automatic crossfade's midpoint) shows the correct elapsed
            // time instead of resetting to 0.
            val positionMs = fadeEngine.displayPlayer?.currentPosition ?: 0L
            _positionMs.value = positionMs
            _seekEvents.tryEmit(positionMs)

            recomputeAudioOutputInfo()
            if (wasAuxiliary) scheduleAuxiliaryRefill()
        }

        override fun onCrossfadeSwapped(targetQueueIndex: Int, targetPositionMs: Long) {
            // The UI-facing switch (track, queue index, position) already
            // happened at onCrossfadeLogicalSwitch; this only finalizes the
            // underlying player now that the incoming stream is primary.
            consecutiveLoadErrors = 0
            pendingFadeWindowStart?.let { start ->
                loadedRange = start..(pendingFadeWindowEnd ?: start)
            }
            pendingFadeWindowStart = null
            pendingFadeWindowEnd = null

            // Re-apply session-level modes: toggles landing mid-fade on the old
            // player would otherwise be lost at the swap.
            player.repeatMode = when (_playbackState.value.repeatMode) {
                RepeatMode.NONE -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
            player.shuffleModeEnabled = _playbackState.value.isShuffle

            activePlayerField.addListener(playerListener)

            // No queue mutation can land mid-fade (every mutator cancels the
            // fade first), so pendingWindowResync is guaranteed false here;
            // the window around the new current index just needs adjusting.
            scope.launch { adjustWindow() }
        }

        override fun onCrossfadeAborted(logicalSwitchHadFired: Boolean) {
            pendingFadeWindowStart = null
            pendingFadeWindowEnd = null
            crossfadeArmedForTrackId = null

            // Re-attach the main listener to the (still playing) old stream.
            // Idempotent even if it was never detached (plain quick fades never
            // remove it), since removing first rules out a duplicate add.
            activePlayerField.removeListener(playerListener)
            activePlayerField.addListener(playerListener)

            if (logicalSwitchHadFired) {
                // The UI had already switched to the (now aborted) incoming
                // track: revert it back to the outgoing stream, which is the
                // one still actually playing.
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

            // The incoming track never became current: resume listen counting
            // on the old one (same semantics as a pause/resume cycle).
            val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
            if (trackId != null) {
                pendingListenTrackId = trackId
                if (player.isPlaying) scheduleListenCount(trackId)
            }

            _playbackState.value = _playbackState.value.copy(isPlaying = player.isPlaying)
        }

        override fun onFadeTargetError(targetTrackId: Long) {
            // The incoming track failed mid-fade: restore the single old stream,
            // then run the natural error-advance path for the failed target.
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
        startAudioPathObservers()
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

    /** Re-bind hook for the MediaSession (set by [PlaybackService]). */
    fun setOnPlayerSwappedListener(listener: (() -> Unit)?) {
        onPlayerSwappedListener = listener
    }

    /**
     * Shared bookkeeping for a track becoming current — used by the natural
     * [Player.Listener.onMediaItemTransition] and by the crossfade swap, so the
     * queue advance after a fade is identical to a natural transition
     * (repeat/shuffle respected, listen counting restarted, output re-evaluated).
     */
    private fun applyTrackTransition(
        trackId: Long,
        positionMs: Long,
        emitSeekEvent: Boolean,
        fallbackIndex: Int? = null
    ) {
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
        val index = resolveExactQueueIndex(trackId) ?: fallbackIndex ?: return
        val track = currentQueue[index]

        // A played auxiliary track becomes a main track: unflag it,
        // then refill the pool with fresh random picks.
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

        // The new track's format may hit the output path differently
        // (rate/depth support, direct capability): re-evaluate.
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
     * Shared error handling for a failed track — used by the natural
     * [Player.Listener.onPlayerError] and by a fade whose incoming track
     * failed to prepare. Never breaks the transition: it skips onward.
     */
    private fun handleTrackError(
        error: PlaybackException?,
        failedTrackId: Long?,
        syncCursorToPlayer: Boolean
    ) {
        val trackId = failedTrackId
            ?: player.currentMediaItem?.mediaId?.toLongOrNull()
        val failedTrack = trackId?.let { id ->
            _playbackState.value.queue.firstOrNull { it.id == id }
        }
        Log.w(
            "AudioEngine",
            "Playback error (${error?.errorCodeName ?: "unknown"}) on '${failedTrack?.path ?: "unknown"}'; skipping"
        )

        // Keep the cursor in sync with the player's actual item: the
        // transition callback may not have run for the failed track. When a
        // FADE's target failed, the cursor is already correct (still on the
        // outgoing track) and must not be moved onto the failed id.
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
            // Too many dead tracks in a row (e.g. a deleted folder):
            // stop rather than race through the rest of the queue.
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

        // Move to the next item. After an error the player sits in
        // STATE_IDLE: a seek alone doesn't resume, the new item must be
        // prepared (and playback re-requested) explicitly.
        // The error path never crossfades: the old player is idle/dead, and
        // fading into the same item would briefly double-play it.
        advanceToNext(useFade = false)
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
            player.play()
        }
    }

    /**
     * Re-evaluates the whole audio output path (bit-perfect verdict, fidelity score,
     * pipeline snapshot) from the current track, device, settings and DSP state, and
     * publishes it into [PlaybackState.audioOutputInfo] when anything changed.
     */
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

    /**
     * Keeps every input of the audio-path evaluation observed: the DSP state (also
     * drives the direct-volume behavior), the active output device, and the user
     * settings (bit-perfect switch, per-device configs). Any change re-evaluates
     * [PlaybackState.audioOutputInfo].
     */
    private fun startAudioPathObservers() {
        outputDeviceManager.start()

        // Media attributes: proper routing for the sink (media usage, music content)
        // and focus handling, which the default player construction left unset.
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
     * Direct volume control (Poweramp-style): gain is applied inside the float DSP
     * chain and the AudioTrack stays at full scale — no AudioFlinger re-quantization.
     * When disabled, the DSP gain is bypassed and the platform mixer does the scaling.
     *
     * Skipped while a fade is active: the ramp owns the volume for its duration and
     * re-reads the base every step, so a DSP change mid-fade is picked up by the ramp
     * instead of stomping it.
     */
    private fun applyVolumePath(state: DspState) {
        if (fadeEngine.isFading) return
        player.volume = if (state.directVolumeControl) {
            1f
        } else {
            state.volumePercent / 100f
        }
    }

    /**
     * Counts [trackId] as listened once it survives [listenedThresholdPercent] of
     * the track's duration. The check at fire time guards against a pause or a
     * transition landing right on the threshold.
     */
    private fun scheduleListenCount(trackId: Long) {
        listenTimerJob?.cancel()
        listenTimerJob = scope.launch {
            delay(listenThresholdMs(trackId))
            if (player.isPlaying && player.currentMediaItem?.mediaId?.toLongOrNull() == trackId) {
                pendingListenTrackId = null
                trackRepository.incrementPlayCount(trackId, System.currentTimeMillis())
            }
        }
    }

    /**
     * Playback time after which [trackId] counts as listened: the user-configured
     * percentage of the track's duration, taken from the queue (falling back to the
     * player's timeline, then to an immediate count when no duration is known).
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
            // One-shot snapshot + refresh on completed library scans. Collecting the
            // live getAllTracks() Flow here re-emitted the whole table on every row
            // write -- including once per played track (incrementPlayCount invalidates
            // the table), re-materializing a potentially multi-MB list and re-running
            // the O(library) auxiliary refill roughly every song. Stat-only writes
            // (play count, score, instrumental flag) cannot affect pool selection, and
            // every library-content change goes through a scan, which finishes with
            // ScanStatus.Finished.
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

    fun setQueue(tracks: List<TrackEntity>, startIndex: Int = 0, sourceCollectionId: String? = null) {
        // Queue reset invalidates any in-flight fade (and its secondary player).
        fadeEngine.cancel()
        crossfadeArmedForTrackId = null
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
                queueSourceId = null,
                queueSourceFlags = emptyList()
            )
            recomputeAudioOutputInfo()
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
            auxiliaryFlags = List(entryIds.size) { i -> i >= tracks.size },
            queueSourceId = sourceCollectionId,
            queueSourceFlags = List(tracks.size) { sourceCollectionId != null } +
                    List(AUXILIARY_QUEUE_LENGTH) { false }
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
     * Inserts [tracks] right after the current track ("play next"), keeping
     * their order. With no queue active, starts a fresh queue with these
     * tracks (playing the first one).
     */
    fun playNextAll(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        val current = _playbackState.value
        if (current.queue.isEmpty()) {
            setQueue(tracks, 0)
            return
        }
        insertTracks(tracks, current.queueIndex + 1)
    }

    /**
     * Appends [tracks] to the end of the MAIN queue, keeping their order —
     * never behind the random auxiliary pool (same boundary rule as
     * [addToQueue]). With no queue active, starts a fresh queue with these
     * tracks (playing the first one).
     */
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
     * Plays [tracks] picked from a list (library, playlist, search results, ...):
     * keeps the played history up to and including the current track, drops the
     * upcoming main-queue tracks, queues [tracks] in their place and jumps to
     * [startIndex]. The auxiliary pool is left untouched, so no refill is needed.
     * [sourceCollectionId], when set, marks the queue as started from that
     * collection ([PlaybackState.queueSourceId]); a bare track list passes null.
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

        // The upcoming jump lands on a fresh window: any in-flight fade must go.
        fadeEngine.cancel()

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
                    flags.subList(auxStart, flags.size),
            queueSourceId = sourceCollectionId,
            // The re-stamped source's section starts here: entries kept as
            // history belong to a past occurrence and are never highlighted.
            queueSourceFlags = List(keptCount) { false } +
                    List(tracks.size) { sourceCollectionId != null } +
                    List(current.queue.size - auxStart) { false }
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
        // A queue mutation shifts the indices a fade captured: cancel cleanly first.
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

        // Inserted tracks are always main tracks, never part of the auxiliary pool.
        val newFlags = current.auxiliaryFlags.toMutableList().apply {
            add(insertIndex.coerceAtMost(size), false)
        }

        // ...and never part of the source collection's section either.
        val newSourceFlags = current.queueSourceFlags.toMutableList().apply {
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
            auxiliaryFlags = newFlags,
            queueSourceFlags = newSourceFlags
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
     * Bulk variant of [insertTrack] for a contiguous [tracks] block at [requestedIndex].
     * Publishing one [PlaybackState] and issuing one [player.addMediaItems] call
     * instead of per-track loops keeps "play next N" / "add all to queue" O(n) instead
     * of O(n²) list copies, N state emissions (each a recomposition of every queue UI
     * collector) and N player timeline-change notifications.
     */
    private fun insertTracks(tracks: List<TrackEntity>, requestedIndex: Int) {
        if (tracks.isEmpty()) return

        // A queue mutation shifts the indices a fade captured: cancel cleanly first.
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

        // Inserted tracks are always main tracks, never part of the auxiliary pool...
        val newFlags = current.auxiliaryFlags.toMutableList().apply {
            addAll(insertIndex.coerceAtMost(size), List(count) { false })
        }

        // ...and never part of the source collection's section either.
        val newSourceFlags = current.queueSourceFlags.toMutableList().apply {
            addAll(insertIndex.coerceAtMost(size), List(count) { false })
        }

        // Keep the cursor on the same track it was on: an insert at or before
        // it shifts every later index (including the cursor's) by the block size.
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
            // Before the window: queue indices shift, the player timeline doesn't.
            insertIndex < window.first ->
                loadedRange = (window.first + count)..(window.last + count)
            // At or inside the window: slot the block into the player at the same
            // relative position (same edge rules as the single-item insert).
            insertIndex <= window.last + 1 -> {
                player.addMediaItems(insertIndex - window.first, tracks.map { it.toMediaItem() })
                loadedRange = window.first..(window.last + count)
            }
            // Further past the window: nothing loaded needs to shift;
            // adjustWindow() loads the items as playback approaches.
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

    fun play() {
        // Any in-flight quick fade (e.g. a fade-out-to-pause the user changed
        // their mind about) is cancelled: volume restored, playback continues.
        fadeEngine.cancel()

        // After a playback error the player stays in STATE_IDLE: playWhenReady
        // alone does nothing there, the timeline must be prepared again.
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        if (fadeEngine.quickFadeEnabled && !player.isPlaying) {
            // Start from silence and ramp up: no click at the resume point.
            fadeEngine.fadeIn()
            player.play()
        } else {
            player.play()
        }
        // Publish last: the play/pause button reacts to the tap immediately,
        // regardless of what fadeEngine.cancel() published on its way through.
        _playbackState.value = _playbackState.value.copy(isPlaying = true)
    }

    fun pause() {
        if (fadeEngine.quickFadeEnabled && player.isPlaying) {
            // Ramp to silence over the quick fade, THEN actually pause — but the
            // button and listen counting react to the tap instantly rather than
            // waiting for the ramp. Publish last: fadeOutAndPause() may itself
            // cancel a prior fade on its way in, which would otherwise stomp
            // this with a stale "still playing" state.
            fadeEngine.fadeOutAndPause()
            listenTimerJob?.cancel()
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
        } else {
            player.pause()
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
                // Only change track if we're within the first 5 seconds.
                // TODO: Make this an app parameter.
                val targetQueueIndex = player.previousMediaItemIndex + loadedRange.first
                val targetId = state.queue.getOrNull(targetQueueIndex)?.id
                if (fadeEngine.quickFadeEnabled && targetId != null && targetId !in failedTrackIds &&
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
                if (fadeEngine.quickFadeEnabled &&
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
                if (fadeEngine.quickFadeEnabled &&
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

    fun skipToIndex(index: Int, forward: Boolean = true) {
        val queue = _playbackState.value.queue
        if (index !in queue.indices) return

        // Never land on a track known to have failed to load: walk in the skip
        // direction to the nearest playable one. If that direction is exhausted,
        // fall back to the original index (the error handler will skip onward).
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

        // Manual skips always crossfade when quick fades are on — the fade
        // builds its own window, so the target needs no loaded timeline entry.
        val targetId = queue.getOrNull(target)?.id
        if (fadeEngine.quickFadeEnabled && targetId != null && targetId !in failedTrackIds &&
            fadeEngine.startCrossfade(target, 0L, fadeEngine.quickFadeDurationMs)
        ) {
            return
        }

        if (target in loadedRange) {
            player.seekTo(target - loadedRange.first, 0L)
            return
        }

        jumpWindowTo(target)
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
        // Deliberately synchronous (unlike loadWindowAndPlay): this must override any
        // in-flight jump atomically, and the ~31-item window maps in low single-digit
        // milliseconds.
        val items = queue.subList(windowStart, windowEnd + 1).map { it.toMediaItem() }
        loadedRange = windowStart..windowEnd
        player.setMediaItems(items, centerIndex - windowStart, 0L)
        player.prepare()
        player.play()
    }

    fun seekTo(positionMs: Long) {
        fadeEngine.cancel()

        // The seek bar jumps to the target right away, whether or not a
        // same-track crossfade smooths the audio transition underneath —
        // startCrossfade's instant logical switch publishes the same value a
        // moment later, so this is never contradicted, only redundant.
        // Only the dedicated position flow is updated here. Publishing position into
        // _playbackState used to emit a new PlaybackState per drag pixel of a seek,
        // recomposing every collector of playbackState (FoamApp, FoamNavHost,
        // MiniPlayer, FullPlayerScreen) on each move; nothing reads it from there.
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

    /**
     * Advances to the next item, crossfading when [useFade] is set and quick
     * fades are enabled. The error path passes false (a dead stream has nothing
     * to fade from, and fading into the same item would double-play it).
     */
    private fun advanceToNext(useFade: Boolean) {
        val state = _playbackState.value
        if (player.hasNextMediaItem()) {
            if (useFade) {
                val targetQueueIndex = player.nextMediaItemIndex + loadedRange.first
                val targetId = state.queue.getOrNull(targetQueueIndex)?.id
                if (fadeEngine.quickFadeEnabled && targetId != null && targetId !in failedTrackIds &&
                    fadeEngine.startCrossfade(targetQueueIndex, 0L, fadeEngine.quickFadeDurationMs)
                ) {
                    return
                }
            }
            player.seekToNextMediaItem()
        } else {
            // Next track not loaded, load a window
            val nextIndex = state.queueIndex + 1
            if (nextIndex in state.queue.indices) {
                if (useFade) {
                    val targetId = state.queue[nextIndex].id
                    if (fadeEngine.quickFadeEnabled && targetId !in failedTrackIds &&
                        fadeEngine.startCrossfade(nextIndex, 0L, fadeEngine.quickFadeDurationMs)
                    ) {
                        return
                    }
                }
                loadWindowAndPlay(state.queue, nextIndex)
            }
        }
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

        // A queue mutation shifts the indices a fade captured: cancel cleanly first.
        fadeEngine.cancel()

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

        // A queue mutation shifts the indices a fade captured: cancel cleanly first.
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

        // Remove the flag with the item, the pool refills below if needed
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

    /** Falls back to all-unflagged if the source flag list doesn't match the queue size. */
    private fun normalizeQueueSourceFlags(flags: List<Boolean>, queueSize: Int): List<Boolean> =
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
            auxiliaryFlags = flags + List(additions.size) { true },
            queueSourceFlags = current.queueSourceFlags + List(additions.size) { false }
        )
    }

    /**
     * Feature 1: crossfade at a natural track transition. Called by the progress
     * tracker while the current track is close to its end — polling catches the
     * window early; onMediaItemTransition only fires when the track is over.
     *
     * Skips the crossfade for REPEAT_MODE_ONE (the track loops seamlessly),
     * when the queue has no next track (natural end), and when the next track
     * is known to be broken (the error path handles the advance).
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
            // The automatic crossfade switches the UI at the ramp's midpoint,
            // not at its end: e.g. a 10s crossfade hands the next track over
            // 5s before the old one finishes, already 5s into itself.
            switchAtT = 0.5f
        )
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var lastEmitted = Long.MIN_VALUE
            while (true) {
                // Before a fade's logical switch, the outgoing (primary) player
                // is what's being shown; from the switch onward, the incoming
                // stream is — even though it isn't promoted to primary until the
                // ramp ends. Either way the seek bar and timer keep ticking
                // through the whole fade instead of freezing.
                val displayPlayer = fadeEngine.displayPlayer ?: player
                val position = displayPlayer.currentPosition
                // Skip redundant emissions: while paused (and between track gaps) the
                // position rarely changes, and re-emitting an identical value still
                // wakes every collector (seek bars, lyrics interpolation) for nothing.
                if (position != lastEmitted) {
                    lastEmitted = position
                    _positionMs.value = position
                }
                // Arming the next automatic crossfade is always evaluated against
                // the real primary player, regardless of what's being displayed.
                if (player.isPlaying) {
                    maybeStartTrackCrossfade()
                }
                if (displayPlayer.isPlaying) {
                    if (_positionMs.subscriptionCount.value > 0) {
                        // UI is listening: tick per rendered frame so the seek bar and
                        // karaoke lyrics stay frame-smooth.
                        awaitFrame()
                    } else {
                        // Playing with no UI listening (e.g. background playback): a
                        // low-rate poll keeps the flow fresh without frame-rate wakeups.
                        delay(POSITION_POLL_SLOW_INTERVAL_MS)
                    }
                } else {
                    // Keep position in sync while paused (catches external seeks)
                    delay(PAUSED_POLL_INTERVAL_MS)
                }
            }
        }
    }
}