package com.foamplayer.discord

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface DiscordCredentialStore {
    suspend fun load(): DiscordAuthSession?
    suspend fun save(session: DiscordAuthSession)
    suspend fun clear()
}

@Serializable
data class DiscordAccount(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null
)

@Serializable
data class DiscordAuthSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long = 0L,
    val account: DiscordAccount? = null
)

data class DiscordAuthorizationSession(
    val state: String,
    val codeVerifier: String,
    val authorizationUrl: String
)

enum class DiscordActivityType(val wireValue: Int) {
    Playing(0),
    Streaming(1),
    Listening(2),
    Watching(3),
    Competing(5)
}

enum class DiscordOnlineStatus(val wireValue: String) {
    Online("online"),
    Idle("idle"),
    Dnd("dnd"),
    Invisible("invisible")
}

data class DiscordPresenceButton(
    val label: String,
    val url: String
)

data class DiscordPresenceAssets(
    val largeImage: String? = null,
    val largeText: String? = null,
    val smallImage: String? = null,
    val smallText: String? = null
)

data class DiscordPresenceTimestamps(
    val startEpochSeconds: Long? = null,
    val endEpochSeconds: Long? = null
)

data class DiscordPresenceActivity(
    val applicationId: Long = DiscordApi.APPLICATION_ID_LONG,
    val name: String,
    val type: DiscordActivityType = DiscordActivityType.Listening,
    val details: String? = null,
    val state: String? = null,
    val assets: DiscordPresenceAssets = DiscordPresenceAssets(),
    val buttons: List<DiscordPresenceButton> = emptyList(),
    val timestamps: DiscordPresenceTimestamps = DiscordPresenceTimestamps(),
    val onlineStatus: DiscordOnlineStatus = DiscordOnlineStatus.Online,
    val platform: String = "android"
)

@Serializable
internal data class DiscordTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long = 0L
)

@Serializable
internal data class DiscordUserInfoResponse(
    val id: String? = null,
    val sub: String? = null,
    val username: String? = null,
    @SerialName("global_name") val globalName: String? = null,
    val discriminator: String? = null,
    val avatar: String? = null,
    val picture: String? = null
)
