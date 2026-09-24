package com.foamplayer.app.audio.dsp

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * A live, lock-free DSP graph. The audio thread pulls a shared [DspChain]
 * snapshot (published via [updateChain]) and interprets it as a linked list of
 * processing nodes, each owning its per-channel state, so a settings change
 * never rebuilds a big state array mid-stream — the interpreter simply adopts
 * the new graph at the next buffer boundary and the old graph is discarded
 * atomically. No locks, no allocation on the audio path.
 *
 * Stage order (Poweramp-style):
 *  1. input headroom (auto-headroom, pre-filter),
 *  2. biquad graph (tone -> graphic -> parametric),
 *  3. balance / M-S stereo width,
 *  4. output gain (direct volume × pre-amp),
 *  5. peak limiter (optional; per-sample soft knee, peak-only — never ducks),
 *  6. soft-clip guard (always, on the processing path only).
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

        // 1+2+3+4: headroom -> filters -> balance/width -> output gain, per frame.
        val filters = chain.filters
        val states = nodeStates
        val hasBalance = chain.balance != 0f
        val hasWidth = chain.stereoWidth != 1f
        val headroom = chain.headroomLinear
        val outputGain = chain.outputGainLinear

        for (i in 0 until frameCount) {
            val base = i * channelCount
            for (c in 0 until channelCount) frame[c] = samples[base + c]

            if (headroom != 1f) {
                for (c in 0 until channelCount) frame[c] *= headroom
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

            if (outputGain != 1f) {
                for (c in 0 until channelCount) frame[c] *= outputGain
            }

            for (c in 0 until channelCount) samples[base + c] = frame[c]
        }

        // 5: peak limiter (optional, user switch). Per-sample soft knee: EVERY
        // sample below the knee passes at unity, so the body of the signal —
        // and therefore loudness — is untouched; only the tips of peaks that
        // would exceed full scale are gently rounded. A broadband (ducking)
        // limiter would attenuate the whole mix around every boosted transient
        // and audibly reduce the volume whenever a frequency is boosted.
        if (chain.limiterEnabled) {
            val knee = 0.95f
            val lim = 1f - knee
            val total = frameCount * channelCount
            for (i in 0 until total) {
                val x = samples[i]
                val a = abs(x)
                if (a > knee) {
                    samples[i] =
                        kotlin.math.sign(x) * (knee + lim * kotlin.math.tanh((a - knee) / lim))
                }
            }
        }

        // 6: soft-clip guard before quantization, always on the processing path.
        val knee = 0.985f
        val lim = 1f - knee
        val total = frameCount * channelCount
        for (i in 0 until total) {
            val x = samples[i]
            val a = abs(x)
            samples[i] = if (a <= knee) x else kotlin.math.sign(x) * (knee + lim * kotlin.math.tanh((a - knee) / lim))
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
