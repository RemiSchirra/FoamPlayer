package com.foamplayer.app.audio.dsp

import kotlin.math.pow

/**
 * Fixed tone controls (Poweramp-style), values in dB. Bass and treble are
 * shelves, mid is a peaking section; only the gains move.
 */
data class ToneSettings(
    val bassDb: Float = 0f,
    val midDb: Float = 0f,
    val trebleDb: Float = 0f
) {
    val isNeutral: Boolean get() = bassDb == 0f && midDb == 0f && trebleDb == 0f

    companion object {
        const val MIN_DB = -12f
        const val MAX_DB = 12f

        // Fixed corner/center frequencies.
        const val BASS_SHELF_HZ = 90f
        const val MID_PEAK_HZ = 1000f
        const val TREBLE_SHELF_HZ = 8000f
    }
}

/**
 * ISO 266-standard band layouts selectable in Settings > Audio > Equalizer.
 * [frequencies] holds each layout's band centers in Hz — the exact ISO values
 * (not a computed series), so the fader labels, the filters and the response
 * curves all sit on the standard grid. The band count lives in
 * [GraphicEqSettings.count].
 */
enum class EqBandLayout(val bands: Int, val q: Double, val frequencies: List<Float>) {
    // [q] is the peaking-filter Q tuned for each grid. Band spacing is 1, 2/3 and
    // 1/3 octave, so Q rises with the band count, but deliberately stays LOW: each
    // band is 2-3x wider than the spacing between bands, so neighbouring sliders
    // blend into a smooth curve instead of showing a spike per slider.
    ISO_10(
        10, 0.9,
        listOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    ),
    ISO_15(
        15, 1.2,
        listOf(
            25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f,
            2500f, 4000f, 6300f, 10000f, 16000f
        )
    ),
    ISO_31(
        31, 1.8,
        listOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f,
            315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f, 2000f, 2500f,
            3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
        )
    );

    companion object {
        /** The layout with [bands] centers, or null for non-ISO counts. */
        fun of(bands: Int): EqBandLayout? = entries.firstOrNull { it.bands == bands }

        /** Nearest ISO layout to an arbitrary stored count (legacy states). */
        fun nearest(bands: Int): EqBandLayout =
            entries.minBy { kotlin.math.abs(it.bands - bands) }
    }
}

/**
 * Graphic EQ with a mutable band layout: [count] in [MIN_BAND_COUNT]..[MAX_BAND_COUNT]
 * (Settings > Audio > Equalizer offers the ISO layouts 10/15/31). ISO layouts
 * use their standard band centers ([EqBandLayout.frequencies]); other counts —
 * e.g. the legacy 21-band grid — fall back to a geometric series spanning
 * 20 Hz..20 kHz, where on the log axis band i sits at index i/(count-1) (the
 * layout the fader strip and mini map viewport assume). Gains are -12..+12 dB.
 */
