package com.foamplayer.app.audio.device

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioAttributes as SystemAudioAttributes
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** How the engine should treat a specific output device for bit-perfect delivery. */
enum class DeviceBitPerfectMode(val label: String) {
    AUTO("Auto"),
    FORCE_ON("Force on"),
    FORCE_OFF("Force off")
}

/** User configuration stored per output device (keyed by [AudioOutputDevice.id]). */
data class DeviceAudioConfig(
    val bitPerfectMode: DeviceBitPerfectMode = DeviceBitPerfectMode.AUTO,
    /** Preferred output sample rate in Hz; 0 = follow the source (no forced shift). */
    val sampleRateOverrideHz: Int = 0
) {
    companion object {
        val DEFAULT = DeviceAudioConfig()
    }
}

/**
 * A single audio output endpoint the app can route to, with the properties the
 * fidelity evaluation and the pipeline view display.
 */
data class AudioOutputDevice(
    /** Stable identifier used as the per-device settings key. */
    val id: String,
    val name: String,
    val typeLabel: String,
    val type: Int,
    val isUsb: Boolean,
    val isWired: Boolean,
    val isBluetooth: Boolean,
    val isSpeaker: Boolean,
    /** Highest PCM sample rate the platform reports as direct-playback capable. */
    val maxSupportedSampleRate: Int,
    /** True when direct (bypass-mixer) PCM playback is supported at all. */
    val supportsDirectPlayback: Boolean
) {
    val isHiResCapable: Boolean get() = maxSupportedSampleRate >= 88_200
}

/** A plug/unplug snapshot, emitted after the device list has been refreshed. */
sealed interface AudioDeviceChange {
    /** [devices] were attached by this change (built-in speakers excluded by consumers as needed). */
    data class Connected(val devices: List<AudioOutputDevice>) : AudioDeviceChange

    /**
     * [devices] detached. [wasRoutingDevice] is true when one of them was the
     * routing target before the change, i.e. the audible output actually moved.
     */
    data class Disconnected(
        val devices: List<AudioOutputDevice>,
        val wasRoutingDevice: Boolean
    ) : AudioDeviceChange
}

/**
 * Owns the view of the system's output devices: enumerates every active output,
 * follows plug/unplug events through [AudioManager.registerAudioDeviceCallback],
 * and exposes the device audio currently routes to (first of USB, wired, Bluetooth,
 * speaker, anything else).
 */
