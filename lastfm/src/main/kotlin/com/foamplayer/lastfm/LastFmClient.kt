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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

    /**
     * Fetches one page of [username]'s scrobble history, newest first.
     * `user.getRecentTracks` does not need a session key, but is still signed
     * like every other call. The currently playing track (which has no date)
     * is omitted, so every returned item is a completed listen.
     */
    suspend fun fetchRecentTracks(
        username: String,
        page: Int = 1,
        limit: Int = MAX_RECENT_TRACKS_PAGE_SIZE
    ): Result<LastFmRecentTracksPage> = withContext(Dispatchers.IO) {
        for (attempt in 0..MAX_RATE_LIMIT_RETRIES) {
            try {
                require(username.isNotBlank()) { "Last.fm username is missing" }
                val body = execute(
                    "user.getRecentTracks",
                    mapOf(
                        "user" to username,
                        "page" to page.coerceAtLeast(1).toString(),
                        "limit" to limit.coerceIn(1, MAX_RECENT_TRACKS_PAGE_SIZE).toString()
                    )
                )
                parseError(body)?.let { throw it }
                return@withContext Result.success(parseRecentTracksPage(body))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LastFmApiException) {
                // Large history downloads are sequential; back off when Last.fm
                // says we are going too fast instead of failing the whole import.
                if (e.code == LastFmApi.ERROR_RATE_LIMIT &&
                    attempt < MAX_RATE_LIMIT_RETRIES
                ) {
                    logger?.log(
                        "user.getRecentTracks rate limited; retrying (attempt ${attempt + 1})"
                    )
                    delay(RATE_LIMIT_RETRY_MS * (attempt + 1))
                    continue
                }
                logger?.log("user.getRecentTracks failed: ${e.message}")
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                logger?.log("user.getRecentTracks failed: ${e.message}")
                return@withContext Result.failure(e)
            }
        }
        Result.failure(IllegalStateException("Last.fm request failed"))
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

    /**
     * Submits up to [MAX_SCROBBLES_PER_REQUEST] completed listens in one
     * `track.scrobble` call using Last.fm's array-style parameters
     * (`artist[0]`, `track[0]`, `timestamp[0]`, ...). A top-level `error` in
     * the response means the whole request failed (invalid session, for
     * example); per-track ignores are accepted as-is so they are not retried
     * forever.
     */
    suspend fun scrobbleBatch(
        session: LastFmSession,
        scrobbles: List<LastFmScrobble>
    ): Result<Unit> {
        if (scrobbles.isEmpty()) return Result.success(Unit)
        val valid = scrobbles.size <= MAX_SCROBBLES_PER_REQUEST &&
                scrobbles.all { it.track.isValid }
        if (!valid) return Result.failure(IllegalArgumentException("Invalid scrobble batch"))
        return callWithSession("track.scrobble", session, scrobbles.toBatchParameters())
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

        /** `user.getRecentTracks` returns at most 200 scrobbles per page. */
        const val MAX_RECENT_TRACKS_PAGE_SIZE = 200

        /** `track.scrobble` accepts at most 50 scrobbles per request. */
        const val MAX_SCROBBLES_PER_REQUEST = 50

        private const val REQUEST_TIMEOUT_MS = 12_000L
        private const val MAX_RATE_LIMIT_RETRIES = 3
        private const val RATE_LIMIT_RETRY_MS = 1_000L
    }
}

/** Parameters for one completed listen, as Last.fm expects them. */
internal fun LastFmTrack.toParameters(): Map<String, String> = buildMap {
    put("artist", artist.trim())
    put("track", title.trim())
    album?.trim()?.takeIf { it.isNotEmpty() }?.let { put("album", it) }
    albumArtist?.trim()?.takeIf { it.isNotEmpty() }?.let { put("albumArtist", it) }
    durationSeconds?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
    trackNumber?.takeIf { it > 0 }?.let { put("trackNumber", it.toString()) }
}

/** Array-style parameters for one batched `track.scrobble` request. */
internal fun List<LastFmScrobble>.toBatchParameters(): Map<String, String> = buildMap {
    forEachIndexed { index, scrobble ->
        scrobble.track.toParameters().forEach { (key, value) -> put("$key[$index]", value) }
        put("timestamp[$index]", scrobble.timestampSeconds.toString())
    }
}

private val lastFmJson = Json { ignoreUnknownKeys = true }

/**
 * Parses one `user.getRecentTracks` JSON response. Only completed scrobbles
 * (entries carrying a `date.uts`) are returned; the now-playing entry is
 * skipped. Last.fm wraps text values as either a plain string or an object
 * with a `#text` field, so both shapes are accepted.
 */
internal fun parseRecentTracksPage(body: String): LastFmRecentTracksPage {
    val root = lastFmJson.parseToJsonElement(body).jsonObject
    val recent = root["recenttracks"]?.jsonObject
        ?: throw IllegalStateException("Last.fm did not return recent tracks")
    val attribute = recent["@attr"]?.jsonObject
    val totalPages = attribute?.get("totalPages").asInt() ?: 0
    val page = attribute?.get("page").asInt() ?: 1
    val total = attribute?.get("total").asInt() ?: 0

    val trackElements = when (val element = recent["track"]) {
        is JsonArray -> element
        is JsonObject -> JsonArray(listOf(element))
        else -> JsonArray(emptyList())
    }

    val tracks = trackElements.mapNotNull { element ->
        val track = element as? JsonObject ?: return@mapNotNull null
        // The now-playing entry has no `date`; only completed listens count.
        val date = track["date"] as? JsonObject ?: return@mapNotNull null
        val timestamp = date["uts"].asText()?.toLongOrNull()
            ?.takeIf { it > 0 } ?: return@mapNotNull null
        val artist = track["artist"].creditedText() ?: return@mapNotNull null
        val title = track["name"].creditedText() ?: return@mapNotNull null
        LastFmScrobbledTrack(
            artist = artist,
            title = title,
            album = track["album"].creditedText(),
            timestampSeconds = timestamp
        )
    }

    return LastFmRecentTracksPage(
        tracks = tracks,
        page = page,
        totalPages = totalPages,
        total = total
    )
}

/** Last.fm wraps most text values as either a string or `{"#text": "..."}`. */
private fun JsonElement?.creditedText(): String? = when (this) {
    is JsonObject -> this["#text"].creditedText()
    is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    else -> null
}

private fun JsonElement?.asText(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement?.asInt(): Int? = when (this) {
    is JsonPrimitive -> intOrNull ?: contentOrNull?.toIntOrNull()
    else -> null
}
