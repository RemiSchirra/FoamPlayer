package com.foamplayer.app.audio.service

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.foamplayer.app.R
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.audio.engine.RepeatMode
import com.google.common.collect.ImmutableList

/** Builds the playback notification with a stable action order: Shuffle, Prev, Play, Next, Repeat. */
@UnstableApi
class FoamMediaNotificationProvider(
    context: Context,
    channelId: String,
    private val shuffleCommand: SessionCommand,
    private val repeatCommand: SessionCommand,
    private val playbackState: () -> PlaybackState
) : DefaultMediaNotificationProvider(context, { DEFAULT_NOTIFICATION_ID }, channelId, R.string.playback_channel_name) {

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        showPauseButton: Boolean
    ): ImmutableList<CommandButton> {
        val state = playbackState()
        return ImmutableList.of(
            customButton(
                command = shuffleCommand,
                icon = if (state.isShuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
                label = "Shuffle"
            ),
            playerButton(CommandButton.ICON_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, "Previous", compactIndex = 0),
            playerButton(
                if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY,
                Player.COMMAND_PLAY_PAUSE,
                if (showPauseButton) "Pause" else "Play",
                compactIndex = 1
            ),
            playerButton(CommandButton.ICON_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, "Next", compactIndex = 2),
            customButton(
                command = repeatCommand,
                icon = when (state.repeatMode) {
                    RepeatMode.NONE -> CommandButton.ICON_REPEAT_OFF
                    RepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL
                    RepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE
                },
                label = "Repeat"
            )
        )
    }

    private fun customButton(command: SessionCommand, icon: Int, label: String): CommandButton =
        CommandButton.Builder(icon)
            .setSessionCommand(command)
            .setDisplayName(label)
            .build()

    private fun playerButton(icon: Int, command: Int, label: String, compactIndex: Int): CommandButton =
        CommandButton.Builder(icon)
            .setPlayerCommand(command)
            .setDisplayName(label)
            .setExtras(Bundle().apply {
                putInt(COMMAND_KEY_COMPACT_VIEW_INDEX, compactIndex)
            })
            .build()
}
