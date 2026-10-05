package com.foamplayer.app.audio.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow

/**
 * A ready-to-run snapshot of the whole DSP chain: everything the audio processor
 * needs, already resolved for one sample rate. Immutable, so the processor can grab
 * a consistent snapshot per buffer even while the UI changes settings.
 *
 * The chain is linear: the biquad filters are the only stage that shapes the
 * spectrum, so the response curve is exactly what the UI shows. To keep boosts from
 * overshooting full scale (which the anti-clip stage would flat-top into harsh
 * crackle), [outputGainLinear] includes an automatic headroom attenuation equal to
 * the peak of the combined EQ response ([headroomDb]).
 *
 * The filter list keeps a STABLE LAYOUT: every tone control / graphic band /
 * parametric node always has a section (identity at 0 dB), so moving a slider off
 * zero never changes the filter count and the audio graph can keep each biquad's
 * memory instead of rebuilding zeroed states (which clicks).
 */
data class DspChain(
    val sampleRate: Int,
    val filters: List<BiquadCoefficients>,
    val outputGainLinear: Float,
    val balance: Float,
    val stereoWidth: Float,
    /** Automatic headroom already folded into [outputGainLinear], in dB (>= 0). */
    val headroomDb: Float = 0f
) {
    /** True when applying this chain leaves samples mathematically untouched. */
    val isPassthrough: Boolean
        get() = filters.all { it == BiquadCoefficients.IDENTITY } &&
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

    /**
     * Builds the full filter chain for [state] at [sampleRateHz], or null for
     * passthrough. With [autoHeadroom] the output gain is lowered by the peak of the
     * combined EQ response so boosts cannot push the signal past full scale; pass
     * false to get the raw chain (e.g. for response curves).
     */
    fun buildChain(state: DspState, sampleRateHz: Int, autoHeadroom: Boolean = true): DspChain? {
        if (sampleRateHz <= 0) return null
        val filters = ArrayList<BiquadCoefficients>(16)
        val fs = sampleRateHz.toDouble()

        if (state.enabled) {
            // Tone section: fixed shelves/peak, always in this order.
            filters += toneFilters(fs, state.tone)

            // Graphic EQ: one peaking section per band (identity at 0 dB, so the
            // layout is stable while sliders move), computed at the band's actual
            // center for the CURRENT band count.
            val graphic = state.graphicEq
            if (graphic.enabled) {
                val bandCount = graphic.gainsDb.size
                val q = GraphicEqSettings.bandQ(bandCount)
                for (index in 0 until bandCount) {
                    filters += BiquadDesigner.peaking(
                        fs, GraphicEqSettings.bandFrequency(index, bandCount).toDouble(),
                        graphic.gainsDb[index].toDouble(), q
                    )
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

        // Automatic headroom: music is mastered close to 0 dBFS, so any boost pushes
        // peaks past full scale and the anti-clip stage would flat-top them (harsh,
        // noisy). Lowering the output by the peak of the combined response keeps the
        // curve shape exactly as shown and nothing can overshoot. The user's own
        // pre-amp and volume are applied on top, untouched.
        val headroomDb = if (autoHeadroom) peakBoostDb(filters, fs) else 0f
        val headroomGain = if (headroomDb > 0f) dbToLinear(-headroomDb) else 1f
        val outputGainLinear = volumeGainLinear(state) * dbToLinear(state.preampDb) * headroomGain
        val chain = DspChain(
            sampleRate = sampleRateHz,
            filters = filters,
            outputGainLinear = outputGainLinear,
            balance = state.balance.coerceIn(-DspState.MAX_BALANCE, DspState.MAX_BALANCE),
            stereoWidth = state.stereoWidth.coerceIn(0f, DspState.MAX_STEREO_WIDTH),
            headroomDb = headroomDb
        )
        return if (chain.isPassthrough) null else chain
    }

    /**
     * Peak of the cascaded magnitude response in dB over the audible range, or 0
     * when the chain never boosts (cuts only / identity). Sampled on a fine log
     * grid; negligible numerical noise above 0 dB is ignored.
     */
    private fun peakBoostDb(filters: List<BiquadCoefficients>, fs: Double): Float {
        if (filters.isEmpty()) return 0f
        val points = 256
        var peak = 0.0
        for (point in 0 until points) {
            val w = 2.0 * PI * responseFrequencyAt(point, points) / fs
            var db = 0.0
            for (filter in filters) db += filter.magnitudeDb(w)
            if (db > peak) peak = db
        }
        return if (peak > 0.01) peak.toFloat() else 0f
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
        // The curve shows the EQ shape only; headroom is a gain, not a shape.
        val chain = buildChain(state, sampleRateHz, autoHeadroom = false)
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
     * high shelf — always three sections (identity at 0 dB) so the chain layout
     * does not change when a tone slider crosses zero.
     */
    private fun toneFilters(fs: Double, tone: ToneSettings): List<BiquadCoefficients> =
        ToneBand.entries.map { band -> toneFilter(fs, band, tone) ?: BiquadCoefficients.IDENTITY }

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