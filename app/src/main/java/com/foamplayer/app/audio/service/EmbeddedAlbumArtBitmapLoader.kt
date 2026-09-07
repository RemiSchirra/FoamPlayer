package com.foamplayer.app.audio.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
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
 */
@UnstableApi
class EmbeddedAlbumArtBitmapLoader(context: Context) : BitmapLoader {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val confirmedMissingArtwork = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap<String, ListenableFuture<Bitmap>>()

    @Volatile
    private var lastDisplayedBitmap: Bitmap? = null

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        loadAsync(key = "bytes:${data.contentHashCode()}") {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val key = uri.toString()
        bitmapCache[key]?.let { bitmap ->
            lastDisplayedBitmap = bitmap
            return Futures.immediateFuture(bitmap)
        }
        if (confirmedMissingArtwork.contains(key)) {
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
                confirmedMissingArtwork.add(key)
                result.set(null)
            } else {
                bitmapCache[key] = bitmap
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
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun immediateNullBitmap(): ListenableFuture<Bitmap> =
        Futures.immediateFuture<Bitmap?>(null) as ListenableFuture<Bitmap>
}
