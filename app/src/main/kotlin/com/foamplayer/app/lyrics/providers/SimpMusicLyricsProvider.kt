package com.foamplayer.app.lyrics.providers

import android.content.Context
import com.foamplayer.app.lyrics.LyricsProvider
import com.foamplayer.simpmusic.SimpMusicLyrics

object SimpMusicLyricsProvider : LyricsProvider {
    override val name: String = "SimpMusic"

    override fun isEnabled(context: Context): Boolean = true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = SimpMusicLyrics.getLyrics(videoId = id, duration = duration)

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        SimpMusicLyrics.getAllLyrics(videoId = id, duration = duration, callback = callback)
    }
}
