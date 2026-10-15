package com.foamplayer.app.data.distant

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local cache for distant-provider cover art. Artwork is downloaded once,
 * while scanning a server, into `filesDir/distant_art` and addressed by a
 * `file://` URI stored on the track. A file is never downloaded twice: a
 * track's art is fetched at scan time and every later lookup is a local read.
 */
@Singleton
class DistantArtworkStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private fun dir(): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Downloads [url] once and returns its `file://` URI; existing files are reused. */
    suspend fun cache(url: String?): String? = withContext(Dispatchers.IO) {
        val remote = url?.takeIf { it.startsWith("http") } ?: return@withContext null
        val file = fileFor(remote)
        if (file.isFile && file.length() > 0) return@withContext file.toURI().toString()
        download(remote, file)?.let { it.toURI().toString() }
    }

    /** Stable, token-agnostic file name so a rotated token reuses the same image. */
    private fun fileFor(url: String): File {
        val key = url.substringBefore('?')
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir(), "$name.$EXTENSION")
    }

    private fun download(url: String, file: File): File? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode !in 200..299) return null

            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_BYTES) {
                            file.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            file.takeIf { it.length() > 0 }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DIR_NAME = "distant_art"
        const val EXTENSION = "jpg"
        const val TIMEOUT_MS = 15_000
        const val BUFFER_BYTES = 8_192
        const val MAX_BYTES = 12 * 1024 * 1024
        const val USER_AGENT = "FoamPlayer/1.0"
    }
}
