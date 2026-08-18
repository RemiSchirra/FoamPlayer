package com.foamplayer.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val queueId: Long = 0,
    val trackId: Long,
    val positionOrder: Int
)