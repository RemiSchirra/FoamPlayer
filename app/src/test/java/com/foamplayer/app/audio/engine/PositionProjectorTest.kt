package com.foamplayer.app.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the playback-clock projector: dead reckoning between quantized player samples, the
 * monotonic guard on small backward corrections, and the priming/settle states.
 */
class PositionProjectorTest {

    // A non-zero base: the projector treats a zero timestamp as "no anchor yet".
    private val t0 = 1_000_000_000L

    private fun ms(value: Long): Long = value * 1_000_000L

    @Test
    fun `first sample is published as is`() {
        val projector = PositionProjector()

        assertEquals(
            1_000L,
            projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0)
        )
    }

    @Test
    fun `negative samples clamp to zero`() {
        val projector = PositionProjector()

        assertEquals(
            0L,
            projector.project(-50, audible = true, loading = false, priming = false, nowNanos = t0)
        )
    }

    @Test
    fun `a settled pause freezes the published clock`() {
        val projector = PositionProjector()

        assertEquals(
            1_000L,
            projector.project(1_000, audible = false, loading = false, priming = false, nowNanos = t0)
        )
        // Wall clock advances, the sample does not: the position must stay put.
        assertEquals(
            1_000L,
            projector.project(1_000, audible = false, loading = false, priming = false, nowNanos = t0 + ms(5_000))
        )
        assertEquals(
            1_000L,
            projector.project(1_000, audible = false, loading = false, priming = false, nowNanos = t0 + ms(30_000))
        )
    }

    @Test
    fun `dead reckoning resumes once the settle window elapses`() {
        val projector = PositionProjector()
        projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0)

        // Still waiting for the stream clock: the position is held at the sample.
        assertEquals(
            1_000L,
            projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0 + ms(320))
        )
        // 16 ms later the anchor moves again.
        assertEquals(
            1_016L,
            projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0 + ms(336))
        )
    }

    @Test
    fun `small backward corrections are held instead of stepping back`() {
        val projector = PositionProjector()
        projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0)
        projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0 + ms(320))
        val published = projector.project(
            1_000,
            audible = true,
            loading = false,
            priming = false,
            nowNanos = t0 + ms(336)
        )
        assertEquals(1_016L, published)

        // The sample drops by 80 ms (clock quantization), which would project to ~1004 ms.
        val held = projector.project(
            920,
            audible = true,
            loading = false,
            priming = false,
            nowNanos = t0 + ms(352)
        )
        assertEquals(published, held)
    }

    @Test
    fun `a discontinuity snaps to the new sample`() {
        val projector = PositionProjector()
        projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0)
        projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0 + ms(320))

        // A 4-second jump is a seek, not played audio.
        assertEquals(
            5_000L,
            projector.project(5_000, audible = true, loading = false, priming = false, nowNanos = t0 + ms(336))
        )
    }

    @Test
    fun `priming holds at the target until the clock really starts`() {
        val projector = PositionProjector()

        assertEquals(
            1_000L,
            projector.project(1_000, audible = true, loading = false, priming = true, nowNanos = t0)
        )
        assertEquals(
            1_000L,
            projector.project(1_000, audible = true, loading = false, priming = true, nowNanos = t0 + ms(100))
        )
        // Priming ends: the settle window starts.
        assertEquals(
            1_000L,
            projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0 + ms(116))
        )
        // Settle timeout re-anchors, then normal dead reckoning resumes.
        assertEquals(
            1_000L,
            projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0 + ms(500))
        )
        assertEquals(
            1_016L,
            projector.project(1_000, audible = true, loading = false, priming = false, nowNanos = t0 + ms(516))
        )
    }

    @Test
    fun `buffering counts as priming`() {
        val projector = PositionProjector()

        assertEquals(
            0L,
            projector.project(0, audible = false, loading = true, priming = true, nowNanos = t0)
        )
        assertEquals(
            0L,
            projector.project(0, audible = false, loading = true, priming = true, nowNanos = t0 + ms(200))
        )
    }

    @Test
    fun `published clock never runs backwards under sample jitter`() {
        val projector = PositionProjector()
        var now = t0
        var sample = 1_000L
        var lastPublished = -1L

        repeat(400) { tick ->
            now += ms(16)
            sample += 16
            // The player's quantized clock regularly reports a few milliseconds less.
            if (tick % 7 == 0) sample -= 20

            val published = projector.project(
                sample,
                audible = true,
                loading = false,
                priming = false,
                nowNanos = now
            )
            if (lastPublished >= 0L) {
                assertTrue("tick $tick: $published after $lastPublished", published >= lastPublished)
            }
            lastPublished = published
        }
    }
}
