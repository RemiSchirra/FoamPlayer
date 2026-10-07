package com.foamplayer.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val path: String,
    val name: String,
    val trackCount: Int = 0
)