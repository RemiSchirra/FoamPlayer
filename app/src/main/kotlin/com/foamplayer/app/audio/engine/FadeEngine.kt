package com.foamplayer.app.audio.engine

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.foamplayer.app.audio.dsp.DspAudioProcessor
import com.foamplayer.app.audio.dsp.DspEngine
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every audio ramp of the app: crossfades between tracks and quick fades
 * around play/pause.
 *
 * Crossfade lifecycle: [startCrossfade] builds a secondary player with its own
 * DSP mirror processor and starts it at volume 0; a ramp loop steps both
 * players' volume with equal-power curves (re-reading the base volume every
 * step). At the switch point the UI switches to the incoming stream
 * ([Host.onCrossfadeLogicalSwitch]); at t == 1 the secondary is promoted
 * ([Host.replacePrimaryPlayer]) and the old player released. Any queue
 * mutation calls [cancel], restoring the old stream and destroying the
 * secondary — exactly one stream keeps playing.
 */
@Singleton
class FadeEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dspEngine: DspEngine,
    private val settingsRepository: SettingsRepository
) {

    /** Host bridge: the queue/player owner (AudioEngine) answers fade-time questions. */
    interface Host {
        val primaryPlayer: ExoPlayer

        /** Promotes [newPlayer] (its mirror processor must STAY registered) and re-binds the session. */
        fun replacePrimaryPlayer(newPlayer: ExoPlayer, newMirror: DspAudioProcessor?)

        /** Media items of a full window around [centerQueueIndex], plus the target's player index. */
        fun buildFadeWindow(centerQueueIndex: Int): FadeWindow?

        fun trackIdAt(queueIndex: Int): Long?

        /** Called synchronously at fade start, before any ramp step runs. */
        fun onCrossfadeStarting(
            windowStart: Int,
            windowEnd: Int,
            targetQueueIndex: Int,
            targetTrackId: Long
        )

        /**
         * The fade's UI-facing switch point: track/position switch to the
         * incoming stream now. Quick fades fire this synchronously at start;
         * the automatic crossfade fires it at the ramp's midpoint.
         */
        fun onCrossfadeLogicalSwitch(targetQueueIndex: Int, targetTrackId: Long)

        /**
         * Ramp complete, incoming player promoted: finalize player-level
         * plumbing. The UI switch already happened at [onCrossfadeLogicalSwitch].
         */
        fun onCrossfadeSwapped(targetQueueIndex: Int, targetPositionMs: Long)

        /**
         * Called after an abort/cancel. [logicalSwitchHadFired] is true when the
         * UI had already switched and must revert to the outgoing track.
         */
        fun onCrossfadeAborted(logicalSwitchHadFired: Boolean)

        /** The incoming track failed to prepare/play: run the engine's error-advance path. */
        fun onFadeTargetError(targetTrackId: Long)

        /** Volume a player carries when NOT fading (DSP direct-volume path aware). */
        fun playerBaseVolume(): Float
    }

    data class FadeWindow(
        val items: List<MediaItem>,
        val targetPlayerIndex: Int,
        val windowStart: Int,
        val windowEnd: Int
    )

    private var host: Host? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // ------------------------------------------------------------- fade state

    private var fadeJob: Job? = null
    private var secondary: ExoPlayer? = null
    private var secondaryMirror: DspAudioProcessor? = null
    private var secondaryListener: Player.Listener? = null
    private var oldPlayerListener: Player.Listener? = null
    private var rampDurationMs: Long = 0L
    private var rampStartedAtNanos: Long = 0L
    private var activeFadeIsCrossfade: Boolean = false

    // Target of the running crossfade, kept so it can be completed early (see completeCrossfadeNow).
    private var crossfadeTargetIndex: Int = -1
    private var crossfadeTargetTrackId: Long = -1L
    private var crossfadeTargetPositionMs: Long = 0L

    // Ramp progress at which the logical switch fires; 0 for quick fades, 0.5 for auto.
    private var switchAtT: Float = 0f
    private var logicalSwitchFired: Boolean = false

    /** True from fade start until the swap (or cancel) completes. */
    @Volatile
    var isFading: Boolean = false
        private set

    /**
     * Player representing "now playing" for UI purposes: the incoming stream
     * once its logical switch fired, null otherwise (callers fall back to primary).
     */
    val displayPlayer: ExoPlayer?
        get() = if (isFading && logicalSwitchFired) secondary else null

    @Volatile
    private var normalFadeMs: Long = 0L

    @Volatile
    private var quickFadeMs: Long = 0L

    // ------------------------------------------------------------ public API

    val normalFadeEnabled: Boolean get() = normalFadeMs > 0L
    val quickFadeEnabled: Boolean get() = quickFadeMs > 0L
    val normalFadeDurationMs: Long get() = normalFadeMs
    val quickFadeDurationMs: Long get() = quickFadeMs

    fun bind(host: Host) {
        this.host = host
    }

    init {
        scope.launch {
            settingsRepository.settings.collect { settings ->
                normalFadeMs = (settings.normalFadeSeconds.coerceIn(
                    AppSettings.MIN_NORMAL_FADE_SECONDS,
                    AppSettings.MAX_NORMAL_FADE_SECONDS
                ) * 1000).toLong()
                quickFadeMs = (settings.quickFadeSeconds.coerceIn(
                    AppSettings.MIN_QUICK_FADE_SECONDS,
                    AppSettings.MAX_QUICK_FADE_SECONDS
                ) * 1000).toLong()
                // An in-flight ramp keeps its captured duration; only future fades see the change.
            }
        }
    }

    // ------------------------------------------------------- fade operations

    /**
     * Crossfades the current player into [targetQueueIndex] at [targetPositionMs].
     * Returns false when a fade is already running or the target can't be prepared.
     *
     * [switchAtT] is the ramp progress (0..1) at which the UI switch fires:
     * 0 (default) for instant response on user-initiated fades, 0.5 for the
     * automatic normal crossfade.
     */
    fun startCrossfade(
        targetQueueIndex: Int,
        targetPositionMs: Long,
        durationMs: Long,
        switchAtT: Float = 0f
    ): Boolean {
        val h = host ?: return false
        if (isFading) return false
        if (durationMs <= 0L) return false

        val old = h.primaryPlayer
        val window = h.buildFadeWindow(targetQueueIndex) ?: return false
        if (window.items.isEmpty()) return false
        val targetTrackId = h.trackIdAt(targetQueueIndex) ?: return false

        val (newPlayer, mirror) = buildSecondaryPlayer() ?: return false
        newPlayer.repeatMode = old.repeatMode
        newPlayer.shuffleModeEnabled = old.shuffleModeEnabled
        newPlayer.setMediaItems(window.items, window.targetPlayerIndex, targetPositionMs)
        newPlayer.volume = 0f
        newPlayer.prepare()
        newPlayer.play()

        isFading = true
        activeFadeIsCrossfade = true
        crossfadeTargetIndex = targetQueueIndex
        crossfadeTargetTrackId = targetTrackId
        crossfadeTargetPositionMs = targetPositionMs
        rampDurationMs = durationMs
        rampStartedAtNanos = System.nanoTime()
        secondary = newPlayer
        secondaryMirror = mirror
        this.switchAtT = switchAtT.coerceIn(0f, 1f)
        logicalSwitchFired = false

        secondaryListener = object : Player.Listener {
            override fun onPlayerError(event: PlaybackException) {
                // Incoming track failed mid-fade: hand over to the engine's error path.
                scope.launch { h.onFadeTargetError(targetTrackId) }
            }
        }
        newPlayer.addListener(secondaryListener!!)

        oldPlayerListener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Old stream advanced mid-fade: stop it, only the incoming stream plays on.
                old.stop()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) old.stop()
            }

            override fun onPlayerError(event: PlaybackException) {
                // Outgoing track died mid-fade: finish the transition now.
                scope.launch { finishCrossfadeNow(targetQueueIndex, targetTrackId, targetPositionMs) }
            }
        }
        old.addListener(oldPlayerListener!!)

        h.onCrossfadeStarting(window.windowStart, window.windowEnd, targetQueueIndex, targetTrackId)

        if (this.switchAtT <= 0f) {
            logicalSwitchFired = true
            h.onCrossfadeLogicalSwitch(targetQueueIndex, targetTrackId)
        }

        fadeJob = scope.launch {
            try {
                while (true) {
                    val t = elapsedT()
                    if (!logicalSwitchFired && t >= this@FadeEngine.switchAtT) {
                        logicalSwitchFired = true
                        h.onCrossfadeLogicalSwitch(targetQueueIndex, targetTrackId)
                    }
                    if (t >= 1f) break
                    val base = h.playerBaseVolume()
                    old.volume = base * FadeMath.fadeOutGain(t)
                    newPlayer.volume = base * FadeMath.fadeInGain(t)
                    delay(RAMP_STEP_MS)
                }
                // Land exactly on the end values — the loop can miss the last step.
                old.volume = 0f
                newPlayer.volume = h.playerBaseVolume()
                finishCrossfadeNow(targetQueueIndex, targetTrackId, targetPositionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Fade ramp failed; cancelling fade", e)
                cancel()
            }
        }
        return true
    }

    /** Quick fade: ramp to silence, then pause. Cancelled mid-ramp = playback continues. */
    fun fadeOutAndPause() {
        if (isFading) cancel()
        val h = host ?: return
        val player = h.primaryPlayer
        if (quickFadeMs <= 0L) {
            player.pause()
            return
        }
        isFading = true
        activeFadeIsCrossfade = false
        rampDurationMs = quickFadeMs
        rampStartedAtNanos = System.nanoTime()
        val startVolume = player.volume
        fadeJob = scope.launch {
            try {
                while (true) {
                    val t = elapsedT()
                    if (t >= 1f) break
                    player.volume = startVolume * FadeMath.fadeOutGain(t)
                    delay(RAMP_STEP_MS)
                }
                player.pause()
                player.volume = h.playerBaseVolume()
                isFading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                player.pause()
                player.volume = h.playerBaseVolume()
                isFading = false
            }
        }
    }

    /** Quick fade: ramp from silence up to base volume. Caller must have muted and started playback. */
    fun fadeIn() {
        if (isFading) cancel()
        val h = host ?: return
        val player = h.primaryPlayer
        if (quickFadeMs <= 0L) return
        isFading = true
        activeFadeIsCrossfade = false
        rampDurationMs = quickFadeMs
        rampStartedAtNanos = System.nanoTime()
        player.volume = 0f
        fadeJob = scope.launch {
            try {
                while (true) {
                    val t = elapsedT()
                    if (t >= 1f) break
                    player.volume = h.playerBaseVolume() * FadeMath.fadeInGain(t)
                    delay(RAMP_STEP_MS)
                }
                player.volume = h.playerBaseVolume()
                isFading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                player.volume = h.playerBaseVolume()
                isFading = false
            }
        }
    }

    /**
     * Cancels the active fade: restores the old stream's volume, destroys the
     * secondary and re-syncs the host. No-op when nothing is fading; safe to
     * call from the ramp's own failure handler.
     */
    fun cancel() {
        if (!isFading) return
        val h = host ?: run {
            isFading = false
            return
        }
        // Only a crossfade has a UI-facing logical switch to potentially revert.
        val switchHadFired = activeFadeIsCrossfade && logicalSwitchFired
        isFading = false
        fadeJob?.cancel()
        fadeJob = null
        restoreAfterAbort(h, switchHadFired)
    }

    /** True while a crossfade (not a quick fade-in/out) is running. */
    val isCrossfading: Boolean get() = isFading && activeFadeIsCrossfade

    /**
     * Jumps to the end of a running crossfade: promotes the incoming stream and releases the
     * outgoing one. Volumes are left where the ramp put them, so there's no audible jump;
     * the caller owns restoring the base volume afterwards. Returns true if a crossfade
     * was completed.
     */
    fun completeCrossfadeNow(): Boolean {
        if (!isCrossfading) return false
        finishCrossfadeNow(crossfadeTargetIndex, crossfadeTargetTrackId, crossfadeTargetPositionMs)
        return true
    }

    // ----------------------------------------------------------------- swap

    /**
     * Completes a crossfade: promotes the secondary, releases the old player.
     * Idempotent (guarded by [isFading]) so the ramp's completion and an
     * old-player error cannot both swap.
     */
    private fun finishCrossfadeNow(targetQueueIndex: Int, targetTrackId: Long, targetPositionMs: Long) {
        if (!isFading) return
        val h = host ?: return
        val newPlayer = secondary ?: return
        if (!logicalSwitchFired) {
            // Outgoing player died before the switch point: catch the UI up now.
            logicalSwitchFired = true
            h.onCrossfadeLogicalSwitch(targetQueueIndex, targetTrackId)
        }
        isFading = false
        fadeJob?.cancel()
        fadeJob = null
        val old = h.primaryPlayer
        detachFadeListeners(old)
        old.stop()
        old.release()
        secondary = null
        activeFadeIsCrossfade = false
        // The promoted mirror stays registered — it's now the active player's chain.
        h.replacePrimaryPlayer(newPlayer, secondaryMirror)
        secondaryMirror = null
        logicalSwitchFired = false
        h.onCrossfadeSwapped(targetQueueIndex, targetPositionMs)
    }

    /** Shared cleanup for [cancel]: single-stream restore + host resync. */
    private fun restoreAfterAbort(h: Host, logicalSwitchHadFired: Boolean) {
        val old = h.primaryPlayer
        detachFadeListeners(old)
        old.volume = h.playerBaseVolume()
        secondary?.let { sec ->
            sec.stop()
            sec.release()
            secondaryMirror?.let { dspEngine.removeMirrorProcessor(it) }
        }
        secondary = null
        secondaryListener = null
        secondaryMirror = null
        activeFadeIsCrossfade = false
        logicalSwitchFired = false
        h.onCrossfadeAborted(logicalSwitchHadFired)
    }

    private fun detachFadeListeners(oldPlayer: ExoPlayer) {
        oldPlayerListener?.let { oldPlayer.removeListener(it) }
        oldPlayerListener = null
        secondaryListener?.let { listener -> secondary?.removeListener(listener) }
        secondaryListener = null
    }

    // -------------------------------------------------------------- plumbing

    private fun elapsedT(): Float {
        val elapsedMs = (System.nanoTime() - rampStartedAtNanos) / 1_000_000.0
        return (elapsedMs / rampDurationMs.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Builds a secondary player with its own DSP mirror processor. No audio
     * focus handling — the promoted player gains it at the swap.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(UnstableApi::class)
    private fun buildSecondaryPlayer(): Pair<ExoPlayer, DspAudioProcessor>? {
        val mirror = dspEngine.createMirrorProcessor()
        return try {
            val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? =
                    DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(mirror))
                        .setEnableFloatOutput(enableFloatOutput)
                        .build()
            }
            val player = ExoPlayer.Builder(context, renderersFactory).build()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false
            )
            player to mirror
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build secondary player", e)
            dspEngine.removeMirrorProcessor(mirror)
            null
        }
    }

    private companion object {
        const val TAG = "FadeEngine"

        /** Volume ramp resolution; 20 ms is smooth and cheap on the main thread. */
        const val RAMP_STEP_MS = 20L
    }
}