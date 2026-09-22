package com.foamplayer.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Resolves a track's album cover from Spotify without any user login.
 *
 * Runs the web player's `searchDesktop` persisted GraphQL query on
 * api-partner.spotify.com (authenticated by [SpotifyAuth]) and returns the
 * first track's album cover URL (Spotify's permanent i.scdn.co CDN).
 */
class SpotifyAlbumArtResolver(
    private val logger: SpotifyLogger? = null
) {
    private val auth = SpotifyAuth(logger)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolveAlbumArt(artist: String, title: String, album: String): String? =
        withContext(Dispatchers.IO) {
            val primary = primaryArtist(artist)
            val t = title.trim()
            val al = album.trim()

            val queries = buildList {
                if (primary.isNotEmpty() && t.isNotEmpty()) add("$primary $t")
                if (primary.isNotEmpty() && al.isNotEmpty() && !al.equals(t, ignoreCase = true)) {
                    add("$primary $al")
                }
                if (isEmpty() && t.isNotEmpty()) add(t)
                if (isEmpty() && al.isNotEmpty()) add(al)
            }

            val token = auth.accessToken()
            if (token.isNullOrBlank()) {
                logger?.log("SpotifyAlbumArt: no access token")
                return@withContext null
            }

            for (query in queries) {
                val cover = search(query, token)
                if (cover != null) {
                    logger?.log("SpotifyAlbumArt: \"$query\" -> $cover")
                    return@withContext cover
                }
                logger?.log("SpotifyAlbumArt: no track result for \"$query\"")
            }
            null
        }

    private suspend fun search(query: String, token: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = auth.httpGet(buildSearchDesktopUrl(query), mapOf("Authorization" to "Bearer $token"))
            parseCover(json.parseToJsonElement(body))
        } catch (e: Exception) {
            logger?.log("SpotifyAlbumArt: search failed: ${e.message}")
            null
        }
    }

    private fun parseCover(root: kotlinx.serialization.json.JsonElement): String? {
        val search = searchV2(root) ?: return null
        val tracks = search["tracksV2"] as? JsonObject ?: return null
        val items = tracks["items"] as? JsonArray ?: return null

        for (entry in items) {
            val wrapper = entry as? JsonObject ?: continue
            val item = wrapper["item"] as? JsonObject ?: continue
            val track = item["data"] as? JsonObject ?: continue
            val album = track["albumOfTrack"] as? JsonObject ?: continue
            val cover = album["coverArt"] as? JsonObject ?: continue
            val sources = cover["sources"] as? JsonArray ?: continue

            val best = sources.bestSourceUrl()
            if (best != null) return best
        }
        return null
    }
}
