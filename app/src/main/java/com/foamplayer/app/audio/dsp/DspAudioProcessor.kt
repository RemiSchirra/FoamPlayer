package com.foamplayer.app.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Real-time DSP chain inserted into ExoPlayer's [androidx.media3.common.audio.AudioSink]
 * (DefaultAudioSink -> setAudioProcessors). 32-bit float processing regardless of the
 * source encoding; the declared output encoding always matches the input encoding so
 * the AudioTrack configuration (and therefore the whole downstream path) is identical
 * with and without DSP.
 *
 * The processor is ALWAYS active (output format == input format) so that runtime DSP
 * changes never require a sink reconfiguration — they only switch the per-buffer
 * behavior between the passthrough fast path (bit-perfect byte copy) and the float
 * processing path. Settings are read via an atomic reference per buffer, so changes
 * apply without touching the audio stream, and coefficients are rebuilt at the
 * stream's actual sample rate.
 *
 * Gain staging on the processing path: auto-headroom (pre-filter) → filters →
 * balance/width → output gain (volume × pre-amp) → optional limiter → soft-clip
 * guard → quantization back to the stream encoding.
 *
 * Supported input encodings: 16-bit, 24-bit packed, 32-bit int and 32-bit float PCM.
 * Any other encoding — and any channel count above stereo — is copied through
 * untouched.
 */
@UnstableApi
class DspAudioProcessor : BaseAudioProcessor() {

    /**
     * The DSP settings the processor should currently run. Written from any thread
     * (the UI coroutine updating settings); read per buffer on the audio thread.
     */
    private val pendingState = AtomicReference<DspState?>(DspState())

    // Per-(filter, channel) filter states, rebuilt whenever the state identity, the
    // channel count or the sample rate changes. Indexed [filterIndex * channelCount + channel].
    private var filterStates: Array<BiquadState?> = emptyArray()
    private var configuredState: DspState? = null
    private var configuredChain: DspChain? = null
    private var configuredChannelCount = 0
    private var configuredSampleRate = 0

    // Scratch buffer for one frame of samples (all channels).
    private var frame = FloatArray(2)

    /** Publishes new DSP settings; called by [DspEngine] whenever they change. */
    fun setState(state: DspState) {
        pendingState.set(state)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // Keep the stream encoding identical end-to-end: bit-perfect by construction
        // and always decodable by the sink, whether or not DSP is engaged.
        return inputAudioFormat
    }

    override fun onQueueEndOfStream() {
        // Nothing pending: this processor never holds more than the buffer in flight.
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Seek/format flush: clear filter memory so no pre-seek energy leaks across.
        resetFilterStates()
    }

    override fun onReset() {
        pendingState.set(DspState())
        configuredState = null
        configuredChain = null
        configuredChannelCount = 0
        configuredSampleRate = 0
        filterStates = emptyArray()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val state = pendingState.get() ?: DspState()
        val sampleRate = inputAudioFormat.sampleRate
        val channelCount = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding

        // Adopt a new state (or a new format) and rebuild the chain + filter states at
        // the ACTUAL sample rate, so coefficients are always correct for the stream.
        if (state !== configuredState ||
            channelCount != configuredChannelCount ||
            sampleRate != configuredSampleRate
        ) {
            configuredState = state
            configuredChannelCount = channelCount
            configuredSampleRate = sampleRate
            configuredChain = DspMath.buildChain(state, sampleRate)
            rebuildFilterStates(configuredChain, channelCount)
        }

        val chain = configuredChain
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()

        val dspPossible = supportsEncoding(encoding) && channelCount in 1..2
        if (chain == null || !dspPossible) {
            // Bit-perfect passthrough: byte-for-byte copy. Also the path for surround
            // content and exotic encodings, which are deliberately left untouched.
            val out = replaceOutputBuffer(limit - position).order(inputBuffer.order())
            while (inputBuffer.hasRemaining()) {
                out.put(inputBuffer.get())
            }
            out.flip()
            inputBuffer.position(limit)
            return
        }

        if (frame.size < channelCount) frame = FloatArray(channelCount)
        val out = replaceOutputBuffer(limit - position).order(ByteOrder.LITTLE_ENDIAN)

        when (encoding) {
            C.ENCODING_PCM_FLOAT -> processFloatFrames(inputBuffer, out, chain, channelCount)
            C.ENCODING_PCM_16BIT -> processPcm16Frames(inputBuffer, out, chain, channelCount)
            C.ENCODING_PCM_24BIT -> processPcm24Frames(inputBuffer, out, chain, channelCount)
            C.ENCODING_PCM_32BIT -> processPcm32Frames(inputBuffer, out, chain, channelCount)
        }

        out.flip()
        inputBuffer.position(limit)
    }

    // ------------------------------------------------------------ frame plumbing

