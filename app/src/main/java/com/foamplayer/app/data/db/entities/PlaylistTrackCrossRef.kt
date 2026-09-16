package com.foamplayer.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "playlist_track_cross_ref",
    primaryKeys = ["playlistId", "trackId"],
    indices = [
        Index(value = ["trackId"]),
        // getTracksForPlaylist() joins on playlistId and sorts by orderIndex; this
        // composite lets SQLite walk the rows already in playlist order instead of
        // running a temp B-tree sort on every playlist open.
        Index(value = ["playlistId", "orderIndex"])
    ]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: Long,
    val orderIndex: Int
)