package com.foamplayer.distant.plex

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
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

/**
 * Plex provider. Sign-in is Plex's browser flow: a PIN is created on plex.tv,
 * the user authenticates on the Plex web page, and the resulting token is read
 * back by polling the PIN. Music libraries are sections of type "artist"; a
 * section's tracks are fetched with `type=10` (track).
 */
class PlexProvider(
    private val client: HttpClient = DistantHttp.client()
) : DistantProvider {

    override val type: DistantProviderType = DistantProviderType.PLEX

    override val pollsForLogin: Boolean get() = true

    private val pins = ConcurrentHashMap<String, PlexPin>()

    override suspend fun login(server: DistantServer): DistantCredentials {
        val token = server.credentials.secret?.takeIf { it.isNotBlank() }
            ?: throw DistantException("A Plex token is required")
        val response = client.get("${server.baseUrl.trimEnd('/')}/identity") {
            header("Accept", "application/json")
            parameter("X-Plex-Token", token)
        }
        ensureSuccess(response, "Plex rejected the token")
        return server.credentials.copy(secret = token)
    }

    override suspend fun authorizationUrl(server: DistantServer): String {
        val response = client.post("$PLEX_TV/api/v2/pins?strong=true") {
            header("Accept", "application/json")
            header("X-Plex-Client-Identifier", CLIENT_IDENTIFIER)
            header("X-Plex-Product", PRODUCT)
            header("X-Plex-Version", VERSION)
        }
        ensureSuccess(response, "Could not start Plex sign-in")
        val pin = response.body<PlexPin>()
        pins[server.id] = pin
        return "$PLEX_AUTH#?clientID=$CLIENT_IDENTIFIER&code=${pin.code}" +
                "&context%5Bdevice%5D%5Bproduct%5D=$PRODUCT"
    }

    override suspend fun pollLogin(server: DistantServer): DistantCredentials? {
        val pin = pins[server.id] ?: return null
        val response = client.get("$PLEX_TV/api/v2/pins/${pin.id}") {
            header("Accept", "application/json")
            header("X-Plex-Client-Identifier", CLIENT_IDENTIFIER)
            parameter("code", pin.code)
        }
        if (!response.status.isSuccess()) return null
        val token = response.body<PlexPin>().authToken?.takeIf { it.isNotBlank() } ?: return null
        pins.remove(server.id)
        return DistantCredentials(secret = token)
    }

    override suspend fun listFolders(server: DistantServer): List<DistantFolderEntry> {
        val token = token(server)
        val response = client.get("${server.baseUrl.trimEnd('/')}/library/sections") {
            header("Accept", "application/json")
            parameter("X-Plex-Token", token)
        }
        ensureSuccess(response, "Could not read Plex libraries")
        val sections = response.body<PlexEnvelope>().MediaContainer?.Directory.orEmpty()
        return sections
            .filter { it.type == "artist" }
            .map { DistantFolderEntry(id = it.key, name = it.title, hint = "Music library") }
    }

    override suspend fun listTracks(server: DistantServer, folder: DistantFolder): List<DistantTrack> {
        val token = token(server)
        val response = client.get("${server.baseUrl.trimEnd('/')}/library/sections/${folder.id}/all") {
            header("Accept", "application/json")
            header("X-Plex-Container-Start", "0")
            header("X-Plex-Container-Size", "10000")
            parameter("type", "10")
            parameter("X-Plex-Token", token)
        }
        ensureSuccess(response, "Could not read Plex tracks")
        return response.body<PlexEnvelope>().MediaContainer?.Metadata.orEmpty().map { it.toTrack() }
    }

    override fun streamUrl(server: DistantServer, remoteId: String): String {
        val token = token(server)
        return "${server.baseUrl.trimEnd('/')}$remoteId?X-Plex-Token=$token"
    }

    override fun artUrl(server: DistantServer, track: DistantTrack): String? {
        val thumb = track.artUrl ?: return null
        val token = server.credentials.secret ?: return null
        return "${server.baseUrl.trimEnd('/')}$thumb?X-Plex-Token=$token"
    }

    private fun token(server: DistantServer): String =
        server.credentials.secret?.takeIf { it.isNotBlank() }
            ?: throw DistantException("Plex token missing")

    private fun PlexTrack.toTrack(): DistantTrack {
        val media = Media.firstOrNull()
        val part = media?.Part?.firstOrNull()
        return DistantTrack(
            remoteId = part?.key ?: ratingKey,
            title = title,
            artist = grandparentTitle.orEmpty(),
            albumArtist = grandparentTitle.orEmpty(),
            album = parentTitle.orEmpty(),
            genre = Genre.firstOrNull()?.tag,
            year = year,
            trackNumber = index,
            discNumber = parentIndex,
            durationMs = duration ?: 0L,
            sampleRate = media?.samplingRate,
            bitDepth = media?.bitDepth,
            channels = media?.audioChannels,
            bitrateKbps = media?.bitrate,
            format = media?.audioCodec?.uppercase(),
            sizeBytes = part?.size,
            artUrl = thumb ?: parentThumb
        )
    }

    private companion object {
        const val PLEX_TV = "https://plex.tv"
        const val PLEX_AUTH = "https://app.plex.tv/auth"
        const val CLIENT_IDENTIFIER = "foamplayer-android"
        const val PRODUCT = "FoamPlayer"
        const val VERSION = "1.0.0"
    }
}

private fun ensureSuccess(response: HttpResponse, message: String) {
    if (!response.status.isSuccess()) {
        throw DistantException("$message (HTTP ${response.status.value})")
    }
}

@Serializable
private data class PlexEnvelope(val MediaContainer: PlexContainer? = null)

@Serializable
private data class PlexContainer(
    val Directory: List<PlexSection> = emptyList(),
    val Metadata: List<PlexTrack> = emptyList()
)

@Serializable
private data class PlexSection(
    val key: String,
    val title: String,
    val type: String? = null
)

@Serializable
private data class PlexTrack(
    val ratingKey: String = "",
    val title: String = "",
    val grandparentTitle: String? = null,
    val parentTitle: String? = null,
    val parentIndex: Int? = null,
    val index: Int? = null,
    val duration: Long? = null,
    val year: Int? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val Genre: List<PlexTag> = emptyList(),
    val Media: List<PlexMedia> = emptyList()
)

@Serializable
private data class PlexTag(val tag: String? = null)

@Serializable
private data class PlexMedia(
    val bitrate: Int? = null,
    val audioChannels: Int? = null,
    val audioCodec: String? = null,
    val samplingRate: Int? = null,
    val bitDepth: Int? = null,
    val Part: List<PlexPart> = emptyList()
)

@Serializable
private data class PlexPart(val key: String = "", val size: Long? = null)

@Serializable
private data class PlexPin(
    val id: Long = 0,
    val code: String = "",
    val authToken: String? = null
)
