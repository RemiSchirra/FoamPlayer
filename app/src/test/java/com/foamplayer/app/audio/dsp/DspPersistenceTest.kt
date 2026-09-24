package com.foamplayer.app.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspPersistenceTest {

    @Test
    fun `state round trips through serialization`() {
        val state = DspState(
            enabled = true,
            preampDb = -3.5f,
            tone = ToneSettings(bassDb = 4f, midDb = -1.5f, trebleDb = 2.5f),
            graphicEq = GraphicEqSettings(
                enabled = true,
                count = 10,
                gainsDb = listOf(6f, 5f, 3f, 1f, 0f, -1f, 0f, 2f, 4f, 5.5f)
            ),
            parametricEq = ParametricEqSettings(
                enabled = true,
                nodes = listOf(
                    ParametricNode(
                        id = 1, type = FilterType.PEAKING,
                        frequencyHz = 120f, gainDb = 3f, q = 0.7f,
                        colorArgb = NodeColorPalette[2]
                    ),
                    ParametricNode(
                        id = 2, type = FilterType.HIGH_PASS,
                        frequencyHz = 9_500f, gainDb = 0f, q = 2f,
                        colorArgb = NodeColorPalette[5]
                    )
                )
            ),
            balance = -0.5f,
            stereoWidth = 1.5f,
            limiterEnabled = false,
            autoHeadroom = true,
            directVolumeControl = false,
            volumePercent = 80
        )

        val restored = DspPersistence.deserialize(DspPersistence.serialize(state))

        assertEquals(state, restored)
    }

    @Test
    fun `v4 payload stores and reads the band count`() {
        val serialized = DspPersistence.serialize(
            DspState(graphicEq = GraphicEqSettings(enabled = true, count = 31))
        )
        assertTrue(serialized.startsWith("4;"))
        val restored = DspPersistence.deserialize(serialized)
        assertEquals(31, restored.graphicEq.count)
        assertEquals(31, restored.graphicEq.gainsDb.size)
    }

    @Test
    fun `v3 payloads read as 21-band layouts with default slope`() {
        // A v3 payload: 21 gains (first is 6), one node without slope/color fields.
        val gains = List(21) { if (it == 0) "6" else "0" }.joinToString(",")
        val v3 = "3;1;0;0;0;0;1;$gains;1;1:PEAKING:120:3:0.7;-0.5;1;1;1;0;1;80"
        val restored = DspPersistence.deserialize(v3)

        assertTrue(restored.graphicEq.enabled)
        assertEquals(21, restored.graphicEq.count)
        assertEquals(6f, restored.graphicEq.gainsDb.first())
        assertTrue(restored.parametricEq.enabled)
        val node = restored.parametricEq.nodes.single()
        assertEquals(120f, node.frequencyHz)
        assertEquals(0.9f, node.slope)
        assertTrue(node.colorArgb in NodeColorPalette)
    }

    @Test
    fun `v2 payloads without node colors parse with palette defaults`() {
        // A v2 payload (node format lacks the color and slope fields): one node at 120 Hz.
        val v2 = "2;1;0;0;0;0;0;0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0;1;1:PEAKING:120:3:0.7;-0.5;1;1;1;0;1;80"
        val restored = DspPersistence.deserialize(v2)

        assertTrue(restored.parametricEq.enabled)
        val node = restored.parametricEq.nodes.single()
        assertEquals(120f, node.frequencyHz)
        assertEquals(3f, node.gainDb)
        assertTrue(node.colorArgb in NodeColorPalette)
    }

    @Test
    fun `v1 payloads parse on legacy indices without autoHeadroom`() {
        // A v1 payload: nodes empty, balance -0.5, width 1, limiter=1, dvc=1, volume=80
        // (autoHeadroom did not exist).
        val v1 = "1;1;0;0;0;0;1;6,0,0,0,0,0,0,0,0,0;0;;-0.5;1;1;1;80"
        val restored = DspPersistence.deserialize(v1)

        assertTrue(restored.enabled)
        assertTrue(restored.graphicEq.enabled)
        assertEquals(21, restored.graphicEq.count)
        assertEquals(-0.5f, restored.balance)
        assertTrue(restored.limiterEnabled)
        assertTrue(!restored.autoHeadroom)
        assertTrue(restored.directVolumeControl)
        assertEquals(80, restored.volumePercent)
    }

    @Test
    fun `default state round trips`() {
        val state = DspState()
        assertEquals(state, DspPersistence.deserialize(DspPersistence.serialize(state)))
    }

    @Test
    fun `malformed input falls back to defaults`() {
        assertEquals(DspState(), DspPersistence.deserialize("garbage"))
        assertEquals(DspState(), DspPersistence.deserialize(null))
        assertEquals(DspState(), DspPersistence.deserialize(""))
    }

    @Test
    fun `out of range values are clamped`() {
        val restored = DspPersistence.deserialize(
            DspPersistence.serialize(DspState(preampDb = 999f, volumePercent = 5000))
        )
        assertTrue(restored.preampDb <= DspState.MAX_PREAMP_DB)
        assertTrue(restored.volumePercent == DspState.MAX_VOLUME_PERCENT)
    }

    @Test
    fun `band count out of range is clamped`() {
        val restored = DspPersistence.deserialize(
            DspPersistence.serialize(DspState(graphicEq = GraphicEqSettings(count = 999)))
        )
        // 999 clamps to MAX_BAND_COUNT (31); gains pad to 31 zeros.
        assertEquals(GraphicEqSettings.MAX_BAND_COUNT, restored.graphicEq.count)
        assertEquals(GraphicEqSettings.MAX_BAND_COUNT, restored.graphicEq.gainsDb.size)
    }

    @Test
    fun `user presets round trip with kind derivation`() {
        val presets = listOf(
            DspPreset(
                name = "My V",
                kind = PresetKind.GRAPHIC,
                graphicGainsDb = List(GraphicEqSettings.DEFAULT_BAND_COUNT) { 0f },
                fullState = DspState(enabled = true, balance = 0.5f),
                isBuiltIn = false
            ),
            DspPreset(
                name = "Late Night",
                kind = PresetKind.GRAPHIC,
                graphicGainsDb = List(GraphicEqSettings.DEFAULT_BAND_COUNT) { 0f },
                fullState = DspState(
                    enabled = true,
                    tone = ToneSettings(bassDb = 2f, trebleDb = -1f),
                    graphicEq = GraphicEqSettings(enabled = true)
                ),
                isBuiltIn = false
            ),
            DspPreset(
                name = "Node Play",
                kind = PresetKind.PARAMETRIC,
                parametricNodes = listOf(
                    ParametricNode(id = 1, frequencyHz = 500f, gainDb = 4f, colorArgb = NodeColorPalette[1])
                ),
                fullState = DspState(
                    enabled = true,
                    parametricEq = ParametricEqSettings(
                        enabled = true,
                        nodes = listOf(ParametricNode(id = 1, frequencyHz = 500f, gainDb = 4f, colorArgb = NodeColorPalette[1]))
                    ),
                    graphicEq = GraphicEqSettings(enabled = false)
                ),
                isBuiltIn = false
            )
        )

        val restored = DspPersistence.deserializePresets(DspPersistence.serializePresets(presets))

        assertEquals(presets.size, restored.size)
        assertEquals(presets[0].name, restored[0].name)
        assertEquals(presets[0].fullState, restored[0].fullState)
        assertEquals(presets[1].fullState, restored[1].fullState)
        assertEquals(presets[2].fullState, restored[2].fullState)
        assertEquals(PresetKind.GRAPHIC, restored[0].kind)
        assertEquals(PresetKind.PARAMETRIC, restored[2].kind)
        assertTrue(restored.all { !it.isBuiltIn })
    }

    @Test
    fun `built-in presets are either graphic or parametric`() {
        DspPreset.BUILT_INS.forEach { preset ->
            when (preset.kind) {
                PresetKind.GRAPHIC ->
                    assertEquals(31, preset.graphicGainsDb.size)
                PresetKind.PARAMETRIC -> {
                    assertTrue(preset.parametricNodes.isNotEmpty())
                    preset.parametricNodes.forEach { node ->
                        assertTrue(node.colorArgb in NodeColorPalette)
                    }
                }
            }
        }
        assertTrue(DspPreset.BUILT_INS.any { it.kind == PresetKind.PARAMETRIC })
    }
}
