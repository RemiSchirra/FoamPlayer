package com.foamplayer.app.audio.dsp

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Anti-clip ceiling of the float processing path (≈ -0.26 dBFS), strictly
 * below full scale so int16/int24/int32 re-quantization can never wrap.
 */
const val SOFT_CLIP_CEILING = 0.97f

/**
 * Anti-clip saturation for the float processing path.
 *
 * Below ±1.0 the function is the IDENTITY — bit-exact, zero distortion on
 * normal program material. Only samples that a boost pushed past full scale
 * (which would otherwise hard-clip into crackle at the int re-quantizers)
 * enter a C1-continuous shoulder that compresses them smoothly into
 * ±[SOFT_CLIP_CEILING]. No gain riding, no look-ahead, no ducking: the body
 * of the signal is never attenuated, so the boost remains exactly the boost
 * the response curve shows. The bit-perfect passthrough path never runs this,
 * so an idle chain stays mathematically transparent.
 */
fun softClip(x: Float): Float {
    val a = abs(x)
    if (a <= 1f) return x
    // f(a) = 1 - room·(1 - exp(-(a - 1)/room)): f(1) = 1, f'(1) = 1 (C1 splice
    // with the identity), f(∞) = 1 - room = ceiling. The tangent at the knee
    // (f'(1) = 1) keeps small over-range excursions essentially linear, so the
    // shoulder is inaudible until peaks are meaningfully past full scale.
    val room = 1f - SOFT_CLIP_CEILING
    val shaped = 1f - room * (1f - exp(-(a - 1f) / room))
    return sign(x) * shaped
}

/**
 * A live, lock-free DSP graph. The audio thread pulls a shared [DspChain]
 * snapshot (published via [updateChain]) and interprets it as a linked list of
 * processing nodes, each owning its per-channel state, so a settings change
 * never rebuilds a big state array mid-stream — the interpreter simply adopts
 * the new graph at the next buffer boundary and the old graph is discarded
 * atomically. No locks, no allocation on the audio path.
 *
 * Stage order (industry-standard linear EQ):
 *  1. biquad graph (tone -> graphic -> parametric),
 *  2. balance / M-S stereo width,
 *  3. output gain (direct volume × pre-amp),
 *  4. anti-clip saturation — bit-exact below 0 dBFS; only the tips of peaks a
 *     boost pushed past full scale get gently rounded into the ceiling, so the
 *     output can never hard-clip into crackle.
 *
 * There is no limiter and no auto-headroom: the chain never attenuates the
 * signal body or reduces loudness, so a frequency boost is heard exactly as
 * the response curve shows.
 */
class EqGraph {

    /** The chain snapshot the graph currently interprets; null = passthrough. */
    private val chainRef = AtomicReference<DspChain?>(null)

    // Per-node processing state, one entry per node in the CURRENT chain.
    // Indexed [nodeIndex * channelCount + channel]; rebuilt lazily on a
    // chain/channel change, cleared on seeks via [resetStates].
    private var nodeStates: Array<BiquadState?> = emptyArray()
    private var stateChain: DspChain? = null
    private var stateChannels = 0

    // Frame scratch buffer (all channels of one frame).
    private var frame = FloatArray(2)

    /** Publishes a new chain snapshot; adopted at the next processed buffer. */
    fun updateChain(chain: DspChain?) {
        chainRef.set(chain)
    }

    /** Clears filter memory (seek / format flush). */
    fun resetStates() {
        for (state in nodeStates) state?.reset()
    }

    /**
     * Processes [frameCount] frames of interleaved float PCM in [samples]
     * (capacity must be at least frameCount * channelCount) in place.
     */
    fun process(
        samples: FloatArray,
        frameCount: Int,
        channelCount: Int,
        passthrough: Boolean
    ) {
        if (frameCount <= 0 || channelCount <= 0) return
        if (passthrough) {
            return
        }
        val chain = adoptChain(channelCount)
            ?: return // passthrough snapshot: samples untouched

        if (frame.size < channelCount) frame = FloatArray(channelCount)

        // 1+2+3: filters -> balance/width -> output gain, per frame.
        val filters = chain.filters
        val states = nodeStates
        val hasBalance = chain.balance != 0f
        val hasWidth = chain.stereoWidth != 1f
        val outputGain = chain.outputGainLinear

        for (i in 0 until frameCount) {
            val base = i * channelCount
            for (c in 0 until channelCount) frame[c] = samples[base + c]

            var nodeIndex = 0
            for (filterIndex in filters.indices) {
                for (c in 0 until channelCount) {
                    frame[c] = states[nodeIndex * channelCount + c]!!.process(frame[c])
                }
                nodeIndex++
            }

            if (channelCount == 2) {
                var left = frame[0]
                var right = frame[1]

                if (hasBalance) {
                    // Linear balance: attenuate the opposite channel, never boost past 1.
                    if (chain.balance < 0f) right *= 1f + chain.balance else left *= 1f - chain.balance
                }

                if (hasWidth) {
                    // Mid/side width: 0 = mono, 1 = untouched, 2 = doubled spread.
                    val mid = (left + right) * 0.5f
                    val side = (left - right) * 0.5f * chain.stereoWidth
                    left = mid + side
                    right = mid - side
                }

                frame[0] = left
                frame[1] = right
            }

            if (outputGain != 1f) {
                for (c in 0 until channelCount) frame[c] *= outputGain
            }

            for (c in 0 until channelCount) samples[base + c] = frame[c]
        }

        // 4: anti-clip saturation, always on the float processing path (the
        // bit-perfect passthrough above never reaches this). Identity below
        // 0 dBFS; only tips pushed past full scale by a boost get rounded into
        // the ceiling, so re-quantization downstream can never hard-clip.
        val total = frameCount * channelCount
        for (i in 0 until total) {
            val x = samples[i]
            if (x > 1f || x < -1f) samples[i] = softClip(x)
        }
    }

    // ------------------------------------------------------------ internals

    /**
     * Adopts the published chain when it (or the channel count) changed,
     * rebuilding per-node states. Returns null when the current snapshot is
     * passthrough — the caller leaves samples untouched.
     */
    private fun adoptChain(channelCount: Int): DspChain? {
        val chain = chainRef.get()
        if (chain === stateChain && channelCount == stateChannels) return chain
        stateChain = chain
        stateChannels = channelCount
        val filters = chain?.filters ?: emptyList()
        val states = arrayOfNulls<BiquadState>(filters.size * channelCount)
        for (i in filters.indices) {
            for (c in 0 until channelCount) {
                states[i * channelCount + c] = BiquadState(filters[i])
            }
        }
        nodeStates = states
        return chain
    }
}
