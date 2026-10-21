package com.foamplayer.app.lastfm

import android.util.Log
import com.foamplayer.lastfm.LastFmClient
import com.foamplayer.lastfm.LastFmLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [LastFmClient] for a given credential set. Credentials can change at
 * runtime (app-provided vs. custom), so clients are created per credential
 * state instead of being a single Hilt singleton.
 */
@Singleton
class LastFmClientFactory @Inject constructor() {

    fun create(credentials: LastFmCredentials): LastFmClient = LastFmClient(
        apiKey = credentials.apiKey,
        apiSecret = credentials.apiSecret,
        logger = LastFmLogger { message -> Log.d(LOG_TAG, message) }
    )

    companion object {
        private const val LOG_TAG = "LastFm"
    }
}