data class GraphicEqSettings(
    val enabled: Boolean = false,
    val count: Int = DEFAULT_BAND_COUNT,
    val gainsDb: List<Float> = List(count) { 0f }
) {
    val isNeutral: Boolean get() = !enabled || gainsDb.all { it == 0f }

    /** Band center for band [index], mapped over the full log axis 20 Hz..20 kHz. */
    fun bandFrequency(index: Int): Float = bandFrequency(index, count)

    /** Trims or pads [gainsDb] to exactly [count] entries. */
    fun normalizedGains(): List<Float> = List(count) { gainsDb.getOrElse(it) { 0f } }

    companion object {
        /** Default layout: the 1-octave ISO grid. */
        const val DEFAULT_BAND_COUNT = 10
        const val MIN_BAND_COUNT = 2
        const val MAX_BAND_COUNT = 31
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f

        /**
         * Band center for band [index] of [bands]: the ISO standard centers for
         * the 10/15/31-band layouts, otherwise a geometric series from 20 Hz to
         * 20 kHz with every band at index/(bands-1) on the log axis.
         */
        fun bandFrequency(index: Int, bands: Int): Float {
            EqBandLayout.of(bands)?.let { layout ->
                return layout.frequencies[index.coerceIn(0, layout.frequencies.lastIndex)]
            }
            if (bands <= 1) return 1000f
            val t = index.toFloat().coerceIn(0f, (bands - 1).toFloat()) / (bands - 1)
            return 20f * 1000f.pow(t)
        }

        /**
         * Resamples [gainsDb] (one gain per band of its own layout) onto a
         * [targetBands]-band layout on the LOG-FREQUENCY axis: each target band
         * center takes the linear interpolation of the source gains at that
         * frequency, held flat outside the source span. This is what makes the
         * audible curve survive a band-layout change and lets presets authored
         * on one grid (built-ins: the 31-band ISO grid) drive any other layout —
         * index-based resampling would shift the shape, since the ISO layouts
         * span different frequency ranges.
         */
        fun resampleGains(gainsDb: List<Float>, targetBands: Int): List<Float> {
            val target = targetBands.coerceIn(MIN_BAND_COUNT, MAX_BAND_COUNT)
            if (gainsDb.isEmpty()) return List(target) { 0f }
            val source = gainsDb.map { it.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }
            val sourceCount = source.size
            if (target == sourceCount) return source

            val sourceFreqs = List(sourceCount) { bandFrequency(it, sourceCount).toDouble() }
            return List(target) { index ->
                val f = bandFrequency(index, target).toDouble()
                val low = sourceFreqs.indexOfLast { it <= f }
                when {
                    low < 0 -> source.first()          // below the source span
                    low >= sourceCount - 1 -> source.last() // above the source span
                    else -> {
                        val t = ((f - sourceFreqs[low]) /
                                (sourceFreqs[low + 1] - sourceFreqs[low])).toFloat().coerceIn(0f, 1f)
                        source[low] + (source[low + 1] - source[low]) * t
                    }
                }
            }
        }

        /**
         * Q of the peaking sections for a graphic EQ with [count] bands, kept low
         * enough that overlapping bands sum into a smooth response (no per-slider
         * spikes).
         *
         * The ISO layouts (10 / 15 / 31 bands) use their tuned [EqBandLayout.q]: the
         * fewer the bands, the wider each one is and the lower its Q. Any other count
         * (legacy layouts) is spaced geometrically over 20 Hz..20 kHz, so its band
         * spacing in octaves is N = log2(1000) / (count - 1); Q = 0.7 * N^-0.63 is the
         * power law through the three ISO values, so it stays continuous with them.
         */
        fun bandQ(count: Int): Double {
            EqBandLayout.of(count)?.let { return it.q }
            val n = count.coerceIn(MIN_BAND_COUNT, MAX_BAND_COUNT)
            val octaves = kotlin.math.log2(1000.0) / (n - 1)
            return 0.7 * octaves.pow(-0.63)
        }
    }
}

/** One parametric EQ node: a filter the user moves around the response graph. */
data class ParametricNode(
    val id: Long,
    val type: FilterType = FilterType.PEAKING,
    val frequencyHz: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1.2f,
    val slope: Float = 0.9f,
    /** ARGB color identifying this node across the graphs and the mini map. */
    val colorArgb: Long = NodeColorPalette.first()
) {
    companion object {
        const val MIN_FREQ_HZ = 20f
        const val MAX_FREQ_HZ = 20000f
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        const val MIN_Q = 0.3f
        const val MAX_Q = 8f
        const val MIN_SLOPE = 0.4f
        const val MAX_SLOPE = 1.2f
    }
}

/** Colors a parametric node can take; new nodes cycle through the palette. */
val NodeColorPalette: List<Long> = listOf(
    0xFF4FC3F7L, // light blue
    0xFF81C784L, // green
    0xFFFFB74DL, // orange
    0xFFE57373L, // red
    0xFFBA68C8L, // purple
    0xFFFFD54FL, // amber
    0xFF4DB6ACL, // teal
    0xFFF06292L  // pink
)

/** Parametric EQ: up to [ParametricEqSettings.MAX_NODES] freely-placed filters. */
data class ParametricEqSettings(
    val enabled: Boolean = false,
    val nodes: List<ParametricNode> = emptyList()
) {
    val isNeutral: Boolean get() = !enabled || nodes.all { it.gainDb == 0f && !it.isCutFilter }

    private val ParametricNode.isCutFilter: Boolean
        get() = type == FilterType.LOW_PASS || type == FilterType.HIGH_PASS

    companion object {
        const val MAX_NODES = 12
    }
}

