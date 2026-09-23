package com.foamplayer.app.audio.dsp

import kotlin.math.pow

/** Tone section (Poweramp-style fixed tone controls), all values in dB. */
data class ToneSettings(
    val bassDb: Float = 0f,
    val midDb: Float = 0f,
    val trebleDb: Float = 0f
) {
    val isNeutral: Boolean get() = bassDb == 0f && midDb == 0f && trebleDb == 0f

    companion object {
        const val MIN_DB = -12f
        const val MAX_DB = 12f

        // Corner/center frequencies of the fixed tone controls.
        const val BASS_SHELF_HZ = 90f
        const val MID_PEAK_HZ = 1000f
        const val TREBLE_SHELF_HZ = 8000f
    }
}

/**
 * 21-band graphic EQ. Gains in dB per band, -12..+12. The 21 centers form a
 * geometric series from exactly 20 Hz to exactly 20 kHz (~half-octave spacing),
 * so on the log-frequency response axis every band sits at index/20 — the same
 * layout the fader strip and the mini map's viewport overlay assume.
 */
data class GraphicEqSettings(
    val enabled: Boolean = false,
    val gainsDb: List<Float> = List(BAND_COUNT) { 0f }
) {
    val isNeutral: Boolean get() = !enabled || gainsDb.all { it == 0f }

    companion object {
        const val BAND_COUNT = 21
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f

        // 20 Hz * 10^(0.15 * i): i = 0 -> 20 Hz, i = 20 -> 20 kHz.
        val BAND_FREQUENCIES: List<Float> = List(BAND_COUNT) { i -> 20f * 10f.pow(0.15f * i) }

        // Overlapping peaking sections need roughly Butterworth Q to sum into a
        // smooth response without ripple valleys between band centers.
        const val BAND_Q = 1.41
    }
}

/** One parametric EQ node: a filter the user moves around the response graph. */
data class ParametricNode(
    val id: Long,
    val type: FilterType = FilterType.PEAKING,
    val frequencyHz: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1.2f,
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

/** Parametric EQ: up to [MAX_NODES] freely-placed filters. */
data class ParametricEqSettings(
    val enabled: Boolean = false,
    val nodes: List<ParametricNode> = emptyList()
) {
    val isNeutral: Boolean get() = !enabled || nodes.all { it.gainDb == 0f && !it.isCutFilter }

    private val ParametricNode.isCutFilter: Boolean
        get() = type == FilterType.LOW_PASS || type == FilterType.HIGH_PASS

    companion object {
        const val MAX_NODES = 8
    }
}

/**
 * The complete, persisted DSP chain configuration. Everything the audio processor
 * needs to build its float filter graph is derived from this state; [isNeutral]
 * decides whether the processor takes the bit-perfect passthrough fast path.
 */
data class DspState(
    val enabled: Boolean = false,
    val preampDb: Float = 0f,
    val tone: ToneSettings = ToneSettings(),
    val graphicEq: GraphicEqSettings = GraphicEqSettings(),
    val parametricEq: ParametricEqSettings = ParametricEqSettings(),
    val balance: Float = 0f,
    val stereoWidth: Float = 1f,
    /** Peaks limiter: keeps boosts from hard-clipping (Poweramp ships this on). */
    val limiterEnabled: Boolean = true,
    /**
     * Auto headroom: attenuates the whole signal by the EQ's spectral peak before
     * the filters, guaranteeing zero clipping at the cost of overall loudness.
     * Off by default — boosting should be heard as boosting; the limiter handles
     * the peaks instead.
     */
    val autoHeadroom: Boolean = false,
    /** Direct volume control: volume is applied in the float DSP, not by AudioFlinger. */
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
                        stereoWidth == 1f &&
                        !limiterEnabled)

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
 * A named EQ snapshot. Built-in presets seed either the graphic EQ + tone or a
 * fixed parametric node layout; user presets capture the whole [DspState]
 * (minus volume) and are persisted by the settings layer. A preset is either
 * graphic or parametric — the two EQ sections are mutually exclusive.
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
        val BUILT_INS: List<DspPreset> = buildList {
            add(DspPreset("Flat", graphicGainsDb = List(GraphicEqSettings.BAND_COUNT) { 0f }, isBuiltIn = true))
            add(
                DspPreset(
                    "Bass Boost",
                    graphicGainsDb = listOf(6f, 5f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                    tone = ToneSettings(bassDb = 4f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Treble Boost",
                    graphicGainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
                    tone = ToneSettings(trebleDb = 3f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "V-Shaped",
                    graphicGainsDb = listOf(5f, 4f, 3f, 1f, 0f, -1f, -1f, -1f, 0f, 1f, 2f, 2f, 3f, 3f, 3f, 3f, 4f, 4f, 5f, 5f, 5f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Rock",
                    graphicGainsDb = listOf(5f, 4f, 3f, 1f, -1f, -2f, -2f, -1f, 0f, 1f, 1f, 2f, 3f, 3f, 3f, 3f, 4f, 4f, 4f, 4f, 4f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Pop",
                    graphicGainsDb = listOf(-1f, 0f, 1f, 3f, 4f, 3f, 1f, 0f, -1f, -1f, -1f, -1f, 0f, 0f, 0f, 1f, 1f, 2f, 2f, 2f, 2f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Jazz",
                    graphicGainsDb = listOf(3f, 3f, 2f, 1f, 1f, -1f, -1f, -1f, 0f, 0f, 1f, 1f, 1f, 1f, 2f, 2f, 2f, 3f, 3f, 3f, 3f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Classical",
                    graphicGainsDb = listOf(4f, 3f, 2f, 1f, 0f, -1f, -1f, -1f, 0f, 0f, 0f, 1f, 2f, 2f, 3f, 3f, 3f, 3f, 4f, 4f, 4f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Vocal",
                    graphicGainsDb = listOf(-3f, -3f, -2f, -1f, 0f, 1f, 3f, 4f, 4f, 4f, 3f, 3f, 2f, 1f, 1f, 0f, 0f, -1f, -1f, -1f, -1f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Electronic",
                    graphicGainsDb = listOf(5f, 5f, 4f, 3f, 1f, 0f, -2f, -1f, 0f, 1f, 1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f, 5f, 5f, 5f),
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
