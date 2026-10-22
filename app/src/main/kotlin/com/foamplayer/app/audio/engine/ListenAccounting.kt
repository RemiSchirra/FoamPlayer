package com.foamplayer.app.audio.engine

/**
 * Listen accounting for the playing occurrence.
 *
 * A track counts as listened once the forward playback accumulated by [accumulate] reaches the
 * configured share of its duration. Accounting is position-driven rather than timer-driven
 * (timers stall in Doze) and the pending occurrence is cleared as soon as it is counted, so pauses
 * and seeks can never count the same play twice.
 *
 * The occurrence is stashed around a crossfade so an aborted fade can restore the outgoing
 * progress instead of losing it.
 */
internal class ListenAccounting(
    /** Larger forward jumps are seeks, not played audio, and don't count as listened. */
    private val maxTickGapMs: Long = MAX_TICK_GAP_MS
) {
    /** Track whose occurrence is being counted, or null once counted/cleared. */
    var pendingTrackId: Long? = null
        private set

    /** Forward playback accumulated for [pendingTrackId]. */
    var elapsedMs: Long = 0L
        private set

    /** (trackId, elapsed) of an outgoing occurrence, kept while a crossfade is in flight. */
    var stashedOccurrence: Pair<Long, Long>? = null
        private set

    /** Position at the previous accumulation tick, -1 until the first tick of an occurrence. */
    private var lastPositionMs: Long = -1L

    fun begin(trackId: Long) {
        pendingTrackId = trackId
        elapsedMs = 0L
        lastPositionMs = -1L
    }

    fun clear() {
        pendingTrackId = null
        elapsedMs = 0L
        lastPositionMs = -1L
        stashedOccurrence = null
    }

    /** Keeps the outgoing occurrence for a possible fade abort, unless the fade targets the same track. */
    fun stashOutgoing(targetTrackId: Long) {
        val outgoing = pendingTrackId ?: return
        if (outgoing == targetTrackId) return
        stashedOccurrence = outgoing to elapsedMs
    }

    fun discardStash() {
        stashedOccurrence = null
    }

    /** Restores the stashed occurrence when it belongs to [trackId], otherwise starts a new one. */
    fun resume(trackId: Long) {
        val stash = stashedOccurrence
        stashedOccurrence = null
        if (stash != null && stash.first == trackId) {
            pendingTrackId = trackId
            elapsedMs = stash.second
            lastPositionMs = -1L
        } else {
            begin(trackId)
        }
    }

    /**
     * Adds the forward delta since the previous tick to [elapsedMs].
     *
     * Pauses add nothing (the caller skips them), a backward seek replays, and a forward jump or a
     * gap over [maxTickGapMs] adds nothing.
     *
     * @param thresholdMs lazily resolves the listen threshold of the pending track; only evaluated
     *   once a positive delta was accumulated.
     * @return the total played time when the occurrence reached its threshold (the occurrence is
     *   cleared first), or null while it is still below the threshold.
     */
    fun accumulate(positionMs: Long, thresholdMs: (trackId: Long) -> Long): Long? {
        val trackId = pendingTrackId ?: return null
        val last = lastPositionMs
        val delta = if (last >= 0L) {
            (positionMs - last).takeIf { it in 1..maxTickGapMs } ?: 0L
        } else {
            0L
        }
        lastPositionMs = positionMs
        if (delta <= 0L) return null

        elapsedMs += delta
        if (elapsedMs < thresholdMs(trackId)) return null

        val playedMs = elapsedMs
        pendingTrackId = null
        return playedMs
    }

    companion object {
        const val MAX_TICK_GAP_MS = 2_000L
    }
}
