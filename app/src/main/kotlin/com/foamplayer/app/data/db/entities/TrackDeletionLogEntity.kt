package com.foamplayer.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Snapshot of a track row taken right before the scanner deletes it (stale
 * path — usually the file moved and was re-scanned under a new path, which
 * re-inserts it with a fresh autoGenerate id).
 *
 * Playlist cross-references keep pointing at the deleted row's id, so the
 * track silently vanishes from every playlist containing it. The log keeps
 * enough of the deleted row's identity for [com.foamplayer.app.data.repository.PlaylistRepairer]
 * to re-link those dangling references to the re-scanned row: the physical
 * signature (size + duration + title) first, then tag metadata, then the bare
 * file name. Entries are consumed when re-linked and pruned by age otherwise.
 */
@Entity(tableName = "track_deletion_log")
data class TrackDeletionLogEntity(
    /** The deleted row's id — the value dangling playlist references hold. */
    @PrimaryKey val trackId: Long,
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val size: Long,
    val duration: Long,
    val deletedAt: Long
)

/** Scan-time snapshot of a track row about to be deleted (see [TrackDeletionLogEntity]). */
fun TrackEntity.toDeletionLogEntry(deletedAt: Long): TrackDeletionLogEntity =
    TrackDeletionLogEntity(
        trackId = id,
        path = path,
        title = title,
        artist = artist,
        album = album,
        size = size,
        duration = duration,
        deletedAt = deletedAt
    )
