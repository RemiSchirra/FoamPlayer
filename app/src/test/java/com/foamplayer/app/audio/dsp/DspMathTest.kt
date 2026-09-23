package com.foamplayer.app.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DspMathTest {

    private val defaultState = DspState()

    @Test
    fun `neutral state builds no chain`() {
        assertNull(DspMath.buildChain(defaultState(), 48_000))
    }

    @Test
    fun `enabled EQ with boost builds a chain with filters`() {
        val state = defaultState().copy(
            enabled = true,
            graphicEq = GraphicEqSettings(enabled = true, gainsDb = listOf(6f) + List(GraphicEqSettings.BAND_COUNT - 1) { 0f })
        )
        val chain = DspMath.buildChain(state, 48_000)
        assertNotNull(chain)
        assertTrue(chain!!.filters.isNotEmpty())
    }

    @Test
    fun `volume only is not passthrough when below 100 percent`() {
        val state = defaultState().copy(volumePercent = 50)
        assertNotNull(DspMath.buildChain(state, 48_000))
    }

    @Test
    fun `volume only at full scale with DVC is passthrough`() {
        val state = defaultState().copy(volumePercent = 100)
        assertNull(DspMath.buildChain(state, 48_000))
    }

    @Test
    fun `non neutral state is flagged as such`() {
        val boosted = defaultState().copy(
            enabled = true,
            graphicEq = GraphicEqSettings(enabled = true, gainsDb = List(GraphicEqSettings.BAND_COUNT) { 3f })
        )
        assertTrue(!boosted.isNeutral)
    }

    @Test
    fun `peaking boost shows its gain at the center frequency`() {
        val state = defaultState().copy(
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
        val state = defaultState().copy(
            enabled = true,
            graphicEq = GraphicEqSettings(enabled = true, gainsDb = List(GraphicEqSettings.BAND_COUNT) { 12f })
        )
        val response = DspMath.combinedResponse(state, sampleRateHz = 48_000)
        assertTrue(response.all { it < 60f && it > -120f })
    }

    @Test
    fun `volume percent maps to 0 dB at full and negative below`() {
        assertTrue(DspMath.volumePercentToDb(100) == 0f)
        assertTrue(DspMath.volumePercentToDb(0) == -60f)
        assertTrue(DspMath.volumePercentToDb(50) < 0f)
    }

    @Test
    fun `limiter keeps output below 1`() {
        assertTrue(DspMath.limit(0.5f) == 0.5f)
        assertTrue(abs(DspMath.limit(2f)) <= 1f)
        assertTrue(abs(DspMath.limit(-2f)) <= 1f)
    }

    @Test
    fun `softClip is transparent below the knee and caps at full scale`() {
        assertTrue(DspMath.softClip(0.5f) == 0.5f)
        assertTrue(DspMath.softClip(-0.5f) == -0.5f)
        val clipped = DspMath.softClip(5f)
        assertTrue("clipped=$clipped", clipped <= 1f && clipped > 0.98f)
        assertTrue(abs(DspMath.softClip(-5f)) <= 1f)
    }

    @Test
    fun `boosted EQ with autoHeadroom yields negative headroom`() {
        val boosted = DspState(
            enabled = true,
            autoHeadroom = true,
            graphicEq = GraphicEqSettings(enabled = true, gainsDb = listOf(6f) + List(GraphicEqSettings.BAND_COUNT - 1) { 0f })
        )
        val headroom = DspMath.headroomDb(boosted)
        assertTrue("headroom=$headroom", headroom < 0f)
        // The compensation should roughly match the boost (±2 dB for sampling error).
        assertTrue("headroom=$headroom", headroom > -8f)
    }

    @Test
    fun `headroom is off by default so boosts are heard as boosts`() {
        val boosted = DspState(
            enabled = true,
            graphicEq = GraphicEqSettings(enabled = true, gainsDb = listOf(6f) + List(GraphicEqSettings.BAND_COUNT - 1) { 0f })
        )
        assertTrue(DspMath.headroomDb(boosted) == 0f)
        val chain = DspMath.buildChain(boosted, 48_000)
        assertNotNull(chain)
        assertTrue(chain!!.headroomLinear == 1f)
    }

    @Test
    fun `cut-only or neutral EQ yields no headroom reduction`() {
        assertTrue(DspMath.headroomDb(DspState()) == 0f)

        val cutOnly = DspState(
            enabled = true,
            autoHeadroom = true,
            graphicEq = GraphicEqSettings(enabled = true, gainsDb = listOf(-6f) + List(GraphicEqSettings.BAND_COUNT - 1) { 0f })
        )
        assertTrue(DspMath.headroomDb(cutOnly) == 0f)
    }

    @Test
    fun `boosted chain with autoHeadroom applies headroom before filters and gain after`() {
        val boosted = DspState(
            enabled = true,
            preampDb = 0f,
            autoHeadroom = true,
            graphicEq = GraphicEqSettings(enabled = true, gainsDb = listOf(6f) + List(GraphicEqSettings.BAND_COUNT - 1) { 0f })
        )
        val chain = DspMath.buildChain(boosted, 48_000)
        assertNotNull(chain)
        assertTrue(chain!!.headroomLinear < 1f)
        // Volume at 100% and preamp at 0: output gain stays unity.
        assertTrue(chain.outputGainLinear == 1f)
        // Passthrough is false: the chain actually processes.
        assertTrue(!chain.isPassthrough)
    }

    @Test
    fun `limiter defaults on and engages only with DSP enabled`() {
        assertTrue(DspState().limiterEnabled)

        // DSP off: the limiter must never break the bit-perfect passthrough.
        val idle = DspState(enabled = false)
        assertNull(DspMath.buildChain(idle, 48_000))

        // DSP on with a flat EQ: limiter-only processing chain.
        val flatDsp = DspState(enabled = true)
        val chain = DspMath.buildChain(flatDsp, 48_000)
        assertNotNull(chain)
        assertTrue(chain!!.limiterEnabled)
        assertTrue(chain.filters.isEmpty())
        assertTrue(!chain.isPassthrough)
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

    private fun defaultState(): DspState = DspState()

    private fun abs(value: Float): Float = kotlin.math.abs(value)
}

