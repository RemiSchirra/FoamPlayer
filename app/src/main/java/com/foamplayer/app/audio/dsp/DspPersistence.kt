package com.foamplayer.app.audio.dsp

import com.foamplayer.app.audio.dsp.FilterType

/**
 * Compact string serialization of [DspState] and user [DspPreset]s for DataStore
 * storage. Fields separated by ';', format version prefixed first:
 *
 * v2: `2;enabled;preamp;bass;mid;treble;gEnabled;g1,g2,..,g10;pEnabled;id:type:freq:gain:q/..;balance;width;limiter;autoHeadroom;dvc;volume`
 * v1: same without the autoHeadroom field (legacy payloads read with it = false).
 *
 * Parsing is defensive: any malformed field falls back to the default value, so a
 * bad or older payload can never crash the audio path.
 */
object DspPersistence {

    private const val FORMAT_VERSION = 2

    fun serialize(state: DspState): String = buildString {
        append(FORMAT_VERSION)
        append(SEP).append(if (state.enabled) "1" else "0")
        append(SEP).append(state.preampDb)
        append(SEP).append(state.tone.bassDb)
        append(SEP).append(state.tone.midDb)
        append(SEP).append(state.tone.trebleDb)
        append(SEP).append(if (state.graphicEq.enabled) "1" else "0")
        append(SEP).append(state.graphicEq.gainsDb.joinToString(LIST) { trim(it) })
        append(SEP).append(if (state.parametricEq.enabled) "1" else "0")
        append(SEP).append(
            state.parametricEq.nodes.joinToString(NODE) { node ->
                listOf(
                    node.id.toString(),
                    node.type.name,
                    trim(node.frequencyHz),
                    trim(node.gainDb),
                    trim(node.q)
                ).joinToString(FIELD)
            }
        )
        append(SEP).append(state.balance)
        append(SEP).append(state.stereoWidth)
        append(SEP).append(if (state.limiterEnabled) "1" else "0")
        append(SEP).append(if (state.autoHeadroom) "1" else "0")
        append(SEP).append(if (state.directVolumeControl) "1" else "0")
        append(SEP).append(state.volumePercent)
    }

    fun deserialize(raw: String?): DspState {
        if (raw.isNullOrBlank()) return DspState()
        return try {
            val parts = raw.split(SEP)
            val default = DspState()

            // v1 payloads lack the autoHeadroom field: its slot holds DVC instead,
            // so the tail fields are parsed on version-specific indices.
            val version = parts.firstOrNull()?.toIntOrNull() ?: 1
            val isV2 = version >= FORMAT_VERSION
            val autoHeadroomIndex = if (isV2) 13 else -1
            val directVolumeIndex = if (isV2) 14 else 13
            val volumeIndex = if (isV2) 15 else 14

            DspState(
                enabled = parts.getOrNull(1)?.toIntOrNull() == 1,
                preampDb = parts.getOrNull(2)?.toFloatOrNull()?.coerceIn(
                    DspState.MIN_PREAMP_DB, DspState.MAX_PREAMP_DB
                ) ?: default.preampDb,
                tone = ToneSettings(
                    bassDb = parts.getOrNull(3)?.toFloatOrNull()?.coerceIn(
                        ToneSettings.MIN_DB, ToneSettings.MAX_DB
                    ) ?: default.tone.bassDb,
                    midDb = parts.getOrNull(4)?.toFloatOrNull()?.coerceIn(
                        ToneSettings.MIN_DB, ToneSettings.MAX_DB
                    ) ?: default.tone.midDb,
                    trebleDb = parts.getOrNull(5)?.toFloatOrNull()?.coerceIn(
                        ToneSettings.MIN_DB, ToneSettings.MAX_DB
                    ) ?: default.tone.trebleDb
                ),
                graphicEq = GraphicEqSettings(
                    enabled = parts.getOrNull(6)?.toIntOrNull() == 1,
                    gainsDb = parts.getOrNull(7)?.split(LIST)
                        ?.mapNotNull { it.toFloatOrNull() }
                        ?.take(GraphicEqSettings.BAND_COUNT)
                        ?.plus(List(GraphicEqSettings.BAND_COUNT) { 0f })
                        ?.take(GraphicEqSettings.BAND_COUNT)
                        ?: default.graphicEq.gainsDb
                ),
                parametricEq = ParametricEqSettings(
                    enabled = parts.getOrNull(8)?.toIntOrNull() == 1,
                    nodes = parts.getOrNull(9)?.split(NODE)
                        ?.mapNotNull(::parseNode)
                        .orEmpty()
                ),
                balance = parts.getOrNull(10)?.toFloatOrNull()?.coerceIn(
                    -DspState.MAX_BALANCE, DspState.MAX_BALANCE
                ) ?: default.balance,
                stereoWidth = parts.getOrNull(11)?.toFloatOrNull()?.coerceIn(
                    0f, DspState.MAX_STEREO_WIDTH
                ) ?: default.stereoWidth,
                limiterEnabled = parts.getOrNull(12)?.toIntOrNull()?.let { it == 1 }
                    ?: default.limiterEnabled,
                autoHeadroom = parts.getOrNull(autoHeadroomIndex)?.toIntOrNull()?.let { it == 1 }
                    ?: default.autoHeadroom,
                directVolumeControl = parts.getOrNull(directVolumeIndex)?.toIntOrNull()
                    ?.let { it != 0 } ?: true,
                volumePercent = parts.getOrNull(volumeIndex)?.toIntOrNull()?.coerceIn(
                    0, DspState.MAX_VOLUME_PERCENT
                ) ?: default.volumePercent
            )
        } catch (e: Exception) {
            DspState()
        }
    }

