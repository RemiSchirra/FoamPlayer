package com.foamplayer.lastfm

/** Minimal logging hook so the library stays free of Android dependencies. */
fun interface LastFmLogger {
    fun log(message: String)
}
