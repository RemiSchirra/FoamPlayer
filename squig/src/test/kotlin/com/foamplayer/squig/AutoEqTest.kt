package com.foamplayer.squig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class AutoEqTest {

    /** Flat curve from 20 Hz to 20 kHz at [db]. */
    private fun flat(db: Float = 0f): FrequencyResponseCurve =
        curveOf { db }

    /** A 1-octave peaking shape at [f0]/[q] with [gain], sampled like a REW export. */
    private fun peaked(f0: Float, q: Float, gain: Float): FrequencyResponseCurve {
        val response = AutoEq.peakingResponseDb(
            AutoEq.logGrid(20f, 20_000f, 12),
            f0,
            gain,
            q
        )
        val grid = AutoEq.logGrid(20f, 20_000f, 12)
        return FrequencyResponseCurve(grid.mapIndexed { i, hz ->
            FrequencyResponseCurve.Point(hz, response[i])
        })
    }

    private fun curveOf(value: (Float) -> Float): FrequencyResponseCurve {
        val grid = AutoEq.logGrid(20f, 20_000f, 12)
        return FrequencyResponseCurve(grid.map { hz -> FrequencyResponseCurve.Point(hz, value(hz)) })
    }

    @Test
    fun `interpolation is linear on the log frequency axis`() {
        val curve = FrequencyResponseCurve(
            listOf(
                FrequencyResponseCurve.Point(100f, 0f),
                FrequencyResponseCurve.Point(1000f, 12f),
            )
        )
        // 100 Hz * 10^(1/2) is halfway on the log axis between 100 and 1000 Hz.
        val mid = 100f * 10.0f.pow(0.5f)
        assertEquals(6f, curve.interpolate(mid), 0.01f)
        // Outside the range: constant extension.
        assertEquals(0f, curve.interpolate(50f), 0.001f)
        assertEquals(12f, curve.interpolate(5000f), 0.001f)
    }

    @Test
    fun `averaging both channels averages their levels`() {
        val left = flat(0f)
        val right = flat(6f)
        val averaged = FrequencyResponseCurve.average(listOf(left, right))
        assertEquals(3f, averaged.interpolate(500f), 0.05f)
    }

    @Test
    fun `constant offset is centered away and needs no filters`() {
        val result = AutoEq.compute(source = flat(0f), target = flat(10f))
        assertTrue(result.filters.isEmpty())
        assertEquals(0f, result.preampDb, 0.001f)
        assertEquals(0f, result.residualRmsDb, 0.05f)
    }

    @Test
    fun `single boosted peak is recovered with the right center and q`() {
        val source = flat(0f)
        val target = peaked(f0 = 100f, q = 1.4f, gain = 6f)
        val result = AutoEq.compute(source, target, maxFilters = 4)

        assertTrue("expected at least one filter", result.filters.isNotEmpty())
        val filter = result.filters.first()

        // The centered difference loses a little level to the 40 Hz–10 kHz mean,
        // so the fitted gain sits slightly below 6 dB.
        assertTrue("gain ${filter.gainDb}", filter.gainDb in 4.5f..6.2f)
        assertTrue("frequency ${filter.frequencyHz}", filter.frequencyHz in 90f..115f)
        assertTrue("q ${filter.q}", filter.q in 1.1f..2.2f)

        // Pre-amp cancels the fitted peak, and the fit converges to a small residual.
        assertTrue("preamp ${result.preampDb}", result.preampDb < -3.5f)
        assertTrue("rms ${result.residualRmsDb}", result.residualRmsDb < 1.5f)
    }

    @Test
    fun `dip is fitted with negative gain`() {
        val source = flat(0f)
        val target = peaked(f0 = 3000f, q = 2f, gain = -8f)
        val result = AutoEq.compute(source, target, maxFilters = 4)

        assertTrue(result.filters.isNotEmpty())
        val filter = result.filters.first()
        assertTrue("gain ${filter.gainDb}", filter.gainDb < -4f)
        assertTrue("frequency ${filter.frequencyHz}", filter.frequencyHz in 2700f..3400f)
    }

    @Test
    fun `preamp keeps the applied boost in headroom`() {
        val result = AutoEq.compute(source = flat(0f), target = peaked(60f, 1f, 14f))
        val appliedPeak = result.filters.maxOfOrNull { abs(it.gainDb) } ?: 0f
        assertTrue(
            "preamp ${result.preampDb} vs peak $appliedPeak",
            result.preampDb <= -appliedPeak + 0.5f
        )
        assertTrue(result.preampDb >= -AutoEq.MAX_PREAMP_DB)
    }

    @Test
    fun `filter gain and q stay inside the app limits`() {
        val result = AutoEq.compute(
            source = flat(0f),
            target = peaked(500f, 0.1f, 25f),
            maxFilters = 6
        )
        for (filter in result.filters) {
            assertTrue(filter.gainDb >= -AutoEq.MAX_FILTER_GAIN_DB)
            assertTrue(filter.gainDb <= AutoEq.MAX_FILTER_GAIN_DB)
            assertTrue(filter.q >= AutoEq.MIN_FILTER_Q)
            assertTrue(filter.q <= AutoEq.MAX_FILTER_Q)
        }
    }
}
