package com.foamplayer.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.key.Keyer
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.media.EmbeddedAlbumArtFetcher
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.notification.ScanProgressNotifier
import com.foamplayer.app.update.UpdateManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Application entry point: Hilt, the playback notification channel and Coil setup. */
@HiltAndroidApp
class FoamApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var scanProgressNotifier: ScanProgressNotifier
    @Inject lateinit var updateManager: UpdateManager
    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scanProgressNotifier.start()
        checkForUpdateOnStartup()
    }

    // Lets AsyncImage(model = EmbeddedAlbumArt(...)) load cover art from audio files.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(EmbeddedAlbumArtFetcher.Factory(this@FoamApplication))
                add(Keyer<EmbeddedAlbumArt> { data, _ -> data.trackUri.toString() })
            }
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            PLAYBACK_CHANNEL_ID,
            "FoamPlayer Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media controls and audio status notification"
            setSound(null, null)
            enableVibration(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    /** When auto-check is enabled, looks GitHub for a newer release on launch. */
    private fun checkForUpdateOnStartup() {
        appScope.launch {
            val autoCheck = runCatching { settingsRepository.settings.first().autoUpdateCheckEnabled }
                .getOrDefault(true)
            if (autoCheck) updateManager.checkForUpdateOnStartup()
        }
    }

    companion object {
        const val PLAYBACK_CHANNEL_ID = "foam_playback_channel"
    }
}