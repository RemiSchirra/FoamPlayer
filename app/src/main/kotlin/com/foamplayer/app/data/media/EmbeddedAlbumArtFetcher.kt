package com.foamplayer.app.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * Coil model meaning "the embedded album art of this audio file" — distinct
 * from a plain image Uri/String so this fetcher only intercepts album-art
 * lookups and never intercepts other images Coil loads elsewhere in the app.
 *
 * Usage: AsyncImage(model = EmbeddedAlbumArt(Uri.parse(track.albumArtPath)))
 */
data class EmbeddedAlbumArt(val trackUri: Uri)

/**
 * Reads the embedded picture straight out of an audio file, every time Coil
 * is asked for it. No file is ever written to app storage here — Coil's own
 * memory/disk cache (already bounded, already used for every other image in
 * the app) is the only caching layer involved.
 */
class EmbeddedAlbumArtFetcher(
    private val data: EmbeddedAlbumArt,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val bytes = readEmbeddedPicture(data.trackUri) ?: return@withContext null

        SourceResult(
            source = ImageSource(source = Buffer().write(bytes), context = context),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK
        )
    }

    private fun readEmbeddedPicture(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<EmbeddedAlbumArt> {
        override fun create(data: EmbeddedAlbumArt, options: Options, imageLoader: ImageLoader): Fetcher {
            return EmbeddedAlbumArtFetcher(data, context)
        }
    }
}