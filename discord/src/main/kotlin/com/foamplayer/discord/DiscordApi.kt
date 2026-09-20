package com.foamplayer.discord

import java.net.URI
import java.net.URLDecoder

object DiscordApi {
    const val APPLICATION_ID = "1551214023347798146"
    const val APPLICATION_ID_LONG = 1551214023347798146L
    const val REDIRECT_URI = "foamplayer-discord://authorize/callback"
    const val REDIRECT_SCHEME = "foamplayer-discord"
    const val REDIRECT_HOST = "authorize"
    const val REDIRECT_PATH = "/callback"
    private const val LEGACY_REDIRECT_PATH = "/authorize/callback"

    const val AUTHORIZATION_ENDPOINT = "https://discord.com/oauth2/authorize"
    const val TOKEN_ENDPOINT = "https://discord.com/api/oauth2/token"
    const val CURRENT_USER_ENDPOINT = "https://discord.com/api/v10/users/@me"
    const val EXTERNAL_ASSETS_ENDPOINT = "https://discord.com/api/v10/applications/$APPLICATION_ID/external-assets"
    const val GATEWAY_URL = "wss://gateway.discord.gg"
    const val GATEWAY_VERSION = 9

    const val OAUTH_SCOPE = "openid identify sdk.social_layer_presence"

    fun isRedirect(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        return runCatching {
            val parsed = URI(uri)
            val host = parsed.host
            val path = parsed.path.orEmpty()
            parsed.scheme == REDIRECT_SCHEME &&
                    ((host == REDIRECT_HOST && path == REDIRECT_PATH) || path == LEGACY_REDIRECT_PATH)
        }.getOrDefault(false)
    }

    fun queryParameter(uri: String, key: String): String? {
        val rawQuery = runCatching { URI(uri).rawQuery }.getOrNull() ?: return null
        return rawQuery.split("&")
            .asSequence()
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) {
                    if (pair == key) "" else null
                } else {
                    val name = URLDecoder.decode(pair.substring(0, index), Charsets.UTF_8)
                    if (name == key) URLDecoder.decode(pair.substring(index + 1), Charsets.UTF_8) else null
                }
            }
            .firstOrNull()
    }
}
