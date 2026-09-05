package com.foamplayer.app.data.repository

import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.lyrics.providers.EmbeddedLyricsProvider
import com.foamplayer.app.lyrics.providers.BetterLyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri

@Singleton
class LyricsRepository @Inject constructor(
    private val embeddedLyricsProvider: EmbeddedLyricsProvider
) {
    suspend fun getLyrics(track: TrackEntity): String? = withContext(Dispatchers.IO) {
        // Try embedded
        //val embedded = embeddedLyricsProvider.getLyrics(track.path.toUri())
        //if (!embedded.isNullOrBlank()) return@withContext embedded

        // TODO : this is a temp test
        // Try online providers
        BetterLyricsProvider.getLyrics(
            id = track.id.toString(),
            title = track.title,
            artist = track.artist,
            album = track.album,
            duration = (track.duration / 1000).toInt()
        ).getOrNull()
    }
}
