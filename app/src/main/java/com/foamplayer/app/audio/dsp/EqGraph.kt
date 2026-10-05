package com.foamplayer.app.audio.dsp

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Start of the anti-clip shoulder (≈ -0.92 dBFS). Everything at or below this
 * magnitude is passed through bit-exact.
 */
const val SOFT_CLIP_KNEE = 0.9f

/**
 * Asymptotic anti-clip ceiling (≈ -0.009 dBFS), strictly below full scale so
 * int16/int24/int32 re-quantization can never wrap or flat-top.
 */
const val SOFT_CLIP_CEILING = 0.999f

/**
 * Anti-clip saturation for the float processing path.
 *
 * Up to ±[SOFT_CLIP_KNEE] the function is the IDENTITY — bit-exact, zero
 * distortion on normal program material. Above the knee it follows
 *
 *     f(a) = K + (C - K) * tanh((a - K) / (C - K))
 *
 * which is strictly MONOTONIC, has f(K) = K and f'(K) = 1 (a C1 splice, with
 * zero curvature at the knee because tanh''(0) = 0) and saturates at C. The
 * body of the signal is never attenuated, so the boost remains exactly the
 * boost the response curve shows; only the tips of peaks a boost pushed toward
 * or past full scale are rounded off.
 *
 * (The previous shoulder, 1 - room·(1 - exp(-(a-1)/room)), had slope -1 at the
 * knee, not +1: output FELL from 1.0 to 0.97 as the input rose past full scale.
 * That folds peaks back on themselves and is what made boosted audio sound
 * harsh and noisy.)
 */
fun softClip(x: Float): Float {
    val a = abs(x)
    if (a <= SOFT_CLIP_KNEE) return x
    val range = SOFT_CLIP_CEILING - SOFT_CLIP_KNEE
    val shaped = SOFT_CLIP_KNEE + range * tanh((a - SOFT_CLIP_KNEE) / range)
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
 *  3. output gain (direct volume × pre-amp × automatic EQ headroom), ramped
 *     linearly across each buffer when it changes (no zipper noise),
 *  4. anti-clip saturation — bit-exact up to the knee ([SOFT_CLIP_KNEE]); only the
 *     tips of peaks above it get gently rounded (monotonically) into the
 *     ceiling, so the output can never hard-clip into crackle.
 *
 * The chain is linear; the automatic headroom (computed in [DspMath.buildChain]
 * from the peak of the EQ response) keeps boosted material below full scale, so
 * the anti-clip stage is only a safety net for user pre-amp / extreme material,
 * not something normal boosts run into.
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

    // Output gain applied at the end of the previous buffer; the next buffer ramps
    // from it to the new target. Not initialized after a flush (snap to target).
    private var appliedGain = 1f
    private var gainInitialized = false

    /** Publishes a new chain snapshot; adopted at the next processed buffer. */
    fun updateChain(chain: DspChain?) {
        chainRef.set(chain)
    }

    /** Clears filter memory (seek / format flush). */
    fun resetStates() {
        for (state in nodeStates) state?.reset()
        gainInitialized = false
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
        if (chain == null) {
            // passthrough snapshot: samples untouched, unity gain
            appliedGain = 1f
            gainInitialized = true
            return
        }

        if (frame.size < channelCount) frame = FloatArray(channelCount)

        // 1+2+3: filters -> balance/width -> output gain, per frame.
        val filters = chain.filters
        val states = nodeStates
        val hasBalance = chain.balance != 0f
        val hasWidth = chain.stereoWidth != 1f

        // Output gain ramps linearly from the previous buffer's final gain to the
        // new target across this buffer: volume / headroom changes never step.
        val targetGain = chain.outputGainLinear
        val startGain = if (gainInitialized) appliedGain else targetGain
        val gainStep = (targetGain - startGain) / frameCount
        var gain = startGain
        appliedGain = targetGain
        gainInitialized = true

        for (i in 0 until frameCount) {
            val base = i * channelCount
            for (c in 0 until channelCount) {
                // NaN guard on INPUT too: one NaN through a biquad poisons its
                // memory forever (silence until the next seek).
                val v = samples[base + c]
                frame[c] = if (v != v) 0f else v
            }

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

            gain += gainStep
            if (gain != 1f) {
                for (c in 0 until channelCount) frame[c] *= gain
            }

            for (c in 0 until channelCount) samples[base + c] = frame[c]
        }

        // 4: anti-clip saturation, always on the float processing path (the
        // bit-perfect passthrough above never reaches this). Identity up to the
        // knee; only tips above it get rounded into the ceiling, so
        // re-quantization downstream can never hard-clip.
        val total = frameCount * channelCount
        for (i in 0 until total) {
            val x = samples[i]
            if (x != x) {
                samples[i] = 0f // NaN guard: the re-quantizers would throw on it
            } else if (x > SOFT_CLIP_KNEE || x < -SOFT_CLIP_KNEE) {
                samples[i] = softClip(x)
            }
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
        val filters = chain?.filters ?: emptyList()
        val needed = filters.size * channelCount

        // Same layout (same filter count and channel count): keep each biquad's
        // memory and only swap its coefficients. Rebuilding zeroed states on
        // every slider tick injected a step into the signal (a click, which a
        // boosted low end turns into a thump); carrying z1/z2 over keeps the
        // waveform continuous while the response morphs.
        if (channelCount == stateChannels && needed == nodeStates.size && needed > 0) {
            for (i in filters.indices) {
                for (c in 0 until channelCount) {
                    nodeStates[i * channelCount + c]!!.setCoefficients(filters[i])
                }
            }
            stateChain = chain
            return chain
        }

        stateChain = chain
        stateChannels = channelCount
        val states = arrayOfNulls<BiquadState>(needed)
        for (i in filters.indices) {
            for (c in 0 until channelCount) {
                states[i * channelCount + c] = BiquadState(filters[i])
            }
        }
        nodeStates = states
        return chain
    }
}