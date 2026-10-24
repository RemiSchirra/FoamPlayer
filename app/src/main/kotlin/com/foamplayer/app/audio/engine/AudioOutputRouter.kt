package com.foamplayer.app.audio.engine

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.foamplayer.app.audio.bitperfect.AudioOutputInfo
import com.foamplayer.app.audio.bitperfect.AudioStreamInfo
import com.foamplayer.app.audio.bitperfect.BitPerfectDetector
import com.foamplayer.app.audio.device.OutputDeviceManager
import com.foamplayer.app.audio.dsp.DspEngine
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.distant.DistantRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.distant.DistantUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One hardware volume-key press on the phone speaker while Bluetooth owns the stream volume. */
private const val PHONE_VOLUME_STEP = 0.05f

/**
 * Where the phone-speaker gain starts: high enough that a silenced headset does not
 * leave the phone inaudible, low enough to always leave room for "volume up"
 * (the speaker's own index is unreachable from an app).
 */
private const val PHONE_VOLUME_SEED_MIN = 0.2f
private const val PHONE_VOLUME_SEED_MAX = 0.9f

/**
 * Owns the audio output path: the bit-perfect verdict published to the UI, the "Play on" device
 * pinning, and the volume path.
 *
 * Split out of [AudioEngine] because routing is a self-contained subsystem: it observes the DSP,
 * device and settings flows, resolves the transport facts of the playing track, and only talks
 * back through [onOutputInfo] / [onPlaybackResumed]. Player access goes through
 * [playerProvider] so a crossfade swap is picked up without re-creating the router.
 */
