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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
 *
 * The cache is a strict visited-LRU queue bounded by the user's "artists to
 * cache" setting: every stored entry ([remember] from the bulk scan,
 * [rememberVisited] from an artist page visit) carries a recency timestamp
 * (last visited, falling back to resolved-at), and [evictToLimit] removes the
 * least-recently used entries first — the oldest always leaves room for a
 * newly stored one. [revisions] ticks on every content change so consumers
 * re-read without polling.
 */
@Singleton
class ArtistImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    private val snapshotMutex = Mutex()
    private var snapshot: JSONObject? = null

    /** Ticks on every content change (store, evict, clear) so consumers re-read. */
    private val _revisions = MutableStateFlow(0L)
    val revisions: StateFlow<Long> = _revisions.asStateFlow()

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
        put(key, entry, file.absolutePath)
        fileUri(file.absolutePath)
    }

    /**
     * Stores the entry produced by the "resolve all artists" scan and evicts
     * the least-recently used entries until at most [limit] remain, so the
     * scan fills the same bounded queue as on-demand visits.
     */
    suspend fun remember(key: String, url: String, limit: Int) = withContext(Dispatchers.IO) {
        put(key, baseEntry(url), download(url, key)?.absolutePath)
        evictToLimit(limit)
    }

    /**
     * Stores an on-demand entry (resolved when an artist page is opened) and
     * evicts the least-recently visited entries until at most [limit] remain,
     * so the cache never outgrows the user's "artists to cache" setting.
     */
    suspend fun rememberVisited(key: String, url: String, limit: Int) = withContext(Dispatchers.IO) {
        put(
            key,
            baseEntry(url).put(KEY_LAST_VISITED, System.currentTimeMillis()),
            download(url, key)?.absolutePath
        )
        evictToLimit(limit)
    }

    /** Refreshes [key]'s visited recency (its artist page was opened again). */
    suspend fun touch(key: String) = withContext(Dispatchers.IO) {
        val entries = loadEntries()
        val entry = entries.optJSONObject(key) ?: return@withContext
        entries.put(key, entry.put(KEY_LAST_VISITED, System.currentTimeMillis()))
        persist(entries)
    }

    /**
     * Trims the cache down to [limit] entries, evicting the least-recently
     * used first (entries without a visited timestamp fall back to their
     * resolved-at one). Both the bulk scan and on-demand visits fill the same
     * queue: the oldest entry always leaves room for a newly stored one.
     */
    suspend fun evictToLimit(limit: Int) = withContext(Dispatchers.IO) {
        val entries = loadEntries()
        val recency = entries.keys().asSequence()
            .mapNotNull { key ->
                val entry = entries.optJSONObject(key) ?: return@mapNotNull null
                key to entry.optLong(KEY_LAST_VISITED, entry.optLong(KEY_RESOLVED_AT, 0L))
            }
            .toList()
        if (recency.size <= limit) return@withContext
        recency
            .sortedBy { it.second }
            .take(recency.size - limit)
            .forEach { (key, _) ->
                entries.optJSONObject(key)?.optString(KEY_PATH)?.let { path ->
                    if (path.isNotBlank()) File(path).delete()
                }
                entries.remove(key)
            }
        persist(entries)
        _revisions.update { it + 1 }
    }

    /** Empties the cache: deletes every image file and the persisted mapping. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cacheDir().listFiles()?.forEach { it.delete() }
        dataStore.edit { preferences -> preferences.remove(STORAGE_KEY) }
        snapshotMutex.withLock { snapshot = JSONObject() }
        _revisions.update { it + 1 }
    }

    private suspend fun put(key: String, entry: JSONObject, path: String?) = withContext(Dispatchers.IO) {
        if (!path.isNullOrBlank()) entry.put(KEY_PATH, path)

        val entries = loadEntries().put(key, entry)
        prune(entries)
        persist(entries)
        _revisions.update { it + 1 }
    }

    private suspend fun persist(entries: JSONObject) {
        dataStore.edit { preferences -> preferences[STORAGE_KEY] = entries.toString() }
        snapshotMutex.withLock { snapshot = entries }
    }

    private fun baseEntry(url: String): JSONObject = JSONObject()
        .put(KEY_URL, url)
        .put(KEY_RESOLVED_AT, System.currentTimeMillis())

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
        private const val KEY_LAST_VISITED = "lastVisited"
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
