package com.foamplayer.app.data.canvas

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.foamplayer.canvas.CanvasArtwork
import com.foamplayer.apple.AppleMusicCanvas
import com.foamplayer.echomusic.EchoMusicCanvas
import com.foamplayer.tidal.TidalCanvas
import com.foamplayer.vivimusic.ViviMusicCanvas
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CanvasProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun getCanvas(
        song: String,
        artist: String,
        album: String? = null,
        allowMobileData: Boolean = false
    ): CanvasArtwork? {
        if (song.isBlank() || artist.isBlank()) return null
        if (!isNetworkAllowed(allowMobileData)) return null

        return AppleMusicCanvas.getBySongArtist(song, artist, album)
            ?.takeIf { it.preferredAnimationUrl != null }
            ?: TidalCanvas.getBySongArtist(song, artist, album)
                ?.takeIf { it.preferredAnimationUrl != null }
            ?: ViviMusicCanvas.getBySongArtist(song, artist)
                ?.takeIf { it.preferredAnimationUrl != null }
            ?: EchoMusicCanvas.getBySongArtist(song, artist)
                ?.takeIf { it.preferredAnimationUrl != null }
    }

    private fun isNetworkAllowed(allowMobileData: Boolean): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        return isWifi || (allowMobileData && isCellular)
    }
}
