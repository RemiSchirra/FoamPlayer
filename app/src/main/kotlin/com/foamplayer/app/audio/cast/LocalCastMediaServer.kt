package com.foamplayer.app.audio.cast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.graphics.scale
import androidx.core.net.toUri

/**
 * Tiny HTTP server that lets a Chromecast fetch what this phone holds.
 *
 * A Cast receiver can only play a URL it can reach over the network: local
 * files (`content://` from MediaStore/SAF, `file://`) are invisible to it, so
 * they are published here under an opaque per-track URL on the LAN address.
 * Byte ranges are honoured (`206 Partial Content`) because the receiver seeks
 * by issuing ranged requests, and the default media receiver starts every track
 * with one.
 *
 * Distant tracks usually already carry their credentials in the query string
 * and are handed to the receiver unchanged by
 * [ChromecastController]. Only the ones that need request headers go through
 * the small proxy path here, which relays the range request upstream.
 *
 * The same server also publishes cover art: pictures embedded in an audio file
 * (or cached as an image by a distant provider) are invisible to the receiver
 * too, so they are extracted on demand and served under their own URL
 * ([publishArtwork]).
 */
@Singleton
class LocalCastMediaServer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private sealed interface Entry

    private class LocalEntry(
        val uri: String,
        /** Snapshot length from the scanner; `AssetFileDescriptor.length` wins when known. */
        val length: Long,
        val mime: String
    ) : Entry

    private class RemoteEntry(
        val url: String,
        val headers: Map<String, String>,
        val mime: String
    ) : Entry

    /**
     * Cover art that is only read on the receiver's first request (publishing a
     * whole queue must stay instant) and kept for the rest of the session.
     */
    private class ArtworkEntry(private val read: () -> Cover?) : Entry {
        @Volatile private var loaded: Cover? = null
        @Volatile private var resolved = false

        fun cover(): Cover? {
            if (resolved) return loaded
            synchronized(this) {
                if (!resolved) {
                    loaded = runCatching(read).getOrNull()
                    resolved = true
                }
                return loaded
            }
        }
    }

    private class Cover(val bytes: ByteArray, val mime: String)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** `albumArtPath` → already published URL, so a republished queue reuses its tokens. */
    private val artworkUrls = ConcurrentHashMap<String, String>()

    private val counter = AtomicLong(0L)
    private val salt = UUID.randomUUID().toString().take(8)
    private val workers = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "foam-cast-http").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    /**
     * Publishes the local file behind [path] and returns its LAN URL, or null
     * when no Wi-Fi/LAN address could be resolved (nothing can be cast then).
     */
    fun publishLocal(path: String, mime: String, length: Long, filename: String): String? =
        publish(LocalEntry(path, length, mime), filename)

    /** Proxies a distant stream that needs request headers, honouring ranges. */
    fun publishRemote(url: String, mime: String, headers: Map<String, String>): String? =
        publish(RemoteEntry(url, headers, mime), titleFrom(url))

    /**
     * Publishes the cover art behind [artworkPath] and returns a URL the
     * receiver can fetch, or null when the track has no usable picture.
     *
     * Local tracks point at the audio file itself (the picture lives in its
     * tags) and distant tracks at an image cached on disk at scan time, so the
     * bytes are extracted when the receiver first asks for the URL. A remote
     * http(s) image is handed over untouched.
     */
    fun publishArtwork(artworkPath: String?): String? {
        val source = artworkPath?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { source.toUri() }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return source
        artworkUrls[source]?.let { return it }
        val url = publish(ArtworkEntry { readCover(uri) }, "cover.jpg") ?: return null
        artworkUrls[source] = url
        return url
    }

    /** Forgets every published track (called when the cast session ends). */
    fun clear() {
        entries.clear()
        artworkUrls.clear()
    }

    fun shutdown() {
        clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    private fun publish(entry: Entry, filename: String): String? {
        val base = ensureStarted() ?: return null
        val token = "$salt-${counter.incrementAndGet().toString(36)}"
        entries[token] = entry
        return "$base/cast/$token/${sanitize(filename)}"
    }

    /** Starts the server on the first call and returns its LAN base URL. */
    @Synchronized
    private fun ensureStarted(): String? {
        if (serverSocket == null) {
            val socket = try {
                ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
            } catch (e: IOException) {
                Log.w(TAG, "Cannot open the cast media server", e)
                return null
            }
            serverSocket = socket
            port = socket.localPort
            Thread({ acceptLoop(socket) }, "foam-cast-http-accept").apply {
                isDaemon = true
                start()
            }
        }
        val address = lanAddress() ?: run {
            Log.w(TAG, "No LAN address; Chromecast will not be able to fetch local files")
            return null
        }
        return "http://$address:$port"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            workers.execute {
                runCatching { handle(client) }
                    .onFailure { Log.d(TAG, "Cast media request failed: ${it.message}") }
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = REQUEST_TIMEOUT_MS
        val input = client.getInputStream()
        val reader = input.bufferedReader(Charsets.ISO_8859_1)

        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]

        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (line.substring(0, colon).trim().equals("Range", ignoreCase = true)) {
                rangeHeader = line.substring(colon + 1).trim()
            }
        }

        val output = client.getOutputStream()
        val segments = runCatching { target.toUri().pathSegments }.getOrNull().orEmpty()
        val castIndex = segments.indexOf("cast")
        val token = if (castIndex >= 0) segments.getOrNull(castIndex + 1) else null
        val entry = token?.let { entries[it] }
        if (entry == null) {
            writeHeaders(output, 404, "Not Found", emptyList())
            return
        }

        when (entry) {
            is LocalEntry -> serveLocal(entry, rangeHeader, output, method == "HEAD")
            is RemoteEntry -> serveRemote(entry, rangeHeader, output, method == "HEAD")
            is ArtworkEntry -> serveArtwork(entry, output, method == "HEAD")
        }
    }

    private fun serveLocal(entry: LocalEntry, rangeHeader: String?, output: OutputStream, headOnly: Boolean) {
        val descriptor = try {
            context.contentResolver.openAssetFileDescriptor(entry.uri.toUri(), "r")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open ${entry.uri}: ${e.message}")
            null
        }
        if (descriptor == null) {
            writeHeaders(output, 404, "Not Found", emptyList())
            return
        }
        // createInputStream() takes ownership of the descriptor and positions it
        // past the declared start offset: closing it closes the fd exactly once.
        descriptor.createInputStream().use { file ->
            val total = when {
                descriptor.length > 0 -> descriptor.length
                entry.length > 0 -> entry.length
                else -> -1L
            }
            val range = parseRange(rangeHeader, total)
            if (rangeHeader != null && range == null && total > 0) {
                writeHeaders(
                    output, 416, "Range Not Satisfiable",
                    listOf("Content-Range" to "bytes */$total")
                )
                return
            }
            val start = range?.first ?: 0L
            val end = range?.last ?: if (total > 0) total - 1 else -1L
            val length = if (end >= start) end - start + 1 else -1L

            val headers = mutableListOf<Pair<String, String>>()
            headers += "Content-Type" to entry.mime
            headers += "Accept-Ranges" to "bytes"
            if (length >= 0) headers += "Content-Length" to length.toString()
            if (range != null && total > 0) {
                headers += "Content-Range" to "bytes $start-$end/$total"
                writeHeaders(output, 206, "Partial Content", headers)
            } else {
                writeHeaders(output, 200, "OK", headers)
            }
            if (headOnly) return

            skipFully(file, start)
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = if (length >= 0) length else Long.MAX_VALUE
            while (remaining > 0) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val read = file.read(buffer, 0, want)
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            output.flush()
        }
    }

    /**
     * Positions [stream] at [offset]. Regular files seek in one step (the case for
     * every MediaStore/SAF audio file); provider-backed pipes read and discard.
     */
    private fun skipFully(stream: InputStream, offset: Long) {
        if (offset <= 0L) return
        var left = offset
        var scratch: ByteArray? = null
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            val buffer = scratch ?: ByteArray(BUFFER_SIZE).also { scratch = it }
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read <= 0) return
            left -= read
        }
    }

    private fun serveRemote(
        entry: RemoteEntry,
        rangeHeader: String?,
        output: OutputStream,
        headOnly: Boolean
    ) {
        val connection = try {
            (URL(entry.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = REQUEST_TIMEOUT_MS
                readTimeout = REQUEST_TIMEOUT_MS
                instanceFollowRedirects = true
                if (rangeHeader != null) setRequestProperty("Range", rangeHeader)
                if (headOnly) requestMethod = "HEAD"
                entry.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot proxy ${entry.url}: ${e.message}")
            writeHeaders(output, 502, "Bad Gateway", emptyList())
            return
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                writeHeaders(output, 502, "Bad Gateway", emptyList())
                return
            }
            val headers = mutableListOf<Pair<String, String>>()
            headers += "Content-Type" to (connection.contentType ?: entry.mime)
            headers += "Accept-Ranges" to "bytes"
            connection.getHeaderField("Content-Range")?.let { headers += "Content-Range" to it }
            connection.contentLengthLong.takeIf { it >= 0 }?.let {
                headers += "Content-Length" to it.toString()
            }
            if (code == 206) {
                writeHeaders(output, 206, "Partial Content", headers)
            } else {
                writeHeaders(output, 200, "OK", headers)
            }
            if (headOnly) return
            val stream: InputStream = connection.inputStream
            stream.use { it.copyTo(output, BUFFER_SIZE) }
            output.flush()
        } finally {
            connection.disconnect()
        }
    }

    /** Serves a cover picture (no ranges: images are small and fetched whole). */
    private fun serveArtwork(entry: ArtworkEntry, output: OutputStream, headOnly: Boolean) {
        val cover = entry.cover()
        if (cover == null) {
            writeHeaders(output, 404, "Not Found", emptyList())
            return
        }
        val headers = listOf(
            "Content-Type" to cover.mime,
            "Content-Length" to cover.bytes.size.toString(),
            // The receiver caches by URL; a re-request of the same track is legit.
            "Cache-Control" to "public, max-age=86400"
        )
        writeHeaders(output, 200, "OK", headers)
        if (headOnly) return
        output.write(cover.bytes)
        output.flush()
    }

    /** Extracts [uri]'s picture: a cached image file, else the audio file's embedded tag. */
    private fun readCover(uri: Uri): Cover? {
        val bytes = if (isImageFile(uri)) readFileBytes(uri) else readEmbeddedPicture(uri)
        return bytes?.let { normalizeCover(it) }
    }

    private fun isImageFile(uri: Uri): Boolean {
        val scheme = uri.scheme
        if (scheme != null && scheme != "file" && scheme != "content") return false
        val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        return extension in IMAGE_EXTENSIONS
    }

    private fun readFileBytes(uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read cover art $uri: ${e.message}")
        null
    }

    private fun readEmbeddedPicture(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            Log.d(TAG, "No embedded cover art in $uri: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Hands the receiver a picture it can decode cheaply: an already small image
     * is served untouched, a large one (embedded covers go up to several MB) is
     * downscaled and re-encoded to JPEG. Entries live until the session ends, so
     * keeping them small matters.
     */
    private fun normalizeCover(bytes: ByteArray): Cover? {
        val mime = imageMimeType(bytes) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= MAX_COVER_PX && bounds.outHeight <= MAX_COVER_PX) {
            return Cover(bytes, mime)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_COVER_PX)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return Cover(bytes, mime)
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_COVER_PX) {
            val ratio = MAX_COVER_PX.toFloat() / longest
            bitmap.scale(
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1)
            ).also { if (it !== bitmap) bitmap.recycle() }
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, out)
        scaled.recycle()
        return Cover(out.toByteArray(), "image/jpeg")
    }

    /** Closest power-of-two sampling that keeps both sides at or above [max]. */
    private fun sampleSizeFor(width: Int, height: Int, max: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= max && height / (sample * 2) >= max) sample *= 2
        return sample
    }

    /** Content type from the picture's magic bytes; null when it is not an image. */
    private fun imageMimeType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        fun byteAt(index: Int) = bytes[index].toInt() and 0xFF
        return when {
            byteAt(0) == 0xFF && byteAt(1) == 0xD8 -> "image/jpeg"
            byteAt(0) == 0x89 && byteAt(1) == 0x50 && byteAt(2) == 0x4E -> "image/png"
            byteAt(0) == 0x47 && byteAt(1) == 0x49 -> "image/gif"
            byteAt(0) == 0x42 && byteAt(1) == 0x4D -> "image/bmp"
            byteAt(0) == 0x52 && byteAt(1) == 0x49 &&
                byteAt(8) == 0x57 && byteAt(9) == 0x45 -> "image/webp"
            else -> null
        }
    }

    private fun writeHeaders(
        output: OutputStream,
        code: Int,
        reason: String,
        headers: List<Pair<String, String>>
    ) {
        val builder = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (name, value) -> builder.append(name).append(": ").append(value).append("\r\n") }
        builder.append("Connection: close\r\n\r\n")
        output.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /** `bytes=<start>-<end>` (or the suffix form `bytes=-<n>`), clamped to [total] when known. */
    private fun parseRange(header: String?, total: Long): LongRange? {
        if (header == null || total <= 0) return null
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startPart = spec.substring(0, dash).trim()
        val endPart = spec.substring(dash + 1).trim()
        if (startPart.isEmpty()) {
            val suffix = endPart.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            return (total - suffix).coerceAtLeast(0)..(total - 1)
        }
        val start = startPart.toLongOrNull() ?: return null
        if (start >= total) return null
        val end = endPart.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        if (end < start) return null
        return start..end
    }

    /** First site-local IPv4 address, i.e. the one a Chromecast can reach. */
    private fun lanAddress(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun sanitize(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return cleaned.takeIf { it.isNotBlank() } ?: "stream"
    }

    private fun titleFrom(url: String): String = url.substringAfterLast('/').substringBefore('?')

    private companion object {
        const val TAG = "LocalCastMediaServer"
        const val BUFFER_SIZE = 64 * 1024
        const val REQUEST_TIMEOUT_MS = 20_000

        /** Longest side of a served cover; a TV never needs more and big art is MBs. */
        const val MAX_COVER_PX = 1024
        const val COVER_QUALITY = 88

        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }
}
