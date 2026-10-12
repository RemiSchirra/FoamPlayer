package com.foamplayer.app.audio.dsp

/**
 * Compact string serialization of [DspState] and user [DspPreset]s for DataStore
 * storage. Fields separated by ';', format version prefixed first:
 *
 * v4: `4;enabled;preamp;bass;mid;treble;gEnabled;gCount;g1,..,gN;pEnabled;id:type:freq:gain:q:slope:color/..;balance;width;limiter;autoHeadroom;dvc;volume`
 * v3: same without the band count and node slope fields (bands read as 21, slope as 0.9).
 * v2: same without the node color field (nodes read with the palette default).
 * v1: same without the autoHeadroom field (legacy payloads read with it = false).
 *
 * The limiter and autoHeadroom switches were removed from the app (the chain is
 * now purely linear); v4 payloads still carry their slots so older backups and
 * restored settings never break the field alignment — they are parsed and
 * silently dropped.
 *
 * Parsing is defensive: any malformed field falls back to the default value, so a
 * bad or older payload can never crash the audio path. Gains lists of any legacy
 * length are trimmed/padded to their declared band count, and the count itself is
 * clamped to the supported range.
 */
object DspPersistence {

    private const val FORMAT_VERSION = 4

    /** Band layout v1-v3 payloads were written with (before the ISO enum picker). */
    private const val LEGACY_BAND_COUNT = 21

    fun serialize(state: DspState): String = buildString {
        append(FORMAT_VERSION)
        append(SEP).append(if (state.enabled) "1" else "0")
        append(SEP).append(state.preampDb)
        append(SEP).append(state.tone.bassDb)
        append(SEP).append(state.tone.midDb)
        append(SEP).append(state.tone.trebleDb)
        append(SEP).append(if (state.graphicEq.enabled) "1" else "0")
        append(SEP).append(state.graphicEq.count)
        append(SEP).append(state.graphicEq.normalizedGains().joinToString(LIST) { trim(it) })
        append(SEP).append(if (state.parametricEq.enabled) "1" else "0")
        append(SEP).append(
            state.parametricEq.nodes.joinToString(NODE) { node ->
                listOf(
                    node.id.toString(),
                    node.type.name,
                    trim(node.frequencyHz),
                    trim(node.gainDb),
                    trim(node.q),
                    trim(node.slope),
                    node.colorArgb.toString(16)
                ).joinToString(FIELD)
            }
        )
        append(SEP).append(state.balance)
        append(SEP).append(state.stereoWidth)
        // Removed switches: slots kept so v4 field alignment stays stable across
        // app versions; the values are written as the new-chain defaults.
        append(SEP).append("0") // limiter (removed — chain is purely linear)
        append(SEP).append("0") // auto headroom (removed — chain is purely linear)
        append(SEP).append(if (state.directVolumeControl) "1" else "0")
        append(SEP).append(state.volumePercent)
    }

