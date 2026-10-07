package com.foamplayer.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Lyrics previously fetched from an online provider and persisted per track, so
 * they stay available offline (and before the next playback session) without
 * re-querying the network. Keyed by track id: library rescans keep ids stable
 * while a track's path stays in the library.
 */
@Entity(tableName = "cached_lyrics")
data class CachedLyricsEntity(
    @PrimaryKey val trackId: Long,
    val lyrics: String,
    /** Name of the provider the lyrics came from (diagnostics), if known. */
    val provider: String?,
    val fetchedAtMs: Long
)
