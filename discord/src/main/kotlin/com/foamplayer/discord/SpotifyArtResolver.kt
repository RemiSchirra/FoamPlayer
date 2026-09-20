package com.foamplayer.discord

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Resolves a track's album cover from Spotify without any user login.
 *
 * 1. Fetches the TOTP secret ("nuance") that Spotify rotates periodically from
 *    a community-maintained gist.
 * 2. Mints an anonymous web-player access token from open.spotify.com/api/token
 *    using that TOTP and Spotify's server time (no sp_dc cookie needed).
 * 3. Runs the web player's `searchDesktop` persisted GraphQL query on
 *    api-partner.spotify.com and returns the first track's album cover URL
 *    (Spotify's permanent i.scdn.co CDN).
 */
class SpotifyArtResolver(
    private val logger: DiscordLogger? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private val nuanceMutex = Mutex()

    private var cachedNuance: CachedNuance? = null
    private var cachedToken: CachedToken? = null

    private data class CachedNuance(val secret: String, val version: Int, val fetchedAt: Long)
    private data class CachedToken(val token: String, val expiresAt: Long)

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
                if (primaryArtist.isNotEmpty() && t.isNotEmpty()) add("$primaryArtist $t")
                if (primaryArtist.isNotEmpty() && al.isNotEmpty() && !al.equals(t, ignoreCase = true)) {
                    add("$primaryArtist $al")
                }
                if (isEmpty() && t.isNotEmpty()) add(t)
                if (isEmpty() && al.isNotEmpty()) add(al)
            }

            val token = accessToken()
            if (token.isNullOrBlank()) {
                logger?.log("SpotifyResolver: no access token")
                return@withContext null
            }

            for (query in queries) {
                val cover = search(query, token)
                if (cover != null) {
                    logger?.log("SpotifyResolver: \"$query\" -> $cover")
                    return@withContext cover
                }
                logger?.log("SpotifyResolver: no track result for \"$query\"")
            }
            null
        }

    private suspend fun accessToken(): String? = tokenMutex.withLock {
        val cached = cachedToken
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            return@withLock cached.token
        }

        val nuance = nuance()
        if (nuance == null) {
            logger?.log("SpotifyResolver: no TOTP secret available")
            return@withLock cached?.token
        }

        val serverTimeSec = fetchServerTime()
        if (serverTimeSec == null) {
            logger?.log("SpotifyResolver: no server time")
            return@withLock cached?.token
        }

        val totp = generateTotp(nuance.secret, serverTimeSec)
        val url = buildString {
            append(TOKEN_URL)
            append("?reason=transport")
            append("&productType=web-player")
            append("&totp=").append(totp)
            append("&totpServer=").append(totp)
            append("&totpVer=").append(nuance.version)
        }

        val body = try {
            httpGet(url)
        } catch (e: Exception) {
            logger?.log("SpotifyResolver: token request failed: ${e.message}")
            return@withLock cached?.token
        }

        val token = runCatching {
            json.parseToJsonElement(body).jsonObject["accessToken"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()

        if (token.isNullOrBlank()) {
            logger?.log("SpotifyResolver: token response missing accessToken")
            return@withLock cached?.token
        }

        val expiresIn = runCatching {
            json.parseToJsonElement(body).jsonObject["expiresIn"]?.let { (it as? JsonPrimitive)?.intOrNull }
        }.getOrNull() ?: DEFAULT_EXPIRES_IN_SECONDS

        cachedToken = CachedToken(token, System.currentTimeMillis() + (expiresIn * 1000L - TOKEN_SKEW_MS))
        logger?.log("SpotifyResolver: anonymous token acquired")
        token
    }

    private suspend fun nuance(): CachedNuance? = nuanceMutex.withLock {
        val cached = cachedNuance
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < NUANCE_TTL_MS) {
            return@withLock cached
        }

        val body = try {
            httpGet(NUANCE_URL)
        } catch (e: Exception) {
            logger?.log("SpotifyResolver: nuance fetch failed: ${e.message}")
            return@withLock cached
        }

        val valid = runCatching {
            val list = json.decodeFromString<List<NuanceEntry>>(body)
            list.filter { it.v > 0 && Regex("^[A-Z2-7]+=*$").matches(it.s.uppercase()) }
                .maxByOrNull { it.v }
                ?.let { CachedNuance(it.s.uppercase(), it.v, System.currentTimeMillis()) }
        }.onFailure {
            logger?.log("SpotifyResolver: nuance parse failed: ${it.message}")
        }.getOrNull()

        if (valid == null) {
            logger?.log("SpotifyResolver: no valid nuance in gist")
            return@withLock cached
        }

        cachedNuance = valid
        valid
    }

    private suspend fun fetchServerTime(): Long? = withContext(Dispatchers.IO) {
        try {
            val body = httpGet(SERVER_TIME_URL)
            json.parseToJsonElement(body).jsonObject["serverTime"]
                ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
        } catch (e: Exception) {
            logger?.log("SpotifyResolver: server time fetch failed: ${e.message}")
            null
        }
    }

    private suspend fun search(query: String, token: String): String? = withContext(Dispatchers.IO) {
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

        val url = buildString {
            append(PARTNER_QUERY_URL)
            append("?operationName=searchDesktop")
            append("&variables=").append(urlEncode(variables))
            append("&extensions=").append(urlEncode(extensions))
        }

        try {
            val body = httpGet(url, mapOf("Authorization" to "Bearer $token"))
            parseCover(json.parseToJsonElement(body))
        } catch (e: Exception) {
            logger?.log("SpotifyResolver: search failed: ${e.message}")
            null
        }
    }

    private fun parseCover(root: kotlinx.serialization.json.JsonElement): String? {
        val data = (root as? JsonObject)?.get("data") as? JsonObject ?: return null
        val search = data["searchV2"] as? JsonObject ?: return null
        val tracks = search["tracksV2"] as? JsonObject ?: return null
        val items = tracks["items"] as? JsonArray ?: return null

        for (entry in items) {
            val wrapper = entry as? JsonObject ?: continue
            val item = wrapper["item"] as? JsonObject ?: continue
            val track = item["data"] as? JsonObject ?: continue
            val album = track["albumOfTrack"] as? JsonObject ?: continue
            val cover = album["coverArt"] as? JsonObject ?: continue
            val sources = cover["sources"] as? JsonArray ?: continue

            val best = sources
                .mapNotNull { it as? JsonObject }
                .mapNotNull { source ->
                    val url = source["url"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                    val width = source["width"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
                    if (url != null) url to width else null
                }
                .maxByOrNull { it.second }
                ?.first
            if (best != null) return best
        }
        return null
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun httpGet(
        urlString: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Accept-Language", "en")
            for ((key, value) in extraHeaders) {
                connection.setRequestProperty(key, value)
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode: ${body.take(200)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun generateTotp(base32Secret: String, serverTimeSec: Long): String {
        val key = base32Decode(base32Secret)
        val timeStep = floor(serverTimeSec.toDouble() / TOTP_INTERVAL_SECONDS).toLong()

        val timeBytes = ByteArray(8)
        var value = timeStep
        for (i in 7 downTo 0) {
            timeBytes[i] = (value and 0xFF).toByte()
            value = value shr 8
        }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(timeBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val code = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        return (code % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleaned = input.uppercase().replace("=", "")
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bits = 0
        for (c in cleaned) {
            val value = alphabet.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }

    @kotlinx.serialization.Serializable
    private data class NuanceEntry(val s: String, val v: Int)

    companion object {
        private const val TOKEN_URL = "https://open.spotify.com/api/token"
        private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
        private const val NUANCE_URL =
            "https://gist.githubusercontent.com/sonic-liberation/22ed9c6ba463899e933427f7de1f0eef/raw/nuances.json"
        private const val PARTNER_QUERY_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
        private const val SEARCH_DESKTOP_HASH = "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val NUANCE_TTL_MS = 5L * 60 * 1000
        private const val TOTP_INTERVAL_SECONDS = 30L
        private const val DEFAULT_EXPIRES_IN_SECONDS = 3600
        private const val TOKEN_SKEW_MS = 60_000L
    }
}
