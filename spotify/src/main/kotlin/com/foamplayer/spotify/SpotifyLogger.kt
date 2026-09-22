package com.foamplayer.spotify

/** Lightweight logging sink so this module stays independent from the Discord one. */
fun interface SpotifyLogger {
    fun log(message: String)
}
