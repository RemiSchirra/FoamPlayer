package com.foamplayer.app.audio.engine

import kotlin.random.Random

/**
 * Pure index math behind the repeat-all queue shuffle.
 *
 * The engine owns the state transitions ([AudioEngine.prepareWrapLanding],
 * [AudioEngine.applyPostWrapShuffle]); the tricky parts — rejection sampling a shuffle landing
 * slot and guaranteeing a permutation that actually differs from the current order — live here so
 * they can be unit-tested without a player.
 */
internal object QueueShuffleMath {

    /**
     * Random primary slot in `first..last`, other than [exclude] and not auxiliary, or -1 when no
     * such slot exists.
     *
     * Rejection sampling keeps this O(1) on long queues (no boxed candidate list); the exhaustive
     * reservoir scan only runs for tiny or auxiliary-heavy ranges.
     */
    fun pickRandomPrimarySlot(
        first: Int,
        last: Int,
        exclude: Int,
        isAuxiliary: (Int) -> Boolean,
        random: Random = Random
    ): Int {
        val span = last - first + 1
        if (span <= 0) return -1
        repeat(16) {
            val i = first + random.nextInt(span)
            if (i != exclude && !isAuxiliary(i)) return i
        }
        var seen = 0
        var pick = -1
        for (i in first..last) {
            if (i == exclude || isAuxiliary(i)) continue
            seen++
            if (random.nextInt(seen) == 0) pick = i
        }
        return pick
    }

    /**
     * Shuffles [slots] into a new permutation that is guaranteed to differ from the input when at
     * least two slots are given, so a post-wrap reshuffle is always visible to the pager.
     */
    fun shuffledSlots(slots: IntArray, random: Random = Random): IntArray {
        if (slots.size < 2) return slots.copyOf()
        val order = slots.copyOf()
        // Fisher-Yates.
        for (i in order.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
        }
        if (order.contentEquals(slots)) {
            val tmp = order[0]
            order[0] = order[1]
            order[1] = tmp
        }
        return order
    }

    /**
     * Normalizes a persisted entry-id list to [queueSize], allocating ids with [allocate] whenever
     * the persisted list cannot be trusted (size mismatch).
     */
    fun normalizeEntryIds(
        entryIds: List<Long>,
        queueSize: Int,
        allocate: () -> Long
    ): List<Long> =
        if (entryIds.size == queueSize) entryIds else List(queueSize) { allocate() }

    /** Normalizes a persisted auxiliary-flag list to [queueSize] (all false when it is unusable). */
    fun normalizeFlags(flags: List<Boolean>, queueSize: Int): List<Boolean> =
        if (flags.size == queueSize) flags else List(queueSize) { false }
}