    /** Applies the full chain to one frame of [frame] samples in place. */
    private fun processFrame(chain: DspChain, channelCount: Int) {
        // 1. Auto headroom, BEFORE the filters: compensates the chain's spectral
        //    peak so EQ boosts can never push samples past full scale (the source
        //    of harsh clipping noise on boosts).
        if (chain.headroomLinear != 1f) {
            for (channel in 0 until channelCount) frame[channel] *= chain.headroomLinear
        }

        // 2. Filter chain (tone -> graphic -> parametric), per channel.
        for (filterIndex in chain.filters.indices) {
            for (channel in 0 until channelCount) {
                frame[channel] =
                    filterStates[filterIndex * channelCount + channel]!!.process(frame[channel])
            }
        }

        // 3. Joint-stereo stages (need both channels of the same frame).
        if (channelCount == 2) {
            var left = frame[0]
            var right = frame[1]

            if (chain.balance != 0f) {
                // Linear balance: attenuate the opposite channel, never boost past 1.
                if (chain.balance < 0f) right *= 1f + chain.balance else left *= 1f - chain.balance
            }

            if (chain.stereoWidth != 1f) {
                // Mid/side width: 0 = mono, 1 = untouched, 2 = doubled stereo spread.
                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f * chain.stereoWidth
                left = mid + side
                right = mid - side
            }

            frame[0] = left
            frame[1] = right
        }

        // 4. Final user gain (direct volume × pre-amp), AFTER the filters, so a
        //    negative pre-amp hands the filters extra headroom.
        if (chain.outputGainLinear != 1f) {
            for (channel in 0 until channelCount) frame[channel] *= chain.outputGainLinear
        }

        // 5. Limiter (optional, user switch): stronger knee for deliberate peak control.
        if (chain.limiterEnabled) {
            for (channel in 0 until channelCount) frame[channel] = DspMath.limit(frame[channel])
        }

        // 6. Gentle anti-hard-clip shoulder before quantization, always on the
        //    processing path. Transparent below ~98.5% full scale.
        for (channel in 0 until channelCount) frame[channel] = DspMath.softClip(frame[channel])
    }

    // ------------------------------------------------------- encoding adapters

    private fun processFloatFrames(
        input: ByteBuffer,
        out: ByteBuffer,
        chain: DspChain,
        channelCount: Int
    ) {
        while (input.remaining() >= channelCount * 4) {
            for (channel in 0 until channelCount) frame[channel] = input.float
            processFrame(chain, channelCount)
            for (channel in 0 until channelCount) out.putFloat(frame[channel])
        }
    }

    private fun processPcm16Frames(
        input: ByteBuffer,
        out: ByteBuffer,
        chain: DspChain,
        channelCount: Int
    ) {
        while (input.remaining() >= channelCount * 2) {
            for (channel in 0 until channelCount) frame[channel] = input.short / 32768f
            processFrame(chain, channelCount)
            for (channel in 0 until channelCount) {
                out.putShort(quantizeToShort(frame[channel]))
            }
        }
    }

    private fun processPcm24Frames(
        input: ByteBuffer,
        out: ByteBuffer,
        chain: DspChain,
        channelCount: Int
    ) {
        while (input.remaining() >= channelCount * 3) {
            for (channel in 0 until channelCount) {
                val b0 = input.get().toInt() and 0xFF
                val b1 = input.get().toInt() and 0xFF
                val b2 = input.get().toInt() and 0xFF
                // Little-endian 24-bit signed -> float in [-1, 1). The 24-bit value
                // is exactly representable in float32, so no precision is lost here.
                // Shifting left by 8 places the sign bit into the Int's bit 31.
                val raw = (b0 or (b1 shl 8) or (b2 shl 16)) shl 8
                frame[channel] = raw / 2147483648f
            }
            processFrame(chain, channelCount)
            for (channel in 0 until channelCount) {
                // float [-1, 1) -> 24-bit little endian, rounded.
                var value = (frame[channel] * 8388607f).roundToInt()
                value = value.coerceIn(-8388608, 8388607)
                out.put((value and 0xFF).toByte())
                out.put(((value shr 8) and 0xFF).toByte())
                out.put(((value shr 16) and 0xFF).toByte())
            }
        }
    }

    private fun processPcm32Frames(
        input: ByteBuffer,
        out: ByteBuffer,
        chain: DspChain,
        channelCount: Int
    ) {
        while (input.remaining() >= channelCount * 4) {
            for (channel in 0 until channelCount) frame[channel] = input.int / 2147483648f
            processFrame(chain, channelCount)
            for (channel in 0 until channelCount) {
                val value = (frame[channel] * Int.MAX_VALUE.toFloat()).roundToInt()
                out.putInt(value.coerceIn(Int.MIN_VALUE, Int.MAX_VALUE))
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun rebuildFilterStates(chain: DspChain?, channelCount: Int) {
        val filters = chain?.filters ?: emptyList()
        val states = arrayOfNulls<BiquadState>(filters.size * channelCount)
        for (i in filters.indices) {
            for (channel in 0 until channelCount) {
                states[i * channelCount + channel] = BiquadState(filters[i])
            }
        }
        filterStates = states
    }

    private fun resetFilterStates() {
        for (state in filterStates) state?.reset()
    }

    private fun supportsEncoding(encoding: Int): Boolean =
        encoding == C.ENCODING_PCM_FLOAT ||
                encoding == C.ENCODING_PCM_16BIT ||
                encoding == C.ENCODING_PCM_24BIT ||
                encoding == C.ENCODING_PCM_32BIT

    private fun quantizeToShort(value: Float): Short =
        (value * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
}
