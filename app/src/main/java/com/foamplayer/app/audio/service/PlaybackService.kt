package com.foamplayer.app.audio.service

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.foamplayer.app.FoamApplication
import com.foamplayer.app.MainActivity
import com.foamplayer.app.R
import com.foamplayer.app.audio.engine.AudioEngine
import com.foamplayer.app.audio.engine.QueuePersistence
import com.foamplayer.app.audio.engine.RepeatMode
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground media session service. Binds the session to [AudioEngine]'s active
 * player, publishes the custom shuffle/repeat commands and the notification.
 *
 * The service does NOT own the player: [AudioEngine] is an app-scoped singleton
 * whose player outlives this service instance (the system can stop an idle
 * service while the process stays alive). Releasing the session's player here
 * would kill the engine's playback thread and brick every later command with a
 * "sending message to a Handler on a dead thread" warning -- the session is
 * merely a bound view of the engine's player.
 *
 * Playback resumption: Media3 retires the paused foreground state after 10
 * minutes, and the system always destroys the service (or the whole process).
 * [QueuePersistence] keeps the playing session on disk; when this service is
 * recreated to serve a media button press (Bluetooth, car, ...) after that,
 * `onPlaybackResumption` rebuilds the queue exactly where it was — so the
 * notification controls recover even after the system killed the session.
 *
 * With "display on lock screen" enabled, screen on/off broadcasts bring the
 * app over the keyguard while a song is playing ([LockScreenPlayerLauncher],
 * overlay permission). Starting is the ONLY trigger: MainActivity keeps
 * itself over the keyguard until unlock, so pausing never dismisses it.
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

    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val shuffleCommand = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    private val repeatCommand = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)

    /** Cached "display on lock screen" setting, read on the screen-state events. */
    @Volatile
    private var displayOnLockScreenEnabled = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // The keyguard state settles a beat after the screen-off/on
                // broadcasts; a short delay keeps the launch check from racing it.
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SCREEN_ON -> serviceScope.launch {
                    delay(LOCK_ARM_DELAY_MS)
                    showLockScreenPlayer()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        discordPresenceController.start()
        registerScreenStateReceiver()
        observeLockScreenSetting()

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
                            .build()
                    )
                    .build()

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ) = when (customCommand.customAction) {
                ACTION_TOGGLE_SHUFFLE -> {
                    audioEngine.toggleShuffle()
                    refreshNotificationButtons(session)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_TOGGLE_REPEAT -> {
                    audioEngine.toggleRepeat()
                    refreshNotificationButtons(session)
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
            .setBitmapLoader(EmbeddedAlbumArtBitmapLoader(this))
            .setSessionActivity(pendingIntent)
            .build()

        // Set the notification provider
        setMediaNotificationProvider(
            FoamMediaNotificationProvider(
                context = this,
                channelId = FoamApplication.PLAYBACK_CHANNEL_ID,
                shuffleCommand = shuffleCommand,
                repeatCommand = repeatCommand,
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

    private fun registerScreenStateReceiver() {
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
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

    /**
     * The only trigger for the lock-screen display: waking or sleeping the
     * phone while a song is playing and the device is locked. Once up, the
     * app stays over the keyguard (MainActivity holds `showWhenLocked` until
     * unlock), so pausing/resuming never dismisses or re-fires it.
     */
    private fun showLockScreenPlayer() {
        if (!displayOnLockScreenEnabled) return
        val state = audioEngine.playbackState.value
        if (!state.isPlaying || !isKeyguardUp()) return
        LockScreenPlayerLauncher.launch(this)
    }

    private fun isKeyguardUp(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

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

    private fun refreshNotificationButtons(session: MediaSession) {
        session.setMediaButtonPreferences(
            listOf(
                CommandButton.Builder(
                    if (audioEngine.playbackState.value.isShuffle) {
                        CommandButton.ICON_SHUFFLE_ON
                    } else {
                        CommandButton.ICON_SHUFFLE_OFF
                    }
                )
                    .setSessionCommand(shuffleCommand)
                    .setDisplayName(getString(R.string.action_shuffle))
                    .build(),
                CommandButton.Builder(
                    when (audioEngine.playbackState.value.repeatMode) {
                        RepeatMode.NONE -> CommandButton.ICON_REPEAT_OFF
                        RepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL
                        RepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE
                    }
                )
                    .setSessionCommand(repeatCommand)
                    .setDisplayName(getString(R.string.action_repeat))
                    .build()
            )
        )
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val LOCK_ARM_DELAY_MS = 300L
        const val ACTION_TOGGLE_SHUFFLE = "com.foamplayer.app.action.TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.foamplayer.app.action.TOGGLE_REPEAT"
        const val EXTRA_OPEN_PLAYER = "com.foamplayer.app.extra.OPEN_PLAYER"
    }
}
