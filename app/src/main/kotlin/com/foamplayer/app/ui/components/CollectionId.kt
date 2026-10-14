package com.foamplayer.app.ui.components

/**
 * Stable ids identifying a playable collection, used as the queue's source
 * marker (PlaybackState.queueSourceId): the screen that starts playback builds
 * its id with these helpers and every list row of the same collection computes
 * the same id to decide whether it is the one actively playing. Keyed by what
 * the app already treats as each collection's identity, so ids match across
 * screens.
 */
object CollectionId {
    private const val Album = "album"
    private const val Artist = "artist"
    private const val Genre = "genre"
    private const val Year = "year"
    private const val Folder = "folder"
    private const val Playlist = "playlist"
    private const val Composer = "composer"

    fun album(name: String) = "$Album:$name"
    fun artist(name: String) = "$Artist:$name"
    fun genre(name: String) = "$Genre:$name"
    fun year(year: Int) = "$Year:$year"
    fun folder(path: String) = "$Folder:$path"
    fun playlist(id: Long) = "$Playlist:$id"
    fun composer(name: String) = "$Composer:$name"
}
