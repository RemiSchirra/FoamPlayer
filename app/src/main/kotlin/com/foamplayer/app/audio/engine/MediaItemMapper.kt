package com.foamplayer.app.audio.engine

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.distant.DistantUri
import com.foamplayer.distant.DistantUris
import java.io.File

/**
 * Maps [TrackEntity] rows onto the ExoPlayer media items the engine feeds its timelines.
 *
 * Split out of [AudioEngine] because the artwork rules are subtle and shared by every timeline
 * load, crossfade window and media-session rebuild.
 */
internal class MediaItemMapper(
    /** Live artwork URL of a distant track, resolved through the provider. */
    private val distantArtworkUrl: (DistantUri) -> String?
) {

    fun toMediaItem(track: TrackEntity): MediaItem {
        val isDistant = DistantUris.isDistant(track.path)
        val artwork = when {
            isDistant -> artworkUriForDistantTrack(track)
            else -> track.path.toUri()
        }
        return MediaItem.Builder()
            .setUri(track.path.toUri())
            .setMediaId(track.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(artwork)
                    .build()
            )
            .build()
    }

    fun mediaItemsFor(tracks: List<TrackEntity>): List<MediaItem> = tracks.map { toMediaItem(it) }

    /**
     * Loadable artwork for a distant track. The metadata artwork URI is consumed
     * out-of-process too (SystemUI's media controls), which cannot read this
     * app's private files — so prefer the provider's live http(s) URL and only
     * fall back to the scan-time cached image (verified to exist) when the
     * provider gives nothing. Never advertise the audio URI itself: no surface
     * can open a `foam-distant://` data source.
     */
    private fun artworkUriForDistantTrack(track: TrackEntity): Uri? {
        DistantUris.parse(track.path)
            ?.let(distantArtworkUrl)
            ?.let { return it.toUri() }
        track.albumArtPath?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { artPath ->
            if (File(artPath.toUri().path ?: "").isFile) return artPath.toUri()
        }
        return null
    }
}
