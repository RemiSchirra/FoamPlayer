package com.foamplayer.app.audio.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow

/**
 * A ready-to-run snapshot of the whole DSP chain: everything the audio processor
 * needs, already resolved for one sample rate. Immutable, so the processor can grab
 * a consistent snapshot per buffer even while the UI changes settings.
 *
 * Gain staging (Poweramp-style anti-clip):
 *  1. [headroomLinear] attenuates the signal BEFORE the filters, compensating the
 *     chain's spectral peak so EQ boosts can never push samples past full scale;
 *  2. the filters shape the signal;
 *  3. [outputGainLinear] (direct volume × pre-amp) applies the user gain AFTER the
 *     filters, so a reduced pre-amp can also hand the filters extra headroom;
 *  4. the look-ahead limiter ([limiterEnabled]) scales down only the peaks that
 *     would otherwise exceed full scale.
 */
data class DspChain(
    val sampleRate: Int,
    val headroomLinear: Float,
    val filters: List<BiquadCoefficients>,
    val outputGainLinear: Float,
    val balance: Float,
    val stereoWidth: Float,
    val limiterEnabled: Boolean
) {
    /** True when applying this chain leaves samples mathematically untouched. */
    val isPassthrough: Boolean
        get() = filters.isEmpty() &&
                headroomLinear == 1f &&
                outputGainLinear == 1f &&
                balance == 0f &&
                stereoWidth == 1f &&
                !limiterEnabled
}

/**
 * Pure-Kotlin DSP graph math: builds biquad chains from [DspState] and evaluates
 * combined magnitude responses for the EQ screens. No Android dependencies, so the
 * same code drives the real-time audio path and the UI canvas.
 */
object DspMath {

    const val RESPONSE_MIN_HZ = 20f
    const val RESPONSE_MAX_HZ = 20000f
    const val DEFAULT_RESPONSE_POINTS = 129

    /** Builds the full filter chain for [state] at [sampleRateHz], or null for passthrough. */
    fun buildChain(state: DspState, sampleRateHz: Int): DspChain? {
        if (sampleRateHz <= 0) return null
        val filters = ArrayList<BiquadCoefficients>(16)
        val fs = sampleRateHz.toDouble()

        if (state.enabled) {
            // Tone section: fixed shelves/peak, always in this order.
            val tone = state.tone
            if (tone.bassDb != 0f) {
                filters += BiquadDesigner.lowShelf(fs, ToneSettings.BASS_SHELF_HZ.toDouble(), tone.bassDb.toDouble())
            }
            if (tone.midDb != 0f) {
                filters += BiquadDesigner.peaking(fs, ToneSettings.MID_PEAK_HZ.toDouble(), tone.midDb.toDouble(), q = 0.9)
            }
            if (tone.trebleDb != 0f) {
                filters += BiquadDesigner.highShelf(fs, ToneSettings.TREBLE_SHELF_HZ.toDouble(), tone.trebleDb.toDouble())
            }

            // Graphic EQ: one peaking section per band with a non-zero gain,
            // computed at the band's actual center for the CURRENT band count.
            val graphic = state.graphicEq
            if (graphic.enabled) {
                val bandCount = graphic.gainsDb.size
                val q = GraphicEqSettings.bandQ(bandCount)
                for (index in 0 until bandCount) {
                    val gain = graphic.gainsDb[index]
                    if (gain != 0f) {
                        filters += BiquadDesigner.peaking(
                            fs, GraphicEqSettings.bandFrequency(index, bandCount).toDouble(),
                            gain.toDouble(), q
                        )
                    }
                }
            }

            // Parametric EQ: user-placed nodes, in list order.
            val parametric = state.parametricEq
            if (parametric.enabled) {
                parametric.nodes.forEach { node ->
                    filters += nodeFilter(fs, node)
                }
            }
        }

        // Anti-clip headroom (OPT-IN): attenuate by the filters' spectral peak so
        // boosts cannot drive the stream past full scale. Off by default — boosting
        // should be heard as boosting; the limiter + soft-clip guard handle peaks.
        val peakDb = filterPeakDb(filters, sampleRateHz)
        val headroomLinear =
            if (state.autoHeadroom && peakDb > 0f) dbToLinear(-peakDb) else 1f

        val outputGainLinear = volumeGainLinear(state) * dbToLinear(state.preampDb)
        val chain = DspChain(
            sampleRate = sampleRateHz,
            headroomLinear = headroomLinear,
            filters = filters,
            outputGainLinear = outputGainLinear,
            balance = state.balance.coerceIn(-DspState.MAX_BALANCE, DspState.MAX_BALANCE),
            stereoWidth = state.stereoWidth.coerceIn(0f, DspState.MAX_STEREO_WIDTH),
            limiterEnabled = state.enabled && state.limiterEnabled
        )
        return if (chain.isPassthrough) null else chain
    }

