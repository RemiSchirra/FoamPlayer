package com.foamplayer.app.audio.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
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
import com.foamplayer.app.audio.engine.RepeatMode
import com.foamplayer.app.discord.DiscordPresenceController
import com.google.common.util.concurrent.Futures
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var audioEngine: AudioEngine

    @Inject
    lateinit var discordPresenceController: DiscordPresenceController

    private var mediaSession: MediaSession? = null

    private val shuffleCommand = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    private val repeatCommand = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        discordPresenceController.start()
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
                // Define the default icon used for the notification
                // TODO : change this in the future to the app icon
                setSmallIcon(R.drawable.ic_music_note)
            }
        )
        mediaSession = session

        // A crossfade promotes its secondary player at the swap: re-bind the
        // session (and refresh the notification) to the new active player so the
        // session/notification/position keep tracking the audible stream.
        audioEngine.setOnPlayerSwappedListener {
            mediaSession?.setPlayer(audioEngine.player)
            mediaSession?.let { refreshNotificationButtons(it) }
            triggerNotificationUpdate()
        }

        addSession(session)
        refreshNotificationButtons(session)
        triggerNotificationUpdate()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        audioEngine.setOnPlayerSwappedListener(null)
        discordPresenceController.stop()
        mediaSession?.run {
            player.release()
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
        const val ACTION_TOGGLE_SHUFFLE = "com.foamplayer.app.action.TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.foamplayer.app.action.TOGGLE_REPEAT"
        const val EXTRA_OPEN_PLAYER = "com.foamplayer.app.extra.OPEN_PLAYER"
    }
}
