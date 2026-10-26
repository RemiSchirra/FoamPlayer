package com.foamplayer.distant.jellyfin

import com.foamplayer.distant.DistantCredentials
import com.foamplayer.distant.DistantException
import com.foamplayer.distant.DistantFolder
import com.foamplayer.distant.DistantFolderEntry
import com.foamplayer.distant.DistantHttp
import com.foamplayer.distant.DistantProvider
import com.foamplayer.distant.DistantProviderType
import com.foamplayer.distant.DistantServer
import com.foamplayer.distant.DistantTrack
import com.foamplayer.distant.RemoteSession
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * Jellyfin provider. Authentication is username + password against
 * `/Users/AuthenticateByName`, yielding an access token and the user id used by
 * the library endpoints.
 */
class JellyfinProvider(
    private val client: HttpClient = DistantHttp.client()
) : DistantProvider {

    override val type: DistantProviderType = DistantProviderType.JELLYFIN

    override suspend fun login(server: DistantServer): DistantCredentials {
        val username = server.credentials.username?.takeIf { it.isNotBlank() }
            ?: throw DistantException("A username is required")
        val password = server.credentials.secret.orEmpty()
        val response = client.post("${base(server)}/Users/AuthenticateByName") {
            contentType(ContentType.Application.Json)
            authorize()
            setBody(AuthRequest(Username = username, Pw = password))
        }
        ensureSuccess(response, "Jellyfin rejected the credentials")
        val result = response.body<AuthResponse>()
        val token = result.AccessToken?.takeIf { it.isNotBlank() }
            ?: throw DistantException("Jellyfin returned no access token")
        return DistantCredentials(username = username, secret = token, userId = result.User?.Id)
    }

    override suspend fun listFolders(server: DistantServer): List<DistantFolderEntry> {
        val userId = userId(server)
        val response = client.get("${base(server)}/Users/$userId/Views") {
            authorize(token(server))
        }
        ensureSuccess(response, "Could not read Jellyfin libraries")
        return response.body<ItemsResponse>().Items.orEmpty()
            .map { DistantFolderEntry(id = it.Id, name = it.Name.orEmpty(), hint = it.CollectionType) }
    }

    override suspend fun listTracks(server: DistantServer, folder: DistantFolder): List<DistantTrack> {
        val userId = userId(server)
        val response = client.get("${base(server)}/Items") {
            authorize(token(server))
            parameter("userId", userId)
            parameter("parentId", folder.id)
            parameter("recursive", "true")
            parameter("includeItemTypes", "Audio")
            parameter("fields", "MediaSources,Genres")
            parameter("limit", "10000")
        }
        ensureSuccess(response, "Could not read Jellyfin tracks")
        return response.body<ItemsResponse>().Items.orEmpty().map { it.toTrack() }
    }

    override fun streamUrl(server: DistantServer, remoteId: String): String =
        "${base(server)}/Audio/$remoteId/stream?static=true&ApiKey=${token(server)}"

    override fun artUrl(server: DistantServer, track: DistantTrack): String? {
        val token = server.credentials.secret ?: return null
        return "${base(server)}/Items/${track.remoteId}/Images/Primary?ApiKey=$token"
    }

    /** Sessions the app is allowed to remote-control right now. */
    override suspend fun listRemoteSessions(server: DistantServer): List<RemoteSession> {
        val response = client.get("${base(server)}/Sessions") {
            authorize(token(server))
            parameter("activeWithinSeconds", "120")
        }
        ensureSuccess(response, "Could not read Jellyfin sessions")
        return response.body<SessionsResponse>().Sessions.orEmpty()
            .filter { session ->
                session.DeviceId != null &&
                        session.PlayableSessionIds.orEmpty().contains(session.Id)
            }
            .map { session ->
                RemoteSession(
                    id = session.Id.orEmpty(),
                    deviceName = session.DeviceName ?: "Jellyfin device",
                    clientName = session.Client ?: "Jellyfin",
                    applicationVersion = session.ApplicationVersion
                )
            }
            .filter { it.id.isNotBlank() }
    }

    /**
     * Commands [sessionId] to start playing [itemIds] (Jellyfin item ids) right
     * now. [positionTicks] (1 tick = 100ns) resumes at that offset; null starts
     * from the beginning.
     */
    override suspend fun remotePlay(server: DistantServer, sessionId: String, itemIds: List<String>, positionTicks: Long?) {
        val response = client.post("${base(server)}/Sessions/$sessionId/Playing") {
            authorize(token(server))
            contentType(ContentType.Application.Json)
            setBody(PlayRequest(ItemIds = itemIds, StartPositionTicks = positionTicks, PlayCommand = "PlayNow"))
        }
        ensureSuccess(response, "Jellyfin rejected the play command")
    }

    override suspend fun remotePause(server: DistantServer, sessionId: String, pause: Boolean) {
        val command = if (pause) "Pause" else "Unpause"
        val response = client.post("${base(server)}/Sessions/$sessionId/Playing/$command") {
            authorize(token(server))
        }
        ensureSuccess(response, "Jellyfin rejected the pause command")
    }

    override suspend fun remoteStop(server: DistantServer, sessionId: String) {
        val response = client.post("${base(server)}/Sessions/$sessionId/Playing/Stop") {
            authorize(token(server))
        }
        ensureSuccess(response, "Jellyfin rejected the stop command")
    }

    private fun base(server: DistantServer): String = server.baseUrl.trimEnd('/')

    private fun token(server: DistantServer): String =
        server.credentials.secret?.takeIf { it.isNotBlank() }
            ?: throw DistantException("Jellyfin token missing")

    private fun userId(server: DistantServer): String =
        server.credentials.userId?.takeIf { it.isNotBlank() }
            ?: throw DistantException("Jellyfin user id missing; reconnect the server")

    /**
     * Jellyfin only reads the standard `Authorization` header unless the server
     * opted into legacy authorization, in which case it reads
     * `X-Emby-Authorization`. Sending both keeps old and new servers happy; the
     * payload itself is the `MediaBrowser` scheme Jellyfin parses for the client
     * name, device id and version its session API requires.
     */
    private fun HttpRequestBuilder.authorize(token: String? = null) {
        val value = authHeader(token)
        header(HttpHeaders.Authorization, value)
        header("X-Emby-Authorization", value)
    }

    private fun authHeader(token: String? = null): String = buildString {
        append("MediaBrowser Client=\"FoamPlayer\", Device=\"Android\", DeviceId=\"foamplayer\", Version=\"1.0.0\"")
        if (!token.isNullOrBlank()) append(", Token=\"$token\"")
    }

    private fun JellyItem.toTrack(): DistantTrack {
        val source = MediaSources.firstOrNull()
        val audio = source?.MediaStreams?.firstOrNull { it.Type.equals("Audio", ignoreCase = true) }
        return DistantTrack(
            remoteId = Id,
            title = Name.orEmpty(),
            artist = Artists?.joinToString("; ").orEmpty().ifBlank { AlbumArtist.orEmpty() },
            albumArtist = AlbumArtist.orEmpty(),
            album = Album.orEmpty(),
            genre = Genres?.joinToString("; ")?.takeIf { it.isNotBlank() },
            year = ProductionYear,
            trackNumber = IndexNumber,
            discNumber = ParentIndexNumber,
            durationMs = RunTimeTicks?.div(10_000L) ?: 0L,
            sampleRate = audio?.SampleRate,
            bitDepth = audio?.BitDepth,
            channels = audio?.Channels,
            bitrateKbps = source?.Bitrate?.let { (it / 1000).toInt() },
            format = source?.Container?.uppercase(),
            sizeBytes = source?.Size,
            artUrl = Id
        )
    }
}