/**
 * The complete, persisted DSP chain configuration. The audio processor derives
 * its filter graph from this state; [isNeutral] decides whether it takes the
 * bit-perfect passthrough fast path.
 *
 * The chain is purely linear (industry-standard EQ semantics): no limiter and
 * no automatic headroom — a boost is exactly the boost the curve shows.
 */
data class DspState(
    val enabled: Boolean = false,
    val preampDb: Float = 0f,
    val tone: ToneSettings = ToneSettings(),
    val graphicEq: GraphicEqSettings = GraphicEqSettings(),
    val parametricEq: ParametricEqSettings = ParametricEqSettings(),
    val balance: Float = 0f,
    val stereoWidth: Float = 1f,
    /** Volume applied in the float DSP instead of by AudioFlinger. */
    val directVolumeControl: Boolean = true,
    /** User volume 0..100 (100 = 0 dB, direct-volume path only). */
    val volumePercent: Int = 100
) {
    /** True when the whole chain is mathematically transparent (bit-perfect passthrough). */
    val isNeutral: Boolean
        get() = !enabled ||
                (preampDb == 0f &&
                        tone.isNeutral &&
                        graphicEq.isNeutral &&
                        parametricEq.isNeutral &&
                        balance == 0f &&
                        stereoWidth == 1f)

    companion object {
        const val MIN_PREAMP_DB = -15f
        const val MAX_PREAMP_DB = 15f
        const val MAX_BALANCE = 1f // -1 (left) .. +1 (right)
        const val MAX_STEREO_WIDTH = 2f // 0 mono, 1 normal, 2 extra wide
        const val MAX_VOLUME_PERCENT = 100
    }
}

/** Which (mutually exclusive) EQ section a preset drives. */
enum class PresetKind { GRAPHIC, PARAMETRIC }

/**
 * A named EQ snapshot. Built-in presets seed the pre-amp plus either the
 * graphic EQ + tone or a fixed parametric node layout; user presets capture
 * the whole [DspState] (minus volume) and are persisted by the settings layer.
 */
