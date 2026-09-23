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
                gainsDb = listOf(6f, 5f, 3f, 1f, 0f, -1f, 0f, 2f, 4f, 5.5f) +
                        List(GraphicEqSettings.BAND_COUNT - 10) { 0f }
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
    fun `v2 payloads without node colors parse with palette defaults`() {
        // A v2 payload (node format lacks the color field): one node at 120 Hz.
        val v2 = "2;1;0;0;0;0;0;0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0;1;1:PEAKING:120:3:0.7;-0.5;1;1;1;0;1;80"
        val restored = DspPersistence.deserialize(v2)

        assertTrue(restored.parametricEq.enabled)
        val node = restored.parametricEq.nodes.single()
        assertEquals(120f, node.frequencyHz)
        assertEquals(3f, node.gainDb)
        assertTrue(node.colorArgb in NodeColorPalette)
    }

    @Test
    fun `21 band centers span exactly 20 Hz to 20 kHz on the log axis`() {
        val frequencies = GraphicEqSettings.BAND_FREQUENCIES
        assertEquals(21, frequencies.size)
        assertEquals(20f, frequencies.first(), 0.01f)
        assertEquals(20_000f, frequencies.last(), 0.01f)
        // Even spacing on the log10 axis: each step is 0.15 decades.
        for (i in 1 until frequencies.size) {
            val step = kotlin.math.log10(frequencies[i] / frequencies[i - 1])
            assertEquals(0.15f, step, 0.001f)
        }
    }

    @Test
    fun `v1 payloads parse on legacy indices without autoHeadroom`() {
        // A v1 payload: nodes empty, balance -0.5, width 1, limiter=1, dvc=1, volume=80
        // (autoHeadroom did not exist).
        val v1 = "1;1;0;0;0;0;1;6,0,0,0,0,0,0,0,0,0;0;;-0.5;1;1;1;80"
        val restored = DspPersistence.deserialize(v1)

        assertTrue(restored.enabled)
        assertTrue(restored.graphicEq.enabled)
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
    fun `user presets round trip with kind derivation`() {
        val presets = listOf(
            DspPreset(
                name = "My V",
                kind = PresetKind.GRAPHIC,
                graphicGainsDb = List(GraphicEqSettings.BAND_COUNT) { 0f },
                fullState = DspState(enabled = true, balance = 0.5f),
                isBuiltIn = false
            ),
            DspPreset(
                name = "Late Night",
                kind = PresetKind.GRAPHIC,
                graphicGainsDb = List(GraphicEqSettings.BAND_COUNT) { 0f },
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
                    assertEquals(GraphicEqSettings.BAND_COUNT, preset.graphicGainsDb.size)
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
