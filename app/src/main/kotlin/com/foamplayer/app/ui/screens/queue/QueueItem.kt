package com.foamplayer.app.ui.screens.queue

import com.foamplayer.app.data.db.entities.TrackEntity

/**
 * An item in the upcoming queue list. [entryId] is the engine-generated unique
 * id; mutations go through the engine by entry id so a snapshot never goes
 * stale mid-gesture. [absoluteIndex] is for diagnostics only.
 */
data class QueueItem(
    val entryId: Long,
    val absoluteIndex: Int,
    val track: TrackEntity,
    /** Rendered translucent. */
    val isAuxiliary: Boolean,
    /** Row appended by the latest auxiliary refill. */
    val isFreshFromRefill: Boolean
)
