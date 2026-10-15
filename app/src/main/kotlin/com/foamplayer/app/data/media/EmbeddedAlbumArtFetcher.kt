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
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * Coil model meaning "this track's album art" — distinct from a plain image
 * Uri/String so this fetcher only intercepts album-art lookups and never
 * intercepts other images Coil loads elsewhere in the app. The Uri points at a
 * local audio file (embedded picture), at an image file cached at scan time
 * (distant providers), or at a remote artwork URL.
 *
 * Usage: AsyncImage(model = EmbeddedAlbumArt(Uri.parse(track.albumArtPath)))
 */
data class EmbeddedAlbumArt(val trackUri: Uri)

/**
 * Resolves the artwork for [EmbeddedAlbumArt.trackUri]: the embedded picture of
 * a local audio file, a cached local image file, or a remote http(s) URL. No
 * file is ever written to app storage here — Coil's own memory/disk cache
 * (already bounded, already used for every other image in the app) is the only
 * caching layer involved.
 */
class EmbeddedAlbumArtFetcher(
    private val data: EmbeddedAlbumArt,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        // Local audio files carry the cover in their tags; distant tracks carry
        // the path of an image cached at scan time; a raw remote URL is the
        // last-resort fallback.
        val uri = data.trackUri
        val remote = uri.scheme == "http" || uri.scheme == "https"
        val bytes = when {
            remote -> readRemote(uri)
            isImageFile(uri) -> readFileBytes(uri)
            else -> readEmbeddedPicture(uri)
        }
        bytes ?: return@withContext null

        SourceResult(
            source = ImageSource(source = Buffer().write(bytes), context = context),
            mimeType = "image/jpeg",
            dataSource = if (remote) DataSource.NETWORK else DataSource.DISK
        )
    }

    private fun isImageFile(uri: Uri): Boolean {
        if (uri.scheme != null && uri.scheme != "file") return false
        val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        return extension in IMAGE_EXTENSIONS
    }

    private fun readFileBytes(uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
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

    private fun readRemote(uri: Uri): ByteArray? {
        val connection = try {
            URL(uri.toString()).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "FoamPlayer/1.0")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<EmbeddedAlbumArt> {
        override fun create(data: EmbeddedAlbumArt, options: Options, imageLoader: ImageLoader): Fetcher {
            return EmbeddedAlbumArtFetcher(data, context)
        }
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    }
}