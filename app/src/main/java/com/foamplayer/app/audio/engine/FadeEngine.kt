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
 * Owns every audio ramp of the app: crossfades between tracks (a secondary
 * ExoPlayer fades in while the outgoing one fades out) and quick fades around
 * play/pause.
 *
 * Lifecycle of a crossfade:
 *  1. [startCrossfade] builds a secondary player (same renderers, its own DSP
 *     mirror processor so both streams get identical tone/volume treatment),
 *     loads a queue window around the target track and starts it at volume 0
 *     while the current player keeps playing.
 *  2. A ramp loop on the main thread steps both players' volume every
 *     [RAMP_STEP_MS]: outgoing * equal-power cos(t), incoming * equal-power
 *     sin(t). The base (non-fade) volume is re-read every step, so DSP volume
 *     changes during a fade are tracked.
 *  3. At the fade's switch point (t == 0 for a quick/manual fade, t == 0.5 for
 *     the automatic normal crossfade) [Host.onCrossfadeLogicalSwitch] fires:
 *     the UI-facing track/position switches to the incoming stream right then,
 *     while both players are still audible.
 *  4. At t == 1 the secondary is promoted to primary ([Host.replacePrimaryPlayer]
 *     re-binds the MediaSession and swaps AudioEngine's active player), the old
 *     player is released, and [Host.onCrossfadeSwapped] finalizes the player-level
 *     plumbing (the UI switch already happened in step 3).
 *
 * Every fade is cancellable: any queue mutation calls [cancel], which kills the
 * ramp, restores the old stream's volume, destroys the secondary (mirror
 * processor included) and lets the host resync its state — exactly one stream
 * keeps playing, no stuck secondary, no double audio.
 *
 * Durations come from the settings Flow. A duration change mid-fade never
 * corrupts an ongoing ramp: the ramp captures its duration at start and only
 * future fades see the new value.
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

        /**
         * Promotes [newPlayer] (carrying [newMirror], its DSP mirror processor
         * which must STAY registered) to the active player and re-binds the
         * MediaSession/notification to it.
         */
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
         * The fade's "logical" switch point: the UI-facing track (title, artist,
         * queue index, duration, seek bar position) should switch to
         * [targetQueueIndex]/[targetTrackId] now, even though the incoming
         * player is only promoted to primary later, at [onCrossfadeSwapped].
         *
         * Fires once per fade. For a quick/manual fade (play-pause resume,
         * seek, skip) this is called synchronously at fade start, so the UI
         * reacts to the tap instantly. For the automatic normal crossfade it
         * is called mid-ramp, at the fade's switch point (see [startCrossfade]),
         * matching how far the incoming stream has already played into itself.
         */
        fun onCrossfadeLogicalSwitch(targetQueueIndex: Int, targetTrackId: Long)

        /**
         * Called once the ramp completes and the incoming player has been
         * promoted to primary: finalize player-level plumbing (window/loadedRange,
         * repeat/shuffle re-apply, listener re-attach). The UI-facing track switch
         * already happened at [onCrossfadeLogicalSwitch].
         */
        fun onCrossfadeSwapped(targetQueueIndex: Int, targetPositionMs: Long)

        /**
         * Called after an abort/cancel: the old stream is the only one left.
         * [logicalSwitchHadFired] is true when [onCrossfadeLogicalSwitch] had
         * already published the incoming track to the UI before the abort — the
         * host must revert its UI-facing state back to the (still playing)
         * outgoing track.
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

    // Ramp progress (0..1) at which the active crossfade's logical switch
    // fires (see Host.onCrossfadeLogicalSwitch). 0 for quick/manual fades
    // (instant); 0.5 for the automatic normal crossfade.
    private var switchAtT: Float = 0f
    private var logicalSwitchFired: Boolean = false

    /** True from fade start until the swap (or cancel) completes. */
    @Volatile
    var isFading: Boolean = false
        private set

    /**
     * The player whose position/track currently represents "now playing" for
     * UI purposes. Null when nothing is fading, or when a crossfade's logical
     * switch hasn't fired yet (the outgoing/primary player is still the one to
     * show). Once the switch fires, this is the incoming stream — even though
     * it is only promoted to primary at the end of the ramp. Callers fall back
     * to the primary player when this is null.
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
     * Crossfade the current player into [targetQueueIndex] (position 0 unless
     * [targetPositionMs] says otherwise — the same-track seek path). Returns
     * false when a fade is already running or the target cannot be prepared.
     *
     * [switchAtT] is the ramp progress (0..1) at which the UI-facing track
     * switch fires (see [Host.onCrossfadeLogicalSwitch]). Defaults to 0: every
     * user-initiated fade (skip, seek, resume) switches the UI instantly. The
     * automatic normal crossfade passes 0.5 so the UI switches at the ramp's
     * midpoint instead.
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
        rampDurationMs = durationMs
        rampStartedAtNanos = System.nanoTime()
        secondary = newPlayer
        secondaryMirror = mirror
        this.switchAtT = switchAtT.coerceIn(0f, 1f)
        logicalSwitchFired = false

        secondaryListener = object : Player.Listener {
            override fun onPlayerError(event: PlaybackException) {
                // The incoming track failed mid-fade: hand over to the engine's
                // error path (cancel + advance) instead of leaving a stuck ramp.
                scope.launch { h.onFadeTargetError(targetTrackId) }
            }
        }
        newPlayer.addListener(secondaryListener!!)

        oldPlayerListener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Timing jitter: the old stream advanced to its next item mid-fade.
                // Stop it so only the incoming stream keeps playing.
                old.stop()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) old.stop()
            }

            override fun onPlayerError(event: PlaybackException) {
                // The outgoing track died mid-fade: finish the transition now.
                scope.launch { finishCrossfadeNow(targetQueueIndex, targetTrackId, targetPositionMs) }
            }
        }
        old.addListener(oldPlayerListener!!)

        h.onCrossfadeStarting(window.windowStart, window.windowEnd, targetQueueIndex, targetTrackId)

        // Instant fades (switchAtT <= 0) publish the UI switch right away,
        // before the ramp even starts stepping.
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

    /**
     * Quick fade: ramp the current player to silence, THEN pause. If cancelled
     * mid-ramp (the user pressed play again), playback simply continues.
     */
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

    /**
     * Quick fade: ramp the current player from silence up to the base volume.
     * The caller must already have muted the player and started playback.
     */
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
     * Cancels the active fade, whichever kind it is: restores the old stream's
     * volume, destroys the secondary (crossfades) and re-syncs the host. Safe
     * to call when nothing is fading (no-op) and from inside the ramp's own
     * failure handler (cancelling the current job from within its catch block
     * still lets the non-suspending cleanup below run).
     */
    fun cancel() {
        if (!isFading) return
        val h = host ?: run {
            isFading = false
            return
        }
        // Only a crossfade has a UI-facing logical switch to potentially revert;
        // a plain quick fade (fade-out-to-pause/fade-in) never fires one.
        val switchHadFired = activeFadeIsCrossfade && logicalSwitchFired
        isFading = false
        fadeJob?.cancel()
        fadeJob = null
        restoreAfterAbort(h, switchHadFired)
    }

    // ----------------------------------------------------------------- swap

    /**
     * Completes a crossfade: promotes the secondary, releases the old player
     * and re-publishes state. Idempotent (guarded by [isFading]) so the ramp's
     * natural completion and an old-player error cannot both swap.
     */
    private fun finishCrossfadeNow(targetQueueIndex: Int, targetTrackId: Long, targetPositionMs: Long) {
        if (!isFading) return
        val h = host ?: return
        val newPlayer = secondary ?: return
        if (!logicalSwitchFired) {
            // The outgoing player died before the ramp reached its switch
            // point (only possible for a mid-ramp switchAtT): catch the UI up
            // now, immediately before promoting the incoming stream, so it
            // never ends up representing a track the UI never switched to.
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
        // The promoted player's mirror processor STAYS registered with the DSP
        // engine — it is now the active player's chain. The retiring player's
        // mirror (if the old primary was itself once a secondary) is dropped by
        // the host in replacePrimaryPlayer.
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
     * Builds a secondary player identical to the primary (same renderers
     * factory shape) but with its own DSP mirror processor. Audio focus is NOT
     * handled here — the promoted player gains it at the swap, so both streams
     * never fight over focus.
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

        /** Volume ramp resolution. 20 ms is smooth enough and cheap on the main thread. */
        const val RAMP_STEP_MS = 20L
    }
}