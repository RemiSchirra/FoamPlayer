package com.foamplayer.app.audio.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tests the repeat-all queue shuffle's index math: landing-slot selection, the guaranteed-change
 * permutation, and the persisted-list normalization.
 */
class QueueShuffleMathTest {

    @Test
    fun `landing slot is neither the playing slot nor auxiliary`() {
        val auxiliary = setOf(2, 5, 9)
        repeat(200) { seed ->
            val slot = QueueShuffleMath.pickRandomPrimarySlot(
                first = 0,
                last = 9,
                exclude = 4,
                isAuxiliary = { it in auxiliary },
                random = Random(seed)
            )
            assertNotEquals(4, slot)
            assertTrue("seed $seed picked auxiliary $slot", slot !in auxiliary)
            assertTrue(slot in 0..9)
        }
    }

    @Test
    fun `returns -1 when the only in-range slot is the excluded one`() {
        val slot = QueueShuffleMath.pickRandomPrimarySlot(
            first = 3,
            last = 3,
            exclude = 3,
            isAuxiliary = { false }
        )

        assertEquals(-1, slot)
    }

    @Test
    fun `returns -1 for an empty range`() {
        assertEquals(
            -1,
            QueueShuffleMath.pickRandomPrimarySlot(first = 5, last = 4, exclude = 5, isAuxiliary = { false })
        )
    }

    @Test
    fun `finds the single non-auxiliary slot even when auxiliary entries dominate`() {
        val auxiliary = setOf(0, 1, 2, 4, 5, 6, 7)

        repeat(50) { seed ->
            val slot = QueueShuffleMath.pickRandomPrimarySlot(
                first = 0,
                last = 7,
                exclude = 3,
                isAuxiliary = { it in auxiliary },
                random = Random(seed)
            )
            // The reservoir scan can only ever find slot 3, which is excluded.
            assertEquals(-1, slot)
        }

        val found = QueueShuffleMath.pickRandomPrimarySlot(
            first = 0,
            last = 7,
            exclude = 0,
            isAuxiliary = { it in auxiliary },
            random = Random(1)
        )
        assertEquals(3, found)
    }

    @Test
    fun `shuffled slots are a permutation of the input`() {
        val slots = intArrayOf(4, 5, 6, 7, 8, 9, 10)

        repeat(50) { seed ->
            val order = QueueShuffleMath.shuffledSlots(slots, Random(seed))
            assertArrayEquals(slots.sortedArray(), order.sortedArray())
        }
    }

    @Test
    fun `shuffled slots always differ from the input for two or more slots`() {
        val slots = intArrayOf(4, 5, 6)

        repeat(50) { seed ->
            val order = QueueShuffleMath.shuffledSlots(slots, Random(seed))
            assertNotEquals(slots.toList(), order.toList())
        }
    }

    @Test
    fun `a single slot is returned unchanged`() {
        val slots = intArrayOf(7)

        assertArrayEquals(slots, QueueShuffleMath.shuffledSlots(slots, Random(3)))
    }

    @Test
    fun `entry ids are kept when the persisted size matches`() {
        val persisted = listOf(10L, 11L, 12L)
        var allocations = 0

        val result = QueueShuffleMath.normalizeEntryIds(persisted, queueSize = 3) {
            allocations++
            99L
        }

        assertSame(persisted, result)
        assertEquals(0, allocations)
    }

    @Test
    fun `entry ids are reallocated when the persisted size is unusable`() {
        var next = 100L

        val result = QueueShuffleMath.normalizeEntryIds(listOf(1L), queueSize = 3) { next++ }

        assertEquals(listOf(100L, 101L, 102L), result)
    }

    @Test
    fun `flags fall back to all false when the size mismatches`() {
        val matching = listOf(true, false)

        assertSame(matching, QueueShuffleMath.normalizeFlags(matching, queueSize = 2))
        assertEquals(listOf(false, false, false), QueueShuffleMath.normalizeFlags(matching, queueSize = 3))
    }
}
