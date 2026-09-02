package com.foamplayer.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.key.Keyer
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.media.EmbeddedAlbumArtFetcher
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FoamApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    // Registers EmbeddedAlbumArtFetcher so any AsyncImage(model = EmbeddedAlbumArt(...))
    // anywhere in the app reads cover art live from the audio file.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(EmbeddedAlbumArtFetcher.Factory(this@FoamApplication))
                add(Keyer<EmbeddedAlbumArt> { data, _ -> data.trackUri.toString() })
            }
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    companion object {
        const val PLAYBACK_CHANNEL_ID = "foam_playback_channel"
    }
}