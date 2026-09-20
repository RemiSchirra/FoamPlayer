package com.foamplayer.app.discord

import android.util.Log
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.discord.DiscordActivityType
import com.foamplayer.discord.DiscordAssetRegistrar
import com.foamplayer.discord.DiscordOAuth
import com.foamplayer.discord.DiscordPresenceActivity
import com.foamplayer.discord.DiscordPresenceAssets
import com.foamplayer.discord.DiscordPresenceClient
import com.foamplayer.discord.DiscordPresenceTimestamps
import com.foamplayer.discord.SpotifyArtResolver
import com.foamplayer.discord.YouTubeThumbnailResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Singleton
class DiscordPresenceController @Inject constructor(
    private val audioEngine: AudioEngine,
    private val settingsRepository: SettingsRepository,
    private val tokenStore: DiscordTokenStore,
    private val oAuth: DiscordOAuth,
    private val presenceClient: DiscordPresenceClient,
    private val assetRegistrar: DiscordAssetRegistrar,
    private val albumArtStore: DiscordAlbumArtStore,
    private val spotifyArtResolver: SpotifyArtResolver,
    private val youtubeArtResolver: YouTubeThumbnailResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val artMutex = Mutex()

    private var started = false
    private var syncJob: Job? = null
    private var lastSignature: PresenceSignature? = null

    // Whether an activity is currently shown on Discord (false = cleared). The
    // presence is intentionally hidden whenever playback is paused or empty.
    @Volatile private var presenceActive = false
    @Volatile private var lastSentStartMs = 0L

    @Volatile private var latestSettings: AppSettings = AppSettings()
    @Volatile private var activeToken: String? = null

    // Albums whose art upload recently failed (or had no art) — retried after a
    // cooldown instead of being dropped for the whole session, so transient
    // host outages don't leave an album artless.
    @Volatile private var artRetryAt: Map<String, Long> = emptyMap()

    private data class PresenceSignature(
        val trackId: Long,
        val title: String,
        val artist: String,
        val album: String,
        val hasImage: Boolean
    )

    fun start() {
        if (started) return
        started = true

        syncJob = scope.launch {
            combine(
                settingsRepository.settings,
                tokenStore.session
            ) { settings, session -> settings to session }
                .collectLatest { (settings, session) ->
                    latestSettings = settings
                    if (!settings.discordPresenceEnabled || session == null) {
                        resetTracking()
                        presenceClient.clearPresence(null)
                        return@collectLatest
                    }

                    val token = oAuth.validAccessToken(tokenStore, session)
                    if (token == null) {
                        resetTracking()
                        presenceClient.clearPresence(null)
                        return@collectLatest
                    }

                    monitorPlayback(token)
                }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        syncJob?.cancel()
        syncJob = null
        resetTracking()
        activeToken = null
        artRetryAt = emptyMap()

        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanupScope.launch {
            runCatching { withTimeout(CLEANUP_TIMEOUT_MS) { presenceClient.clearPresence() } }
            runCatching { withTimeout(CLEANUP_TIMEOUT_MS) { presenceClient.close() } }
            cleanupScope.cancel()
        }
    }

    private suspend fun monitorPlayback(token: String) {
        activeToken = token
        try {
            coroutineScope {
                val stateJob = launch {
                    audioEngine.playbackState.collectLatest { state ->
                        // Collapse rapid skip bursts into a single update for the
                        // final track (Discord throttles presence updates).
                        delay(PRESENCE_DEBOUNCE_MS)
                        sendPresence(token, state)
                    }
                }
                val seekJob = launch {
                    var lastSeekResyncAt = 0L
                    audioEngine.seekEvents.collect {
                        val state = audioEngine.playbackState.value
                        if (!state.isPlaying || state.currentTrack == null) return@collect
                        val now = System.currentTimeMillis()
                        if (now - lastSeekResyncAt < SEEK_RESYNC_COOLDOWN_MS) return@collect
                        lastSeekResyncAt = now
                        sendPresence(token, state, force = true, allowRetry = false, allowResolve = false)
                    }
                }
                val driftJob = launch {
                    while (isActive) {
                        delay(DRIFT_CHECK_INTERVAL_MS)
                        val state = audioEngine.playbackState.value
                        if (state.isPlaying && state.currentTrack != null && hasTimestampDrift()) {
                            sendPresence(token, state, force = true, allowRetry = false, allowResolve = false)
                        }
                    }
                }
                stateJob.join()
                seekJob.cancel()
                driftJob.cancel()
            }
        } finally {
            if (activeToken == token) {
                activeToken = null
            }
        }
    }

    private suspend fun sendPresence(
        token: String,
        state: PlaybackState,
        force: Boolean = false,
        allowRetry: Boolean = true,
        allowResolve: Boolean = true
    ) {
        val track = state.currentTrack
        if (track == null || !state.isPlaying) {
            // Paused or nothing loaded: the presence must disappear.
            lastSignature = null
            if (presenceActive) {
                val cleared = try {
                    presenceClient.clearPresence(token).isSuccess
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(LOG_TAG, "clear FAILED: ${e.message}")
                    false
                }
                if (cleared) {
                    presenceActive = false
                    Log.d(LOG_TAG, "presence cleared (paused/empty)")
                } else if (allowRetry) {
                    scheduleRetry(token)
                }
            }
            return
        }

        // Art is resolved/looked up BEFORE the dedupe check, then registered as
        // a Discord media-proxy asset (raw URLs are ignored by user-token
        // presence). hasImage therefore reflects an asset Discord can actually
        // render: a failed registration yields hasImage=false and is retried
        // by the next update instead of being swallowed.
        val artKey = DiscordAlbumArtStore.trackKey(track.artist, track.title)
        var largeImage = remoteArtUrl(track.albumArtPath)
        if (largeImage == null) {
            largeImage = albumArtStore.cachedUrl(artKey, DiscordAlbumArtStore.ONLINE_TTL_MS)
        }

        // Resolve the cover inline (bounded) so the first presence update
        // already carries the image, instead of racing a follow-up re-send.
        if (largeImage == null && allowResolve &&
            latestSettings.discordOnlineArt && canAttemptArt(artKey)
        ) {
            largeImage = resolveArt(track, artKey)
        }

        var assetImage: String? = null
        if (largeImage != null) {
            assetImage = try {
                assetRegistrar.resolveImage(token, largeImage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(LOG_TAG, "image registration error: ${e.message}")
                null
            }
            if (assetImage == null) {
                Log.d(LOG_TAG, "image registration unavailable, sending without image (will retry)")
            }
        }

        val signature = PresenceSignature(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            hasImage = assetImage != null
        )
        if (!force && signature == lastSignature && presenceActive) return

        val positionMs = audioEngine.positionMs.value.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val startMs = now - positionMs

        val timestamps = DiscordPresenceTimestamps(
            startEpochSeconds = startMs / 1000L,
            endEpochSeconds = (startMs + state.durationMs) / 1000L
        )

        val details = sequenceOf(
            track.title,
            track.album,
            track.artist
        ).firstOrNull { it.isNotBlank() } ?: return

        val activity = DiscordPresenceActivity(
            name = APP_NAME,
            type = DiscordActivityType.Listening,
            details = details.take(MAX_TEXT_LENGTH),
            state = track.artist.takeIf { it.isNotBlank() && it != details }?.take(MAX_TEXT_LENGTH),
            assets = DiscordPresenceAssets(
                largeImage = assetImage,
                largeText = track.album.takeIf { it.isNotBlank() && assetImage != null }?.take(MAX_TEXT_LENGTH)
            ),
            buttons = emptyList(),
            timestamps = timestamps
        )

        val sent = try {
            presenceClient.updatePresence(token, activity).isSuccess
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

        Log.d(
            LOG_TAG,
            "presence ${if (sent) "sent" else "FAILED"} track=\"${track.title}\" image=${assetImage ?: "none"}"
        )

        if (sent) {
            lastSignature = signature
            presenceActive = true
            lastSentStartMs = startMs
        } else if (allowRetry) {
            scheduleRetry(token)
        }

        if (sent && largeImage != null && assetImage == null && allowRetry) {
            // The update went out without the cover because registration
            // failed (e.g. rate limited): retry after the registrar's failure
            // cooldown so the next attempt can actually register.
            scheduleRetry(token, delayMs = ART_REGISTRATION_RETRY_DELAY_MS)
        }

        if (sent && largeImage == null &&
            latestSettings.discordOnlineArt && canAttemptArt(artKey)
        ) {
            // Inline resolution produced nothing (timeout/failure): keep trying
            // in the background and push the cover as soon as it lands.
            requestArtResolve(track, artKey)
        }
    }

    private fun scheduleRetry(token: String, delayMs: Long = RETRY_DELAY_MS) {
        scope.launch {
            delay(delayMs)
            if (activeToken != token) return@launch
            val state = audioEngine.playbackState.value
            if (state.currentTrack != null || presenceActive) {
                Log.d(LOG_TAG, "retrying presence update")
                sendPresence(token, state, force = true, allowRetry = false, allowResolve = false)
            }
        }
    }

    /**
     * Bounded inline resolution: resolves and caches the cover on the calling
     * path so the presence can be sent with the image in a single update.
     * Returns null on failure/timeout (the async fallback then takes over).
     */
    private suspend fun resolveArt(track: TrackEntity, artKey: String): String? =
        try {
            withTimeout(ART_RESOLVE_TIMEOUT_MS) {
                artMutex.withLock {
                    if (!canAttemptArt(artKey)) return@withLock null
                    albumArtStore.cachedUrl(artKey, DiscordAlbumArtStore.ONLINE_TTL_MS)?.let {
                        return@withLock it
                    }

                    val url = spotifyArtResolver.resolveThumbnail(
                        artist = track.artist,
                        title = track.title,
                        album = track.album
                    ) ?: youtubeArtResolver.resolveThumbnail(
                        artist = track.artist,
                        title = track.title,
                        album = track.album
                    )
                    if (url == null) {
                        Log.d(LOG_TAG, "no online art for $artKey")
                        deferArt(artKey)
                        return@withLock null
                    }

                    albumArtStore.remember(artKey, url)
                    Log.d(LOG_TAG, "online art resolved and cached for $artKey")
                    url
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.d(LOG_TAG, "art resolve timed out for $artKey")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(LOG_TAG, "art resolve error: ${e.message}")
            null
        }

    private fun requestArtResolve(track: TrackEntity, artKey: String) {
        scope.launch {
            artMutex.withLock {
                if (!canAttemptArt(artKey)) return@withLock
                if (albumArtStore.cachedUrl(artKey, DiscordAlbumArtStore.ONLINE_TTL_MS) != null) return@withLock

                val url = spotifyArtResolver.resolveThumbnail(
                    artist = track.artist,
                    title = track.title,
                    album = track.album
                ) ?: youtubeArtResolver.resolveThumbnail(
                    artist = track.artist,
                    title = track.title,
                    album = track.album
                )
                if (url == null) {
                    Log.d(LOG_TAG, "no online art for $artKey")
                    deferArt(artKey)
                    return@withLock
                }

                albumArtStore.remember(artKey, url)
                Log.d(LOG_TAG, "online art resolved and cached for $artKey")

                val token = activeToken
                if (token != null) {
                    val state = audioEngine.playbackState.value
                    if (state.currentTrack != null && state.isPlaying) {
                        sendPresence(token, state, force = true, allowRetry = true, allowResolve = false)
                    }
                }
            }
        }
    }

    private fun canAttemptArt(albumKey: String): Boolean =
        (artRetryAt[albumKey] ?: 0L) <= System.currentTimeMillis()

    private fun deferArt(albumKey: String) {
        artRetryAt = artRetryAt + (albumKey to System.currentTimeMillis() + ART_RETRY_COOLDOWN_MS)
    }

    private fun hasTimestampDrift(): Boolean {
        if (lastSentStartMs == 0L) return false
        val expectedPositionMs = System.currentTimeMillis() - lastSentStartMs
        val actualPositionMs = audioEngine.positionMs.value
        return abs(expectedPositionMs - actualPositionMs) > DRIFT_TOLERANCE_MS
    }

    private fun remoteArtUrl(path: String?): String? =
        path?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun resetTracking() {
        lastSignature = null
        presenceActive = false
        lastSentStartMs = 0L
    }

    companion object {
        private const val LOG_TAG = "DiscordRPC"
        private const val APP_NAME = "FoamPlayer"
        private const val MAX_TEXT_LENGTH = 128
        private const val PRESENCE_DEBOUNCE_MS = 300L
        private const val SEEK_RESYNC_COOLDOWN_MS = 2_500L
        private const val DRIFT_CHECK_INTERVAL_MS = 10_000L
        private const val DRIFT_TOLERANCE_MS = 3_000L
        private const val CLEANUP_TIMEOUT_MS = 5_000L
        private const val RETRY_DELAY_MS = 4_000L
        private const val ART_RESOLVE_TIMEOUT_MS = 5_000L
        private const val ART_RETRY_COOLDOWN_MS = 5 * 60_000L

        /** Past the registrar's 15s failure cooldown, so the retry can register. */
        private const val ART_REGISTRATION_RETRY_DELAY_MS = 16_000L
    }
}
