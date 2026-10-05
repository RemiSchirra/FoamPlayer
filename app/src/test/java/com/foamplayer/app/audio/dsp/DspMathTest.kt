package com.foamplayer.app.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class DspMathTest {

    @Test
    fun `neutral state builds no chain`() {
        assertNull(DspMath.buildChain(DspState(), 48_000))
    }

    @Test
    fun `enabled EQ with boost builds a chain with filters`() {
        val state = DspState(
            enabled = true,
            graphicEq = GraphicEqSettings(
                enabled = true,
                gainsDb = listOf(6f) + List(GraphicEqSettings.DEFAULT_BAND_COUNT - 1) { 0f }
            )
        )
        val chain = DspMath.buildChain(state, 48_000)
        assertNotNull(chain)
        assertTrue(chain!!.filters.isNotEmpty())
    }

    @Test
    fun `volume only is not passthrough when below 100 percent`() {
        val state = DspState(volumePercent = 50)
        assertNotNull(DspMath.buildChain(state, 48_000))
    }

    @Test
    fun `volume only at full scale with DVC is passthrough`() {
        val state = DspState(volumePercent = 100)
        assertNull(DspMath.buildChain(state, 48_000))
    }

    @Test
    fun `non neutral state is flagged as such`() {
        val boosted = DspState(
            enabled = true,
            graphicEq = GraphicEqSettings(
                enabled = true,
                gainsDb = List(GraphicEqSettings.DEFAULT_BAND_COUNT) { 3f }
            )
        )
        assertTrue(!boosted.isNeutral)
    }

    @Test
    fun `peaking boost shows its gain at the center frequency`() {
        val state = DspState(
            enabled = true,
            parametricEq = ParametricEqSettings(
                enabled = true,
                nodes = listOf(ParametricNode(id = 1, frequencyHz = 1000f, gainDb = 6f, q = 1f))
            )
        )
        val response = DspMath.combinedResponse(state, sampleRateHz = 48_000, pointCount = 129)
        val index = DspMath.responseIndexForFrequency(1000f, 129).toInt()
        val peak = response[index]
        assertTrue("Expected ~6dB at 1kHz, got $peak", peak in 5.0f..7.0f)
    }

    @Test
    fun `response at extremes stays within a sane range`() {
        val state = DspState(
            enabled = true,
            graphicEq = GraphicEqSettings(
                enabled = true,
                gainsDb = List(GraphicEqSettings.DEFAULT_BAND_COUNT) { 12f }
            )
        )
        val response = DspMath.combinedResponse(state, sampleRateHz = 48_000)
        assertTrue(response.all { it < 60f && it > -120f })
    }

    @Test
    fun `graphic band centers span exactly 20 Hz to 20 kHz for every layout`() {
        GraphicEqSettings.UI_BAND_COUNTS.forEach { count ->
            val first = GraphicEqSettings.bandFrequency(0, count)
            val last = GraphicEqSettings.bandFrequency(count - 1, count)
            assertEquals("first band @ $count", 20f, first, 0.01f)
            assertEquals("last band @ $count", 20_000f, last, 0.01f)
        }
    }

    @Test
    fun `graphic band count 21 reproduces the legacy 0 point15 decade grid`() {
        for (i in 0 until 21) {
            val expected = 20f * 10.0.pow(0.15 * i).toFloat()
            assertEquals(
                expected, GraphicEqSettings.bandFrequency(i, 21), expected * 0.001f
            )
        }
    }

    @Test
    fun `band Q scales with layout density`() {
        assertTrue(GraphicEqSettings.bandQ(31) > GraphicEqSettings.bandQ(21))
        assertTrue(GraphicEqSettings.bandQ(21) > GraphicEqSettings.bandQ(10))
    }

    @Test
    fun `graphic response follows gains at any band count`() {
        // 6-band: boost the lowest band, the response must rise toward 20 Hz.
        val gains6 = listOf(6f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val response = DspMath.graphicResponse(gains6, pointCount = 129)
        val lowIndex = DspMath.responseIndexForFrequency(25f, 129).toInt()
        val midIndex = DspMath.responseIndexForFrequency(1000f, 129).toInt()
        assertTrue("low=${response[lowIndex]}", response[lowIndex] > 2f)
        assertTrue("mid=${response[midIndex]}", abs(response[midIndex]) < 2f)
    }

    @Test
    fun `volume percent maps to 0 dB at full and negative below`() {
        assertTrue(DspMath.volumePercentToDb(100) == 0f)
        assertTrue(DspMath.volumePercentToDb(0) == -60f)
        assertTrue(DspMath.volumePercentToDb(50) < 0f)
    }

    @Test
    fun `boosts are heard as boosts with no volume reduction`() {
        // Purely linear chain: no auto headroom anywhere, boosts at any level
        // leave the output gain untouched.
        val boosted = DspState(
            enabled = true,
            graphicEq = GraphicEqSettings(
                enabled = true,
                gainsDb = listOf(6f) + List(GraphicEqSettings.DEFAULT_BAND_COUNT - 1) { 0f }
            )
        )
        val chain = DspMath.buildChain(boosted, 48_000)
        assertNotNull(chain)
        // Volume at 100% and preamp at 0: output gain stays unity — true boost.
        assertTrue(chain!!.outputGainLinear == 1f)
        assertTrue(chain.filters.isNotEmpty())
        assertTrue(!chain.isPassthrough)
    }

    @Test
    fun `cut-only EQ is passthrough-compatible with output gain untouched`() {
        val cutOnly = DspState(
            enabled = true,
            graphicEq = GraphicEqSettings(
                enabled = true,
                gainsDb = listOf(-6f) + List(GraphicEqSettings.DEFAULT_BAND_COUNT - 1) { 0f }
            )
        )
        val chain = DspMath.buildChain(cutOnly, 48_000)
        assertNotNull(chain)
        assertTrue(chain!!.outputGainLinear == 1f)
    }

    // ------------------------------------------------------- anti-clip saturation

    @Test
    fun `softClip is bit-exact below full scale`() {
        assertTrue(softClip(0.5f) == 0.5f)
        assertTrue(softClip(-0.5f) == -0.5f)
        assertTrue(softClip(0.9999f) == 0.9999f)
        assertTrue(softClip(-1f) == -1f)
    }

    @Test
    fun `softClip caps the tips of boosted peaks below full scale`() {
        // Everything a boost pushed past full scale lands in the shoulder and
        // converges to the ceiling — strictly below full scale, so no
        // re-quantizer downstream can flat-top or wrap it.
        assertTrue(softClip(1.001f) in 0.98f..1f)
        assertTrue(softClip(1.5f) > 0.9f)
        assertTrue(softClip(5f) > 0.9f)
        assertTrue(abs(softClip(5f)) <= SOFT_CLIP_CEILING + 1e-6f)
        assertTrue(abs(softClip(-5f)) <= SOFT_CLIP_CEILING + 1e-6f)
        assertTrue(abs(softClip(100f)) <= SOFT_CLIP_CEILING + 1e-6f)
    }

    @Test
    fun `softClip is odd-symmetric and continuous at full scale`() {
        assertTrue(softClip(0.2f) == -softClip(-0.2f))
        // Continuity at the knee: the first samples past 0 dBFS move only a
        // hair, so the rounding is smooth instead of a hard cliff.
        val above = softClip(1.0001f)
        assertTrue("above=$above", above > 0.999f && above < 1.0002f)
    }

    @Test
    fun `biquad identity has 0 dB response everywhere`() {
        val identity = BiquadCoefficients.IDENTITY
        assertEquals(0.0, identity.magnitudeDb(0.0), 1e-9)
        assertEquals(0.0, identity.magnitudeDb(1.0), 1e-9)
    }

    @Test
    fun `low pass attenuates high frequencies`() {
        val filter = BiquadDesigner.lowPass(48_000.0, 1_000.0, 0.707)
        val low = filter.magnitudeDb(2.0 * Math.PI * 100.0 / 48_000.0)
        val high = filter.magnitudeDb(2.0 * Math.PI * 16_000.0 / 48_000.0)
        assertTrue("low=$low", low > -3.0)
        assertTrue("high=$high", high < -6.0)
    }

    @Test
    fun `invalid frequency falls back to identity`() {
        val filter = BiquadDesigner.peaking(48_000.0, 48_000.0, 6.0, 1.0)
        assertEquals(BiquadCoefficients.IDENTITY, filter)
    }

    @Test
    fun `first order filters roll off 6 dB per octave`() {
        val lp = BiquadDesigner.firstOrderLowPass(48_000.0, 1_000.0)
        val at1k = lp.magnitudeDb(2.0 * Math.PI * 1_000.0 / 48_000.0)
        val at2k = lp.magnitudeDb(2.0 * Math.PI * 2_000.0 / 48_000.0)
        assertTrue("at1k=$at1k", abs(at1k + 3.0) < 0.5)
        assertTrue("at2k=$at2k", at2k < -5.5 && at2k > -7.5)

        val hp = BiquadDesigner.firstOrderHighPass(48_000.0, 1_000.0)
        val lowHp = hp.magnitudeDb(2.0 * Math.PI * 250.0 / 48_000.0)
        assertTrue("lowHp=$lowHp", lowHp < -11.0)
    }

    @Test
    fun `shelf honors custom slope`() {
        val shallow = BiquadDesigner.lowShelf(48_000.0, 100.0, 6.0, slope = 1.2)
        val steep = BiquadDesigner.lowShelf(48_000.0, 100.0, 6.0, slope = 0.5)
        // A steeper (smaller S) shelf passes more of the boost one octave up.
        val w = 2.0 * Math.PI * 200.0 / 48_000.0
        assertTrue(steep.magnitudeDb(w) > shallow.magnitudeDb(w))
    }

    // ------------------------------------------------------------------ EqGraph

    @Test
    fun `EqGraph passthrough leaves samples untouched`() {
        val graph = EqGraph()
        graph.updateChain(null)
        val samples = floatArrayOf(0.25f, -0.5f, 0.75f, -0.9f)
        val before = samples.copyOf()
        graph.process(samples, 2, 2, passthrough = true)
        assertTrue(samples.contentEquals(before))
    }

    @Test
    fun `EqGraph applies output gain to a whole buffer`() {
        val graph = EqGraph()
        // Volume-only chain: 50% DVC => (50 - 100) * 0.6 = -30 dB.
        graph.updateChain(DspMath.buildChain(DspState(volumePercent = 50), 48_000))
        val samples = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)
        graph.process(samples, 2, 2, passthrough = false)
        // -30 dB gain stays exact here: the shaped output is far below the
        // anti-clip knee, so the stage is bit-exact and only the gain applied.
        assertEquals(DspMath.dbToLinear(-30f), samples[0], 1e-3f)
    }

    @Test
    fun `EqGraph with a real boost shapes hot peaks into the anti-clip ceiling`() {
        val graph = EqGraph()
        // A real boost chain (flat DSP is passthrough and bypasses everything).
        graph.updateChain(
            DspMath.buildChain(
                DspState(
                    enabled = true,
                    graphicEq = GraphicEqSettings(
                        enabled = true,
                        gainsDb = List(GraphicEqSettings.DEFAULT_BAND_COUNT) { 6f }
                    )
                ),
                48_000
            )
        )
        // Peaks a boost pushed past full scale: shaped into the shoulder just
        // under the ceiling — no flat-tops, no crackle.
        val hot = FloatArray(256 * 2) { 1.5f }
        graph.process(hot, 256, 2, passthrough = false)
        assertTrue(hot.all { abs(it) in 0.9f..0.9999f })
        // No sample reaches full scale: int re-quantization can't wrap.
        assertTrue(hot.none { abs(it) >= 1.0f })
    }

    @Test
    fun `EqGraph balance attenuates the opposite channel`() {
        val graph = EqGraph()
        graph.updateChain(
            DspMath.buildChain(DspState(enabled = true, balance = -0.5f), 48_000)
        )
        // 0.5 stays under the knee, so balance math is verifiable bit-exact.
        val samples = floatArrayOf(0.5f, 0.5f)
        graph.process(samples, 1, 2, passthrough = false)
        // Balance -0.5: right channel scales by 1 + (-0.5) = 0.5; left stays.
        assertEquals(0.5f, samples[0], 1e-3f)
        assertEquals(0.25f, samples[1], 1e-3f)
    }

    private fun abs(value: Float): Float = kotlin.math.abs(value)
}
