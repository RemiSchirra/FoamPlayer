package com.foamplayer.app.discord

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Small persisted cache of resolved presence image URLs (keyed per track).
 * Entries expire after [ttlMs] so dead links are eventually re-resolved.
 */
@Singleton
class DiscordAlbumArtStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    suspend fun cachedUrl(key: String, ttlMs: Long): String? = withContext(Dispatchers.IO) {
        val entry = loadEntries().optJSONObject(key) ?: return@withContext null
        val url = entry.optString(KEY_URL).takeIf { it.startsWith("http") } ?: return@withContext null
        val resolvedAt = entry.optLong(KEY_RESOLVED_AT, 0L)
        if (System.currentTimeMillis() - resolvedAt > ttlMs) {
            null
        } else {
            url
        }
    }

    suspend fun remember(key: String, url: String) = withContext(Dispatchers.IO) {
        val entries = loadEntries().put(
            key,
            JSONObject()
                .put(KEY_URL, url)
                .put(KEY_RESOLVED_AT, System.currentTimeMillis())
        )
        prune(entries)
        dataStore.edit { preferences ->
            preferences[STORAGE_KEY] = entries.toString()
        }
    }

    private suspend fun loadEntries(): JSONObject = withContext(Dispatchers.IO) {
        val stored = runCatching { dataStore.data.first()[STORAGE_KEY] }.getOrNull()
        if (stored.isNullOrBlank()) {
            JSONObject()
        } else {
            runCatching { JSONObject(stored) }.getOrDefault(JSONObject())
        }
    }

    private fun prune(entries: JSONObject) {
        val keys = entries.keys().asSequence().toList()
        if (keys.size <= MAX_ENTRIES) return
        keys
            .sortedBy { entries.optJSONObject(it)?.optLong(KEY_RESOLVED_AT, 0L) ?: 0L }
            .take(keys.size - MAX_ENTRIES)
            .forEach { entries.remove(it) }
    }

    companion object {
        private val STORAGE_KEY = stringPreferencesKey("discord_album_art_urls")
        private const val KEY_URL = "url"
        private const val KEY_RESOLVED_AT = "resolvedAt"
        private const val MAX_ENTRIES = 200

        /** Album covers on Spotify's CDN don't expire; only re-resolve very stale links. */
        const val ONLINE_TTL_MS = 30L * 24 * 60 * 60 * 1000

        // "art3" version prefix: bumped whenever the resolution source/strategy
        // changes, so stale results from previous strategies are never reused.
        fun trackKey(artist: String, title: String): String =
            "art3|${artist.trim().lowercase()}|${title.trim().lowercase()}"
    }
}
