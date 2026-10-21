package com.foamplayer.lastfm

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Client for the parts of the Last.fm Web API this app needs: the browser
 * token flow (`auth.getToken` → `auth.getSession`) and scrobbling
 * (`track.updateNowPlaying` → `track.scrobble`).
 *
 * Every request is signed with the API shared secret. Ktor is configured
 * without [io.ktor.client.plugins.HttpCallValidator] throwing, because Last.fm
 * reports API-level failures as JSON bodies whose `error` code is meaningful
 * (a pending auth token, for example, is not a real failure).
 */
class LastFmClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val logger: LastFmLogger? = null,
    httpClient: HttpClient? = null
) {
    private val ownsClient = httpClient == null
    private val http: HttpClient = httpClient ?: HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether credentials were supplied at build time. */
    val isConfigured: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()

    /** Requests a token and builds the browser URL the user must visit. */
    suspend fun createAuthorizationSession(): Result<LastFmAuthorizationSession> =
        withContext(Dispatchers.IO) {
            try {
                require(isConfigured) { NOT_CONFIGURED_MESSAGE }
                val body = execute("auth.getToken")
                parseError(body)?.let { throw it }
                val token = parseString(body, "token")
                    ?: throw IllegalStateException("Last.fm did not return an authorization token")
                Result.success(
                    LastFmAuthorizationSession(
                        token = token,
                        authorizationUrl = LastFmApi.authorizationUrl(apiKey, token)
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.log("auth.getToken failed: ${e.message}")
                Result.failure(e)
            }
        }

    /**
     * Polls `auth.getSession`. Last.fm answers with error 14 until the user
     * approves the token in the browser, which maps to [LastFmSessionResult.Pending].
     */
    suspend fun fetchSession(token: String): LastFmSessionResult =
        withContext(Dispatchers.IO) {
            try {
                val body = execute("auth.getSession", mapOf("token" to token))
                val error = parseError(body)
                when {
                    error != null && error.code == LastFmApi.ERROR_UNAUTHORIZED_TOKEN ->
                        LastFmSessionResult.Pending
                    error != null -> LastFmSessionResult.Failed(error.message)
                    else -> parseSession(body)
                        ?.let { LastFmSessionResult.Authorized(it) }
                        ?: LastFmSessionResult.Failed("Last.fm did not return a session")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.log("auth.getSession failed: ${e.message}")
                LastFmSessionResult.Failed(e.message)
            }
        }

    /** Tells Last.fm what is playing right now. */
    suspend fun updateNowPlaying(
        session: LastFmSession,
        track: LastFmTrack
    ): Result<Unit> {
        if (!track.isValid) return Result.failure(IllegalArgumentException("Invalid Last.fm track"))
        return callWithSession("track.updateNowPlaying", session, track.toParameters())
    }

    /** Submits one completed listen. */
    suspend fun scrobble(
        session: LastFmSession,
        scrobble: LastFmScrobble
    ): Result<Unit> {
        if (!scrobble.track.isValid) {
            return Result.failure(IllegalArgumentException("Invalid Last.fm track"))
        }
        val params = scrobble.track.toParameters() +
                ("timestamp" to scrobble.timestampSeconds.toString())
        return callWithSession("track.scrobble", session, params)
    }

    private suspend fun callWithSession(
        method: String,
        session: LastFmSession,
        params: Map<String, String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = execute(method, params + ("sk" to session.sessionKey))
            parseError(body)?.let { throw it }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger?.log("$method failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Signs and POSTs one API call, returning the raw response body. */
    private suspend fun execute(
        method: String,
        params: Map<String, String> = emptyMap()
    ): String {
        if (!isConfigured) throw IllegalStateException(NOT_CONFIGURED_MESSAGE)
        val signedParams = params + mapOf("method" to method, "api_key" to apiKey)
        val form = signedParams + mapOf(
            "api_sig" to LastFmApi.signature(signedParams, apiSecret),
            "format" to "json"
        )
        return http.post(LastFmApi.BASE_URL) {
            header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            header(HttpHeaders.Accept, "application/json")
            setBody(form.entries.joinToString("&") { (key, value) ->
                "${key.formEncoded()}=${value.formEncoded()}"
            })
        }.bodyAsText()
    }

    private fun parseError(body: String): LastFmApiException? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null
        val code = (root["error"] as? JsonPrimitive)?.intOrNull ?: return null
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull
            ?: "Last.fm error $code"
        return LastFmApiException(code, message)
    }

    private fun parseString(body: String, key: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject[key]
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseSession(body: String): LastFmSession? = runCatching {
        val session = json.parseToJsonElement(body).jsonObject["session"]?.jsonObject
            ?: return@runCatching null
        val key = session["key"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val username = session["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val subscriber = session["subscriber"]?.jsonPrimitive?.let { primitive ->
            primitive.booleanOrNull ?: (primitive.contentOrNull == "1")
        } ?: false
        LastFmSession(username = username, sessionKey = key, subscriber = subscriber)
    }.getOrNull()

    private fun LastFmTrack.toParameters(): Map<String, String> = buildMap {
        put("artist", artist.trim())
        put("track", title.trim())
        album?.trim()?.takeIf { it.isNotEmpty() }?.let { put("album", it) }
        albumArtist?.trim()?.takeIf { it.isNotEmpty() }?.let { put("albumArtist", it) }
        durationSeconds?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
        trackNumber?.takeIf { it > 0 }?.let { put("trackNumber", it.toString()) }
    }

    private fun String.formEncoded(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name())

    fun close() {
        if (ownsClient) {
            runCatching { http.close() }
        }
    }

    companion object {
        const val NOT_CONFIGURED_MESSAGE =
            "Last.fm API key and secret are not configured"

        private const val REQUEST_TIMEOUT_MS = 12_000L
    }
}
