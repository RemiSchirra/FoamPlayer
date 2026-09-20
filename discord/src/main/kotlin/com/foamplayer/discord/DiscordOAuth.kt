package com.foamplayer.discord

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class DiscordOAuth(
    httpClient: HttpClient? = null
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
    private val secureRandom = SecureRandom()

    fun createAuthorizationSession(): DiscordAuthorizationSession {
        val state = randomUrlSafeString(32)
        val verifier = randomUrlSafeString(64)
        val challenge = sha256Base64Url(verifier)
        val url = buildString {
            append(DiscordApi.AUTHORIZATION_ENDPOINT)
            append("?client_id=").append(DiscordApi.APPLICATION_ID.queryEncoded())
            append("&response_type=code")
            append("&redirect_uri=").append(DiscordApi.REDIRECT_URI.queryEncoded())
            append("&scope=").append(DiscordApi.OAUTH_SCOPE.queryEncoded())
            append("&state=").append(state.queryEncoded())
            append("&code_challenge=").append(challenge.queryEncoded())
            append("&code_challenge_method=S256")
        }
        return DiscordAuthorizationSession(
            state = state,
            codeVerifier = verifier,
            authorizationUrl = url
        )
    }

    suspend fun completeAuthorization(
        session: DiscordAuthorizationSession,
        redirectUri: String
    ): Result<DiscordAuthSession> = withContext(Dispatchers.IO) {
        runCatching {
            require(DiscordApi.isRedirect(redirectUri)) { "Unexpected Discord redirect" }
            require(DiscordApi.queryParameter(redirectUri, "state") == session.state) {
                "Discord authorization state mismatch"
            }

            DiscordApi.queryParameter(redirectUri, "error")?.let { error ->
                val description = DiscordApi.queryParameter(redirectUri, "error_description")
                throw IllegalStateException(description ?: error)
            }

            val code = requireNotNull(DiscordApi.queryParameter(redirectUri, "code")) {
                "Discord authorization code is missing"
            }

            val token = exchangeAuthorizationCode(code, session.codeVerifier)
            val account = runCatching { fetchAccount(token.accessToken) }.getOrNull()
            token.toAuthSession(account)
        }
    }

    suspend fun fetchAccount(accessToken: String): DiscordAccount = withContext(Dispatchers.IO) {
        val body = http.get(DiscordApi.CURRENT_USER_ENDPOINT) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.Accept, "application/json")
        }.bodyAsText()
        val user = json.decodeFromString<DiscordUserInfoResponse>(body)
        val userId = user.id ?: user.sub.orEmpty()
        val username = user.username ?: userId
        val displayName = user.globalName ?: user.username ?: username
        DiscordAccount(
            id = userId,
            username = username,
            displayName = displayName,
            avatarUrl = user.picture?.takeIf { it.isNotBlank() }
                ?: buildAvatarUrl(userId, user.avatar, user.discriminator)
        )
    }

    suspend fun validAccessToken(
        store: DiscordCredentialStore,
        session: DiscordAuthSession?
    ): String? = withContext(Dispatchers.IO) {
        val current = session ?: store.load() ?: return@withContext null
        val token = current.accessToken.trim()
        if (token.isBlank()) return@withContext null

        val expiresAt = current.expiresAtMillis
        if (expiresAt == 0L || System.currentTimeMillis() + EXPIRY_SKEW_MS < expiresAt) {
            return@withContext token
        }

        val refreshToken = current.refreshToken?.trim().orEmpty()
        if (refreshToken.isBlank()) return@withContext token

        refreshAccessToken(refreshToken)
            .getOrNull()
            ?.also { store.save(it) }
            ?.accessToken
            ?: token
    }

    private suspend fun refreshAccessToken(refreshToken: String): Result<DiscordAuthSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = postForm(
                    url = DiscordApi.TOKEN_ENDPOINT,
                    params = mapOf(
                        "client_id" to DiscordApi.APPLICATION_ID,
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken
                    )
                ).let { json.decodeFromString<DiscordTokenResponse>(it) }

                val account = runCatching { fetchAccount(token.accessToken) }.getOrNull()
                token.toAuthSession(account, fallbackRefreshToken = refreshToken)
            }
        }

    private suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String
    ): DiscordTokenResponse = withContext(Dispatchers.IO) {
        postForm(
            url = DiscordApi.TOKEN_ENDPOINT,
            params = mapOf(
                "client_id" to DiscordApi.APPLICATION_ID,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to DiscordApi.REDIRECT_URI,
                "code_verifier" to codeVerifier
            )
        ).let { json.decodeFromString<DiscordTokenResponse>(it) }
    }

    private suspend fun postForm(url: String, params: Map<String, String>): String =
        http.post(url) {
            header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            header(HttpHeaders.Accept, "application/json")
            setBody(params.entries.joinToString("&") { (key, value) ->
                "${key.urlEncoded()}=${value.urlEncoded()}"
            })
        }.bodyAsText()

    private fun DiscordTokenResponse.toAuthSession(
        account: DiscordAccount?,
        fallbackRefreshToken: String? = null
    ): DiscordAuthSession {
        val expiresInMillis = expiresInSeconds.coerceAtLeast(0L) * 1000L
        return DiscordAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken ?: fallbackRefreshToken,
            expiresAtMillis = if (expiresInMillis > 0L) {
                System.currentTimeMillis() + expiresInMillis
            } else {
                0L
            },
            account = account
        )
    }

    private fun buildAvatarUrl(userId: String, avatarHash: String?, discriminator: String?): String? {
        if (userId.isBlank()) return null
        val hash = avatarHash?.takeIf { it.isNotBlank() }
        if (hash != null) {
            val extension = if (hash.startsWith("a_")) "gif" else "png"
            return "https://cdn.discordapp.com/avatars/$userId/$hash.$extension?size=256"
        }
        val defaultIndex = discriminator?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.rem(5)
            ?: userId.toLongOrNull()?.let { ((it shr 22) % 6L).toInt() }
            ?: 0
        return "https://cdn.discordapp.com/embed/avatars/$defaultIndex.png"
    }

    private fun randomUrlSafeString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun String.queryEncoded(): String = urlEncoded().replace("+", "%20")

    fun close() {
        if (ownsClient) {
            runCatching { http.close() }
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 12_000L
        private const val EXPIRY_SKEW_MS = 60_000L
    }
}