    fun deserialize(raw: String?): DspState {
        if (raw.isNullOrBlank()) return DspState()
        return try {
            val parts = raw.split(SEP)
            val default = DspState()

            // A non-numeric version prefix means the payload is not ours at all
            // (e.g. garbage) — fall back to the pure defaults, not the legacy grid.
            val version = parts.firstOrNull()?.toIntOrNull() ?: return default
            val isV4 = version >= 4
            val isV2 = version >= 2

            // Tail field indices depend on the payload version: v4 inserts the
            // band count at index 7, shifting the gains and every field after
            // them by one slot (so the tail starts at balance = 11); v1-v3 read
            // the gains at index 7 and start the tail at balance = 10. v1 lacks
            // the autoHeadroom field entirely.
            val balanceIndex = if (isV4) 11 else 10
            val widthIndex = balanceIndex + 1
            // limiter / autoHeadroom are read for alignment only and dropped —
            // the new chain has neither a limiter nor auto-headroom.
            val directVolumeIndex = if (isV2) balanceIndex + 4 else balanceIndex + 3
            val volumeIndex = directVolumeIndex + 1

            val graphicGains = parseGains(
                parts.getOrNull(if (isV4) 8 else 7),
                if (isV4) parseCount(parts.getOrNull(7)) else LEGACY_BAND_COUNT
            )

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
                    count = graphicGains.second,
                    gainsDb = graphicGains.first
                ),
                parametricEq = ParametricEqSettings(
                    enabled = parts.getOrNull(if (isV4) 9 else 8)?.toIntOrNull() == 1,
                    nodes = parts.getOrNull(if (isV4) 10 else 9)?.split(NODE)
                        ?.mapNotNull(::parseNode)
                        .orEmpty()
                ),
                balance = parts.getOrNull(balanceIndex)?.toFloatOrNull()?.coerceIn(
                    -DspState.MAX_BALANCE, DspState.MAX_BALANCE
                ) ?: default.balance,
                stereoWidth = parts.getOrNull(widthIndex)?.toFloatOrNull()?.coerceIn(
                    0f, DspState.MAX_STEREO_WIDTH
                ) ?: default.stereoWidth,
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

    /** Parses a gains list, trimmed/padded to [count] with the count clamped. */
    private fun parseGains(raw: String?, count: Int): Pair<List<Float>, Int> {
        val safeCount = count.coerceIn(GraphicEqSettings.MIN_BAND_COUNT, GraphicEqSettings.MAX_BAND_COUNT)
        val values = raw?.split(LIST)
            ?.mapNotNull { it.toFloatOrNull() }
            ?.take(safeCount)
            .orEmpty()
        return Pair(List(safeCount) { values.getOrElse(it) { 0f }.coerceIn(GraphicEqSettings.MIN_GAIN_DB, GraphicEqSettings.MAX_GAIN_DB) }, safeCount)
    }

    private fun parseCount(raw: String?): Int =
        raw?.toIntOrNull() ?: GraphicEqSettings.DEFAULT_BAND_COUNT

    private fun parseNode(raw: String): ParametricNode? {
        val fields = raw.split(FIELD)
        if (fields.size < 5) return null
        val id = fields[0].toLongOrNull() ?: return null
        val type = FilterType.entries.firstOrNull { it.name == fields[1] } ?: return null
        val freq = fields[2].toFloatOrNull() ?: return null
        val gain = fields[3].toFloatOrNull() ?: 0f
        val q = fields[4].toFloatOrNull() ?: 1.2f
        // v3 payloads have no slope field; v2/v1 have no slope and no color.
        val slope = fields.getOrNull(5)?.toFloatOrNull() ?: 0.9f
        val color = fields.getOrNull(6)?.toLongOrNull(16)
            ?: fields.getOrNull(5)?.toLongOrNull(16)
            ?: NodeColorPalette[(id % NodeColorPalette.size).toInt()]
        return ParametricNode(
            id = id,
            type = type,
            frequencyHz = freq.coerceIn(ParametricNode.MIN_FREQ_HZ, ParametricNode.MAX_FREQ_HZ),
            gainDb = gain.coerceIn(ParametricNode.MIN_GAIN_DB, ParametricNode.MAX_GAIN_DB),
            q = q.coerceIn(ParametricNode.MIN_Q, ParametricNode.MAX_Q),
            slope = slope.coerceIn(ParametricNode.MIN_SLOPE, ParametricNode.MAX_SLOPE),
            colorArgb = color
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
                val state = deserialize(parts[1])
                DspPreset(
                    name = name,
                    kind = if (state.parametricEq.enabled) PresetKind.PARAMETRIC else PresetKind.GRAPHIC,
                    graphicGainsDb = state.graphicEq.gainsDb,
                    parametricNodes = state.parametricEq.nodes,
                    fullState = state,
                    isBuiltIn = false
                )
            }
            .orEmpty()

    private fun presetState(preset: DspPreset): DspState =
        preset.fullState ?: when (preset.kind) {
            PresetKind.GRAPHIC -> DspState(
                enabled = true,
                graphicEq = GraphicEqSettings(enabled = true, gainsDb = preset.graphicGainsDb),
                tone = preset.tone
            )
            PresetKind.PARAMETRIC -> DspState(
                enabled = true,
                parametricEq = ParametricEqSettings(enabled = true, nodes = preset.parametricNodes),
                tone = preset.tone
            )
        }

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
