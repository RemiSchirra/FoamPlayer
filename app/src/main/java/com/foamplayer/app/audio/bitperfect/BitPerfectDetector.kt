package com.foamplayer.app.audio.bitperfect

import android.media.AudioManager
import com.foamplayer.app.audio.device.DeviceAudioConfig
import com.foamplayer.app.audio.device.DeviceBitPerfectMode
import com.foamplayer.app.audio.device.OutputDeviceManager
import com.foamplayer.app.audio.dsp.DspMath
import com.foamplayer.app.audio.dsp.DspState
import com.foamplayer.app.audio.fidelity.FidelityItem
import com.foamplayer.app.audio.fidelity.FidelityScore
import com.foamplayer.app.audio.fidelity.FidelityStatus
import com.foamplayer.app.audio.pipeline.AudioPipeline
import com.foamplayer.app.audio.pipeline.PipelineStage
import com.foamplayer.app.audio.pipeline.PipelineStageStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure evaluator of the active audio path. Given the playing track's format, the
 * current output device, the user's per-device configuration and the DSP state, it
 * derives:
 *  - the [AudioOutputInfo] (rates, depths, bit-perfect verdict, fallback reason),
 *  - the [FidelityScore] breakdown (0..100),
 *  - the [AudioPipeline] snapshot rendered by the settings' pipeline modal.
 *
 * Bit-perfect semantics follow Poweramp's Direct volume control model: the path is
 * bit-perfect when the DSP chain is mathematically transparent (byte-for-byte
 * passthrough) and the samples reach the mixer untouched — either at the source's
 * native rate (no resampling needed) or on a direct-capable route.
 */
