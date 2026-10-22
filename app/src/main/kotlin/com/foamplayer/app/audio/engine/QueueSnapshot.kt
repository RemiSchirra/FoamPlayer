package com.foamplayer.app.audio.engine

/**
 * The serializable part of a playback state: the main queue without auxiliary filler entries.
 *
 * Auxiliary entries are transient listening-radio filler and are never restored, so they must not
 * be persisted. The saved current index is therefore counted among main entries only, because
 * auxiliary entries may precede the current track.
 */
internal data class QueueSnapshot(
    val trackIds: List<Long>,
    val sourceFlags: List<Boolean>,
    val currentIndex: Int
) {
    companion object {
        /** Pure O(queue) projection of [PlaybackState] onto what a session snapshot persists. */
        fun from(state: PlaybackState): QueueSnapshot {
            val ids = ArrayList<Long>(state.queue.size)
            val flags = ArrayList<Boolean>(state.queue.size)
            var currentIndex = 0
            state.queue.forEachIndexed { i, track ->
                if (state.auxiliaryFlags.getOrNull(i) != true) {
                    if (i < state.queueIndex) currentIndex++
                    ids.add(track.id)
                    flags.add(state.queueSourceFlags.getOrNull(i) == true)
                }
            }
            return QueueSnapshot(
                trackIds = ids,
                sourceFlags = flags,
                currentIndex = currentIndex.coerceIn(0, ids.lastIndex.coerceAtLeast(0))
            )
        }
    }
}
