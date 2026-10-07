package com.foamplayer.app.lyrics

import android.content.Context

/** One online lyrics source: lookup by track metadata, or multi-candidate search. */
interface LyricsProvider {
    val name: String

    fun isEnabled(context: Context): Boolean

    /** Best single lyrics payload for the track, or a failure. */
    suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String>

    /** Reports every distinct candidate through [callback] (default: [getLyrics]'s single result). */
    suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, album, duration).onSuccess(callback)
    }
}