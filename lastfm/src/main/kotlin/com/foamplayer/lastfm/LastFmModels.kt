package com.foamplayer.lastfm

/** An authenticated Last.fm account. [sessionKey] is the `sk` API parameter. */
data class LastFmSession(
    val username: String,
    val sessionKey: String,
    val subscriber: Boolean = false
)

/** A pending browser authorization: [token] is polled until approval. */
data class LastFmAuthorizationSession(
    val token: String,
    val authorizationUrl: String
)

/** The fields Last.fm accepts for both now-playing updates and scrobbles. */
data class LastFmTrack(
    val artist: String,
    val title: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationSeconds: Int? = null,
    val trackNumber: Int? = null
) {
    val isValid: Boolean get() = artist.isNotBlank() && title.isNotBlank()
}

/**
 * One completed listen. [timestampSeconds] is when the track started playing,
 * as Last.fm expects.
 */
data class LastFmScrobble(
    val track: LastFmTrack,
    val timestampSeconds: Long
)

/** Result of polling `auth.getSession` for a pending token. */
sealed interface LastFmSessionResult {
    data class Authorized(val session: LastFmSession) : LastFmSessionResult
    data object Pending : LastFmSessionResult
    data class Failed(val message: String?) : LastFmSessionResult
}

/** A structured error returned by the Last.fm Web API. */
class LastFmApiException(
    val code: Int,
    message: String
) : Exception(message) {
    val isInvalidSession: Boolean get() = code == LastFmApi.ERROR_INVALID_SESSION
}
