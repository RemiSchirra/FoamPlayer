package com.foamplayer.lastfm

import java.net.URLEncoder
import java.security.MessageDigest

/** Endpoints, signature rules and error codes of the Last.fm scrobbling API. */
object LastFmApi {
    const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"
    const val AUTH_PAGE_URL = "https://www.last.fm/api/auth/"

    /** The session key was revoked or the user disconnected the app. */
    const val ERROR_INVALID_SESSION = 9

    /** The auth token exists but the user has not approved the app yet. */
    const val ERROR_UNAUTHORIZED_TOKEN = 14

    /** Too many requests in a short window; the call can be retried later. */
    const val ERROR_RATE_LIMIT = 29

    /** Browser page the user opens to grant the pending [token] access. */
    fun authorizationUrl(apiKey: String, token: String): String =
        "$AUTH_PAGE_URL?api_key=${apiKey.queryEncoded()}&token=${token.queryEncoded()}"

    /**
     * Last.fm's request signature: parameters sorted by name, concatenated as
     * `name + value` with no separator, followed by the shared secret, hashed
     * with MD5. `api_sig`, `format` and `callback` never take part in it.
     */
    fun signature(params: Map<String, String>, secret: String): String {
        val payload = params
            .filterKeys { it != "api_sig" && it != "format" && it != "callback" }
            .toSortedMap()
            .entries
            .joinToString(separator = "") { (key, value) -> key + value } + secret
        return md5Hex(payload)
    }

    internal fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun String.queryEncoded(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
}
