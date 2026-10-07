package com.foamplayer.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cache of artist images resolved from Spotify.
 *
 * The picture bytes live in the app's internal cache directory
 * (`context.cacheDir/artist_images`, one file per artist, named after the
 * SHA-256 of the artist key). The resolved CDN URL -> file mapping is
 * persisted in DataStore so a re-scan only fills the gaps: the file never
 * has to be re-downloaded while it exists, and when it was evicted
 * [imagePath] fetches it again from the stored URL without a new Spotify
 * lookup. [clearAll] removes both the files and the mapping.
 */
@Singleton
class ArtistImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    private val snapshotMutex = Mutex()
    private var snapshot: JSONObject? = null

    private fun cacheDir(): File = File(context.cacheDir, DIR_NAME).apply { mkdirs() }

    private fun fileUri(path: String): String = "file://$path"

    /** Whether [key] has a resolved entry (image on disk or re-downloadable). */
    suspend fun hasEntry(key: String): Boolean = withContext(Dispatchers.IO) {
        loadEntries().optJSONObject(key)
            ?.optString(KEY_URL)
            ?.takeIf { it.startsWith("http") } != null
    }

    /** Number of entries whose image file is currently in the cache directory. */
    suspend fun cachedFileCount(): Int = withContext(Dispatchers.IO) {
        val entries = loadEntries()
        entries.keys().asSequence().count { key ->
            entries.optJSONObject(key)?.optString(KEY_PATH)?.let { File(it).isFile } == true
        }
    }

    /**
     * Loadable file URI ("file://...") of the cached image for [key]. When the
     * file was evicted from the cache it is downloaded once more from the
     * stored CDN URL.
     */
    suspend fun imagePath(key: String): String? = withContext(Dispatchers.IO) {
        val entry = loadEntries().optJSONObject(key) ?: return@withContext null
        val path = entry.optString(KEY_PATH)
        if (path.isNotBlank() && File(path).isFile) return@withContext fileUri(path)

        val url = entry.optString(KEY_URL).takeIf { it.startsWith("http") } ?: return@withContext null
        val file = download(url, key) ?: return@withContext null
        store(key, url, file.absolutePath)
        fileUri(file.absolutePath)
    }

    /** Downloads [url] into the cache and persists the entry. */
    suspend fun remember(key: String, url: String) = withContext(Dispatchers.IO) {
        store(key, url, download(url, key)?.absolutePath)
    }

    /** Empties the cache: deletes every image file and the persisted mapping. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cacheDir().listFiles()?.forEach { it.delete() }
        dataStore.edit { preferences -> preferences.remove(STORAGE_KEY) }
        snapshotMutex.withLock { snapshot = JSONObject() }
    }

    private suspend fun store(key: String, url: String, path: String?) = withContext(Dispatchers.IO) {
        val entry = JSONObject()
            .put(KEY_URL, url)
            .put(KEY_RESOLVED_AT, System.currentTimeMillis())
        if (!path.isNullOrBlank()) entry.put(KEY_PATH, path)

        val entries = loadEntries().put(key, entry)
        prune(entries)
        dataStore.edit { preferences -> preferences[STORAGE_KEY] = entries.toString() }
        snapshotMutex.withLock { snapshot = entries }
    }

    private suspend fun loadEntries(): JSONObject = snapshotMutex.withLock {
        snapshot?.let { return@withLock it }
        val stored = runCatching { dataStore.data.first()[STORAGE_KEY] }.getOrNull()
        val parsed = if (stored.isNullOrBlank()) {
            JSONObject()
        } else {
            runCatching { JSONObject(stored) }.getOrDefault(JSONObject())
        }
        snapshot = parsed
        parsed
    }

    private fun prune(entries: JSONObject) {
        val keys = entries.keys().asSequence().toList()
        if (keys.size <= MAX_ENTRIES) return
        keys
            .sortedBy { entries.optJSONObject(it)?.optLong(KEY_RESOLVED_AT, 0L) ?: 0L }
            .take(keys.size - MAX_ENTRIES)
            .forEach { key ->
                entries.optJSONObject(key)?.optString(KEY_PATH)?.let { path ->
                    if (path.isNotBlank()) File(path).delete()
                }
                entries.remove(key)
            }
    }

    /** Streams [url] into the cache (bounded by [MAX_IMAGE_BYTES]); null on failure. */
    private fun download(url: String, key: String): File? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", DOWNLOAD_USER_AGENT)

            if (connection.responseCode !in 200..299) return null

            val file = fileFor(key, url)
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            var total = 0
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) {
                            file.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return file.takeIf { it.length() > 0 }
        } catch (_: Exception) {
            return null
        } finally {
            connection.disconnect()
        }
    }

    /** "artist_images/<sha256 of key>.<ext>" — stable per artist, never a raw name. */
    private fun fileFor(key: String, url: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val extension = url.substringAfterLast('.', "")
            .takeWhile { it.isLetterOrDigit() }
            .lowercase()
            .ifBlank { DEFAULT_EXTENSION }
        return File(cacheDir(), "$name.$extension")
    }

    companion object {
        private val STORAGE_KEY = stringPreferencesKey("artist_image_urls")
        private const val KEY_URL = "url"
        private const val KEY_PATH = "path"
        private const val KEY_RESOLVED_AT = "resolvedAt"
        private const val MAX_ENTRIES = 2000
        private const val DIR_NAME = "artist_images"
        private const val DOWNLOAD_TIMEOUT_MS = 12_000
        private const val DOWNLOAD_BUFFER_BYTES = 8_192
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        private const val DEFAULT_EXTENSION = "jpg"
        private const val DOWNLOAD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * "arimg1" version prefix: bumped whenever the resolution source/strategy
         * changes, so stale results from previous strategies are never reused.
         */
        fun key(artist: String): String =
            "arimg1|${artist.trim().lowercase()}"
    }
}
