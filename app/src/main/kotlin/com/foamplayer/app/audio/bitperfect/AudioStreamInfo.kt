package com.foamplayer.app.audio.bitperfect

/**
 * How the currently playing track is being fetched over the network, rendered by
 * the pipeline's Transport stage. [endpoint] is the scheme + authority of the
 * resolved stream (never the full URL: it carries the provider token).
 */
data class AudioStreamInfo(
    val provider: String,
    val endpoint: String,
    /** True when the provider serves the original file without server-side transcoding. */
    val direct: Boolean = true
)
