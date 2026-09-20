package com.foamplayer.app.audio.device

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioAttributes as SystemAudioAttributes
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** The device the engine currently routes to, or null when nothing is attached. */
    private val _activeDevice = MutableStateFlow<AudioOutputDevice?>(null)
    val activeDevice: StateFlow<AudioOutputDevice?> = _activeDevice.asStateFlow()

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refresh()
        }
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    /** Re-enumerates all output devices and publishes a new snapshot. */
    fun refresh() {
        val systemDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val resolved = systemDevices
            .filter { it.isSink }
            .map { it.toOutputDevice() }
            .sortedByDescending { it.routingPriority }

        _devices.value = resolved
        _activeDevice.value = resolved.firstOrNull()
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

    companion object {
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
