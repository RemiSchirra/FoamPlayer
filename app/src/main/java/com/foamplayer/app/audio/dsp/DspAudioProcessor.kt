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
 * Decoding adapters convert the stream encoding to a float working buffer; all DSP
 * stages (see [EqGraph]) run there; the result is re-quantized to the stream
 * encoding. Supported encodings: 16-bit, 24-bit packed, 32-bit int and 32-bit float
 * PCM. Any other encoding — and any channel count above stereo — is copied through
 * untouched.
 */
@UnstableApi
class DspAudioProcessor : BaseAudioProcessor() {

    /**
     * The DSP settings the processor should currently run. Written from any thread
     * (the UI coroutine updating settings); read per buffer on the audio thread.
     */
    private val pendingState = AtomicReference<DspState?>(DspState())

    // The live DSP graph: adopts chain snapshots atomically, lock-free.
    private val graph = EqGraph()

    private var configuredState: DspState? = null
    private var configuredChain: DspChain? = null

    // Interleaved float working buffer for one input buffer of audio.
    private var work = FloatArray(0)

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
        graph.resetStates()
    }

    override fun onReset() {
        configuredState = null
        configuredChain = null
        graph.updateChain(null)
        graph.resetStates()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val state = pendingState.get() ?: DspState()
        val sampleRate = inputAudioFormat.sampleRate
        val channelCount = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding

        // Adopt a new state (or a new format) and rebuild the chain at the ACTUAL
        // sample rate, so coefficients are always correct for the stream.
        if (state !== configuredState || sampleRate != (configuredChain?.sampleRate ?: -1)) {
            configuredState = state
            configuredChain = DspMath.buildChain(state, sampleRate)
            graph.updateChain(configuredChain)
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

        val frameCount = (limit - position) / (channelCount * bytesPerSample(encoding))
        if (frameCount <= 0) {
            val out = replaceOutputBuffer(0)
            out.flip()
            inputBuffer.position(limit)
            return
        }
        if (work.size < frameCount * channelCount) {
            work = FloatArray(frameCount * channelCount)
        }

        // Decode -> float work buffer.
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> decodeFloat(inputBuffer, frameCount, channelCount)
            C.ENCODING_PCM_16BIT -> decodePcm16(inputBuffer, frameCount, channelCount)
            C.ENCODING_PCM_24BIT -> decodePcm24(inputBuffer, frameCount, channelCount)
            C.ENCODING_PCM_32BIT -> decodePcm32(inputBuffer, frameCount, channelCount)
        }

        // The whole chain, buffer-at-a-time.
        graph.process(work, frameCount, channelCount, passthrough = false)

        // Re-quantize the work buffer back into the output byte buffer.
        val out = replaceOutputBuffer(limit - position).order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> encodeFloat(out, frameCount, channelCount)
            C.ENCODING_PCM_16BIT -> encodePcm16(out, frameCount, channelCount)
            C.ENCODING_PCM_24BIT -> encodePcm24(out, frameCount, channelCount)
            C.ENCODING_PCM_32BIT -> encodePcm32(out, frameCount, channelCount)
        }

        out.flip()
        inputBuffer.position(limit)
    }

    // ------------------------------------------------------- decode adapters

    private fun decodeFloat(input: ByteBuffer, frameCount: Int, channelCount: Int) {
        for (i in 0 until frameCount * channelCount) work[i] = input.float
    }

    private fun decodePcm16(input: ByteBuffer, frameCount: Int, channelCount: Int) {
        for (i in 0 until frameCount * channelCount) work[i] = input.short / 32768f
    }

    private fun decodePcm24(input: ByteBuffer, frameCount: Int, channelCount: Int) {
        for (i in 0 until frameCount * channelCount) {
            val b0 = input.get().toInt() and 0xFF
            val b1 = input.get().toInt() and 0xFF
            val b2 = input.get().toInt() and 0xFF
            // Little-endian 24-bit signed -> float in [-1, 1). The 24-bit value
            // is exactly representable in float32, so no precision is lost here.
            // Shifting left by 8 places the sign bit into the Int's bit 31.
            val raw = (b0 or (b1 shl 8) or (b2 shl 16)) shl 8
            work[i] = raw / 2147483648f
        }
    }

    private fun decodePcm32(input: ByteBuffer, frameCount: Int, channelCount: Int) {
        for (i in 0 until frameCount * channelCount) work[i] = input.int / 2147483648f
    }

    // ------------------------------------------------------- encode adapters

    private fun encodeFloat(out: ByteBuffer, frameCount: Int, channelCount: Int) {
        for (i in 0 until frameCount * channelCount) {
            val v = work[i]
            out.putFloat(if (v != v) 0f else v)
        }
    }

    private fun encodePcm16(out: ByteBuffer, frameCount: Int, channelCount: Int) {
        for (i in 0 until frameCount * channelCount) {
            out.putShort(quantizeToShort(work[i]))
        }
    }

    // The encoders use the SAME scale as the decoders (2^(bits-1)), so an
    // untouched sample round-trips bit-exact. The old x32767 / x8388607 scale
    // against a /32768 / /8388608 decode shaved a signal-correlated error of
    // up to 1 LSB off every sample (a tiny gain error plus distortion).

    private fun encodePcm24(out: ByteBuffer, frameCount: Int, channelCount: Int) {
        for (i in 0 until frameCount * channelCount) {
            // float [-1, 1) -> 24-bit little endian, rounded.
            val v = work[i]
            var value = if (v != v) 0 else (v * 8388608f).roundToInt()
            value = value.coerceIn(-8388608, 8388607)
            out.put((value and 0xFF).toByte())
            out.put(((value shr 8) and 0xFF).toByte())
            out.put(((value shr 16) and 0xFF).toByte())
        }
    }

    private fun encodePcm32(out: ByteBuffer, frameCount: Int, channelCount: Int) {
        for (i in 0 until frameCount * channelCount) {
            val v = work[i]
            val value = if (v != v) 0 else (v * 2147483648f).roundToInt()
            out.putInt(value)
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun supportsEncoding(encoding: Int): Boolean =
        encoding == C.ENCODING_PCM_FLOAT ||
                encoding == C.ENCODING_PCM_16BIT ||
                encoding == C.ENCODING_PCM_24BIT ||
                encoding == C.ENCODING_PCM_32BIT

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_24BIT -> 3
        else -> 2
    }

    private fun quantizeToShort(value: Float): Short {
        if (value != value) return 0
        return (value * 32768f).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}