@Singleton
class BitPerfectDetector @Inject constructor(
    private val audioManager: AudioManager,
    private val outputDeviceManager: OutputDeviceManager
) {

    /** Inputs gathered from the engine at evaluation time. */
    data class Input(
        val sourceSampleRate: Int,
        val sourceBitDepth: Int,
        val sourceFormat: String,
        val sourceChannels: Int,
        val sourceBitrateKbps: Int,
        val dspState: DspState,
        /** Master bit-perfect switch from the settings; when off, never claim it. */
        val masterBitPerfectEnabled: Boolean = true,
        val deviceConfig: DeviceAudioConfig
    )

    fun evaluate(input: Input): AudioOutputInfo {
        val device = outputDeviceManager.activeDevice.value
        val mixerRate = OutputDeviceManager.systemMixerSampleRate(audioManager)
        val dsp = input.dspState
        val dspNeutral = dsp.isNeutral

        val deviceName = device?.name ?: "No output device"
        val deviceType = device?.typeLabel ?: "Unknown"
        val config = input.deviceConfig

        val directCapable = device?.supportsDirectPlayback == true
        val usbDevice = device?.isUsb == true

        // The platform resamples whenever the mixer route rate differs from the
        // source rate and no direct path is used.
        val rateMatchesMixer = input.sourceSampleRate == mixerRate
        val rateMatchesDevice = device != null && input.sourceSampleRate == device.maxSupportedSampleRate
        val resampling = !rateMatchesMixer && !rateMatchesDevice

        // Direct volume control keeps all gain changes in the float DSP; without it,
        // the platform mixer scales (and re-quantizes) the samples.
        val directVolume = dsp.directVolumeControl
        val volumeDb = DspStateVolume.dbFor(dsp.volumePercent)

        // Per-device override: FORCE_ON claims bit-perfect when the hardware can
        // deliver it, FORCE_OFF never claims it, AUTO follows the detected conditions.
        val conditionsBitPerfect =
            dspNeutral && !resampling && (directCapable || rateMatchesMixer)
        val isBitPerfect = input.masterBitPerfectEnabled && when (config.bitPerfectMode) {
            DeviceBitPerfectMode.FORCE_ON -> conditionsBitPerfect || usbDevice
            DeviceBitPerfectMode.FORCE_OFF -> false
            DeviceBitPerfectMode.AUTO -> conditionsBitPerfect
        }

        val outputSampleRate = when {
            config.sampleRateOverrideHz > 0 -> config.sampleRateOverrideHz
            isBitPerfect -> input.sourceSampleRate
            else -> mixerRate
        }
        val outputBitDepth = when {
            input.sourceBitDepth >= 24 && (isBitPerfect || usbDevice) -> input.sourceBitDepth
            else -> maxOf(input.sourceBitDepth, 16)
        }

        val fallbackReason = when {
            isBitPerfect -> null
            !dspNeutral -> "DSP chain engaged — float processing path (bit-perfect bypassed)"
            resampling -> "System resampler: ${input.sourceSampleRate} Hz → $mixerRate Hz"
            !directCapable -> "Mixed output route (no direct path for this format)"
            else -> "AudioFlinger mixer transformation forced"
        }

        val fidelity = buildFidelity(
            input, dsp, dspNeutral, directVolume, volumeDb, resampling,
            directCapable, usbDevice, device, mixerRate, isBitPerfect
        )
        val headroomDb = DspMath.headroomDb(dsp, mixerRate)
        val pipeline = buildPipeline(
            input, dsp, dspNeutral, directVolume, volumeDb, headroomDb, resampling,
            directCapable, usbDevice, device, mixerRate, outputSampleRate, outputBitDepth,
            isBitPerfect
        )

        return AudioOutputInfo(
            deviceName = deviceName,
            deviceType = deviceType,
            sourceSampleRate = input.sourceSampleRate,
            sourceBitDepth = input.sourceBitDepth,
            outputSampleRate = outputSampleRate,
            outputBitDepth = outputBitDepth,
            isDirectPlayback = directCapable,
            isBitPerfectMode = isBitPerfect,
            fallbackReason = fallbackReason,
            sourceFormat = input.sourceFormat,
            sourceChannels = input.sourceChannels,
            sourceBitrateKbps = input.sourceBitrateKbps,
            isDspActive = !dspNeutral,
            isDspNeutral = dspNeutral,
            directVolumeControl = directVolume,
            volumeGainDb = volumeDb,
            headroomDb = headroomDb,
            isResampling = resampling,
            fidelity = fidelity,
            pipeline = pipeline
        )
    }

    // ------------------------------------------------------------------ scoring

    private fun buildFidelity(
        input: Input,
        dsp: DspState,
        dspNeutral: Boolean,
        directVolume: Boolean,
        volumeDb: Float,
        resampling: Boolean,
        directCapable: Boolean,
        usbDevice: Boolean,
        device: com.foamplayer.app.audio.device.AudioOutputDevice?,
        mixerRate: Int,
        isBitPerfect: Boolean
    ): FidelityScore {
        val items = ArrayList<FidelityItem>(6)

        // 1. Sample rate path (25 pts)
        val ratePoints = when {
            !resampling && isBitPerfect -> 25
            !resampling -> 20
            directCapable -> 15
            else -> 6
        }
        items += FidelityItem(
            label = "Sample rate",
            status = when {
                !resampling -> FidelityStatus.PASS
                directCapable -> FidelityStatus.WARN
                else -> FidelityStatus.FAIL
            },
            detail = when {
                !resampling -> "${input.sourceSampleRate} Hz native"
                else -> "${input.sourceSampleRate} Hz → $mixerRate Hz system SRC"
            },
            points = ratePoints,
            maxPoints = 25
        )

        // 2. Bit depth path (20 pts)
        val halDepth = if (usbDevice) 32 else 16
        val depthPoints = when {
            input.sourceBitDepth <= halDepth && (isBitPerfect || usbDevice) -> 20
            input.sourceBitDepth <= halDepth -> 14
            else -> 5
        }
        items += FidelityItem(
            label = "Bit depth",
            status = when {
                input.sourceBitDepth <= halDepth && (isBitPerfect || usbDevice) -> FidelityStatus.PASS
                input.sourceBitDepth <= halDepth -> FidelityStatus.WARN
                else -> FidelityStatus.FAIL
            },
            detail = "${input.sourceBitDepth}-bit source → $halDepth-bit HAL",
            points = depthPoints,
            maxPoints = 20
        )

        // 3. DSP path (25 pts)
        val dspEngaged = !dspNeutral
        val limiterOn = dsp.enabled && dsp.limiterEnabled
        val dspPoints = when {
            !dspEngaged -> 25
            !limiterOn -> 17
            else -> 12
        }
        items += FidelityItem(
            label = "DSP chain",
            status = when {
                !dspEngaged -> FidelityStatus.PASS
                !limiterOn -> FidelityStatus.WARN
                else -> FidelityStatus.WARN
            },
            detail = when {
                !dspEngaged -> "Transparent (bit-perfect passthrough)"
                limiterOn -> "Float 32-bit processing + limiter engaged"
                else -> "Float 32-bit processing (tone/EQ)"
            },
            points = dspPoints,
            maxPoints = 25
        )

        // 4. Volume path (15 pts)
        val volumePoints = when {
            directVolume && volumeDb >= 0f -> 15
            directVolume -> 12
            else -> 6
        }
        items += FidelityItem(
            label = "Volume path",
            status = when {
                directVolume -> FidelityStatus.PASS
                else -> FidelityStatus.WARN
            },
            detail = when {
                directVolume && volumeDb >= 0f -> "Direct volume control, full scale"
                directVolume -> "Direct volume control, %.1f dB".format(volumeDb)
                else -> "Platform mixer scaling (re-quantized)"
            },
            points = volumePoints,
            maxPoints = 15
        )

        // 5. Output device (15 pts)
        val devicePoints = when {
            usbDevice -> 15
            device?.isWired == true -> 12
            device?.type == android.media.AudioDeviceInfo.TYPE_HDMI -> 12
            device?.isBluetooth == true -> 6
            device?.isSpeaker == true -> 8
            else -> 5
        }
        items += FidelityItem(
            label = "Output device",
            status = when {
                usbDevice -> FidelityStatus.PASS
                device?.isBluetooth == true -> FidelityStatus.WARN
                device == null -> FidelityStatus.FAIL
                else -> FidelityStatus.WARN
            },
            detail = device?.let { "${it.name} · ${it.typeLabel}" } ?: "No active output",
            points = devicePoints,
            maxPoints = 15
        )

        val total = items.sumOf { it.points }.coerceAtMost(FidelityScore.MAX_SCORE)
        return FidelityScore(
            total = total,
            max = FidelityScore.MAX_SCORE,
            label = FidelityScore.labelFor(total),
            items = items
        )
    }

    // ----------------------------------------------------------------- pipeline

    private fun buildPipeline(
        input: Input,
        dsp: DspState,
        dspNeutral: Boolean,
        directVolume: Boolean,
        volumeDb: Float,
        headroomDb: Float,
        resampling: Boolean,
        directCapable: Boolean,
        usbDevice: Boolean,
        device: com.foamplayer.app.audio.device.AudioOutputDevice?,
        mixerRate: Int,
        outputSampleRate: Int,
        outputBitDepth: Int,
        isBitPerfect: Boolean
    ): AudioPipeline {
        val stages = ArrayList<PipelineStage>(9)

        // 1. Source file
        stages += PipelineStage(
            name = "Source file",
            status = PipelineStageStatus.ACTIVE,
            headline = "${input.sourceFormat} · ${input.sourceSampleRate} Hz · ${input.sourceBitDepth}-bit",
            details = listOf(
                "${input.sourceChannels} ${if (input.sourceChannels == 1) "channel" else "channels"}",
                "${input.sourceBitrateKbps} kbps"
            )
        )

        // 2. Decoder
        stages += PipelineStage(
            name = "Decoder",
            status = PipelineStageStatus.ACTIVE,
            headline = "Lossless decode → 32-bit float",
            details = listOf(
                when (input.sourceFormat.uppercase()) {
                    "MP3", "AAC", "OGG", "OPUS", "WMA" -> "Lossy codec decode"
                    else -> "Lossless codec decode"
                },
                "ExoPlayer software pipeline"
            )
        )

        // 3. Volume
        stages += PipelineStage(
            name = "Volume",
            status = if (directVolume) PipelineStageStatus.ACTIVE else PipelineStageStatus.WARN,
            headline = when {
                directVolume && volumeDb >= 0f -> "Direct volume control · full scale"
                directVolume -> "Direct volume control · %.1f dB".format(volumeDb)
                else -> "Platform mixer volume (re-quantized)"
            },
            details = buildList {
                add(if (directVolume) "32-bit float gain, no quantization" else "AudioFlinger int16 mixing")
                add("Volume: ${dsp.volumePercent}%")
                add(
                    if (headroomDb < -0.05f) {
                        "Auto headroom %.1f dB (anti-clip)".format(headroomDb)
                    } else {
                        "No headroom reduction needed"
                    }
                )
            }
        )

        // 4. Tone
        val toneNeutral = dsp.tone.isNeutral
        stages += PipelineStage(
            name = "Tone",
            status = if (toneNeutral) PipelineStageStatus.BYPASSED else PipelineStageStatus.ACTIVE,
            headline = if (toneNeutral) "Bypassed" else "Bass/Mid/Treble applied",
            details = if (toneNeutral) {
                listOf("All knobs centered")
            } else {
                listOf(
                    "Bass %+.1f dB".format(dsp.tone.bassDb),
                    "Mid %+.1f dB".format(dsp.tone.midDb),
                    "Treble %+.1f dB".format(dsp.tone.trebleDb)
                )
            }
        )

        // 5. Graphic EQ
        val graphicActive = dsp.graphicEq.enabled && dsp.graphicEq.gainsDb.any { it != 0f }
        stages += PipelineStage(
            name = "Graphic EQ",
            status = if (graphicActive) PipelineStageStatus.ACTIVE else PipelineStageStatus.BYPASSED,
            headline = if (graphicActive) "${GraphicEqHelper.activeBandCount(dsp)} active bands" else "Bypassed",
            details = if (graphicActive) {
                listOf("10-band 1-octave peaking sections")
            } else {
                listOf(if (dsp.graphicEq.enabled) "All bands flat" else "Disabled")
            }
        )

        // 6. Parametric EQ
        val parametricActive = dsp.parametricEq.enabled && dsp.parametricEq.nodes.isNotEmpty()
        stages += PipelineStage(
            name = "Parametric EQ",
            status = if (parametricActive) PipelineStageStatus.ACTIVE else PipelineStageStatus.BYPASSED,
            headline = if (parametricActive) "${dsp.parametricEq.nodes.size} node(s)" else "Bypassed",
            details = if (parametricActive) {
                dsp.parametricEq.nodes.take(4).map {
                    "${it.type.label} @ ${formatHz(it.frequencyHz)} %+.1f dB Q%.2f".format(it.gainDb, it.q)
                }
            } else {
                listOf(if (dsp.parametricEq.enabled) "No nodes" else "Disabled")
            }
        )

        // 7. Balance & stereo
        val stereoNeutral = dsp.balance == 0f && dsp.stereoWidth == 1f
        stages += PipelineStage(
            name = "Balance & Stereo",
            status = if (stereoNeutral) PipelineStageStatus.BYPASSED else PipelineStageStatus.ACTIVE,
            headline = when {
                stereoNeutral -> "Bypassed"
                dsp.balance != 0f && dsp.stereoWidth != 1f -> "Balance + width applied"
                dsp.balance != 0f -> "Balance applied"
                else -> "Stereo width applied"
            },
            details = if (stereoNeutral) {
                listOf("Centered · width 1.0")
            } else {
                listOf(
                    "Balance %+.0f%%".format(dsp.balance * 100),
                    "Width ×%.1f".format(dsp.stereoWidth)
                )
            }
        )

        // 8. Limiter
        stages += PipelineStage(
            name = "Limiter",
            status = if (dsp.enabled && dsp.limiterEnabled) PipelineStageStatus.ACTIVE else PipelineStageStatus.BYPASSED,
            headline = if (dsp.enabled && dsp.limiterEnabled) "Engaged (soft knee)" else "Bypassed",
            details = listOf(if (dsp.enabled && dsp.limiterEnabled) "Prevents clipping above 0 dBFS" else "No peak control")
        )

        // 9. Resampler
        stages += PipelineStage(
            name = "Resampler",
            status = if (resampling) PipelineStageStatus.WARN else PipelineStageStatus.BYPASSED,
            headline = if (resampling) "${input.sourceSampleRate} Hz → $mixerRate Hz" else "None (native rate)",
            details = if (resampling) {
                listOf("System AudioFlinger SRC — quality loss possible")
            } else {
                listOf("Source rate delivered untouched")
            }
        )

        // 10. Output device
        stages += PipelineStage(
            name = "Output device",
            status = if (device == null) PipelineStageStatus.WARN else PipelineStageStatus.ACTIVE,
            headline = device?.let { "${it.name} · ${it.typeLabel}" } ?: "No output",
            details = buildList {
                add("$outputSampleRate Hz · $outputBitDepth-bit")
                add(
                    when {
                        isBitPerfect -> "Bit-perfect path"
                        directCapable -> "Direct-capable route"
                        else -> "Standard mixed route"
                    }
                )
            }
        )

        return AudioPipeline(stages)
    }

    private fun formatHz(hz: Float): String =
        if (hz >= 1000f) "%.1f kHz".format(hz / 1000f) else "%.0f Hz".format(hz)
}

/** Volume percent → dB helper kept separate so scoring stays readable. */
private object DspStateVolume {
    fun dbFor(percent: Int): Float =
        if (percent <= 0) -60f
        else ((percent.coerceAtMost(100)) - 100) * 0.6f
}

private object GraphicEqHelper {
    fun activeBandCount(dsp: DspState): Int = dsp.graphicEq.gainsDb.count { it != 0f }
}
