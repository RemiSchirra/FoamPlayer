package com.foamplayer.app.audio.bitperfect

import com.foamplayer.app.audio.fidelity.FidelityScore
import com.foamplayer.app.audio.pipeline.AudioPipeline

/**
 * Everything known about the active audio path for the track currently playing.
 * Published into [com.foamplayer.app.audio.engine.PlaybackState.audioOutputInfo]
 * by the engine whenever the track, the output device or the DSP state changes.
 */
data class AudioOutputInfo(
    val deviceName: String,
    val deviceType: String,
    val sourceSampleRate: Int,
    val sourceBitDepth: Int,
    val outputSampleRate: Int,
    val outputBitDepth: Int,
    val isDirectPlayback: Boolean,
    val isBitPerfectMode: Boolean,
    val fallbackReason: String? = null,
    // ---- extended pipeline facts -------------------------------------------
    /** Container/codec format of the source ("FLAC", "MP3", ...). */
    val sourceFormat: String = "",
    val sourceChannels: Int = 2,
    val sourceBitrateKbps: Int = 0,
    /** True when any DSP stage is engaged (EQ, tone, balance, ...). */
    val isDspActive: Boolean = false,
    /** True when the DSP chain is mathematically transparent (passthrough). */
    val isDspNeutral: Boolean = true,
    /** True when volume is applied in the float DSP instead of the platform mixer. */
    val directVolumeControl: Boolean = true,
    val volumeGainDb: Float = 0f,
    /** Auto-applied anti-clip headroom from the DSP chain (≤ 0 dB, 0 when none). */
    val headroomDb: Float = 0f,
    /** True when the platform must resample (source rate != mixer/route rate). */
    val isResampling: Boolean = false,
    val fidelity: FidelityScore? = null,
    val pipeline: AudioPipeline? = null
)