    /**
     * Peak magnitude of the combined [filters] response in dB (≥ 0), sampled across
     * the audio band. 65 points is plenty for a headroom estimate and keeps the
     * per-chain-build cost trivial.
     */
    fun filterPeakDb(filters: List<BiquadCoefficients>, sampleRateHz: Int): Float {
        if (filters.isEmpty() || sampleRateHz <= 0) return 0f
        var peak = 0.0
        for (point in 0 until HEADROOM_SAMPLE_POINTS) {
            val freq = responseFrequencyAt(point, HEADROOM_SAMPLE_POINTS)
            val w = 2.0 * PI * freq / sampleRateHz
            var db = 0.0
            for (filter in filters) db += filter.magnitudeDb(w)
            if (db > peak) peak = db
        }
        return peak.toFloat()
    }

    /**
     * Headroom the chain would apply for [state], in dB (≤ 0, 0 when transparent).
     * Used by the pipeline visualization; [sampleRateHz] defaults to 48 kHz, the
     * same display assumption as the response curves.
     */
    fun headroomDb(state: DspState, sampleRateHz: Int = 48_000): Float =
        buildChain(state, sampleRateHz)?.let { chain ->
            if (chain.headroomLinear >= 1f) 0f
            else 20f * log10(chain.headroomLinear.coerceAtLeast(1e-6f))
        } ?: 0f

    /** Direct-volume gain: 0% is mute, 100% is unity, dB scale in between. */
    fun volumeGainLinear(state: DspState): Float {
        if (!state.directVolumeControl) return 1f
        val percent = state.volumePercent.coerceIn(0, DspState.MAX_VOLUME_PERCENT)
        if (percent == 0) return 0f
        return dbToLinear((percent - DspState.MAX_VOLUME_PERCENT) * 0.6f)
    }

    /** User volume percent -> dB readout for the EQ screen slider. */
    fun volumePercentToDb(percent: Int): Float {
        if (percent <= 0) return -60f
        return ((percent.coerceAtMost(DspState.MAX_VOLUME_PERCENT)) - DspState.MAX_VOLUME_PERCENT) * 0.6f
    }

    /**
     * Combined magnitude response of the whole (tone + graphic + parametric) chain
     * in dB, sampled logarithmically across [RESPONSE_MIN_HZ]..[RESPONSE_MAX_HZ].
     * [sampleRateHz] defaults to 48 kHz so the curve renders before any audio
     * format is known.
     */
    fun combinedResponse(
        state: DspState,
        sampleRateHz: Int = 48_000,
        pointCount: Int = DEFAULT_RESPONSE_POINTS
    ): List<Float> {
        val chain = buildChain(state, sampleRateHz)
        val filters = chain?.filters ?: emptyList()
        return (0 until pointCount).map { point ->
            val freq = responseFrequencyAt(point, pointCount)
            val w = 2.0 * PI * freq / sampleRateHz.coerceAtLeast(1)
            var db = 0.0
            for (filter in filters) db += filter.magnitudeDb(w)
            db.toFloat()
        }
    }

    /** Response of only one EQ section (graphic or parametric), for the mode-specific graph. */
    fun sectionResponse(
        state: DspState,
        section: Section,
        sampleRateHz: Int = 48_000,
        pointCount: Int = DEFAULT_RESPONSE_POINTS
    ): List<Float> {
        val fs = sampleRateHz.coerceAtLeast(1).toDouble()
        val filters = ArrayList<BiquadCoefficients>(12)
        when (section) {
            Section.GRAPHIC -> {
                val graphic = state.graphicEq
                if (graphic.enabled) {
                    val bandCount = graphic.gainsDb.size
                    val q = GraphicEqSettings.bandQ(bandCount)
                    for (index in 0 until bandCount) {
                        val gain = graphic.gainsDb[index]
                        if (gain != 0f) {
                            filters += BiquadDesigner.peaking(
                                fs, GraphicEqSettings.bandFrequency(index, bandCount).toDouble(),
                                gain.toDouble(), q
                            )
                        }
                    }
                }
            }
            Section.PARAMETRIC -> {
                val parametric = state.parametricEq
                if (parametric.enabled) {
                    parametric.nodes.forEach { node ->
                        filters += nodeFilter(fs, node)
                    }
                }
            }
        }
        return evaluateResponse(filters, sampleRateHz, pointCount)
    }

