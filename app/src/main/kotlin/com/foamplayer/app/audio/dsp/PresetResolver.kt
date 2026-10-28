package com.foamplayer.app.audio.dsp

/**
 * What is playing, reduced to the fields the preset resolution needs. Kept
 * independent of the engine and its entities so the resolution stays pure
 * and unit-testable.
 */
data class PresetPlaybackContext(
    /** Current track id, null when nothing is playing. */
    val trackId: Long? = null,
    /** Current track's album name. */
    val album: String? = null,
    /** Collection id the queue was started from ("album:…", "playlist:…"). */
    val queueSourceId: String? = null,
    /**
     * True while the current track is still inside the queue source's own
     * section (the engine's queueSourceFlags): a playlist queue keeps its
     * "playlist:" source marker after swiping onto a track that is not a
     * playlist member, and the playlist assignment must not follow it.
     */
    val inQueueSourceSection: Boolean = false
)

/** The queue-source marker prefix for playlists ([com.foamplayer.app.ui.components.CollectionId.playlist]). */
private const val PLAYLIST_SOURCE_PREFIX = "playlist:"

/**
 * The assigned preset for [context], in priority order: track > album >
 * playlist (only while the current track is still inside the queue source's
 * own section — see [PresetPlaybackContext.inQueueSourceSection]) >
 * output-device kind. Null when nothing is assigned (the auto-switch revert
 * target).
 */
fun assignedPresetFor(
    context: PresetPlaybackContext,
    assignments: PresetAssignments,
    deviceKind: AssignedDeviceKind?
): String? {
    val trackId = context.trackId ?: return null
    assignments.trackPresets[trackId]?.let { return it }
    context.album?.let { album -> assignments.albumPresets[album]?.let { return it } }
    context.queueSourceId
        ?.takeIf { it.startsWith(PLAYLIST_SOURCE_PREFIX) }
        ?.takeIf { context.inQueueSourceSection }
        ?.let { sourceId ->
            val playlistId = sourceId.removePrefix(PLAYLIST_SOURCE_PREFIX).toLongOrNull()
                ?: return@let null
            assignments.playlistPresets[playlistId]?.let { return it }
        }
    return deviceKind?.let { assignments.devicePresets[it] }
}
