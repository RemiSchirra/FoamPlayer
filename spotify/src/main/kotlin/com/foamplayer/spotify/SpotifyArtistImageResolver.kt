package com.foamplayer.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Resolves an artist's picture (the avatar shown on the artist profile) from
 * Spotify without any user login.
 *
 * Runs the same anonymous `searchDesktop` persisted GraphQL query as
 * [SpotifyAlbumArtResolver] — authenticated by the shared [SpotifyAuth] — and
 * walks the response's artists section (`searchV2.artists.items[].data
 * .visuals.avatarImage.sources`, with the profile header image as a fallback),
 * returning the widest source URL (Spotify's permanent i.scdn.co CDN).
 */
class SpotifyArtistImageResolver(
    private val logger: SpotifyLogger? = null
) {
    private val auth = SpotifyAuth(logger)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolveArtistImage(artist: String): String? = withContext(Dispatchers.IO) {
        val name = primaryArtist(artist)
        if (name.isBlank()) {
            logger?.log("SpotifyArtistImage: empty artist name")
            return@withContext null
        }

        val token = auth.accessToken()
        if (token.isNullOrBlank()) {
            logger?.log("SpotifyArtistImage: no access token")
            return@withContext null
        }

        try {
            val body = auth.httpGet(buildSearchDesktopUrl(name), mapOf("Authorization" to "Bearer $token"))
            val image = parseArtistImage(json.parseToJsonElement(body))
            if (image != null) {
                logger?.log("SpotifyArtistImage: \"$name\" -> $image")
            } else {
                logger?.log("SpotifyArtistImage: no artist result for \"$name\"")
            }
            image
        } catch (e: Exception) {
            logger?.log("SpotifyArtistImage: search failed: ${e.message}")
            null
        }
    }

    private fun parseArtistImage(root: kotlinx.serialization.json.JsonElement): String? {
        val search = searchV2(root) ?: return null
        val artists = search["artists"] as? JsonObject ?: return null
        val items = artists["items"] as? JsonArray ?: return null

        for (entry in items) {
            val wrapper = entry as? JsonObject ?: continue
            val data = wrapper["data"] as? JsonObject ?: continue
            val visuals = data["visuals"] as? JsonObject ?: continue

            // Avatar first (square profile picture), header image as fallback.
            val avatar = (visuals["avatarImage"] as? JsonObject)?.get("sources") as? JsonArray
            avatar?.bestSourceUrl()?.let { return it }
            val header = (visuals["headerImage"] as? JsonObject)?.get("sources") as? JsonArray
            header?.bestSourceUrl()?.let { return it }
        }
        return null
    }
}