    /**
     * Response of the graphic EQ alone for [gainsDb] with centers derived from the
     * list length — the curve the fader strip draws behind its sliders, where it
     * must match them 1:1 (tone/pre-amp contributions excluded on purpose).
     */
    fun graphicResponse(
        gainsDb: List<Float>,
        sampleRateHz: Int = 48_000,
        pointCount: Int = DEFAULT_RESPONSE_POINTS
    ): List<Float> {
        val fs = sampleRateHz.coerceAtLeast(1).toDouble()
        val bandCount = gainsDb.size
        val q = GraphicEqSettings.bandQ(bandCount)
        val filters = ArrayList<BiquadCoefficients>(bandCount)
        for (index in 0 until bandCount) {
            val gain = gainsDb[index]
            if (gain != 0f) {
                filters += BiquadDesigner.peaking(
                    fs, GraphicEqSettings.bandFrequency(index, bandCount).toDouble(),
                    gain.toDouble(), q
                )
            }
        }
        return evaluateResponse(filters, sampleRateHz, pointCount)
    }

    /** Response of a single parametric node, for the per-node colored mini map curves. */
    fun nodeResponse(
        node: ParametricNode,
        sampleRateHz: Int = 48_000,
        pointCount: Int = DEFAULT_RESPONSE_POINTS
    ): List<Float> =
        evaluateResponse(
            listOf(nodeFilter(sampleRateHz.coerceAtLeast(1).toDouble(), node)),
            sampleRateHz,
            pointCount
        )

    private fun nodeFilter(fs: Double, node: ParametricNode): BiquadCoefficients = when (node.type) {
        FilterType.PEAKING -> BiquadDesigner.peaking(
            fs, node.frequencyHz.toDouble(), node.gainDb.toDouble(), node.q.toDouble()
        )
        FilterType.LOW_SHELF -> BiquadDesigner.lowShelf(
            fs, node.frequencyHz.toDouble(), node.gainDb.toDouble(), node.slope.toDouble()
        )
        FilterType.HIGH_SHELF -> BiquadDesigner.highShelf(
            fs, node.frequencyHz.toDouble(), node.gainDb.toDouble(), node.slope.toDouble()
        )
        FilterType.LOW_PASS -> BiquadDesigner.lowPass(fs, node.frequencyHz.toDouble(), node.q.toDouble())
        FilterType.HIGH_PASS -> BiquadDesigner.highPass(fs, node.frequencyHz.toDouble(), node.q.toDouble())
    }

    private fun evaluateResponse(
        filters: List<BiquadCoefficients>,
        sampleRateHz: Int,
        pointCount: Int
    ): List<Float> {
        val fs = sampleRateHz.coerceAtLeast(1).toDouble()
        return (0 until pointCount).map { point ->
            val freq = responseFrequencyAt(point, pointCount)
            val w = 2.0 * PI * freq / fs
            var db = 0.0
            for (filter in filters) db += filter.magnitudeDb(w)
            db.toFloat()
        }
    }

    enum class Section { GRAPHIC, PARAMETRIC }

    /** Log-frequency of response point [index] out of [pointCount]. */
    fun responseFrequencyAt(index: Int, pointCount: Int): Float {
        val t = if (pointCount <= 1) 0f else index.toFloat() / (pointCount - 1)
        val logMin = log10(RESPONSE_MIN_HZ)
        val logMax = log10(RESPONSE_MAX_HZ)
        return 10f.pow((logMin + (logMax - logMin) * t).toFloat())
    }

    /** Inverse of [responseFrequencyAt]: response index for a frequency (float). */
    fun responseIndexForFrequency(frequencyHz: Float, pointCount: Int): Float {
        val logMin = log10(RESPONSE_MIN_HZ)
        val logMax = log10(RESPONSE_MAX_HZ)
        val t = (log10(frequencyHz.coerceIn(RESPONSE_MIN_HZ, RESPONSE_MAX_HZ)) - logMin) / (logMax - logMin)
        return t * (pointCount - 1)
    }

    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    /**
     * Soft limiter: transparent below [KNEE], gently compressing above it so EQ/preamp
     * boost can never hard-clip. Applied per sample after all other stages when the
     * user's Limiter switch is on.
     */
    fun limit(x: Float): Float {
        val knee = 0.95f
        val abs = kotlin.math.abs(x)
        if (abs <= knee) return x
        val over = (abs - knee) / (1f - knee)
        return kotlin.math.sign(x) * (knee + (1f - knee) * kotlin.math.tanh(over))
    }

    /**
     * Final anti-hard-clip guard, always active on the processing path (never on the
     * bit-perfect passthrough): transparent below 98.5% full scale, a very gentle
     * tanh shoulder above it. This is what turns what used to be harsh digital
     * clipping on boosts into inaudible soft rounding.
     */
    fun softClip(x: Float): Float {
        val knee = 0.985f
        val abs = kotlin.math.abs(x)
        if (abs <= knee) return x
        val over = (abs - knee) / (1f - knee)
        return kotlin.math.sign(x) * (knee + (1f - knee) * kotlin.math.tanh(over))
    }

    private const val HEADROOM_SAMPLE_POINTS = 65
}
