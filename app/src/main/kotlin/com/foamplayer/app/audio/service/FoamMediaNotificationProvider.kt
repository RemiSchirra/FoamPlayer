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
import com.foamplayer.app.data.settings.NotificationExtraButton
import com.google.common.collect.ImmutableList

/** Builds the playback notification with a stable action order: extra button, Prev, Play, Next, extra button. */
@UnstableApi
class FoamMediaNotificationProvider(
    context: Context,
    channelId: String,
    private val shuffleCommand: SessionCommand,
    private val repeatCommand: SessionCommand,
    private val closeCommand: SessionCommand,
    private val scoreCommand: SessionCommand,
    private val leftButton: () -> NotificationExtraButton,
    private val rightButton: () -> NotificationExtraButton,
    private val currentScore: () -> Int,
    private val playbackState: () -> PlaybackState
) : DefaultMediaNotificationProvider(context, { DEFAULT_NOTIFICATION_ID }, channelId, R.string.playback_channel_name) {

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        showPauseButton: Boolean
    ): ImmutableList<CommandButton> {
        val state = playbackState()
        val buttons = ImmutableList.builder<CommandButton>()

        leftButton().takeIf { it != NotificationExtraButton.NONE }?.let {
            buttons.add(customButton(it, state))
        }
        buttons.add(playerButton(CommandButton.ICON_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, "Previous", compactIndex = 0))
        buttons.add(
            playerButton(
                if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY,
                Player.COMMAND_PLAY_PAUSE,
                if (showPauseButton) "Pause" else "Play",
                compactIndex = 1
            )
        )
        buttons.add(playerButton(CommandButton.ICON_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, "Next", compactIndex = 2))
        rightButton().takeIf { it != NotificationExtraButton.NONE }?.let {
            buttons.add(customButton(it, state))
        }
        return buttons.build()
    }

    private fun customButton(kind: NotificationExtraButton, state: PlaybackState): CommandButton {
        val builder = CommandButton.Builder(when (kind) {
            NotificationExtraButton.REPEAT -> when (state.repeatMode) {
                com.foamplayer.app.audio.engine.RepeatMode.NONE -> CommandButton.ICON_REPEAT_OFF
                com.foamplayer.app.audio.engine.RepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL
                com.foamplayer.app.audio.engine.RepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE
            }
            NotificationExtraButton.SHUFFLE ->
                if (state.isShuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
            // No predefined constant: a custom cross resource instead.
            NotificationExtraButton.CLOSE -> CommandButton.ICON_UNDEFINED
            // Score: the icon mirrors the current track's score (broken heart has no constant).
            NotificationExtraButton.SCORE -> when (currentScore()) {
                2 -> CommandButton.ICON_STAR_FILLED
                1 -> CommandButton.ICON_HEART_FILLED
                else -> CommandButton.ICON_HEART_UNFILLED
            }
            else -> CommandButton.ICON_UNDEFINED
        })
        if (kind == NotificationExtraButton.CLOSE) {
            builder.setCustomIconResId(R.drawable.ic_notif_close)
        }
        if (kind == NotificationExtraButton.SCORE && currentScore() == -1) {
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
                    NotificationExtraButton.REPEAT -> "Repeat"
                    NotificationExtraButton.SHUFFLE -> "Shuffle"
                    NotificationExtraButton.CLOSE -> "Close"
                    else -> "Score"
                }
            )
            .build()
    }

    private fun playerButton(icon: Int, command: Int, label: String, compactIndex: Int): CommandButton =
        CommandButton.Builder(icon)
            .setPlayerCommand(command)
            .setDisplayName(label)
            .setExtras(Bundle().apply {
                putInt(COMMAND_KEY_COMPACT_VIEW_INDEX, compactIndex)
            })
            .build()
}
