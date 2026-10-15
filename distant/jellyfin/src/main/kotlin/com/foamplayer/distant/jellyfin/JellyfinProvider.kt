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
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
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
            header("X-Emby-Authorization", authHeader())
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
            header("X-Emby-Authorization", authHeader(token(server)))
        }
        ensureSuccess(response, "Could not read Jellyfin libraries")
        return response.body<ItemsResponse>().Items.orEmpty()
            .map { DistantFolderEntry(id = it.Id, name = it.Name.orEmpty(), hint = it.CollectionType) }
    }

    override suspend fun listTracks(server: DistantServer, folder: DistantFolder): List<DistantTrack> {
        val userId = userId(server)
        val response = client.get("${base(server)}/Items") {
            header("X-Emby-Authorization", authHeader(token(server)))
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
        "${base(server)}/Audio/$remoteId/stream?static=true&api_key=${token(server)}"

    override fun artUrl(server: DistantServer, track: DistantTrack): String? {
        val token = server.credentials.secret ?: return null
        return "${base(server)}/Items/${track.remoteId}/Images/Primary?api_key=$token"
    }

    private fun base(server: DistantServer): String = server.baseUrl.trimEnd('/')

    private fun token(server: DistantServer): String =
        server.credentials.secret?.takeIf { it.isNotBlank() }
            ?: throw DistantException("Jellyfin token missing")

    private fun userId(server: DistantServer): String =
        server.credentials.userId?.takeIf { it.isNotBlank() }
            ?: throw DistantException("Jellyfin user id missing; reconnect the server")

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
