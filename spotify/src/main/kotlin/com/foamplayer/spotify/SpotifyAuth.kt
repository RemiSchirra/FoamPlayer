package com.foamplayer.spotify

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Shared anonymous Spotify web-player authentication: mints and caches the
 * access token every resolver in this module queries the web player with.
 *
 * 1. Fetches the TOTP secret ("nuance") that Spotify rotates periodically from
 *    a community-maintained gist.
 * 2. Mints an anonymous web-player access token from open.spotify.com/api/token
 *    using that TOTP and Spotify's server time (no sp_dc cookie needed).
 */
internal class SpotifyAuth(private val logger: SpotifyLogger? = null) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private val nuanceMutex = Mutex()

    private var cachedNuance: CachedNuance? = null
    private var cachedToken: CachedToken? = null

    private data class CachedNuance(val secret: String, val version: Int, val fetchedAt: Long)
    private data class CachedToken(val token: String, val expiresAt: Long)

    /** Blocking GET used by the resolvers; call from [Dispatchers.IO]. */
    fun httpGet(
        urlString: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
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

    fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    suspend fun accessToken(): String? = tokenMutex.withLock {
        val cached = cachedToken
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            return@withLock cached.token
        }

        val nuance = nuance()
        if (nuance == null) {
            logger?.log("SpotifyAuth: no TOTP secret available")
            return@withLock cached?.token
        }

        val serverTimeSec = fetchServerTime()
        if (serverTimeSec == null) {
            logger?.log("SpotifyAuth: no server time")
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
            withContext(Dispatchers.IO) { httpGet(url) }
        } catch (e: Exception) {
            logger?.log("SpotifyAuth: token request failed: ${e.message}")
            return@withLock cached?.token
        }

        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val token = parsed?.get("accessToken")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (token.isNullOrBlank()) {
            logger?.log("SpotifyAuth: token response missing accessToken")
            return@withLock cached?.token
        }

        val expiresIn = parsed["expiresIn"]?.let { (it as? JsonPrimitive)?.intOrNull }
            ?: DEFAULT_EXPIRES_IN_SECONDS

        cachedToken = CachedToken(token, System.currentTimeMillis() + (expiresIn * 1000L - TOKEN_SKEW_MS))
        logger?.log("SpotifyAuth: anonymous token acquired")
        token
    }

    private suspend fun nuance(): CachedNuance? = nuanceMutex.withLock {
        val cached = cachedNuance
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < NUANCE_TTL_MS) {
            return@withLock cached
        }

        val body = try {
            withContext(Dispatchers.IO) { httpGet(NUANCE_URL) }
        } catch (e: Exception) {
            logger?.log("SpotifyAuth: nuance fetch failed: ${e.message}")
            return@withLock cached
        }

        val valid = runCatching {
            val list = json.decodeFromString<List<NuanceEntry>>(body)
            list.filter { it.v > 0 && Regex("^[A-Z2-7]+=*$").matches(it.s.uppercase()) }
                .maxByOrNull { it.v }
                ?.let { CachedNuance(it.s.uppercase(), it.v, System.currentTimeMillis()) }
        }.onFailure {
            logger?.log("SpotifyAuth: nuance parse failed: ${it.message}")
        }.getOrNull()

        if (valid == null) {
            logger?.log("SpotifyAuth: no valid nuance in gist")
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
            logger?.log("SpotifyAuth: server time fetch failed: ${e.message}")
            null
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
        private const val NUANCE_TTL_MS = 5L * 60 * 1000
        private const val TOTP_INTERVAL_SECONDS = 30L
        private const val DEFAULT_EXPIRES_IN_SECONDS = 3600
        private const val TOKEN_SKEW_MS = 60_000L
        private const val REQUEST_TIMEOUT_MS = 12_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
