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
 * Loads cover art embedded in audio files for Media3 notifications, on IO.
 *
 * Every request resolves to the artwork of the requested URI exactly: cache
 * hit → immediate, confirmed missing picture → null, otherwise the pending
 * decode of that URI. Media3 holds the currently displayed icon while the
 * future is pending and re-posts the notification when it completes — that
 * completion IS the refresh. Returning anything else (e.g. the previously
 * displayed bitmap as an already-completed future) makes Media3 adopt the
 * wrong song's cover immediately, and since artwork is only re-requested when
 * the artwork URI changes, nothing ever corrects it afterwards.
 *
 * A genuinely missing picture is cached as a confirmed miss (the only null
 * case). Bitmaps are resized small.
 */
@UnstableApi
class EmbeddedAlbumArtBitmapLoader(context: Context) : BitmapLoader {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Small LRU cap: artwork is cheap to re-decode, native heap isn't cheap to hold.
    private val bitmapCache = object : LruCache<String, Bitmap>(BITMAP_CACHE_ENTRIES) {}
    private val confirmedMissingArtwork = LruCache<String, Boolean>(MISS_CACHE_ENTRIES)
    private val inFlight = ConcurrentHashMap<String, ListenableFuture<Bitmap>>()

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        loadAsync(key = "bytes:${data.size}:${data.contentHashCode()}") {
            decodeAndResize(data)
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val key = uri.toString()
        bitmapCache.get(key)?.let { bitmap ->
            return Futures.immediateFuture(bitmap)
        }
        if (confirmedMissingArtwork.get(key) != null) {
            return immediateNullBitmap()
        }
        // Always hand back the future of THIS artwork, even while it decodes.
        // The previously shown bitmap must never be returned as a completed
        // future: it would be adopted for the new song and never corrected.
        return inFlight[key] ?: loadAsync(key) { readEmbeddedArtwork(uri) }
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
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)

        options.inSampleSize = calculateInSampleSize(options, MAX_ARTWORK_SIZE, MAX_ARTWORK_SIZE)
        options.inJustDecodeBounds = false

        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null

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
