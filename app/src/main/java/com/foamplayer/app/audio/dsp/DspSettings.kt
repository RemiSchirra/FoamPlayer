package com.foamplayer.app.audio.dsp

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

/** 10-band 1-octave graphic EQ. Gains in dB per band, -12..+12. */
data class GraphicEqSettings(
    val enabled: Boolean = false,
    val gainsDb: List<Float> = List(BAND_COUNT) { 0f }
) {
    val isNeutral: Boolean get() = !enabled || gainsDb.all { it == 0f }

    companion object {
        const val BAND_COUNT = 10
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f

        // 1-octave ISO centers.
        val BAND_FREQUENCIES = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

        // Overlapping 1-octave peaking sections need roughly Butterworth Q to sum
        // into a smooth response without ripple valleys between band centers.
        const val BAND_Q = 1.41
    }
}

/** One parametric EQ node: a filter the user moves around the response graph. */
data class ParametricNode(
    val id: Long,
    val type: FilterType = FilterType.PEAKING,
    val frequencyHz: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1.2f
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

/**
 * A named DSP snapshot. Built-in presets seed the graphic EQ + tone; user presets
 * capture the whole [DspState] (minus volume) and are persisted by the settings layer.
 */
data class DspPreset(
    val name: String,
    val graphicGainsDb: List<Float>,
    val tone: ToneSettings = ToneSettings(),
    /** When set, applying the preset restores this full state instead of only the EQ bands. */
    val fullState: DspState? = null,
    val isBuiltIn: Boolean = false
) {
    companion object {
        val BUILT_INS: List<DspPreset> = buildList {
            add(DspPreset("Flat", List(GraphicEqSettings.BAND_COUNT) { 0f }, isBuiltIn = true))
            add(
                DspPreset(
                    "Bass Boost",
                    listOf(6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
                    tone = ToneSettings(bassDb = 4f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Treble Boost",
                    listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f),
                    tone = ToneSettings(trebleDb = 3f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "V-Shaped",
                    listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Rock",
                    listOf(5f, 4f, 2f, -1f, -2f, -1f, 1f, 3f, 4f, 4f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Pop",
                    listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Jazz",
                    listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Classical",
                    listOf(4f, 3f, 2f, 0f, -1f, -1f, 0f, 2f, 3f, 4f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Vocal",
                    listOf(-3f, -2f, 0f, 3f, 4f, 4f, 3f, 1f, 0f, -1f),
                    isBuiltIn = true
                )
            )
            add(
                DspPreset(
                    "Electronic",
                    listOf(5f, 4f, 1f, 0f, -2f, 1f, 1f, 3f, 4f, 5f),
                    isBuiltIn = true
                )
            )
        }
    }
}
