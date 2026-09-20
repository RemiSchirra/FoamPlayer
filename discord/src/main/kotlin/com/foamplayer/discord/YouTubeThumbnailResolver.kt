package com.foamplayer.discord

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Resolves a track's cover art by searching YouTube for "artist album" first
 * (album uploads and topic videos carry the album cover as thumbnail), then
 * "artist title" as a fallback. Only the primary artist is used — multi-artist
 * tags ("A; B") are split on the library's separators. Uses the same public
 * Innertube endpoint the YouTube web client itself uses, so no API key or
 * quota is involved.
 */
class YouTubeThumbnailResolver(
    httpClient: HttpClient? = null,
    private val logger: DiscordLogger? = null
) {
    private val ownsClient = httpClient == null
    private val http: HttpClient = httpClient ?: HttpClient(OkHttp) {
        expectSuccess = true
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolveThumbnail(artist: String, title: String, album: String): String? =
        withContext(Dispatchers.IO) {
            val primaryArtist = artist
                .split(';', ',', '&')
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
            val t = title.trim()
            val al = album.trim()

            val queries = buildList {
                if (primaryArtist.isNotEmpty() && al.isNotEmpty() && !al.equals(t, ignoreCase = true)) {
                    add("$primaryArtist $al")
                }
                if (primaryArtist.isNotEmpty() && t.isNotEmpty()) add("$primaryArtist $t")
                if (isEmpty() && t.isNotEmpty()) add(t)
                if (isEmpty() && al.isNotEmpty()) add(al)
            }

            for (query in queries) {
                val thumbnail = search(query)
                if (thumbnail != null) {
                    logger?.log("YouTubeResolver: \"$query\" -> $thumbnail")
                    return@withContext thumbnail
                }
                logger?.log("YouTubeResolver: no video result for \"$query\"")
            }
            null
        }

    private suspend fun search(query: String): String? {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("query", query)
        }.toString()

        return runCatching {
            val response = http.post("$SEARCH_ENDPOINT?key=$WEB_API_KEY&prettyPrint=false") {
                header(HttpHeaders.ContentType, "application/json")
                header("X-YouTube-Client-Name", CLIENT_NAME_ID)
                header("X-YouTube-Client-Version", CLIENT_VERSION)
                setBody(body)
            }.bodyAsText()
            findThumbnail(json.parseToJsonElement(response).jsonObject)
        }.onFailure {
            logger?.log("YouTubeResolver: search failed: ${it.message}")
        }.getOrNull()
    }

    /**
     * Depth-first walk of the response looking for the first video renderer
     * (an object carrying both a videoId and a thumbnail), which keeps this
     * resilient against YouTube layout changes.
     */
    private fun findThumbnail(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                (element["videoRenderer"] as? JsonObject)?.let { renderer ->
                    thumbnailOf(renderer)?.let { return it }
                }
                if (element.containsKey("videoId") && element.containsKey("thumbnail")) {
                    thumbnailOf(element)?.let { return it }
                }
                for (value in element.values) {
                    findThumbnail(value)?.let { return it }
                }
            }

            is JsonArray -> {
                for (item in element) {
                    findThumbnail(item)?.let { return it }
                }
            }

            else -> Unit
        }
        return null
    }

    private fun thumbnailOf(renderer: JsonObject): String? {
        val videoId = (renderer["videoId"] as? JsonPrimitive)?.contentOrNull ?: return null
        val thumbnails = (renderer["thumbnail"] as? JsonObject)?.get("thumbnails") as? JsonArray
        val best = thumbnails
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { entry ->
                val url = (entry["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val width = (entry["width"] as? JsonPrimitive)?.intOrNull ?: 0
                url to width
            }
            ?.maxByOrNull { it.second }
            ?.first
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        return when {
            best.startsWith("//") -> "https:$best"
            best.startsWith("http://") || best.startsWith("https://") -> best
            else -> "https://$best"
        }
    }

    fun close() {
        if (ownsClient) {
            runCatching { http.close() }
        }
    }

    companion object {
        private const val SEARCH_ENDPOINT = "https://www.youtube.com/youtubei/v1/search"
        private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val CLIENT_NAME = "WEB"
        private const val CLIENT_NAME_ID = "1"
        private const val CLIENT_VERSION = "2.20240304.00.00"
        private const val REQUEST_TIMEOUT_MS = 12_000L
    }
}
