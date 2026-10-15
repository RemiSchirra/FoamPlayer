package com.foamplayer.distant

import java.util.Base64

/**
 * Remote tracks are addressed by a synthetic URI so the rest of the app (Room
 * paths, the queue, the media notification) can treat them like local files.
 * The remote id is base64url-encoded because Plex part keys contain slashes.
 */
object DistantUris {

    const val SCHEME = "foam-distant"

    fun build(type: DistantProviderType, serverId: String, remoteId: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(remoteId.toByteArray(Charsets.UTF_8))
        return "$SCHEME://${type.name.lowercase()}/$serverId/$encoded"
    }

    fun isDistant(uri: String?): Boolean = uri?.startsWith("$SCHEME://") == true

    fun parse(uri: String): DistantUri? {
        if (!isDistant(uri)) return null
        val rest = uri.removePrefix("$SCHEME://")
        val parts = rest.split('/', limit = 3)
        if (parts.size != 3) return null
        val type = DistantProviderType.entries.firstOrNull { it.name.equals(parts[0], ignoreCase = true) } ?: return null
        val remoteId = runCatching {
            String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return DistantUri(type, parts[1], remoteId)
    }
}

data class DistantUri(
    val type: DistantProviderType,
    val serverId: String,
    val remoteId: String
)
