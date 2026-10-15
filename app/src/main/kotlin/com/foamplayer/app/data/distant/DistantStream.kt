package com.foamplayer.app.data.distant

/** A resolved, playable remote stream: the final URL plus any required headers. */
data class DistantStream(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)
