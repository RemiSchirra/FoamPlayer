package com.foamplayer.app.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the listen-threshold accounting: forward-only accumulation, gap and seek rejection,
 * single counting per occurrence, and the crossfade stash/restore.
 */
class ListenAccountingTest {

    @Test
    fun `counts once when the accumulated time reaches the threshold`() {
        val listen = ListenAccounting()
        listen.begin(7)

        assertNull(listen.accumulate(0L) { 1_500L })
        assertNull(listen.accumulate(1_000L) { 1_500L })
        assertEquals(2_000L, listen.accumulate(2_000L) { 1_500L })

        // Counted occurrences are cleared: a pause or a replay never counts twice.
        assertNull(listen.pendingTrackId)
        assertNull(listen.accumulate(3_000L) { 1_500L })
    }

    @Test
    fun `zero threshold counts on the first forward tick`() {
        val listen = ListenAccounting()
        listen.begin(3)

        assertNull(listen.accumulate(0L) { 0L })
        assertEquals(100L, listen.accumulate(100L) { 0L })
    }

    @Test
    fun `a forward jump larger than a tick gap is a seek and adds nothing`() {
        val listen = ListenAccounting()
        listen.begin(1)

        listen.accumulate(0L) { 100L }
        assertNull(listen.accumulate(5_000L) { 100L })
        assertEquals(0L, listen.elapsedMs)

        // Playback continues from the seek target.
        assertEquals(600L, listen.accumulate(5_600L) { 600L })
    }

    @Test
    fun `a backward seek replays and accumulates from the new position`() {
        val listen = ListenAccounting()
        listen.begin(1)

        listen.accumulate(5_000L) { 1_000L }
        assertNull(listen.accumulate(4_000L) { 1_000L })
        assertEquals(0L, listen.elapsedMs)

        assertNull(listen.accumulate(4_600L) { 1_000L })
        assertEquals(600L, listen.elapsedMs)
        assertEquals(1_100L, listen.accumulate(5_100L) { 1_000L })
    }

    @Test
    fun `paused ticks add nothing`() {
        val listen = ListenAccounting()
        listen.begin(1)
        listen.accumulate(1_000L) { 1_000L }

        // The caller skips paused ticks entirely; a repeated sample is a zero delta anyway.
        assertNull(listen.accumulate(1_000L) { 1_000L })
        assertEquals(0L, listen.elapsedMs)
    }

    @Test
    fun `threshold is only evaluated once a positive delta was accumulated`() {
        val listen = ListenAccounting()
        listen.begin(1)
        listen.accumulate(1_000L) { 1_000L }
        var evaluations = 0

        listen.accumulate(1_000L) { evaluations++; 1_000L }
        listen.accumulate(900L) { evaluations++; 1_000L }

        assertEquals(0, evaluations)
    }

    @Test
    fun `outgoing occurrence is stashed across a crossfade and restored on abort`() {
        val listen = ListenAccounting()
        listen.begin(1)
        listen.accumulate(0L) { 1_000L }
        listen.accumulate(900L) { 1_000L }

        listen.stashOutgoing(targetTrackId = 2)
        listen.begin(2)

        assertEquals(1L to 900L, listen.stashedOccurrence)

        listen.resume(trackId = 1)
        assertEquals(1L, listen.pendingTrackId)
        assertEquals(900L, listen.elapsedMs)
        // Resuming restarts the delta chain.
        assertNull(listen.accumulate(950L) { 1_000L })
        assertEquals(1_000L, listen.accumulate(1_050L) { 1_000L })
    }

    @Test
    fun `resuming a different track starts a fresh occurrence`() {
        val listen = ListenAccounting()
        listen.begin(1)
        listen.accumulate(0L) { 1_000L }
        listen.accumulate(900L) { 1_000L }
        listen.stashOutgoing(targetTrackId = 2)

        listen.resume(trackId = 2)

        assertEquals(2L, listen.pendingTrackId)
        assertEquals(0L, listen.elapsedMs)
        assertNull(listen.stashedOccurrence)
    }

    @Test
    fun `stashing the track being faded to keeps no stash`() {
        val listen = ListenAccounting()
        listen.begin(5)
        listen.accumulate(0L) { 1_000L }
        listen.accumulate(400L) { 1_000L }

        listen.stashOutgoing(targetTrackId = 5)

        assertNull(listen.stashedOccurrence)
    }

    @Test
    fun `clear wipes the occurrence and the stash`() {
        val listen = ListenAccounting()
        listen.begin(1)
        listen.accumulate(0L) { 1_000L }
        listen.accumulate(400L) { 1_000L }
        listen.stashOutgoing(targetTrackId = 2)

        listen.clear()

        assertNull(listen.pendingTrackId)
        assertEquals(0L, listen.elapsedMs)
        assertNull(listen.stashedOccurrence)
        assertNull(listen.accumulate(1_000L) { 0L })
    }

    @Test
    fun `played time reported at the threshold is the accumulated total`() {
        val listen = ListenAccounting()
        listen.begin(1)

        listen.accumulate(0L) { 4_000L }
        listen.accumulate(2_000L) { 4_000L }
        listen.accumulate(3_500L) { 4_000L }
        val played = listen.accumulate(4_800L) { 4_000L }

        assertEquals(4_800L, played)
    }
}
