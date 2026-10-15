package com.foamplayer.app.data.distant

import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.distant.DistantAuthMethod
import com.foamplayer.distant.DistantCredentials
import com.foamplayer.distant.DistantException
import com.foamplayer.distant.DistantFolder
import com.foamplayer.distant.DistantFolderEntry
import com.foamplayer.distant.DistantProviderRegistry
import com.foamplayer.distant.DistantProviderType
import com.foamplayer.distant.DistantServer
import com.foamplayer.distant.DistantTrack
import com.foamplayer.distant.DistantUris
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A connection in progress: the draft server plus the browser URL to open. */
data class OAuthStart(
    val server: DistantServer,
    val authorizationUrl: String
)

/**
 * Entry point for the distant providers: connect/disconnect servers, browse
 * folders, resolve stream URLs, and keep OAuth tokens fresh. Servers are
 * persisted in the settings DataStore and mirrored in an in-memory snapshot so
 * the player can resolve a stream URL synchronously on its loader thread.
 */
@Singleton
class DistantRepository @Inject constructor(
    private val registry: DistantProviderRegistry,
    private val settingsRepository: SettingsRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serversCache: List<DistantServer> = emptyList()

    private val _connectionEvents = MutableSharedFlow<DistantServer>(extraBufferCapacity = 4)
    val connectionEvents: SharedFlow<DistantServer> = _connectionEvents

    val servers: Flow<List<DistantServer>> = settingsRepository.settings.map { it.distantServers }

    init {
        scope.launch {
            settingsRepository.settings.collect { serversCache = it.distantServers }
        }
    }

    fun cachedServers(): List<DistantServer> = serversCache

    fun server(id: String): DistantServer? = serversCache.firstOrNull { it.id == id }

    /** Validates media-server credentials and persists the connection. */
    suspend fun connectMediaServer(
        type: DistantProviderType,
        name: String,
        baseUrl: String,
        username: String,
        password: String,
        token: String
    ): DistantServer {
        val draft = DistantServer(
            id = newId(type),
            type = type,
            name = name.ifBlank { type.displayName },
            baseUrl = baseUrl.trim(),
            credentials = DistantCredentials(
                username = username.takeIf { it.isNotBlank() },
                secret = token.takeIf { it.isNotBlank() } ?: password.takeIf { it.isNotBlank() }
            )
        )
        // Validated but not persisted: the server is only added once the user
        // picks folders and confirms (see [saveFolders]).
        val credentials = registry.get(type).login(draft)
        return draft.copy(credentials = credentials)
    }

    /** Starts a browser sign-in and returns the URL to open. */
    suspend fun beginOAuth(type: DistantProviderType, name: String, baseUrl: String): OAuthStart {
        val draft = DistantServer(
            id = newId(type),
            type = type,
            name = name.ifBlank { type.displayName },
            baseUrl = baseUrl.trim(),
            credentials = DistantCredentials()
        )
        val provider = registry.get(type)
        val url = provider.authorizationUrl(draft)
            ?: throw DistantException("${type.displayName} does not use browser sign-in")
        if (provider.pollsForLogin) {
            startLoginPolling(draft)
        }
        return OAuthStart(draft, url)
    }

    /** Polls a polling provider (Plex) until the user finishes signing in. */
    private fun startLoginPolling(draft: DistantServer) {
        scope.launch {
            repeat(LOGIN_POLL_ATTEMPTS) {
                delay(LOGIN_POLL_INTERVAL_MS)
                val credentials = runCatching { registry.get(draft.type).pollLogin(draft) }.getOrNull()
                if (credentials != null) {
                    // Hand the signed-in server back in memory; it is persisted
                    // only when the user confirms a folder selection.
                    _connectionEvents.tryEmit(draft.copy(credentials = credentials))
                    return@launch
                }
            }
        }
    }

    /** Refreshes any OAuth token that is expired or about to expire. */
    suspend fun refreshExpiredTokens() {
        val threshold = System.currentTimeMillis() + 60_000
        serversCache
            .filter { it.type.authMethod == DistantAuthMethod.OAUTH }
            .filter { it.credentials.refreshToken != null && it.credentials.tokenExpiresAt in 1..threshold }
            .forEach { server ->
                runCatching { registry.get(server.type).refresh(server) }
                    .getOrNull()
                    ?.let { settingsRepository.upsertDistantServer(server.copy(credentials = it)) }
            }
    }

    suspend fun listFolders(server: DistantServer): List<DistantFolderEntry> =
        registry.get(server.type).listFolders(server)

    suspend fun listTracks(server: DistantServer, folder: DistantFolder): List<DistantTrack> =
        registry.get(server.type).listTracks(server, folder)

    suspend fun saveFolders(server: DistantServer, folders: List<DistantFolder>) =
        settingsRepository.upsertDistantServer(server.copy(folders = folders))

    suspend fun removeServer(serverId: String) = settingsRepository.removeDistantServer(serverId)

    fun artFor(server: DistantServer, track: DistantTrack): String? =
        registry.get(server.type).artUrl(server, track)

    /** Synchronous stream resolution used by the player's data source. */
    fun streamFor(uri: String): DistantStream? {
        val parsed = DistantUris.parse(uri) ?: return null
        val server = server(parsed.serverId) ?: return null
        val provider = registry.get(parsed.type)
        return DistantStream(
            url = provider.streamUrl(server, parsed.remoteId),
            headers = provider.streamHeaders(server, parsed.remoteId)
        )
    }

    private fun newId(type: DistantProviderType): String = "${type.name.lowercase()}-${UUID.randomUUID()}"

    private companion object {
        const val LOGIN_POLL_INTERVAL_MS = 2_000L
        const val LOGIN_POLL_ATTEMPTS = 150
    }
}
