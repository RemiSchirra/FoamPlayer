package com.foamplayer.app.data.repository

import android.content.Context
import androidx.core.net.toUri
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.lyrics.LyricsProvider
import com.foamplayer.app.lyrics.lyricsProvider
import com.foamplayer.app.lyrics.providers.EmbeddedLyricsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val embeddedLyricsProvider: EmbeddedLyricsProvider
) {
    suspend fun getLyrics(track: TrackEntity): String? = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        for (provider in onlineProviders(settings)) {
            if (!provider.isEnabled(context)) continue
            val lyrics = fetchFrom(provider, track)
            if (!lyrics.isNullOrBlank()) return@withContext lyrics
        }

        embeddedLyricsProvider.getLyrics(track.path.toUri())?.takeIf { it.isNotBlank() }
    }

    private fun onlineProviders(settings: AppSettings): List<LyricsProvider> =
        settings.lyricsProviderOrder
            .filter { it in settings.enabledLyricsProviders }
            .mapNotNull { it.lyricsProvider }

    private suspend fun fetchFrom(provider: LyricsProvider, track: TrackEntity): String? =
        try {
            provider.getLyrics(
                id = track.id.toString(),
                title = track.title,
                artist = track.artist,
                album = track.album,
                duration = (track.duration / 1000).toInt()
            ).getOrNull()?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}
