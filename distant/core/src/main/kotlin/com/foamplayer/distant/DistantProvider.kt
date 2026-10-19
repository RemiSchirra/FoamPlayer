package com.foamplayer.distant

/**
 * One backing service (Plex, Jellyfin). Implementations are
 * stateless: every call receives the [DistantServer] carrying the current
 * credentials, so a token refresh is just a new server record.
 */
interface DistantProvider {

    val type: DistantProviderType

    /** Validates the server and returns the credentials to persist. */
    suspend fun login(server: DistantServer): DistantCredentials

    /** Browser URL for the sign-in page; null for providers that do not use one. */
    suspend fun authorizationUrl(server: DistantServer): String? = null

    /**
     * True when the browser sign-in completes without a redirect back to the
     * app and must be observed by polling [pollLogin] instead (Plex).
     */
    val pollsForLogin: Boolean get() = false

    /** [pollsForLogin] providers: the credentials of a finished sign-in, or null while pending. */
    suspend fun pollLogin(server: DistantServer): DistantCredentials? = null

    /** Renews expired credentials, or null when the provider has no refresh support. */
    suspend fun refresh(server: DistantServer): DistantCredentials? = null

    /** Headers the stream request needs (a provider may carry its token here). */
    fun streamHeaders(server: DistantServer, remoteId: String): Map<String, String> = emptyMap()

    /** The folders (music sections) offered to the user. */
    suspend fun listFolders(server: DistantServer): List<DistantFolderEntry>

    /** Every playable audio track under [folder] (recursing into its subfolders). */
    suspend fun listTracks(server: DistantServer, folder: DistantFolder): List<DistantTrack>

    /** A direct, authorized URL the player can stream [remoteId] from. */
    fun streamUrl(server: DistantServer, remoteId: String): String

    /** Whether [remoteId] (a file name) looks like a playable audio file. */
    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    /** Cover art for [track], if the provider exposes one. */
    fun artUrl(server: DistantServer, track: DistantTrack): String? = track.artUrl

    // ── Remote play ("Play on") — optional capability ────────────────────────

    /** Controllable sessions (devices the server can drive), empty when unsupported. */
    suspend fun listRemoteSessions(server: DistantServer): List<RemoteSession> = emptyList()

    /** Commands the session's device to play [itemIds] now (optionally at [positionTicks]). */
    suspend fun remotePlay(
        server: DistantServer,
        sessionId: String,
        itemIds: List<String>,
        positionTicks: Long?
    ) {
        throw DistantException("${type.displayName} does not support remote play")
    }

    /** Commands [sessionId]'s device to pause or resume. */
    suspend fun remotePause(server: DistantServer, sessionId: String, pause: Boolean) {
        throw DistantException("${type.displayName} does not support remote play")
    }

    /** Commands [sessionId]'s device to stop playing. */
    suspend fun remoteStop(server: DistantServer, sessionId: String) {
        throw DistantException("${type.displayName} does not support remote play")
    }
}

/** One controllable remote playback session (a device the server can drive). */
data class RemoteSession(
    val id: String,
    val deviceName: String,
    val clientName: String,
    val applicationVersion: String?
)

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "alac", "aiff", "aif", "ape", "m4b", "mp4"
)

/** Resolves a [DistantProviderType] to its implementation. */
class DistantProviderRegistry(providers: List<DistantProvider>) {

    private val byType: Map<DistantProviderType, DistantProvider> = providers.associateBy { it.type }

    val all: List<DistantProvider> = providers

    fun get(type: DistantProviderType): DistantProvider =
        byType[type] ?: throw DistantException("No provider registered for ${type.displayName}")
}
