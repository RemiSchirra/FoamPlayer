package com.foamplayer.app.audio.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads cover art embedded in audio files for Media3 notifications.
 *
 * Extraction and decoding always happen on [Dispatchers.IO]. While artwork for a new item is
 * being read, the last successfully displayed bitmap is returned immediately so a notification
 * update never briefly falls back to an empty large icon. A missing embedded picture is cached as
 * a confirmed miss and is the only case that returns null.
 *
 * Bitmaps are resized to a fixed small size to avoid performance lag in the notification system.
 */
@UnstableApi
class EmbeddedAlbumArtBitmapLoader(context: Context) : BitmapLoader {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Each entry is a decoded 512x512 ARGB_8888 bitmap (~1 MB of native heap). A long
    // listening session over a large library would otherwise pin hundreds of them for
    // the process lifetime; artwork is cheap to re-decode from the file, so a small
    // LRU cap costs only re-reads.
    private val bitmapCache = object : LruCache<String, Bitmap>(BITMAP_CACHE_ENTRIES) {}
    private val confirmedMissingArtwork = LruCache<String, Boolean>(MISS_CACHE_ENTRIES)
    private val inFlight = ConcurrentHashMap<String, ListenableFuture<Bitmap>>()

    @Volatile
    private var lastDisplayedBitmap: Bitmap? = null

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        loadAsync(key = "bytes:${data.contentHashCode()}") {
            decodeAndResize(data)
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val key = uri.toString()
        bitmapCache.get(key)?.let { bitmap ->
            lastDisplayedBitmap = bitmap
            return Futures.immediateFuture(bitmap)
        }
        if (confirmedMissingArtwork.get(key) != null) {
            return immediateNullBitmap()
        }

        // Start the expensive work now, but keep the existing image visible until a later
        // notification refresh observes the decoded bitmap in bitmapCache.
        val loading = inFlight[key] ?: loadAsync(key) { readEmbeddedArtwork(uri) }
        return lastDisplayedBitmap?.let(Futures::immediateFuture) ?: loading
    }

    private fun loadAsync(key: String, block: suspend () -> Bitmap?): ListenableFuture<Bitmap> {
        val result = SettableFuture.create<Bitmap>()
        val existing = inFlight.putIfAbsent(key, result)
        if (existing != null) return existing

        scope.launch {
            val bitmap = try {
                withContext(Dispatchers.IO) { block() }
            } catch (_: Exception) {
                null
            }
            if (bitmap == null) {
                confirmedMissingArtwork.put(key, true)
                result.set(null)
            } else {
                bitmapCache.put(key, bitmap)
                lastDisplayedBitmap = bitmap
                result.set(bitmap)
            }
            inFlight.remove(key, result)
        }
        return result
    }

    private fun readEmbeddedArtwork(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            retriever.embeddedPicture?.let { bytes ->
                decodeAndResize(bytes)
            }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun decodeAndResize(data: ByteArray): Bitmap? {
        // First decode with inJustDecodeBounds=true to check dimensions
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)

        // Calculate inSampleSize to downsample the image during decode to save memory
        options.inSampleSize = calculateInSampleSize(options, MAX_ARTWORK_SIZE, MAX_ARTWORK_SIZE)
        options.inJustDecodeBounds = false

        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null

        // Ensure the final bitmap is exactly within our target size for consistency
        return if (decoded.width > MAX_ARTWORK_SIZE || decoded.height > MAX_ARTWORK_SIZE) {
            decoded.scale(MAX_ARTWORK_SIZE, MAX_ARTWORK_SIZE, true)
        } else {
            decoded
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    @Suppress("UNCHECKED_CAST")
    private fun immediateNullBitmap(): ListenableFuture<Bitmap> =
        Futures.immediateFuture<Bitmap?>(null) as ListenableFuture<Bitmap>

    private companion object {
        private const val MAX_ARTWORK_SIZE = 512
        private const val BITMAP_CACHE_ENTRIES = 8
        private const val MISS_CACHE_ENTRIES = 256
    }
}
