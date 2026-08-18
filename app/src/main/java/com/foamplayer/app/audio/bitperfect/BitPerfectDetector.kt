package com.foamplayer.app.audio.bitperfect

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BitPerfectDetector @Inject constructor(
    private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun evaluateOutput(
        sourceSampleRate: Int,
        sourceBitDepth: Int,
        isEqualizerActive: Boolean
    ): AudioOutputInfo {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val primaryDevice = devices.firstOrNull { it.isSink } ?: devices.firstOrNull()

        val deviceName = primaryDevice?.productName?.toString() ?: "Internal Audio Engine"
        val deviceType = getDeviceTypeString(primaryDevice?.type ?: AudioDeviceInfo.TYPE_UNKNOWN)

        if (isEqualizerActive) {
            return AudioOutputInfo(
                deviceName = deviceName,
                deviceType = deviceType,
                sourceSampleRate = sourceSampleRate,
                sourceBitDepth = sourceBitDepth,
                outputSampleRate = sourceSampleRate,
                outputBitDepth = 16,
                isDirectPlayback = false,
                isBitPerfectMode = false,
                fallbackReason = "Equalizer/DSP active (Bypasses bit-perfect hardware path)"
            )
        }

        val encoding = when (sourceBitDepth) {
            24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            32 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val isDirectSupported = checkDirectPlayback(sourceSampleRate, encoding)

        val isBitPerfect = isDirectSupported && (primaryDevice?.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                primaryDevice?.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                primaryDevice?.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES)

        return AudioOutputInfo(
            deviceName = deviceName,
            deviceType = deviceType,
            sourceSampleRate = sourceSampleRate,
            sourceBitDepth = sourceBitDepth,
            outputSampleRate = if (isBitPerfect) sourceSampleRate else 48000,
            outputBitDepth = if (isBitPerfect) sourceBitDepth else 16,
            isDirectPlayback = isDirectSupported,
            isBitPerfectMode = isBitPerfect,
            fallbackReason = if (!isBitPerfect) "AudioFlinger SRC or mixer transformation forced" else null
        )
    }

    private fun checkDirectPlayback(sampleRate: Int, encoding: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            return AudioTrack.isDirectPlaybackSupported(format, attributes)
        }
        return false
    }

    private fun getDeviceTypeString(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "External USB DAC"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Analogue Headphones (3.5mm)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio (A2DP)"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Internal Device Speaker"
            else -> "Standard Audio Endpoint"
        }
    }
}