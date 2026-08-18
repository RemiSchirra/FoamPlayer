package com.foamplayer.app.audio.bitperfect

data class AudioOutputInfo(
    val deviceName: String,
    val deviceType: String,
    val sourceSampleRate: Int,
    val sourceBitDepth: Int,
    val outputSampleRate: Int,
    val outputBitDepth: Int,
    val isDirectPlayback: Boolean,
    val isBitPerfectMode: Boolean,
    val fallbackReason: String? = null
)