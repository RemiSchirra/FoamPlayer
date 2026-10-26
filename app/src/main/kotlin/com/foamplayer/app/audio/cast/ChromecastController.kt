package com.foamplayer.app.audio.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.distant.DistantRepository
import com.foamplayer.app.toast.ToastCenter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.foamplayer.distant.DistantUris
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Chromecast discovery and remote playback.
 *
 * Discovery rides on the Android `MediaRouter`: the Cast SDK publishes every
 * Chromecast/speaker group of the LAN as a route, and selecting one starts a
 * `CastSession` with the Default Media Receiver. Playback is then driven through
 * [RemoteMediaClient]'s media queue, which keeps next/previous/seek working from
 * both the phone and the TV remote.
 *
 * The phone stays the source of truth for the *queue*: [loadQueue] publishes a
 * slice of the engine's queue (item ids are the absolute queue indices), and
 * [CastStatus] reports back where the receiver is so the engine can mirror it
 * into its own playback state.
 */
@Singleton
class ChromecastController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaServer: LocalCastMediaServer,
    private val distantRepository: DistantRepository,
    private val toastCenter: ToastCenter
) {

    /** One selectable Chromecast. [id] is the route id, stable for the session. */
    data class Device(val id: String, val name: String)

    /** The receiver's position, translated back to engine queue indices. */
    data class CastStatus(
        val queueIndex: Int,
        val isPlaying: Boolean,
        /** True when the receiver is expected to (or is trying to) play, i.e. not paused/idle. */
        val isPlaybackRequested: Boolean,
        val positionMs: Long,
        /** The receiver is fetching/priming the current item. */
        val isBuffering: Boolean,
        /** The receiver ran out of queue (end of the slice that was cast). */
        val finished: Boolean
    )

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    /** True while a discovery callback is registered (the "Play on" sheet is open). */
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** `"chromecast:<routeId>"` while a receiver is connected, else null. */
    private val _connectedDeviceId = MutableStateFlow<String?>(null)
    val connectedDeviceId: StateFlow<String?> = _connectedDeviceId.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    /** Engine hook: the receiver moved / paused / finished. Always called on the main thread. */
    var onStatus: ((CastStatus) -> Unit)? = null

    /** Engine hook: the session is up and ready to receive a queue. */
    var onSessionStarted: (() -> Unit)? = null

    /** Engine hook: the session went away (TV off, user disconnected). */
    var onSessionLost: (() -> Unit)? = null

    /**
     * True while the receiver owns playback — including while a session is still
     * starting or being switched. During that gap the local player must stay
     * parked and its position must not leak back into the UI.
     */
    val isCasting: Boolean get() = _connectedDeviceId.value != null || pendingRouteId != null

    private var castContext: CastContext? = null
    private var initialized = false
    private var discovering = false
    private var activeSession: CastSession? = null
    private var remoteClient: RemoteMediaClient? = null
    private var pendingRouteId: String? = null

    /** Main-thread scheduler for the delayed queue fallback (Cast SDK callbacks live here too). */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Separate from [mainHandler], whose callbacks a session teardown wipes. */
    private val discoveryHandler = Handler(Looper.getMainLooper())

    /**
     * Safety net for discovery: re-reads the route list on a timer instead of
     * trusting `onRouteAdded` alone, which some receiver/firmware combinations
     * deliver late (or not at all) to a manually registered callback.
     */
    private val discoveryRefresh = object : Runnable {
        override fun run() {
            if (!discovering) return
            refreshDevices()
            discoveryHandler.postDelayed(this, DISCOVERY_REFRESH_MS)
        }
    }

    /** Number of receivers last published, so the discovery log only prints on change. */
    private var publishedDeviceCount = -1

    /**
     * False once the receiver refused a media queue. Some receivers (the Default
     * Media Receiver among them) only accept single-item loads, so playback then
     * runs one item at a time instead of relying on the receiver's queue.
     */
    private var queueSupported = true

    /** Absolute engine queue index of the item the receiver's queue starts at. */
    private var loadedStart: Int = 0

    /** Items published in one go; item ids are relative to this slice (0 until [loadedCount]). */
    private var loadedCount: Int = 0

    /** Last volume commanded on the receiver, so rapid key presses accumulate. */
    private var volumeOverride: Double? = null
    private var volumeOverrideAt: Long = 0L

    /**
     * Position commanded on the receiver, published until the receiver's own status
     * catches up (see [positionMs]). Without it a seek/jump would flash back to the
     * old position for the network round trip.
     */
    private var positionOverrideMs: Long? = null
    private var positionOverrideAt: Long = 0L

    /** Increments on every published queue; stale async failures must not clear a newer one. */
    private var loadGeneration: Int = 0

    private val mediaRouter: MediaRouter?
        get() = runCatching { MediaRouter.getInstance(context) }.getOrNull()

    private val selector: MediaRouteSelector? by lazy {
        runCatching {
            MediaRouteSelector.Builder()
                .addControlCategory(
                    CastMediaControlIntent.categoryForCast(FoamCastOptionsProvider.DefaultMediaReceiverAppId)
                )
                .build()
        }.getOrNull()
    }

    // discovery

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices()
    }

    /** Starts scanning the LAN, registering the route callback that feeds [devices]. */
    @Synchronized
    fun startDiscovery() {
        if (!ensureInitialized()) {
            Log.w(TAG, "Cast SDK unavailable; cannot discover receivers")
            _devices.value = emptyList()
            return
        }
        if (discovering) return
        val router = mediaRouter ?: run {
            Log.w(TAG, "MediaRouter unavailable; cannot discover receivers")
            return
        }
        val routeSelector = selector ?: return
        discovering = true
        _scanning.value = true
        runCatching {
            router.addCallback(
                routeSelector,
                routerCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
            )
        }.onFailure { Log.w(TAG, "Cannot register the discovery callback", it) }
        refreshDevices()
        discoveryHandler.removeCallbacks(discoveryRefresh)
        discoveryHandler.postDelayed(discoveryRefresh, DISCOVERY_REFRESH_MS)
    }

    /** Stops scanning; the device list is kept so the sheet does not flash empty on reopen. */
    @Synchronized
    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        _scanning.value = false
        discoveryHandler.removeCallbacks(discoveryRefresh)
        val router = mediaRouter ?: return
        runCatching { router.removeCallback(routerCallback) }
    }

    private fun refreshDevices() {
        val router = mediaRouter ?: return
        val routeSelector = selector ?: return

        val byDevice = LinkedHashMap<String, Pair<MediaRouter.RouteInfo, Device>>()
        for (route in router.routes) {
            val cast = runCatching { CastDevice.getFromBundle(route.extras) }.getOrNull()
            if (cast == null && !route.matchesSelector(routeSelector)) continue
            val key = cast?.deviceId?.takeIf { it.isNotBlank() } ?: route.name
            val known = byDevice[key]
            if (known == null || (route.isSelected && !known.first.isSelected)) {
                byDevice[key] = route to Device(route.id, route.name)
            }
        }
        val devices = byDevice.values.map { it.second }

        if (devices.size != publishedDeviceCount) {
            publishedDeviceCount = devices.size
            Log.d(TAG, "Cast discovery: ${devices.size} receiver(s) ${devices.map { it.name }}")
        }
        _devices.value = devices
    }

    // session

    /**
     * Connects to [routeId]. Selecting the route is what starts the Cast session:
     * the SDK's route provider launches the receiver and reports back through
     * [sessionListener].
     */
    fun connect(routeId: String): Boolean {
        if (!ensureInitialized()) {
            toastCenter.pushRes(R.string.toast_cast_unavailable)
            return false
        }
        val router = mediaRouter ?: return false
        val route = router.routes.firstOrNull { it.id == routeId }
        if (route == null) {
            refreshDevices()
            return false
        }
        pendingRouteId = routeId
        return runCatching {
            router.selectRoute(route)
            true
        }.getOrElse {
            Log.w(TAG, "Route selection failed", it)
            pendingRouteId = null
            false
        }
    }

    /** Ends the session and stops the receiver. */
    fun disconnect() {
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = publishStatus()

        override fun onQueueStatusUpdated() = publishStatus()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)

        // A late "ended" for a session the engine already replaced must not tear
        // down the new one (switching receivers ends the previous session).
        override fun onSessionEnded(session: CastSession, error: Int) {
            // A session ending while another route is pending is a receiver
            // switch: the hand-off is still in flight, so stay quiet for it.
            if (session === activeSession) detach(notify = pendingRouteId == null)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) = detach(notify = true)

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            if (session === activeSession) detach(notify = true)
        }

        override fun onSessionStarting(session: CastSession) = Unit

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    private fun attach(session: CastSession) {
        if (activeSession === session) return
        remoteClient?.unregisterCallback(mediaCallback)
        activeSession = session
        val client = session.remoteMediaClient
        remoteClient = client
        client?.registerCallback(mediaCallback)

        val routeId = pendingRouteId ?: selectedRouteId()
        pendingRouteId = null
        _connectedDeviceId.value = routeId?.let { "$ID_PREFIX$it" }
            ?: session.castDevice?.deviceId?.let { "$ID_PREFIX$it" }
        _connectedDeviceName.value = session.castDevice?.friendlyName
            ?: _devices.value.firstOrNull { it.id == routeId }?.name
        refreshDevices()
        onSessionStarted?.invoke()
    }

    private fun detach(notify: Boolean) {
        remoteClient?.unregisterCallback(mediaCallback)
        remoteClient = null
        activeSession = null
        loadedStart = 0
        loadedCount = 0
        volumeOverride = null
        positionOverrideMs = null
        // Drops a pending single-item fallback of the session that is going away,
        // and re-arms the queue for the next receiver.
        loadGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        queueSupported = true
        mediaServer.clear()
        val wasLive = _connectedDeviceId.value != null
        val hadAttempt = pendingRouteId != null
        _connectedDeviceId.value = null
        _connectedDeviceName.value = null
        // While switching receivers the pending route stays armed (see
        // [onSessionEnded]): the in-flight hand-off reports its own outcome.
        if (!notify || (!wasLive && !hadAttempt)) return
        pendingRouteId = null
        onSessionLost?.invoke()
    }

    private fun selectedRouteId(): String? {
        val router = mediaRouter ?: return null
        val routeSelector = selector ?: return null
        return router.routes.firstOrNull { it.isSelected && it.matchesSelector(routeSelector) }?.id
    }

    // playback

    /**
     * Publishes [tracks] as the receiver's media queue. [startIndex] is the absolute
     * engine queue index of `tracks.first()`; queue item ids are relative to the
     * slice and mapped back to absolute indices in [publishStatus].
     *
     * Receivers that reject the queue (the Default Media Receiver answers
     * "Invalid Request" to some payloads) are retried one item at a time, which
     * every receiver accepts; the engine drives next/previous either way.
     */
    fun loadQueue(
        tracks: List<TrackEntity>,
        startIndex: Int,
        positionMs: Long,
        play: Boolean
    ): Boolean {
        val client = remoteClient ?: return false
        if (tracks.isEmpty()) return false
        if (!queueSupported) return loadSingle(client, tracks, startIndex, positionMs, play)

        val items = ArrayList<MediaQueueItem>(minOf(tracks.size, QUEUE_WINDOW))
        for ((offset, track) in tracks.withIndex()) {
            if (items.size >= QUEUE_WINDOW) break
            val info = mediaInfo(track)
            if (info == null) {
                // The current track must be playable; a later one just truncates
                // the window (reaching it re-loads the queue and reports there).
                // The caller toasts the failure for the starting track.
                if (offset == 0) return false
                Log.w(TAG, "Truncating the cast queue at unplayable '${track.title}'")
                break
            }
            // Relative ids: the receiver validates them against the queue it is
            // given, and a fresh queue always starts at 0. The slice offset is
            // added back in [publishStatus].
            items += MediaQueueItem.Builder(info)
                .setItemId(offset)
                .setAutoplay(true)
                .build()
        }
        if (items.isEmpty()) return false

        val generation = ++loadGeneration
        loadedStart = startIndex
        loadedCount = items.size
        setPositionOverride(positionMs)
        return runCatching {
            client.queueLoad(
                items.toTypedArray(),
                0,
                MediaStatus.REPEAT_MODE_REPEAT_OFF,
                positionMs.coerceAtLeast(0L),
                null
            ).setResultCallback { result ->
                if (result.status.isSuccess || generation != loadGeneration) return@setResultCallback
                Log.w(TAG, "queueLoad rejected (${result.status.statusMessage}); loading one item at a time")
                queueSupported = false
                loadedStart = 0
                loadedCount = 0
                // The rejection may also be the receiver still settling after the
                // session came up: retry once the media channel had a moment.
                mainHandler.postDelayed({
                    if (generation != loadGeneration) return@postDelayed
                    loadSingle(client, tracks, startIndex, positionMs, play)
                }, QUEUE_FALLBACK_DELAY_MS)
            }
            if (!play) client.pause()
            true
        }.getOrElse {
            Log.w(TAG, "queueLoad failed", it)
            loadedStart = 0
            loadedCount = 0
            false
        }
    }

    /** Loads the first track of [tracks] (a slice that starts at [startIndex]), without a queue. */
    private fun loadSingle(
        client: RemoteMediaClient,
        tracks: List<TrackEntity>,
        startIndex: Int,
        positionMs: Long,
        play: Boolean
    ): Boolean {
        val track = tracks.firstOrNull() ?: return false
        val info = mediaInfo(track) ?: run {
            toastCenter.pushRes(R.string.toast_cast_load_failed, track.title)
            return false
        }
        loadGeneration++
        loadedStart = startIndex
        loadedCount = 1
        setPositionOverride(positionMs)
        return runCatching {
            client.load(info, play, positionMs.coerceAtLeast(0L), null)
                .setResultCallback { result ->
                    if (result.status.isSuccess) return@setResultCallback
                    Log.w(TAG, "load rejected: ${result.status.statusMessage}")
                    if (loadedStart == startIndex) {
                        loadedStart = 0
                        loadedCount = 0
                    }
                    toastCenter.pushRes(R.string.toast_cast_load_failed, track.title)
                }
            true
        }.getOrElse {
            Log.w(TAG, "load failed", it)
            loadedStart = 0
            loadedCount = 0
            false
        }
    }

    /** True when [queueIndex] is part of the slice currently on the receiver. */
    fun isIndexLoaded(queueIndex: Int): Boolean =
        loadedCount > 0 && queueIndex in loadedStart until (loadedStart + loadedCount)

    /** Jumps inside the loaded slice. Returns false when the index is not loaded. */
    fun jumpTo(queueIndex: Int, positionMs: Long, play: Boolean): Boolean {
        val client = remoteClient ?: return false
        if (!queueSupported || !isIndexLoaded(queueIndex)) return false
        setPositionOverride(positionMs)
        return runCatching {
            client.queueJumpToItem(queueIndex - loadedStart, positionMs.coerceAtLeast(0L), null)
            if (!play) client.pause()
            true
        }.getOrElse {
            Log.w(TAG, "queueJumpToItem failed", it)
            false
        }
    }

    fun play() {
        runCatching { remoteClient?.play() }
    }

    fun pause() {
        runCatching { remoteClient?.pause() }
    }

    fun seekTo(positionMs: Long) {
        val client = remoteClient ?: return
        setPositionOverride(positionMs)
        val options = MediaSeekOptions.Builder()
            .setPosition(positionMs.coerceAtLeast(0L))
            .build()
        runCatching { client.seek(options) }
    }

    /** Records a locally commanded position so [positionMs] does not report the old one meanwhile. */
    private fun setPositionOverride(positionMs: Long) {
        positionOverrideMs = positionMs.coerceAtLeast(0L)
        positionOverrideAt = SystemClock.elapsedRealtime()
    }

    /**
     * Receiver volume, 0..1. The app's DSP volume only applies to the local
     * pipeline, so while casting the slider has to drive the receiver instead.
     */
    fun setVolume(fraction: Float) {
        runCatching { remoteClient?.setStreamVolume(fraction.coerceIn(0f, 1f).toDouble()) }
    }

    /**
     * Steps the Cast DEVICE's own volume (the TV's/speaker's, what its remote and
     * its on-screen volume bar use) by [steps] key presses of [VOLUME_STEP].
     *
     * The SDK's cached volume only refreshes once the receiver answers, so the
     * last commanded value is used as the base for [VOLUME_OVERRIDE_TTL_MS]:
     * without it three quick presses in a row would all compute from the same
     * stale value and only move one step. Returns false when there is no session
     * to drive, so the caller can leave the key to the platform.
     */
    fun adjustVolume(steps: Int): Boolean {
        val session = activeSession ?: return false
        val reported = session.volume
        val now = SystemClock.elapsedRealtime()
        val base = volumeOverride
            ?.takeIf { now - volumeOverrideAt <= VOLUME_OVERRIDE_TTL_MS }
            ?: reported
        if (base < 0.0) return false
        val target = (base + steps * VOLUME_STEP).coerceIn(0.0, 1.0)
        if (target == base) return true
        return runCatching { session.setVolume(target) }
            .map {
                volumeOverride = target
                volumeOverrideAt = now
                true
            }
            .getOrElse {
                Log.w(TAG, "setDeviceVolume failed", it)
                false
            }
    }

    /** The receiver's live position, or null while nothing is loaded. */
    fun positionMs(): Long? {
        val client = remoteClient ?: return null
        if (!client.hasMediaSession()) return null
        // A locally commanded seek/jump wins until the receiver's status catches up,
        // so the UI does not flash back to the pre-seek position for the round trip.
        positionOverrideMs?.let { override ->
            if (SystemClock.elapsedRealtime() - positionOverrideAt <= POSITION_OVERRIDE_TTL_MS) {
                return override
            }
            positionOverrideMs = null
        }
        return client.approximateStreamPosition.coerceAtLeast(0L)
    }

    /**
     * True while the receiver has no media playing (before the first load, or after
     * it stopped). A queue jump issued to an idle receiver is dropped, so the
     * engine loads afresh instead in that state.
     */
    val isReceiverIdle: Boolean
        get() {
            val state = remoteClient?.mediaStatus?.playerState ?: return true
            return state == MediaStatus.PLAYER_STATE_IDLE
        }

    /** True while the receiver's playback clock is running (its position can be dead-reckoned). */
    val isReceiverPlaying: Boolean
        get() = remoteClient?.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING

    /** True while the receiver is priming a newly loaded item. */
    val isReceiverBuffering: Boolean
        get() = remoteClient?.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_BUFFERING

    // media URLs

    /** Builds the Cast [MediaInfo] for [track], publishing local files over HTTP. */
    private fun mediaInfo(track: TrackEntity): MediaInfo? {
        val mime = mimeTypeFor(track.format) ?: run {
            Log.w(TAG, "No Cast content type for '${track.format}'; not castable")
            return null
        }
        val url = if (DistantUris.isDistant(track.path)) {
            distantUrl(track, mime) ?: return null
        } else {
            mediaServer.publishLocal(
                path = track.path,
                mime = mime,
                length = track.size,
                filename = fileNameFor(track)
            ) ?: return null
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, track.title)
            putString(MediaMetadata.KEY_ARTIST, track.artist)
            putString(MediaMetadata.KEY_ALBUM_TITLE, track.album)
            mediaServer.publishArtwork(track.albumArtPath)?.let { url ->
                addImage(WebImage(Uri.parse(url)))
            }
        }
        return MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setContentUrl(url)
            .setStreamDuration(track.duration.coerceAtLeast(0L))
            .setMetadata(metadata)
            .build()
    }

    /**
     * Distant tracks: providers put their token in the query string, so the
     * resolved URL is handed to the receiver as-is. Only streams that genuinely
     * need request headers are relayed through the local proxy.
     */
    private fun distantUrl(track: TrackEntity, mime: String): String? {
        val stream = runCatching { distantRepository.streamFor(track.path) }.getOrNull() ?: return null
        if (stream.headers.isEmpty()) return stream.url
        return mediaServer.publishRemote(stream.url, mime, stream.headers)
    }

    private fun publishStatus() {
        val client = remoteClient ?: return
        val status = client.mediaStatus ?: return
        if (loadedCount <= 0) return
        val queueIndex = if (queueSupported) {
            val itemId = client.currentItem?.itemId ?: return
            if (itemId !in 0 until loadedCount) return
            loadedStart + itemId
        } else {
            // One-item mode: the only item the receiver can be playing is the one
            // that was loaded last.
            loadedStart
        }
        val playerState = status.playerState
        val idle = playerState == MediaStatus.PLAYER_STATE_IDLE
        val paused = playerState == MediaStatus.PLAYER_STATE_PAUSED
        val finished = idle && status.idleReason == MediaStatus.IDLE_REASON_FINISHED
        val receiverPositionMs =
            if (finished) 0L else client.approximateStreamPosition.coerceAtLeast(0L)
        // The receiver answered the seek/jump: its position is authoritative again.
        positionOverrideMs?.let { override ->
            if (abs(receiverPositionMs - override) <= POSITION_OVERRIDE_MATCH_MS) {
                positionOverrideMs = null
            }
        }
        onStatus?.invoke(
            CastStatus(
                queueIndex = queueIndex,
                isPlaying = client.isPlaying && !finished,
                isPlaybackRequested = !paused && !finished,
                positionMs = receiverPositionMs,
                isBuffering = playerState == MediaStatus.PLAYER_STATE_BUFFERING,
                finished = finished
            )
        )
    }

    private fun ensureInitialized(): Boolean {
        if (initialized) return castContext != null
        // Transient Play services failures must not poison the controller for the
        // rest of the process: only a successful init is remembered.
        val resolved = runCatching { CastContext.getSharedInstance(context) }
            .onFailure { Log.w(TAG, "Cast is unavailable on this device", it) }
            .getOrNull()
            ?: return false
        castContext = resolved
        initialized = true
        resolved.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        return true
    }

    companion object {
        private const val TAG = "ChromecastController"

        /** Prefix of every [com.foamplayer.app.ui.modals.PlayOnDevice] id of type CHROMECAST. */
        const val ID_PREFIX = "chromecast:"

        /**
         * Upcoming tracks published to a receiver in one request. The whole queue
         * travels as a single Cast channel message, which is capped at 64 KB, so
         * this stays far below it (~450 bytes per item): reaching the end of the
         * slice simply re-publishes the next one.
         */
        const val QUEUE_WINDOW = 32

        /** Grace period before retrying a refused queue as a single-item load. */
        private const val QUEUE_FALLBACK_DELAY_MS = 700L

        /**
         * How often the "Play on" scan re-reads the MediaRouter route list. Cast
         * discovery itself is mDNS-driven and takes a few seconds; this only makes
         * sure a missed route callback cannot leave the sheet empty forever.
         */
        private const val DISCOVERY_REFRESH_MS = 1_500L

        /** Device volume moved per hardware key press (5%: 20 steps over the range). */
        private const val VOLUME_STEP = 0.05

        /** How long a locally commanded volume outranks the receiver's cached one. */
        private const val VOLUME_OVERRIDE_TTL_MS = 2_000L

        /** How long a locally commanded position outranks the receiver's (seek round-trip window). */
        private const val POSITION_OVERRIDE_TTL_MS = 2_000L

        /** Receiver position within this distance of a commanded one means the receiver caught up. */
        private const val POSITION_OVERRIDE_MATCH_MS = 1_500L

        /**
         * The Cast content type for a scanner [format] (the uppercased file
         * extension), or null when receivers cannot decode it. Never a wildcard:
         * one item with an unusable content type makes the receiver reject the
         * whole queue with "Invalid Request".
         */
        fun mimeTypeFor(format: String): String? = when (format.uppercase()) {
            "MP3" -> "audio/mpeg"
            "FLAC" -> "audio/flac"
            "WAV", "WAVE" -> "audio/wav"
            "M4A", "M4B", "MP4" -> "audio/mp4"
            "AAC" -> "audio/aac"
            "OGG", "OGA", "OPUS" -> "audio/ogg"
            "AIFF", "AIF" -> "audio/aiff"
            "WEBM", "WEBA" -> "audio/webm"
            else -> null
        }

        private fun fileNameFor(track: TrackEntity): String {
            val uri = runCatching { Uri.parse(track.path) }.getOrNull()
            val fromPath = uri?.lastPathSegment?.substringAfterLast('/')
            return fromPath?.takeIf { it.isNotBlank() } ?: (track.title + "." + track.format.lowercase())
        }
    }
}
