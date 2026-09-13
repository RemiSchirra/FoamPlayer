package com.foamplayer.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val name: String,
    /** User-chosen cover image (content URI). Null = letter fallback. */
    val imageUrl: String? = null,
    /** Manual ordering rank (edit-mode drag & drop); ties fall back to name order. */
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)