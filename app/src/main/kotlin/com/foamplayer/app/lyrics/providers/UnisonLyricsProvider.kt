package com.foamplayer.app.lyrics.providers

import android.content.Context
import com.foamplayer.app.lyrics.LyricsProvider
import com.foamplayer.unison.Unison

object UnisonLyricsProvider : LyricsProvider {
    override val name = "Unison"

    override fun isEnabled(context: Context): Boolean = true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        Unison.getLyrics(
            videoId = id,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = duration,
        )

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        Unison.getAllLyrics(
            videoId = id,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = duration,
            callback = callback,
        )
    }
}
