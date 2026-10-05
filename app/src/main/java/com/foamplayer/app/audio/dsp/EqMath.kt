package com.foamplayer.app.audio.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow

/**
 * A ready-to-run snapshot of the whole DSP chain: everything the audio processor
 * needs, already resolved for one sample rate. Immutable, so the processor can grab
 * a consistent snapshot per buffer even while the UI changes settings.
 *
 * The chain is purely linear (industry-standard EQ semantics): the biquad filters
 * are the only stage that shapes the spectrum, so a frequency boost is exactly the
 * boost the response curve shows — no pre-attenuation, no saturation, no volume
 * reduction anywhere in the path.
 */
data class DspChain(
    val sampleRate: Int,
    val filters: List<BiquadCoefficients>,
    val outputGainLinear: Float,
    val balance: Float,
    val stereoWidth: Float
) {
    /** True when applying this chain leaves samples mathematically untouched. */
    val isPassthrough: Boolean
        get() = filters.isEmpty() &&
                outputGainLinear == 1f &&
                balance == 0f &&
                stereoWidth == 1f
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
            filters += toneFilters(fs, state.tone)

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

        // Purely linear chain: no auto-headroom attenuation before the filters and
        // no limiter after them — boosting is heard exactly as boosting, the same
        // way a hardware EQ behaves. The stream encoding's own range (int16/int24/
        // int32 wrap or float headroom) is the only boundary the signal sees.
        val outputGainLinear = volumeGainLinear(state) * dbToLinear(state.preampDb)
        val chain = DspChain(
            sampleRate = sampleRateHz,
            filters = filters,
            outputGainLinear = outputGainLinear,
            balance = state.balance.coerceIn(-DspState.MAX_BALANCE, DspState.MAX_BALANCE),
            stereoWidth = state.stereoWidth.coerceIn(0f, DspState.MAX_STEREO_WIDTH)
        )
        return if (chain.isPassthrough) null else chain
    }

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

    /**
     * Response of ONLY one EQ section (graphic or parametric) — no fixed tone
     * controls. The mini map's main curve: tone contributions are drawn there
     * as their own separated curves via [toneCurves], so the two never mix.
     */
    fun sectionResponse(
        state: DspState,
        section: Section,
        sampleRateHz: Int = 48_000,
        pointCount: Int = DEFAULT_RESPONSE_POINTS
    ): List<Float> =
        evaluateResponse(
            sectionFilters(sampleRateHz.coerceAtLeast(1).toDouble(), state, section),
            sampleRateHz,
            pointCount
        )

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

    /**
     * Individual response curve of each non-neutral fixed tone control, in
     * [ToneBand] order — the separated per-tone curves the mini map draws. Bands
     * at 0 dB are skipped (they are identity filters on the audio path too), so
     * the returned list only holds tone controls that actually shape the signal.
     */
    fun toneCurves(
        tone: ToneSettings,
        sampleRateHz: Int = 48_000,
        pointCount: Int = DEFAULT_RESPONSE_POINTS
    ): List<Pair<ToneBand, List<Float>>> {
        val fs = sampleRateHz.coerceAtLeast(1).toDouble()
        return ToneBand.entries.mapNotNull { band ->
            toneFilter(fs, band, tone)?.let { filter ->
                band to evaluateResponse(listOf(filter), sampleRateHz, pointCount)
            }
        }
    }

    /**
     * The fixed tone controls as biquads: bass low shelf, mid peaking, treble
     * high shelf — the exact filters [buildChain] inserts, skipped at 0 dB.
     */
    private fun toneFilters(fs: Double, tone: ToneSettings): List<BiquadCoefficients> =
        ToneBand.entries.mapNotNull { band -> toneFilter(fs, band, tone) }

    /** Biquad of one fixed tone control, or null at 0 dB (identity on the audio path). */
    private fun toneFilter(fs: Double, band: ToneBand, tone: ToneSettings): BiquadCoefficients? =
        when (band) {
            ToneBand.BASS ->
                if (tone.bassDb != 0f) {
                    BiquadDesigner.lowShelf(
                        fs, ToneSettings.BASS_SHELF_HZ.toDouble(), tone.bassDb.toDouble()
                    )
                } else {
                    null
                }
            ToneBand.MID ->
                if (tone.midDb != 0f) {
                    BiquadDesigner.peaking(
                        fs, ToneSettings.MID_PEAK_HZ.toDouble(), tone.midDb.toDouble(), q = 0.9
                    )
                } else {
                    null
                }
            ToneBand.TREBLE ->
                if (tone.trebleDb != 0f) {
                    BiquadDesigner.highShelf(
                        fs, ToneSettings.TREBLE_SHELF_HZ.toDouble(), tone.trebleDb.toDouble()
                    )
                } else {
                    null
                }
        }

    /** Biquads of one switchable EQ section; empty when that section is off. */
    private fun sectionFilters(
        fs: Double,
        state: DspState,
        section: Section
    ): List<BiquadCoefficients> {
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
        return filters
    }

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

    /** The fixed tone controls, identified for per-tone response curves. */
    enum class ToneBand { BASS, MID, TREBLE }

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
}