private fun ensureSuccess(response: HttpResponse, message: String) {
    if (!response.status.isSuccess()) {
        throw DistantException("$message (HTTP ${response.status.value})")
    }
}

@Serializable
private data class AuthRequest(val Username: String, val Pw: String)

@Serializable
private data class AuthResponse(val AccessToken: String? = null, val User: AuthUser? = null)

@Serializable
private data class AuthUser(val Id: String? = null)

@Serializable
private data class ItemsResponse(val Items: List<JellyItem>? = null)

@Serializable
private data class JellyItem(
    val Id: String = "",
    val Name: String? = null,
    val CollectionType: String? = null,
    val Album: String? = null,
    val AlbumArtist: String? = null,
    val Artists: List<String>? = null,
    val IndexNumber: Int? = null,
    val ParentIndexNumber: Int? = null,
    val ProductionYear: Int? = null,
    val RunTimeTicks: Long? = null,
    val Genres: List<String>? = null,
    val MediaSources: List<JellySource> = emptyList()
)

@Serializable
private data class JellySource(
    val Container: String? = null,
    val Bitrate: Long? = null,
    val Size: Long? = null,
    val MediaStreams: List<JellyStream> = emptyList()
)

@Serializable
private data class JellyStream(
    val Type: String? = null,
    val Codec: String? = null,
    val SampleRate: Int? = null,
    val BitDepth: Int? = null,
    val Channels: Int? = null
)

@Serializable
private data class SessionsResponse(val Sessions: List<JellySession>? = null)

@Serializable
private data class JellySession(
    val Id: String? = null,
    val DeviceId: String? = null,
    val DeviceName: String? = null,
    val Client: String? = null,
    val ApplicationVersion: String? = null,
    val PlayableSessionIds: List<String>? = null
)

@Serializable
private data class PlayRequest(
    val ItemIds: List<String>,
    val StartPositionTicks: Long? = null,
    val PlayCommand: String = "PlayNow"
)
