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
        Index(value = ["title"])
    ]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val genre: String?,
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
    val lyrics: String?
)