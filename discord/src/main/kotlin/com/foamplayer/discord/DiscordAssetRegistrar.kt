package com.foamplayer.discord

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Registers external image URLs with Discord's `external-assets` endpoint so
 * they can be referenced in presence assets as `mp:<path>`. User-token
 * presence only renders asset values it can proxy — raw URLs are silently
 * ignored. Registrations expire server-side, so resolved paths are cached
 * only for [CACHE_TTL_MS] and re-registered afterwards.
 */
class DiscordAssetRegistrar(
    httpClient: HttpClient? = null,
    private val logger: DiscordLogger? = null
) {
    private val ownsClient = httpClient == null
    private val http: HttpClient = httpClient ?: HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val failures = ConcurrentHashMap<String, Long>()
    private val mutex = Mutex()

    private data class CacheEntry(val path: String, val resolvedAt: Long)

    /** The endpoint is rate limited; the response states how long to wait. */
    private class RateLimitedException(val retryAfterMs: Long) :
        Exception("Rate limited for ${retryAfterMs}ms")

    /**
     * Returns a value usable in an activity asset field: the input as-is for
     * asset keys / already-proxied images, or an `mp:` media-proxy path for a
     * freshly (or previously) registered external URL. Returns null when an
     * external URL could not be registered — raw URLs are never returned as a
     * fallback because user-token presence silently ignores them. Cancellation
     * always propagates. Failures are remembered briefly so callers retrying
     * on every update don't hammer the endpoint.
     */
    suspend fun resolveImage(accessToken: String, image: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                when (val parsed = parse(image)) {
                    is Parsed.Literal -> parsed.value
                    is Parsed.ExternalUrl -> {
                        val cached = cache[image]
                        if (cached != null && System.currentTimeMillis() - cached.resolvedAt <= CACHE_TTL_MS) {
                            return@withLock "mp:${cached.path}"
                        }
                        val failedAt = failures[image]
                        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) {
                            return@withLock null
                        }

                        var registered: String? = null
                        var lastError: Exception? = null
                        repeat(REGISTRATION_ATTEMPTS) { attempt ->
                            try {
                                registered = registerExternal(accessToken, image)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: RateLimitedException) {
                                lastError = e
                                logger?.log("AssetRegistrar: rate limited, waiting ${e.retryAfterMs}ms")
                                if (attempt < REGISTRATION_ATTEMPTS - 1) {
                                    kotlinx.coroutines.delay(e.retryAfterMs)
                                }
                            } catch (e: Exception) {
                                lastError = e
                                logger?.log("AssetRegistrar: attempt ${attempt + 1} failed: ${e.message}")
                                if (attempt < REGISTRATION_ATTEMPTS - 1) {
                                    kotlinx.coroutines.delay(REGISTRATION_RETRY_DELAY_MS)
                                }
                            }
                            if (registered != null) return@repeat
                        }

                        if (registered != null) {
                            failures.remove(image)
                            cache[image] = CacheEntry(registered!!, System.currentTimeMillis())
                            "mp:$registered"
                        } else {
                            failures[image] = System.currentTimeMillis()
                            logger?.log(
                                "AssetRegistrar: registration failed for $image" +
                                        (lastError?.let { ": ${it.message}" } ?: "")
                            )
                            null
                        }
                    }
                }
            }
        }

    fun clearCache() {
        cache.clear()
        failures.clear()
    }

    private suspend fun registerExternal(accessToken: String, image: String): String? =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put(
                        "urls",
                        kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(image)))
                    )
                }
            )
            val response = http.post(DiscordApi.EXTERNAL_ASSETS_ENDPOINT) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(HttpHeaders.ContentType, "application/json")
                header(HttpHeaders.Accept, "application/json")
                setBody(body)
            }
            val bodyText = response.bodyAsText()

            when (response.status.value) {
                in 200..299 -> {
                    val array = json.parseToJsonElement(bodyText).jsonArray
                    val path = array.firstOrNull()
                        ?.jsonObject?.get("external_asset_path")
                        ?.jsonPrimitive?.content
                    if (path == null) {
                        logger?.log("AssetRegistrar: no external_asset_path in response for $image")
                    }
                    path
                }

                429 -> {
                    val retryAfterSeconds = runCatching {
                        json.parseToJsonElement(bodyText).jsonObject["retry_after"]
                            ?.jsonPrimitive?.doubleOrNull
                    }.getOrNull() ?: 1.0
                    throw RateLimitedException(
                        (retryAfterSeconds * 1000).toLong().coerceIn(250L, 10_000L)
                    )
                }

                else -> throw IllegalStateException("HTTP ${response.status.value}: ${bodyText.take(200)}")
            }
        }

    private sealed class Parsed {
        data class Literal(val value: String) : Parsed()
        data class ExternalUrl(val url: String) : Parsed()
    }

    private fun parse(image: String): Parsed {
        if (SNOWFLAKE_REGEX.matches(image)) return Parsed.Literal(image)
        if (PREFIXES.any { image.startsWith(it) }) return Parsed.Literal(image)
        if (image.startsWith("external/")) return Parsed.Literal("mp:$image")

        val isHttpUrl = runCatching {
            val uri = URI(image)
            (uri.scheme == "http" || uri.scheme == "https") && uri.host != null
        }.getOrDefault(false)
        if (!isHttpUrl) return Parsed.Literal(image)

        val isDiscordCdn = CDN_PREFIXES.any { image.startsWith(it) }
        if (isDiscordCdn) {
            val converted = CDN_PREFIXES.fold(image) { acc, prefix -> acc.replace(prefix, "mp:") }
            return Parsed.Literal(converted)
        }
        return Parsed.ExternalUrl(image)
    }

    fun close() {
        if (ownsClient) {
            runCatching { http.close() }
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 12_000L
        private const val CACHE_TTL_MS = 12L * 60 * 60 * 1000
        private const val FAILURE_COOLDOWN_MS = 15_000L
        private const val REGISTRATION_ATTEMPTS = 3
        private const val REGISTRATION_RETRY_DELAY_MS = 1_000L
        private val SNOWFLAKE_REGEX = Regex("^[0-9]{17,19}$")
        private val PREFIXES = listOf("mp:", "youtube:", "spotify:", "twitch:")
        private val CDN_PREFIXES = listOf(
            "https://cdn.discordapp.com/",
            "http://cdn.discordapp.com/",
            "https://media.discordapp.net/",
            "http://media.discordapp.net/"
        )
    }
}