class OutputDeviceManager(
    private val audioManager: AudioManager
) {
    private val _devices = MutableStateFlow<List<AudioOutputDevice>>(emptyList())
    val devices: StateFlow<List<AudioOutputDevice>> = _devices.asStateFlow()

    /** Plug/unplug events, deduplicated against the previous device snapshot. */
    private val _deviceChanges = MutableSharedFlow<AudioDeviceChange>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val deviceChanges: SharedFlow<AudioDeviceChange> = _deviceChanges.asSharedFlow()

    /** The device the engine currently routes to, or null when nothing is attached. */
    private val _activeDevice = MutableStateFlow<AudioOutputDevice?>(null)
    val activeDevice: StateFlow<AudioOutputDevice?> = _activeDevice.asStateFlow()

    /**
     * User-picked output override: the [AudioOutputDevice.id] to route playback to,
     * [ThisDevicePickId] for the phone's own local outputs, or null for "no
     * explicit pick" (fresh start: the platform's default routing). When the
     * picked device detaches the override falls back to null automatically.
     */
    private val _preferredDeviceId = MutableStateFlow<String?>(null)
    val preferredDeviceId: StateFlow<String?> = _preferredDeviceId.asStateFlow()

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshAndNotify()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshAndNotify()
        }
    }

    private var started = false

    /** False until [start]'s first enumeration lands, so the launch snapshot is not a "connected" event. */
    private var initialRefreshComplete = false

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refresh()
        initialRefreshComplete = true
    }

    fun stop() {
        if (!started) return
        started = false
        initialRefreshComplete = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    /**
     * Routes playback to [deviceId] (a concrete attached device, or the virtual
     * "this device" target [ThisDevicePickId]). Returns false when no attached
     * device matches (the override is not kept in that case, so callers can
     * reflect it in their UI).
     *
     * The actual re-route happens in the engine, which resolves the pick to a live
     * [AudioDeviceInfo] and hands it to the active player's audio sink.
     */
    fun setPreferredDevice(deviceId: String): Boolean {
        val exists = deviceId == ThisDevicePickId || _devices.value.any { it.id == deviceId }
        if (!exists) return false
        if (_preferredDeviceId.value == deviceId) return false // no-op pick, nothing changed
        _preferredDeviceId.value = deviceId
        return true
    }

    /** True once the user has made an explicit "Play on" pick (this device counts). */
    val hasExplicitPick: Boolean get() = _preferredDeviceId.value != null

    /**
     * The currently-resolved routing target: the user's preferred device when it
     * is still attached, the system-default active device otherwise. Null when
     * nothing is attached at all. Drives the fidelity/info display.
     */
    val routingDevice: AudioOutputDevice?
        get() {
            val preferredId = _preferredDeviceId.value
            val byPreference = _devices.value.firstOrNull { it.id == preferredId }
            if (preferredId != null && byPreference != null) return byPreference
            return _activeDevice.value
        }

    /**
     * What the "Play on" sheet should SHOW as selected: the routing target above,
     * folded back to sheet ids. A concrete BT pick shows as that device; the best
     * local output always shows as "this device" (the virtual pick that resolves
     * to it), so with no explicit pick the checkmark sits on where audio actually
     * plays (BT attached => the BT row, not "this device").
     */
    val selectedPlayOnDeviceId: StateFlow<String?> =
        combine(_preferredDeviceId, _devices, _activeDevice) { preferredId, devices, active ->
            when {
                preferredId == null -> active?.takeIf { it.isBluetooth }?.id ?: ThisDevicePickId
                preferredId == ThisDevicePickId -> ThisDevicePickId
                else -> devices.firstOrNull { it.id == preferredId }?.id ?: ThisDevicePickId
            }
        }.stateIn(CoroutineScope(Dispatchers.Default + SupervisorJob()), SharingStarted.Eagerly, ThisDevicePickId)

    /**
     * The best LOCAL output of this phone (USB > wired > speaker): what "this
     * device" means. Bluetooth endpoints are excluded -- they are separate
     * "Play on" targets, not the phone itself.
     */
    val localDevice: AudioOutputDevice?
        get() = _devices.value
            .filter { !it.isBluetooth }
            .maxByOrNull { it.routingPriority }

    /**
     * The live [AudioDeviceInfo] whose [AudioOutputDevice.id] matches [deviceId]
     * (same "$type:$address:$name" hash as [toOutputDevice]), or null when it has
     * detached since. The platform types can't be cached -- they are only valid
     * while the device is attached -- so the engine resolves through here at
     * apply time.
     */
    fun systemDeviceInfoForId(deviceId: String): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.toOutputDevice().id == deviceId }

    /** Re-enumerates all output devices and publishes a new snapshot. */
    fun refresh() {
        val systemDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val resolved = systemDevices
            .filter { it.isSink }
            .map { it.toOutputDevice() }
            .sortedByDescending { it.routingPriority }

        _devices.value = resolved
        _activeDevice.value = resolved.firstOrNull()
        val pick = _preferredDeviceId.value
        if (pick != null && pick != ThisDevicePickId &&
            resolved.none { it.id == pick }
        ) {
            _preferredDeviceId.value = null
        }
    }

    /**
     * Refreshes after a system callback and emits what actually changed, with
     * the routing target captured beforehand so consumers can tell whether the
     * audible output was moved.
     */
    private fun refreshAndNotify() {
        val previousDevices = _devices.value
        val previousRoutingId = routingDevice?.id
        refresh()

        // The callback registration can announce the already-attached devices
        // before start()'s initial enumeration has settled: never treat the
        // launch snapshot as a fresh connection.
        if (!initialRefreshComplete) return

        val previousIds = previousDevices.mapTo(HashSet()) { it.id }
        val currentIds = _devices.value.mapTo(HashSet()) { it.id }
        val added = _devices.value.filter { it.id !in previousIds }
        val removed = previousDevices.filter { it.id !in currentIds }

        if (added.isNotEmpty()) _deviceChanges.tryEmit(AudioDeviceChange.Connected(added))
        if (removed.isNotEmpty()) {
            _deviceChanges.tryEmit(
                AudioDeviceChange.Disconnected(
                    devices = removed,
                    wasRoutingDevice = previousRoutingId != null &&
                        removed.any { it.id == previousRoutingId }
                )
            )
        }
    }

    private val AudioOutputDevice.routingPriority: Int
        get() = when {
            isUsb -> 5
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> 4
            isBluetooth -> 3
            isSpeaker -> 2
            else -> 1
        }

    private fun AudioDeviceInfo.toOutputDevice(): AudioOutputDevice {
        val productName = productName?.toString().orEmpty().ifEmpty { "Audio device" }
        val address = if (Build.VERSION.SDK_INT >= 28) address else ""

        // AudioDeviceInfo does not expose its supported sample rates, so the highest
        // rate AudioTrack accepts as a direct stream is used as the capability bound.
        var maxRate = 0
        for (rate in PROBE_SAMPLE_RATES) {
            if (isDirectPlaybackSupported(rate)) {
                maxRate = rate
                break
            }
        }
        val supportsDirect = maxRate > 0

        return AudioOutputDevice(
            id = "$type:$address:$productName".hashCode().toString(),
            name = productName,
            typeLabel = typeLabel(type),
            type = type,
            isUsb = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY,
            isWired = type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET,
            isBluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            isSpeaker = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            maxSupportedSampleRate = maxRate,
            supportsDirectPlayback = supportsDirect
        )
    }

    private fun AudioDeviceInfo.isDirectPlaybackSupported(sampleRate: Int): Boolean =
        try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val attributes = SystemAudioAttributes.Builder()
                .setUsage(SystemAudioAttributes.USAGE_MEDIA)
                .setContentType(SystemAudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            AudioTrack.isDirectPlaybackSupported(format, attributes)
        } catch (e: Exception) {
            false
        }

    /**
     * The music stream volume as 0..100, i.e. the level the hardware keys move.
     * With a Bluetooth output attached the platform keeps that level bound to the
     * headset, which is why the phone-speaker override in the engine seeds from it.
     */
    fun musicVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (current * 100 / max).coerceIn(0, 100)
    }

    companion object {
        /** Virtual "play on this phone" target id (never a real AudioOutputDevice id). */
        const val ThisDevicePickId = "this_device"

        private val PROBE_SAMPLE_RATES = listOf(192_000, 96_000, 88_200, 48_000, 44_100)

        fun typeLabel(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB DAC"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (A2DP)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (SCO)"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Device Speaker"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            else -> "Audio Output"
        }

        /** The system mixer rate reported by the platform (drives SRC detection). */
        fun systemMixerSampleRate(audioManager: AudioManager): Int =
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
                ?: 48_000
    }
}
