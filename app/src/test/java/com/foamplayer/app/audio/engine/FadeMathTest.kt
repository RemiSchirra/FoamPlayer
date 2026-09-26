package com.foamplayer.app.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests the pure math the fade engine relies on: the equal-power ramp curves
 * and the crossfade trigger arithmetic (including short-track clamping).
 */
class FadeMathTest {

    // ------------------------------------------------------------- ramp curves

    @Test
    fun `fade out curve starts at 1 and ends at 0`() {
        assertEquals(1f, FadeMath.fadeOutGain(0f), 1e-6f)
        assertEquals(0f, FadeMath.fadeOutGain(1f), 1e-6f)
    }

    @Test
    fun `fade in curve starts at 0 and ends at 1`() {
        assertEquals(0f, FadeMath.fadeInGain(0f), 1e-6f)
        assertEquals(1f, FadeMath.fadeInGain(1f), 1e-6f)
    }

    @Test
    fun `equal power sum is constant across the whole ramp`() {
        // sin^2 + cos^2 == 1: overlapping streams never dip in loudness.
        var step = 0
        while (step <= 1000) {
            val t = step / 1000f
            val sum = FadeMath.equalPowerSum(t)
            assertTrue("t=$t sum=$sum", abs(sum - 1f) < 1e-4f)
            step++
        }
    }

    @Test
    fun `ramp curves are monotonic and never exceed their bounds`() {
        var step = 0
        while (step <= 1000) {
            val t = step / 1000f
            val out = FadeMath.fadeOutGain(t)
            val inn = FadeMath.fadeInGain(t)
            assertTrue("out=$out", out in 0f..1f)
            assertTrue("in=$inn", inn in 0f..1f)
            if (step > 0) {
                val previousOut = FadeMath.fadeOutGain((step - 1) / 1000f)
                val previousIn = FadeMath.fadeInGain((step - 1) / 1000f)
                assertTrue(out <= previousOut)
                assertTrue(inn >= previousIn)
            }
            step++
        }
    }

    @Test
    fun `progress values are clamped into 0-1`() {
        assertEquals(1f, FadeMath.fadeOutGain(-0.5f), 1e-6f)
        assertEquals(1f, FadeMath.fadeInGain(1.5f), 1e-6f)
        assertEquals(0f, FadeMath.fadeInGain(-0.5f), 1e-6f)
        assertEquals(0f, FadeMath.fadeOutGain(1.5f), 1e-6f)
    }

    // -------------------------------------------------- trigger threshold math

    @Test
    fun `crossfade threshold is clamped to half the track`() {
        // Long track: threshold = the user's fade.
        assertEquals(10_000L, FadeMath.crossfadeStartThresholdMs(10_000L, 300_000L))
        // Short track: threshold = half the track, never the full fade.
        assertEquals(2_000L, FadeMath.crossfadeStartThresholdMs(10_000L, 4_000L))
        assertEquals(2_000L, FadeMath.crossfadeStartThresholdMs(10_000L, 3_999L))
    }

    @Test
    fun `clamped fade duration never exceeds half the track`() {
        assertEquals(10_000L, FadeMath.clampedFadeDurationMs(10_000L, 300_000L))
        assertEquals(2_000L, FadeMath.clampedFadeDurationMs(10_000L, 4_000L))
        // Unknown duration -> no fade.
        assertEquals(0L, FadeMath.clampedFadeDurationMs(10_000L, 0L))
    }

    @Test
    fun `crossfade triggers inside the threshold window only`() {
        val normalFadeMs = 10_000L
        val durationMs = 300_000L

        // Early in the track: no fade.
        assertFalse(FadeMath.shouldStartCrossfade(durationMs - 1, normalFadeMs, durationMs))
        // Exactly at the threshold boundary: fires.
        assertTrue(FadeMath.shouldStartCrossfade(10_000L, normalFadeMs, durationMs))
        // Just before it: no fade.
        assertFalse(FadeMath.shouldStartCrossfade(10_001L, normalFadeMs, durationMs))
        // One frame of runway left: still fires.
        assertTrue(FadeMath.shouldStartCrossfade(1L, normalFadeMs, durationMs))
        // No runway left: too late, the natural transition takes over.
        assertFalse(FadeMath.shouldStartCrossfade(0L, normalFadeMs, durationMs))
        assertFalse(FadeMath.shouldStartCrossfade(-5L, normalFadeMs, durationMs))
    }

    @Test
    fun `short track triggers at its own clamped threshold`() {
        val normalFadeMs = 10_000L
        val durationMs = 6_000L
        val threshold = FadeMath.crossfadeStartThresholdMs(normalFadeMs, durationMs)
        assertEquals(3_000L, threshold)

        assertTrue(FadeMath.shouldStartCrossfade(2_999L, normalFadeMs, durationMs))
        assertFalse(FadeMath.shouldStartCrossfade(3_001L, normalFadeMs, durationMs))
    }

    @Test
    fun `zero or unknown duration never triggers`() {
        assertFalse(FadeMath.shouldStartCrossfade(1L, 10_000L, 0L))
        assertFalse(FadeMath.shouldStartCrossfade(1L, 0L, 300_000L))
    }
}
