package com.foamplayer.app.audio.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.foamplayer.app.AppForegroundState
import com.foamplayer.app.FoamApplication
import com.foamplayer.app.MainActivity
import com.foamplayer.app.R
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.audio.engine.QueuePersistence
import com.foamplayer.app.audio.engine.RepeatMode
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.settings.NotificationExtraButton
import com.foamplayer.app.discord.DiscordPresenceController
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground media session service. Binds the session to [AudioEngine]'s active
 * player, publishes the custom commands (shuffle / repeat / close / score) and
 * the notification with its two user-configurable extra buttons.
 *
 * The service does NOT own the player: [AudioEngine] is an app-scoped singleton
 * whose player outlives this service instance (the system can stop an idle
 * service while the process stays alive). Releasing the session's player here
 * would kill the engine's playback thread and brick every later command with a
 * "sending message to a Handler on a dead thread" warning -- the session is
 * merely a bound view of the engine's player.
 *
 * "Close" (the cross button): pauses playback, drops the foreground state and
 * dismisses the notification — WITHOUT stopping the service. Every notification
 * (re-)post path flows through [onUpdateNotificationAsync], which swallows
 * updates while the session is closed, so the dismissed notification stays
 * gone. The session and queue survive; when the user plays again, the first
 * update arrives with playback active, the closed flag is lifted and Media3
 * re-posts the notification and re-promotes the service to the foreground.
 *
 * Playback resumption: Media3 retires the paused foreground state after 10
 * minutes, and the system always destroys the service (or the whole process).
 * [QueuePersistence] keeps the playing session on disk; when this service is
 * recreated to serve a media button press (Bluetooth, car, ...) after that,
 * `onPlaybackResumption` rebuilds the queue exactly where it was — so the
 * notification controls recover even after the system killed the session.
 *
 * With "display on lock screen" enabled, WAKING the screen while a song is
 * playing brings the app over the keyguard ([LockScreenPlayerLauncher],
 * overlay permission). The wake is the ONLY decision point: MainActivity holds
 * its own `showWhenLocked` until the session is closed (pause, unlock, Fast
 * lock), so pausing/resuming never dismisses or re-fires it, and a play/pause
 * pressed on the lock-screen notification never raises the app (no screen
 * event happens for it).
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var audioEngine: AudioEngine

    @Inject
    lateinit var queuePersistence: QueuePersistence

    @Inject
    lateinit var trackRepository: TrackRepository

    @Inject
    lateinit var discordPresenceController: DiscordPresenceController

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var distantRepository: com.foamplayer.app.data.distant.DistantRepository

    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val shuffleCommand = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    private val repeatCommand = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)
    private val closeCommand = SessionCommand(ACTION_CLOSE_NOTIFICATION, Bundle.EMPTY)
    private val scoreCommand = SessionCommand(ACTION_TOGGLE_SCORE, Bundle.EMPTY)

    /** Cached notification-button configuration, read by the provider lambdas. */
    @Volatile
    private var notificationButtonLeft = NotificationExtraButton.SCORE
    @Volatile
    private var notificationButtonRight = NotificationExtraButton.CLOSE

    /**
     * The current track's fresh score, mirrored from the shared
     * [TrackRepository.scoreOverrides] overlay (written by BOTH the in-app
     * heart and this service's own toggle) with a DB read as fallback for
     * scores that predate the overlay. Read by the provider's icon lambda.
     */
    @Volatile
    private var currentTrackScore = 0

    /** Cached "display on lock screen" setting, read on the screen-state events. */
    @Volatile
    private var displayOnLockScreenEnabled = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) showLockScreenPlayer()
        }
    }

    override fun onCreate() {
        super.onCreate()
        discordPresenceController.start()
        registerScreenStateReceiver()
        observeLockScreenSetting()
        observeNotificationButtons()

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(shuffleCommand)
                            .add(repeatCommand)
                            .add(closeCommand)
                            .add(scoreCommand)
                            .build()
                    )
                    .build()

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int = when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    audioEngine.next()
                    SessionResult.RESULT_INFO_SKIPPED
                }
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    audioEngine.previous()
                    SessionResult.RESULT_INFO_SKIPPED
                }
                else -> super.onPlayerCommandRequest(session, controller, playerCommand)
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ) = when (customCommand.customAction) {
                ACTION_TOGGLE_SHUFFLE -> {
                    audioEngine.toggleShuffle()
                    refreshNotificationButtons(session)
                    triggerNotificationUpdate()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_TOGGLE_REPEAT -> {
                    audioEngine.toggleRepeat()
                    refreshNotificationButtons(session)
                    triggerNotificationUpdate()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_CLOSE_NOTIFICATION -> {
                    // "Close": pause and dismiss the notification. The flag must
                    // be set BEFORE pausing — the pause event itself triggers a
                    // notification update that must arrive already suppressed.
                    // The service and session stay alive, so the next play
                    // re-opens the notification; the queue snapshot also stays
                    // on disk for a later process restart.
                    notificationClosed = true
                    audioEngine.pause()
                    hideNotification()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_TOGGLE_SCORE -> {
                    serviceScope.launch {
                        toggleCurrentTrackScore()
                        refreshNotificationButtons(session)
                        triggerNotificationUpdate()
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                isForPlayback: Boolean
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                serviceScope.launch {
                    try {
                        val result = this@PlaybackService.buildResumptionState()
                        if (result != null) {
                            future.set(result)
                        } else {
                            future.setException(IllegalStateException("No resumable snapshot on disk"))
                        }
                    } catch (e: CancellationException) {
                        future.setException(e)
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Playback resumption failed", e)
                        future.setException(e)
                    }
                }
                return future
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_PLAYER, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val session = MediaSession.Builder(this, audioEngine.player)
            .setCallback(callback)
            .setBitmapLoader(
                EmbeddedAlbumArtBitmapLoader(this) { uri ->
                    com.foamplayer.distant.DistantUris.parse(uri.toString())
                        ?.let { distantRepository.artworkUrlFor(it) }
                }
            )
            .setSessionActivity(pendingIntent)
            .build()

        // Set the notification provider
        setMediaNotificationProvider(
            FoamMediaNotificationProvider(
                context = this,
                channelId = FoamApplication.PLAYBACK_CHANNEL_ID,
                shuffleCommand = shuffleCommand,
                repeatCommand = repeatCommand,
                closeCommand = closeCommand,
                scoreCommand = scoreCommand,
                leftButton = { notificationButtonLeft },
                rightButton = { notificationButtonRight },
                currentScore = { currentTrackScore },
                playbackState = { audioEngine.playbackState.value }
            ).apply {
                // TODO: change this to the app icon
                setSmallIcon(R.drawable.ic_music_note)
            }
        )
        mediaSession = session

        audioEngine.setOnPlayerSwappedListener {
            mediaSession?.setPlayer(audioEngine.player)
            mediaSession?.let { refreshNotificationButtons(it) }
            triggerNotificationUpdate()
        }

        audioEngine.setOnDisplayPlayerChangedListener { displayPlayer ->
            mediaSession?.let { session ->
                if (session.player !== displayPlayer) {
                    session.setPlayer(displayPlayer)
                    triggerNotificationUpdate()
                }
            }
        }

        addSession(session)
        refreshNotificationButtons(session)
        triggerNotificationUpdate()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * Swallows every notification update while the user closed the notification
     * (and playback is paused), so nothing re-posts the dismissed notification.
     * The first update that arrives while playback is active clears the closed
     * state instead, letting the normal flow re-post the notification and
     * re-promote the service to the foreground.
     */
    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ): ListenableFuture<Void?> {
        if (notificationClosed) {
            if (audioEngine.playbackState.value.isPlaying) {
                notificationClosed = false
            } else {
                return Futures.immediateVoidFuture()
            }
        }
        return super.onUpdateNotificationAsync(session, startInForegroundRequired)
    }

    /**
     * Drops the foreground state and cancels the playback notification while
     * keeping the service (and the session) alive. Both calls are defensive:
     * stopForeground throws if the service isn't foreground, and the cancel is
     * a belt-and-suspenders against a still-pending bitmap callback re-posting
     * the dismissed notification.
     */
    private fun hideNotification() {
        runCatching { Util.stopForeground(this, /* removeNotification= */ true) }
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        }
    }

    private fun registerScreenStateReceiver() {
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Keeps the cached "display on lock screen" flag in sync with settings. */
    private fun observeLockScreenSetting() {
        serviceScope.launch {
            settingsRepository.settings
                .map { settings -> settings.displayOnLockScreen }
                .distinctUntilChanged()
                .collect { displayOnLockScreenEnabled = it }
        }
    }

    /** Keeps the notification-button configuration and the score cache fresh. */
    private fun observeNotificationButtons() {
        serviceScope.launch {
            settingsRepository.settings.collect { settings ->
                notificationButtonLeft = settings.notificationButtonLeft
                notificationButtonRight = settings.notificationButtonRight
                // The configuration changed: rebuild the visible buttons.
                mediaSession?.let { refreshNotificationButtons(it) }
                triggerNotificationUpdate()
            }
        }
        // The score icon must follow the current track: refresh on every track
        // transition, seeding the cache from the shared overlay (fresher than
        // the queue snapshot) with a DB read as fallback.
        serviceScope.launch {
            audioEngine.playbackState.collect { state ->
                val track = state.currentTrack ?: return@collect
                currentTrackScore = trackRepository.scoreOverrides.value[track.id]
                    ?: trackRepository.getTrackById(track.id)?.score
                    ?: track.score
            }
        }
        // Scores written ANYWHERE (the in-app heart, this service's toggle) flow
        // through the shared repository overlay: when the current track's entry
        // changes, mirror it into the cache and re-render the notification so
        // the heart icon follows the DB immediately, in both directions.
        serviceScope.launch {
            trackRepository.scoreOverrides.collect { overrides ->
                val track = audioEngine.playbackState.value.currentTrack ?: return@collect
                val score = overrides[track.id] ?: return@collect
                if (currentTrackScore != score) {
                    currentTrackScore = score
                    mediaSession?.let { refreshNotificationButtons(it) }
                    triggerNotificationUpdate()
                }
            }
        }
    }

    /**
     * The only trigger for the lock-screen display: the screen WAKES while a
     * song is playing AND the app was the user's foreground surface when the
     * phone locked ([AppForegroundState]). Screen-off never launches (the
     * session decision belongs to the wake), and once up the app stays over
     * the keyguard until an explicit close trigger in MainActivity — so
     * pausing/resuming never dismisses or re-fires it.
     */
    private fun showLockScreenPlayer() {
        if (!displayOnLockScreenEnabled) return
        if (!AppForegroundState.isInForeground) return
        if (!audioEngine.playbackState.value.isPlaying) return
        LockScreenPlayerLauncher.launch(this)
    }

    /**
     * Cycles the current track's score: not noted -> like -> super like ->
     * dislike -> not noted. Writes through the shared repository overlay (so
     * the in-app heart updates instantly) and mirrors the fresh value into
     * [currentTrackScore] so the notification icon updates immediately.
     */
    private suspend fun toggleCurrentTrackScore() {
        val track = audioEngine.playbackState.value.currentTrack ?: return
        val current = trackRepository.scoreOverrides.value[track.id]
            ?: trackRepository.getTrackById(track.id)?.score
            ?: track.score
        val next = when (current) {
            1 -> 2
            2 -> -1
            -1 -> 0
            else -> 1
        }
        trackRepository.setScore(track.id, next)
        currentTrackScore = next
    }

    /**
     * Playback resumption state: the engine's own session when the process is
     * still alive (service demoted, queue live), otherwise the DataStore
     * snapshot resolved against the library and rebuilt in the engine paused
     * at the saved position.
     */
    private suspend fun buildResumptionState(): MediaSession.MediaItemsWithStartPosition? {
        // The engine may still hold the whole session (service demoted but the
        // process alive): resume from the live queue, no rebuild needed.
        val live = audioEngine.playbackState.value
        val liveIndex = live.queueIndex
        if (live.currentTrack != null && liveIndex in live.queue.indices) {
            return MediaSession.MediaItemsWithStartPosition(
                audioEngine.mediaItemsFor(live.queue),
                liveIndex,
                audioEngine.positionMs.value.coerceAtLeast(0L)
            )
        }

        val saved = queuePersistence.current() ?: return null
        val byId = trackRepository.getTracksByIds(saved.trackIds).associateBy { it.id }
        val queue = saved.trackIds.mapNotNull { byId[it] }
        if (queue.isEmpty() || saved.currentIndex !in queue.indices) return null

        val positionMs = saved.positionMs.coerceAtLeast(0L)
        audioEngine.restoreQueue(
            tracks = queue,
            startIndex = saved.currentIndex,
            positionMs = positionMs,
            sourceCollectionId = saved.queueSourceId,
            sourceFlags = saved.sourceFlags.takeIf { it.size == queue.size },
            isShuffle = saved.isShuffle,
            repeatMode = saved.repeatMode
        )

        return MediaSession.MediaItemsWithStartPosition(
            audioEngine.mediaItemsFor(queue),
            saved.currentIndex,
            positionMs
        )
    }

    override fun onDestroy() {
        audioEngine.setOnPlayerSwappedListener(null)
        audioEngine.setOnDisplayPlayerChangedListener(null)
        runCatching { unregisterReceiver(screenStateReceiver) }
        serviceScope.cancel()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    /** Builds one [CommandButton] for a configured extra notification button. */
    private fun extraButtonCommandButton(kind: NotificationExtraButton): CommandButton? {
        if (kind == NotificationExtraButton.NONE) return null
        val state = audioEngine.playbackState.value
        val builder = CommandButton.Builder(
            when (kind) {
                NotificationExtraButton.REPEAT -> when (state.repeatMode) {
                    RepeatMode.NONE -> CommandButton.ICON_REPEAT_OFF
                    RepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL
                    RepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE
                }
                NotificationExtraButton.SHUFFLE ->
                    if (state.isShuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
                NotificationExtraButton.CLOSE -> CommandButton.ICON_UNDEFINED
                NotificationExtraButton.SCORE -> when (currentTrackScore) {
                    2 -> CommandButton.ICON_STAR_FILLED
                    1 -> CommandButton.ICON_HEART_FILLED
                    -1 -> CommandButton.ICON_UNDEFINED
                    else -> CommandButton.ICON_HEART_UNFILLED
                }
                else -> CommandButton.ICON_UNDEFINED
            }
        )
        if (kind == NotificationExtraButton.CLOSE) {
            builder.setCustomIconResId(R.drawable.ic_notif_close)
        }
        if (kind == NotificationExtraButton.SCORE && currentTrackScore == -1) {
            builder.setCustomIconResId(R.drawable.ic_notif_heart_broken)
        }
        return builder
            .setSessionCommand(
                when (kind) {
                    NotificationExtraButton.REPEAT -> repeatCommand
                    NotificationExtraButton.SHUFFLE -> shuffleCommand
                    NotificationExtraButton.CLOSE -> closeCommand
                    else -> scoreCommand
                }
            )
            .setDisplayName(
                when (kind) {
                    NotificationExtraButton.REPEAT -> getString(R.string.action_repeat)
                    NotificationExtraButton.SHUFFLE -> getString(R.string.action_shuffle)
                    NotificationExtraButton.CLOSE -> getString(R.string.notification_button_close)
                    else -> getString(R.string.notification_button_score)
                }
            )
            .build()
    }

    /**
     * Updates the session's media button preferences to mirror the configured
     * extra buttons: the notification re-renders and controllers (Wear, Auto)
     * see the same button set.
     */
    private fun refreshNotificationButtons(session: MediaSession) {
        val buttons = listOfNotNull(
            extraButtonCommandButton(notificationButtonLeft),
            extraButtonCommandButton(notificationButtonRight)
        )
        session.setMediaButtonPreferences(buttons)
    }

    companion object {
        private const val TAG = "PlaybackService"

        /**
         * True while the user closed the notification and playback is paused:
         * every notification update is suppressed until playback starts again
         * (then the flag is lifted and the notification re-appears). Process-wide
         * so it survives service recreation (the system may kill and restart the
         * demoted background service while the process lives) — a fresh process
         * starts clean, which reads as a new playback session.
         */
        @Volatile
        private var notificationClosed = false

        const val ACTION_TOGGLE_SHUFFLE = "com.foamplayer.app.action.TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.foamplayer.app.action.TOGGLE_REPEAT"
        const val ACTION_CLOSE_NOTIFICATION = "com.foamplayer.app.action.CLOSE_NOTIFICATION"
        const val ACTION_TOGGLE_SCORE = "com.foamplayer.app.action.TOGGLE_SCORE"
        const val EXTRA_OPEN_PLAYER = "com.foamplayer.app.extra.OPEN_PLAYER"
    }
}
