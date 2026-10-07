package com.foamplayer.app.lyrics.providers

import android.content.Context
import com.foamplayer.app.lyrics.LyricsProvider
import com.foamplayer.paxsenix.PaxsenixLyrics

object PaxsenixSpotifyLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: Spotify"

    override fun isEnabled(context: Context): Boolean = true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = PaxsenixLyrics.getSpotifyLyrics(title, artist, duration)

    override suspend fun getAllLyrics(
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
