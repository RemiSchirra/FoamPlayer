package com.foamplayer.app.ui.components

import com.foamplayer.app.data.db.entities.TrackEntity
import kotlin.math.abs

/*
 * Shared by the full player's hero pager and the mini player's pager: in repeat-all both show the
 * primary queue as a closed loop, so next at the end and previous at the start are ordinary
 * one-page moves instead of a sweep across the whole queue (or a locked / auxiliary neighbour).
 */

private const val GHOST_ENTRY_ID_BASE = Long.MIN_VALUE + 1
/** Ghost pages on each side of the repeat-all loop. Two, so a ghost keeps its twin's neighbours on both sides. */
private const val GHOSTS_PER_SIDE = 2

/**
 * What the hero pager displays, and how its page numbers map back to engine queue indices.
 *
 * Outside repeat-all (or with fewer than two primary tracks) this is the engine queue as is.
 * In repeat-all it is `[2 ghosts] + primary tracks + [2 ghosts]`, the ghosts continuing the loop
 * cyclically. The auxiliary pool and any demoted history are not pageable, so the pager can never
 * swipe into them, and the loop's seams have neighbouring pages to swipe or animate onto.
 */
internal class LoopingPagerModel(
    val queue: List<TrackEntity>,
    val entryIds: List<Long>,
    /** The playing track's page. */
    val index: Int,
    val circular: Boolean,
    private val firstReal: Int,
    private val lastReal: Int
) {
    private val realCount get() = lastReal - firstReal + 1

    fun isGhostPage(page: Int): Boolean =
        circular && (page < GHOSTS_PER_SIDE || page >= GHOSTS_PER_SIDE + realCount)

    fun isTrailingGhost(page: Int): Boolean = circular && page >= GHOSTS_PER_SIDE + realCount

    /** The real page showing the same track as ghost [page]. */
    fun ghostTwinPage(page: Int): Int =
        GHOSTS_PER_SIDE + Math.floorMod(page - GHOSTS_PER_SIDE, realCount)

    /**
     * Distance from pager scroll [position] to the nearest page showing the playing track. In the
     * loop the playing track of the first / last two real pages also sits on a ghost page at the
     * opposite end, so a seam wrap (page n -> ghost n + 1) must read as "arriving at the playing
     * track" (distance 1 -> 0), not as "far from it" (distance ~ n).
     */
    fun distanceToPlayingPage(position: Float): Float {
        val playing = index.coerceAtLeast(0)
        var distance = abs(position - playing)
        if (circular) {
            val after = playing + realCount
            val before = playing - realCount
            if (after in queue.indices && isGhostPage(after)) distance = minOf(distance, abs(position - after))
            if (before in queue.indices && isGhostPage(before)) distance = minOf(distance, abs(position - before))
        }
        return distance
    }

    /** Engine queue index for pager [page]. */
    fun realIndexOf(page: Int): Int =
        if (!circular) page else firstReal + Math.floorMod(page - GHOSTS_PER_SIDE, realCount)

    /**
     * The ghost page to animate onto when the playing track moved across the loop's seam
     * ([fromPage] -> [toPage] spans the whole loop), or null for an ordinary move. With two
     * tracks both directions land on the same pair of pages, so [backward] breaks the tie.
     */
    fun wrapTransitPage(fromPage: Int, toPage: Int, backward: Boolean): Int? {
        if (!circular) return null
        val firstPage = GHOSTS_PER_SIDE
        val lastPage = GHOSTS_PER_SIDE + realCount - 1
        val ambiguous = realCount < 3
        return when {
            fromPage == lastPage && toPage == firstPage && !(ambiguous && backward) -> lastPage + 1
            fromPage == firstPage && toPage == lastPage && (!ambiguous || backward) -> firstPage - 1
            else -> null
        }
    }
}

/**
 * Builds the [LoopingPagerModel]. The display lists are rebuilt only when the engine's queue, flags,
 * entry ids or repeat mode change (by identity), so their references stay stable while the playing
 * index moves; an index change only creates a light model sharing the same lists.
 */
internal class LoopingPagerModelCache {
    private var srcQueue: List<TrackEntity>? = null
    private var srcFlags: List<Boolean>? = null
    private var srcIds: List<Long>? = null
    private var srcRepeatAll = false

    private var circularEligible = false
    private var first = 0
    private var last = 0
    private var displayQueue: List<TrackEntity> = emptyList()
    private var displayIds: List<Long> = emptyList()

    private var model: LoopingPagerModel? = null
    private var modelIndex = Int.MIN_VALUE
    private var modelCircular = false

    fun resolve(
        queue: List<TrackEntity>,
        auxiliaryFlags: List<Boolean>,
        entryIds: List<Long>,
        queueIndex: Int,
        repeatAll: Boolean
    ): LoopingPagerModel {
        if (queue !== srcQueue || auxiliaryFlags !== srcFlags || entryIds !== srcIds || repeatAll != srcRepeatAll) {
            srcQueue = queue
            srcFlags = auxiliaryFlags
            srcIds = entryIds
            srcRepeatAll = repeatAll
            first = auxiliaryFlags.indexOfFirst { !it }
            last = auxiliaryFlags.indexOfLast { !it }
            circularEligible = repeatAll &&
                    auxiliaryFlags.size == queue.size &&
                    entryIds.size == queue.size &&
                    first >= 0 && last > first
            if (circularEligible) {
                val real = queue.subList(first, last + 1)
                val count = real.size
                val leading = List(GHOSTS_PER_SIDE) { k -> real[Math.floorMod(count - GHOSTS_PER_SIDE + k, count)] }
                val trailing = List(GHOSTS_PER_SIDE) { k -> real[k % count] }
                displayQueue = leading + real + trailing
                displayIds = List(GHOSTS_PER_SIDE) { k -> GHOST_ENTRY_ID_BASE + k } +
                        entryIds.subList(first, last + 1) +
                        List(GHOSTS_PER_SIDE) { k -> GHOST_ENTRY_ID_BASE + GHOSTS_PER_SIDE + k }
            }
            model = null
        }

        // Circular only while the playing track is inside the loop; otherwise show the plain queue.
        val circular = circularEligible && queueIndex in first..last
        val cached = model
        if (cached != null && modelIndex == queueIndex && modelCircular == circular) return cached

        val built = if (circular) {
            LoopingPagerModel(
                queue = displayQueue,
                entryIds = displayIds,
                index = queueIndex - first + GHOSTS_PER_SIDE,
                circular = true,
                firstReal = first,
                lastReal = last
            )
        } else {
            LoopingPagerModel(queue, entryIds, queueIndex, false, 0, 0)
        }
        model = built
        modelIndex = queueIndex
        modelCircular = circular
        return built
    }
}