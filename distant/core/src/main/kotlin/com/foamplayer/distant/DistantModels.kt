package com.foamplayer.distant

import kotlinx.serialization.Serializable

/** How a provider authenticates: a pasted token, username/password, or a browser OAuth dance. */
enum class DistantAuthMethod { TOKEN, CREDENTIALS, OAUTH }

/**
 * Every distant source supported by the app. [displayName] is shown in the UI;
 * [baseUrlHint] is the placeholder of the server-address field.
 */
enum class DistantProviderType(
    val displayName: String,
    val authMethod: DistantAuthMethod,
    val requiresBaseUrl: Boolean,
    val baseUrlHint: String? = null
) {
    PLEX("Plex", DistantAuthMethod.OAUTH, true, "http://192.168.1.10:32400"),
    JELLYFIN("Jellyfin", DistantAuthMethod.CREDENTIALS, true, "http://192.168.1.10:8096")
}

/** A configured remote library folder (a Plex/Jellyfin section). */
@Serializable
data class DistantFolder(
    val id: String,
    val name: String,
    val path: String = ""
)

/** Secret state of a connected server: a bearer token, or a user + password. */
@Serializable
data class DistantCredentials(
    val username: String? = null,
    val secret: String? = null,
    val userId: String? = null,
    val refreshToken: String? = null,
    val tokenExpiresAt: Long = 0L
)

/** One connected distant server and the folders the user picked to add to the library. */
@Serializable
data class DistantServer(
    val id: String,
    val type: DistantProviderType,
    val name: String,
    val baseUrl: String,
    val credentials: DistantCredentials,
    val folders: List<DistantFolder> = emptyList()
)

/** One folder offered while browsing a freshly connected server. */
@Serializable
data class DistantFolderEntry(
    val id: String,
    val name: String,
    val path: String = "",
    val hint: String? = null
)

/** A single playable remote track, normalized across providers. */
@Serializable
data class DistantTrack(
    val remoteId: String,
    val title: String,
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val genre: String? = null,
    val composer: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long = 0L,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val bitrateKbps: Int? = null,
    val format: String? = null,
    val sizeBytes: Long? = null,
    val artUrl: String? = null
)

/** Raised for any expected provider failure; the message is user-presentable. */
class DistantException(message: String, cause: Throwable? = null) : Exception(message, cause)
