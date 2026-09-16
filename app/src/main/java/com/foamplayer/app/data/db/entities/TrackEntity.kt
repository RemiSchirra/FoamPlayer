package com.foamplayer.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["album"]),
        Index(value = ["artist"]),
        Index(value = ["albumArtist"]),
        Index(value = ["title"]),
        Index(value = ["genre"]),
        Index(value = ["year"]),
        Index(value = ["lastPlayedAt"])
    ]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val genre: String?,
    val composer: String? = null,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val duration: Long,
    val bitrate: Int,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val format: String,
    val path: String,
    val size: Long,
    val albumArtPath: String?,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    /** User rating: -1 = dislike, 0 = not noted, 1 = like, 2 = super like. */
    val score: Int = 0,
    /** User-set flag: the track has no lyrics (the lyrics view shows a music note instead). */
    val instrumental: Boolean = false
)