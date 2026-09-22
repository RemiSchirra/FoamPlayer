package com.foamplayer.spotify

import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Shared plumbing of the Spotify web-player GraphQL search used by every
 * resolver of this module: the `searchDesktop` persisted query (hash), the
 * partner endpoint URL builder, and the JSON navigation helpers.
 */
internal const val SEARCH_DESKTOP_HASH =
    "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49"

/** The artist as tagged on Spotify: the first name of a multi-artist tag ("A; B"). */
internal fun primaryArtist(artist: String): String =
    artist
        .split(';', ',', '&')
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()

/** URL of the anonymous `searchDesktop` persisted GraphQL query for [query]. */
internal fun buildSearchDesktopUrl(query: String): String {
    val variables = buildJsonObject {
        put("searchTerm", query)
        put("offset", 0)
        put("limit", 3)
        put("numberOfTopResults", 5)
        put("includeAudiobooks", false)
        put("includeArtistHasConcertsField", false)
        put("includePreReleases", false)
        put("includeLocalConcertsField", false)
        put("includeAuthors", false)
    }.toString()

    val extensions = buildJsonObject {
        putJsonObject("persistedQuery") {
            put("version", 1)
            put("sha256Hash", SEARCH_DESKTOP_HASH)
        }
    }.toString()

    val encode = { value: String -> URLEncoder.encode(value, Charsets.UTF_8.name()) }
    return "${PARTNER_QUERY_URL}?operationName=searchDesktop" +
        "&variables=${encode(variables)}" +
        "&extensions=${encode(extensions)}"
}

internal fun searchV2(root: JsonElement): JsonObject? {
    val data = (root as? JsonObject)?.get("data") as? JsonObject ?: return null
    return data["searchV2"] as? JsonObject
}

/** Widest URL of a Spotify `sources` array ([url, width] pairs). */
internal fun JsonArray.bestSourceUrl(): String? =
    mapNotNull { it as? JsonObject }
        .mapNotNull { source ->
            val url = source["url"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            val width = source["width"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
            if (url != null) url to width else null
        }
        .maxByOrNull { it.second }
        ?.first

private const val PARTNER_QUERY_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
