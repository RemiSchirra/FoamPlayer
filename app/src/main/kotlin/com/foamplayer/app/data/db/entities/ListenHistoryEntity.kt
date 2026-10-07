package com.foamplayer.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One counted listen: a row is appended every time a track survives the user's
 * listened-threshold (the same moment [TrackEntity.playCount] is incremented).
 * The per-listen timestamps are what the stats screen's period analysis
 * (1 week .. all time) aggregates over; the tracks table keeps only the
 * aggregate count and the latest timestamp.
 */
@Entity(
    tableName = "listen_history",
    indices = [
        Index(value = ["listenedAt"]),
        Index(value = ["trackId"])
    ]
)
data class ListenHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    /** Epoch ms of the moment the listen was counted (the threshold-crossing moment). */
    val listenedAt: Long
)
