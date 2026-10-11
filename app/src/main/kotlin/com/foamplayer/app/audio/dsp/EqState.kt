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
 * Graphic EQ with a mutable band layout: [count] in [MIN_BAND_COUNT]..[MAX_BAND_COUNT]
 * (UI offers 6/10/15/21/31), band centers forming a geometric series spanning
 * 20 Hz..20 kHz, so on the log axis band i sits at index i/(count-1) — the
 * layout the fader strip and mini map viewport assume. Gains are -12..+12 dB.
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
        const val DEFAULT_BAND_COUNT = 21
        const val MIN_BAND_COUNT = 2
        const val MAX_BAND_COUNT = 31
        val UI_BAND_COUNTS = listOf(6, 10, 15, 21, 31)
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f

        /**
         * Band center for band [index] of [bands]: a geometric series from 20 Hz
         * to 20 kHz, so on the log axis every band sits at index/(bands-1) —
         * the layout the fader strip and mini map viewport assume.
         */
        fun bandFrequency(index: Int, bands: Int): Float {
            if (bands <= 1) return 1000f
            val t = index.toFloat().coerceIn(0f, (bands - 1).toFloat()) / (bands - 1)
            return 20f * 1000f.pow(t)
        }

        /**
         * Roughly Butterworth Q for overlapping peaking sections, so the bands
         * sum into a smooth response. Denser bands want a slightly higher Q.
         */
        fun bandQ(count: Int): Double {
            val n = count.coerceIn(MIN_BAND_COUNT, MAX_BAND_COUNT)
            return 1.41 * (n.toDouble() / DEFAULT_BAND_COUNT).pow(0.32)
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
 * A named EQ snapshot. Built-in presets seed either the graphic EQ + tone or
 * a fixed parametric node layout; user presets capture the whole [DspState]
 * (minus volume) and are persisted by the settings layer.
 */
data class DspPreset(
    val name: String,
    val kind: PresetKind = PresetKind.GRAPHIC,
    val graphicGainsDb: List<Float> = emptyList(),
    val parametricNodes: List<ParametricNode> = emptyList(),
    val tone: ToneSettings = ToneSettings(),
    /** When set, applying the preset restores this full state instead of only the EQ section. */
    val fullState: DspState? = null,
    val isBuiltIn: Boolean = false
) {
    /** The [DspState] this preset represents, used for response previews and legacy applies. */
    fun previewState(): DspState = fullState ?: when (kind) {
        PresetKind.GRAPHIC -> DspState(
            enabled = true,
            tone = tone,
            graphicEq = GraphicEqSettings(enabled = true, gainsDb = graphicGainsDb)
        )
        PresetKind.PARAMETRIC -> DspState(
            enabled = true,
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