data class DspPreset(
    val name: String,
    val kind: PresetKind = PresetKind.GRAPHIC,
    val graphicGainsDb: List<Float> = emptyList(),
    val parametricNodes: List<ParametricNode> = emptyList(),
    val tone: ToneSettings = ToneSettings(),
    val preampDb: Float = 0f,
    /** When set, applying the preset restores this full state instead of only the EQ section. */
    val fullState: DspState? = null,
    val isBuiltIn: Boolean = false
) {
    /** The [DspState] this preset represents, used for response previews and legacy applies. */
    fun previewState(): DspState = fullState ?: when (kind) {
        PresetKind.GRAPHIC -> DspState(
            enabled = true,
            preampDb = preampDb,
            tone = tone,
            graphicEq = GraphicEqSettings(
                enabled = true,
                count = graphicGainsDb.size,
                gainsDb = graphicGainsDb
            )
        )
        PresetKind.PARAMETRIC -> DspState(
            enabled = true,
            preampDb = preampDb,
            tone = tone,
            parametricEq = ParametricEqSettings(enabled = true, nodes = parametricNodes)
        )
    }

    companion object {
        /**
         * Built-in presets, expressed as shapes on a 31-band grid and resampled
         * by [DspEngine.applyPreset] to whatever band count the user has selected.
         */
        private fun gains(vararg values: Float): List<Float> = values.toList()

        val BUILT_INS: List<DspPreset> = buildList {
            add(DspPreset("Flat", graphicGainsDb = gains(*FloatArray(31) { 0f }), isBuiltIn = true))
            add(
                DspPreset(
                    "Bass Boost",
                    graphicGainsDb = gains(6f, 5.5f, 5f, 4.5f, 4f, 3.5f, 3f, 2.5f, 2f, 1.5f, 1f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                    tone = ToneSettings(bassDb = 4f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Treble Boost",
                    graphicGainsDb = gains(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f),
                    tone = ToneSettings(trebleDb = 3f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "V-Shaped",
                    graphicGainsDb = gains(5f, 4.5f, 4f, 3.5f, 3f, 2.5f, 2f, 1.5f, 1f, 0f, -1f, -1.5f, -2f, -1.5f, -1f, 0f, 1f, 2f, 2.5f, 3f, 3.5f, 4f, 4.5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Rock",
                    graphicGainsDb = gains(5f, 4.5f, 4f, 3.5f, 3f, 2f, 1f, 0f, -1f, -2f, -2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 2f, 2.5f, 3f, 3f, 3.5f, 4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Pop",
                    graphicGainsDb = gains(-1f, -0.5f, 0f, 0.5f, 1f, 2f, 3f, 4f, 4f, 3.5f, 3f, 2f, 1f, 0f, -1f, -1f, -1f, -1f, -1f, 0f, 0f, 0f, 0f, 0.5f, 1f, 1.5f, 2f, 2f, 2f, 2f, 2f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Jazz",
                    graphicGainsDb = gains(3f, 3f, 2.5f, 2f, 2f, 1.5f, 1f, 1f, 0.5f, -0.5f, -1f, -1f, -1f, -0.5f, 0f, 0f, 0.5f, 1f, 1f, 1.5f, 2f, 2f, 2f, 2f, 2.5f, 2.5f, 3f, 3f, 3f, 3f, 3f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Classical",
                    graphicGainsDb = gains(4f, 3.5f, 3f, 2.5f, 2f, 1.5f, 1f, 0.5f, 0f, -0.5f, -1f, -1f, -1f, -0.5f, 0f, 0f, 0.5f, 1f, 1.5f, 2f, 2f, 2.5f, 3f, 3f, 3f, 3.5f, 3.5f, 4f, 4f, 4f, 4f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Vocal",
                    graphicGainsDb = gains(-3f, -3f, -2.5f, -2f, -1.5f, -1f, 0f, 1f, 2f, 3f, 4f, 4.5f, 5f, 4.5f, 4f, 3.5f, 3f, 2.5f, 2f, 1.5f, 1f, 0.5f, 0f, 0f, -0.5f, -1f, -1f, -1f, -1f, -1f, -1f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Electronic",
                    graphicGainsDb = gains(5f, 5f, 4.5f, 4f, 4f, 3.5f, 3f, 2.5f, 2f, 1f, 0f, -1f, -2f, -1f, 0f, 0.5f, 1f, 1f, 1.5f, 2f, 2.5f, 3f, 3f, 3.5f, 4f, 4f, 4.5f, 5f, 5f, 5f, 5f),
                    isBuiltIn = true
                )
            )

            // Built-in parametric presets: fixed node layouts with palette colors.
            add(
                DspPreset(
                    name = "Low Cut",
                    kind = PresetKind.PARAMETRIC,
                    parametricNodes = listOf(
                        ParametricNode(
                            id = 1, type = FilterType.HIGH_PASS,
                            frequencyHz = 35f, q = 0.71f,
                            colorArgb = NodeColorPalette[2]
                        )
                    ),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    name = "De-Mud",
                    kind = PresetKind.PARAMETRIC,
                    parametricNodes = listOf(
                        ParametricNode(
                            id = 1, type = FilterType.LOW_SHELF,
                            frequencyHz = 120f, gainDb = -3f, q = 0.8f,
                            colorArgb = NodeColorPalette[3]
                        ),
                        ParametricNode(
                            id = 2, type = FilterType.PEAKING,
                            frequencyHz = 320f, gainDb = -3.5f, q = 1.1f,
                            colorArgb = NodeColorPalette[1]
                        )
                    ),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    name = "Presence",
                    kind = PresetKind.PARAMETRIC,
                    parametricNodes = listOf(
                        ParametricNode(
                            id = 1, type = FilterType.PEAKING,
                            frequencyHz = 3000f, gainDb = 3.5f, q = 1.2f,
                            colorArgb = NodeColorPalette[0]
                        ),
                        ParametricNode(
                            id = 2, type = FilterType.PEAKING,
                            frequencyHz = 8000f, gainDb = 2f, q = 1.5f,
                            colorArgb = NodeColorPalette[6]
                        )
                    ),
                    isBuiltIn = true
                )
            )
        }
    }
}