    private fun parseNode(raw: String): ParametricNode? {
        val fields = raw.split(FIELD)
        if (fields.size < 5) return null
        val id = fields[0].toLongOrNull() ?: return null
        val type = FilterType.entries.firstOrNull { it.name == fields[1] } ?: return null
        val freq = fields[2].toFloatOrNull() ?: return null
        val gain = fields[3].toFloatOrNull() ?: 0f
        val q = fields[4].toFloatOrNull() ?: 1.2f
        return ParametricNode(
            id = id,
            type = type,
            frequencyHz = freq.coerceIn(ParametricNode.MIN_FREQ_HZ, ParametricNode.MAX_FREQ_HZ),
            gainDb = gain.coerceIn(ParametricNode.MIN_GAIN_DB, ParametricNode.MAX_GAIN_DB),
            q = q.coerceIn(ParametricNode.MIN_Q, ParametricNode.MAX_Q)
        )
    }

    // --------------------------------------------------------------- presets

    /** "name|state" entries separated by newlines. */
    fun serializePresets(presets: List<DspPreset>): String =
        presets.joinToString(ENTRY) { preset ->
            "${sanitizeName(preset.name)}$FIELD${serialize(presetState(preset))}"
        }

    fun deserializePresets(raw: String?): List<DspPreset> =
        raw?.split(ENTRY)
            ?.mapNotNull { entry ->
                val parts = entry.split(FIELD, limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val name = parts[0].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                DspPreset(
                    name = name,
                    graphicGainsDb = deserialize(parts[1]).graphicEq.gainsDb,
                    fullState = deserialize(parts[1]),
                    isBuiltIn = false
                )
            }
            .orEmpty()

    private fun presetState(preset: DspPreset): DspState =
        preset.fullState ?: DspState(
            enabled = true,
            graphicEq = GraphicEqSettings(enabled = true, gainsDb = preset.graphicGainsDb),
            tone = preset.tone
        )

    fun sanitizeName(name: String): String = name.trim().replace(SEP, " ").replace(FIELD, " ").take(40)

    private fun trim(value: Float): String {
        // Drop trailing zeros so the payload stays compact ("6" instead of "6.0").
        val rounded = (value * 1000).toInt() / 1000f
        return rounded.toString()
    }

    private const val SEP = ";"
    private const val LIST = ","
    private const val NODE = "/"
    private const val FIELD = ":"
    private const val ENTRY = "\n"
}