@OptIn(UnstableApi::class)
internal class AudioOutputRouter(
    private val playerProvider: () -> ExoPlayer,
    private val fadeEngine: FadeEngine,
    private val outputDeviceManager: OutputDeviceManager,
    private val dspEngine: DspEngine,
    private val bitPerfectDetector: BitPerfectDetector,
    private val settingsRepository: SettingsRepository,
    private val distantRepository: DistantRepository,
    private val mediaAudioAttributes: AudioAttributes,
    private val scope: CoroutineScope,
    private val currentTrack: () -> TrackEntity?,
    private val latestSettings: () -> AppSettings,
    private val onOutputInfo: (AudioOutputInfo?) -> Unit,
    private val onPlaybackResumed: () -> Unit
) {
    private var lastAudioOutputInfo: AudioOutputInfo? = null

    /** Extra gain for the phone's own output while a Bluetooth one is attached. */
    private var phoneSpeakerGain = 1f

    /** True once [phoneSpeakerGain] was seeded for the current pinned-over-Bluetooth state. */
    private var phoneSpeakerGainSeeded = false

    fun start() {
        outputDeviceManager.start()

        playerProvider().setAudioAttributes(mediaAudioAttributes, /* handleAudioFocus = */ true)

        scope.launch {
            dspEngine.state.collect { state ->
                applyVolumePath(state)
                recompute()
            }
        }
        scope.launch {
            outputDeviceManager.activeDevice.collect {
                recompute()
            }
        }
        scope.launch {
            val persisted = settingsRepository.settings.first().preferredOutputDeviceId
            if (persisted != null &&
                outputDeviceManager.preferredDeviceId.value == null
            ) {
                // "Play on this device" must not outlive a Bluetooth output the
                // phone connected on its own (headset already paired at launch):
                // the sheet and the audio follow the headset instead.
                val attachedBluetooth = outputDeviceManager.devices.value.firstOrNull { it.isBluetooth }
                if (persisted == OutputDeviceManager.ThisDevicePickId && attachedBluetooth != null) {
                    outputDeviceManager.setPreferredDevice(attachedBluetooth.id)
                    settingsRepository.setPreferredOutputDeviceId(attachedBluetooth.id)
                } else {
                    outputDeviceManager.setPreferredDevice(persisted)
                }
            }
        }
        scope.launch {
            var knownIds = emptySet<String>()
            outputDeviceManager.devices.collect { devices ->
                val attachedIds = devices.mapTo(HashSet()) { it.id }
                // A Bluetooth output that just attached (headphones switched on,
                // car kit in range) takes over from a "this device" pin, so the
                // "Play on" sheet shows where the sound actually goes.
                val connected = devices.firstOrNull { it.isBluetooth && it.id !in knownIds }
                knownIds = attachedIds
                if (connected != null &&
                    outputDeviceManager.preferredDeviceId.value == OutputDeviceManager.ThisDevicePickId
                ) {
                    outputDeviceManager.setPreferredDevice(connected.id)
                    settingsRepository.setPreferredOutputDeviceId(connected.id)
                }
                applyPreferredAudioDevice()
                recompute()
            }
        }
        scope.launch {
            outputDeviceManager.preferredDeviceId.collect {
                applyPreferredAudioDevice()
                recompute()
            }
        }
    }

    /** Re-evaluates the output path and publishes it when it changed. */
    fun recompute() {
        val track = currentTrack()
        if (track == null) {
            if (lastAudioOutputInfo != null) {
                lastAudioOutputInfo = null
                onOutputInfo(null)
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
                masterBitPerfectEnabled = latestSettings().bitPerfectEnabled,
                isStreaming = DistantUris.isDistant(track.path),
                stream = streamInfoFor(track),
                deviceConfig = latestSettings().deviceAudioConfig(outputDeviceManager.activeDevice.value?.id)
            )
        )
        if (info != lastAudioOutputInfo) {
            lastAudioOutputInfo = info
            onOutputInfo(info)
        }
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
            recompute()
            return false
        }
        applyPreferredAudioDevice()
        scope.launch { settingsRepository.setPreferredOutputDeviceId(deviceId) }
        recompute()

        val player = playerProvider()
        if (player.playbackState != Player.STATE_IDLE) {
            val resume = player.playWhenReady
            val positionMs = player.currentPosition
            player.stop()
            player.seekTo(positionMs)
            player.prepare()
            if (resume) {
                player.play()
                onPlaybackResumed()
            }
        }
        return true
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
        applyTo(playerProvider())
        // Mid-crossfade the AUDIBLE stream is the not-yet-promoted secondary: pin
        // it too, or the incoming stream stays on the platform's default route.
        fadeEngine.activeSecondaryPlayer?.let { applyTo(it) }
        syncPhoneSpeakerGain()
    }

    /**
     * True when the phone's own output is the routing target while a Bluetooth
     * output is attached. Android keeps the music stream volume bound to the
     * attached headset in that case, so the hardware keys would raise the (silent)
     * headset and leave the phone's speaker untouched; the engine moves
     * [phoneOutputGain] instead. Every other combination is left to the platform.
     */
    fun localOutputIsPhoneOverBluetooth(): Boolean {
        val pinnedId = outputDeviceManager.preferredDeviceId.value ?: return false
        val pinned = if (pinnedId == OutputDeviceManager.ThisDevicePickId) {
            outputDeviceManager.localDevice
        } else {
            outputDeviceManager.devices.value.firstOrNull { it.id == pinnedId }
        } ?: return false
        if (pinned.isBluetooth) return false
        return outputDeviceManager.devices.value.any { it.isBluetooth }
    }

    /** The gain currently applied on top of the DSP volume (1 when the platform owns the volume). */
    val phoneOutputGain: Float
        get() = if (localOutputIsPhoneOverBluetooth()) phoneSpeakerGain else 1f

    /**
     * Moves the phone speaker's volume by [steps] key presses of [PHONE_VOLUME_STEP].
     * Returns false when this output is not the pinned-over-Bluetooth case, i.e.
     * when the platform adjusts the right device itself.
     */
    fun adjustPhoneOutputVolume(steps: Int): Boolean {
        if (!localOutputIsPhoneOverBluetooth()) return false
        syncPhoneSpeakerGain()
        phoneSpeakerGain = (phoneSpeakerGain + steps * PHONE_VOLUME_STEP).coerceIn(0f, 1f)
        applyVolumePath(dspEngine.state.value)
        return true
    }

    /**
     * Seeds/clears the phone-speaker gain when the routing target changes. The
     * phone speaker's own volume knob cannot be addressed while the headset owns
     * the stream volume, so the gain starts where that volume currently sits
     * (with headroom upward) and reverts to unity as soon as the headset becomes
     * the target again, so no hidden attenuation survives the switch.
     */
    private fun syncPhoneSpeakerGain() {
        if (!localOutputIsPhoneOverBluetooth()) {
            if (phoneSpeakerGainSeeded || phoneSpeakerGain != 1f) {
                phoneSpeakerGainSeeded = false
                phoneSpeakerGain = 1f
                applyVolumePath(dspEngine.state.value)
            }
            return
        }
        if (phoneSpeakerGainSeeded) return
        phoneSpeakerGainSeeded = true
        phoneSpeakerGain = (outputDeviceManager.musicVolumePercent() / 100f)
            .coerceIn(PHONE_VOLUME_SEED_MIN, PHONE_VOLUME_SEED_MAX)
        applyVolumePath(dspEngine.state.value)
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
    fun applyTo(target: ExoPlayer) {
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
     * With direct volume control the gain is applied in the float DSP chain and the AudioTrack
     * stays at full scale. Skipped during a fade, which reads the base volume itself.
     */
    private fun applyVolumePath(state: DspState) {
        if (fadeEngine.isFading) return
        val dspGain = if (state.directVolumeControl) 1f else state.volumePercent / 100f
        playerProvider().volume = dspGain * phoneOutputGain
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
}
