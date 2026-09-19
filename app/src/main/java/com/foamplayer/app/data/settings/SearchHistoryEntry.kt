package com.foamplayer.app.data.settings

sealed interface SearchHistoryEntry {
    data class Track(val path: String) : SearchHistoryEntry
    data class Album(val album: String, val artist: String) : SearchHistoryEntry
    data class Artist(val name: String) : SearchHistoryEntry
}

val SearchHistoryEntry.identityKey: String
    get() = when (this) {
        is SearchHistoryEntry.Track -> "track:$path"
        is SearchHistoryEntry.Album -> "album:$album"
        is SearchHistoryEntry.Artist -> "artist:$name"
    }
