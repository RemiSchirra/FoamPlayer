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
                gainsDb = listOf(6f, 5f, 3f, 1f, 0f, -1f, 0f, 2f, 4f, 5.5f)
            ),
            parametricEq = ParametricEqSettings(
                enabled = true,
                nodes = listOf(
                    ParametricNode(id = 1, type = FilterType.PEAKING, frequencyHz = 120f, gainDb = 3f, q = 0.7f),
                    ParametricNode(id = 2, type = FilterType.HIGH_PASS, frequencyHz = 9_500f, gainDb = 0f, q = 2f)
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
    fun `user presets round trip`() {
        val presets = listOf(
            DspPreset(
                name = "My V",
                graphicGainsDb = List(GraphicEqSettings.BAND_COUNT) { 0f },
                fullState = DspState(enabled = true, balance = 0.5f),
                isBuiltIn = false
            ),
            DspPreset(
                name = "Late Night",
                graphicGainsDb = listOf(2f, 1f, 0f, 0f, 0f, 0f, 0f, -1f, -2f, -3f),
                fullState = DspState(
                    enabled = true,
                    tone = ToneSettings(bassDb = 2f, trebleDb = -1f),
                    graphicEq = GraphicEqSettings(enabled = true)
                ),
                isBuiltIn = false
            )
        )

        val restored = DspPersistence.deserializePresets(DspPersistence.serializePresets(presets))

        assertEquals(presets.size, restored.size)
        assertEquals(presets[0].name, restored[0].name)
        assertEquals(presets[0].fullState, restored[0].fullState)
        assertEquals(presets[1].fullState, restored[1].fullState)
        assertTrue(restored.all { !it.isBuiltIn })
    }
}
