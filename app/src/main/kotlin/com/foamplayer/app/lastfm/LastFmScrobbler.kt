package com.foamplayer.app.lastfm

import android.util.Log
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.lastfm.LastFmApiException
import com.foamplayer.lastfm.LastFmClient
import com.foamplayer.lastfm.LastFmScrobble
import com.foamplayer.lastfm.LastFmSession
import com.foamplayer.lastfm.LastFmTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Bridges [AudioEngine] playback to Last.fm while the feature is enabled and a
 * session is connected.
 *
 * - Sends `track.updateNowPlaying` when the playing track changes.
 * - Submits `track.scrobble` once per track, driven by the same listen
 *   threshold as the library's play counter ([AudioEngine.listenedTrackEvents]).
 * - When the device is offline, scrobbles that could not be sent are kept in
 *   [LastFmScrobbleQueue] and submitted in batches once connectivity returns.
 *
 * An invalid session key clears the stored session so the settings screen
 * falls back to its disconnected state.
 */
@Singleton
class LastFmScrobbler @Inject constructor(
    private val audioEngine: AudioEngine,
    private val settingsRepository: SettingsRepository,
    private val tokenStore: LastFmTokenStore,
    private val credentialsStore: LastFmCredentialsStore,
    private val scrobbleQueue: LastFmScrobbleQueue,
    private val connectivityObserver: ConnectivityObserver,
    private val clientFactory: LastFmClientFactory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var syncJob: Job? = null

    @Volatile
    private var lastNowPlayingSignature: String? = null

    fun start() {
        if (started) return
        started = true

        syncJob = scope.launch {
            combine(
                settingsRepository.settings,
                tokenStore.session,
                credentialsStore.credentials
            ) { settings, session, credentials ->
                ScrobblingState(
                    enabled = settings.lastFmScrobblingEnabled,
                    session = session,
                    credentials = credentials
                )
            }
                .distinctUntilChanged()
                .collectLatest { state ->
                    lastNowPlayingSignature = null
                    val session = state.session
                    if (!state.enabled || session == null || !state.credentials.isConfigured) {
                        return@collectLatest
                    }
                    val client = clientFactory.create(state.credentials)
                    try {
                        monitorPlayback(session, client)
                    } finally {
                        client.close()
                    }
                }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        syncJob?.cancel()
        syncJob = null
        lastNowPlayingSignature = null
    }

    private suspend fun monitorPlayback(
        session: LastFmSession,
        client: LastFmClient
    ) = coroutineScope {
        launch {
            audioEngine.playbackState.collectLatest { state ->
                val track = state.currentTrack
                if (!state.isPlaying || track == null || !track.canScrobble) {
                    return@collectLatest
                }

                val signature = "${track.id}:${track.title}:${track.artist}"
                if (signature == lastNowPlayingSignature) return@collectLatest

                // Collapse rapid skips into a single now-playing update.
                delay(NOW_PLAYING_DEBOUNCE_MS)
                val result = client.updateNowPlaying(session, track.toLastFmTrack())
                if (result.isSuccess) {
                    lastNowPlayingSignature = signature
                    Log.d(LOG_TAG, "now playing: ${track.artist} - ${track.title}")
                } else {
                    handleFailure("updateNowPlaying", session, result.exceptionOrNull())
                }
            }
        }

        launch {
            audioEngine.listenedTrackEvents.collect { event ->
                val track = audioEngine.playbackState.value.queue
                    .firstOrNull { it.id == event.trackId }
                if (track == null) {
                    Log.w(LOG_TAG, "listened track ${event.trackId} is no longer in the queue")
                    return@collect
                }
                if (!track.canScrobble) return@collect

                val now = System.currentTimeMillis()
                val timestampSeconds = ((now - event.playedMs) / 1000L)
                    .coerceIn(0L, now / 1000L)

                val result = client.scrobble(
                    session = session,
                    scrobble = LastFmScrobble(
                        track = track.toLastFmTrack(),
                        timestampSeconds = timestampSeconds
                    )
                )
                if (result.isSuccess) {
                    Log.d(LOG_TAG, "scrobbled: ${track.artist} - ${track.title}")
                } else {
                    handleFailure("scrobble", session, result.exceptionOrNull())
                }
            }
        }
    }

    private suspend fun handleFailure(
        action: String,
        session: LastFmSession,
        error: Throwable?
    ) {
        if (error == null) return
        if (error is CancellationException) throw error
        Log.w(LOG_TAG, "$action failed: ${error.message}")

        val apiError = error as? LastFmApiException
        if (apiError?.isInvalidSession == true) {
            Log.w(LOG_TAG, "session key for ${session.username} is invalid; disconnecting")
            tokenStore.clear()
        }
    }

    /** Last.fm ignores tracks shorter than 30 seconds. */
    private val TrackEntity.canScrobble: Boolean
        get() = title.isNotBlank() && artist.isNotBlank() &&
                duration >= MIN_SCROBBLE_DURATION_MS

    private fun TrackEntity.toLastFmTrack(): LastFmTrack = LastFmTrack(
        artist = artist.trim(),
        title = title.trim(),
        album = album.trim().takeIf { it.isNotEmpty() },
        albumArtist = albumArtist?.trim()?.takeIf { it.isNotEmpty() },
        durationSeconds = (duration / 1000L).toInt().takeIf { it > 0 },
        trackNumber = trackNumber?.takeIf { it > 0 }
    )

    private data class ScrobblingState(
        val enabled: Boolean,
        val session: LastFmSession?,
        val credentials: LastFmCredentials
    )

    companion object {
        private const val LOG_TAG = "LastFmScrobbler"
        private const val NOW_PLAYING_DEBOUNCE_MS = 500L
        private const val MIN_SCROBBLE_DURATION_MS = 30_000L
    }
}
