package com.foamplayer.app.audio.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.foamplayer.app.data.settings.AppSettings

/**
 * Owns the engine's audio focus, replacing the player's built-in focus
 * handling (which always pauses on a focus loss and always resumes once the
 * focus comes back) with the two user-configurable behaviors:
 *  - [AppSettings.pauseOnFocusLoss]: pausing when another app starts playing
 *    (the focus moves to a higher-priority stream),
 *  - [AppSettings.resumeOnFocusRegain]: resuming when the interrupted audio
 *    stops and the focus is back.
 *
 * Only a pause issued in reaction to a transient loss is eligible for a
 * regain resume; a manual pause (or a fresh play) always resets the state.
 * Reactions read the settings snapshot at callback time, so toggling a
 * preference applies to the very next focus event without touching the
 * players.
 */
internal class AudioFocusHandler(
    context: Context,
    private val latestSettings: () -> AppSettings,
    private val isPlaying: () -> Boolean,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) : AudioManager.OnAudioFocusChangeListener {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener(this)
        // The loss reaction is a pause, not a volume duck (see onAudioFocusChange).
        .setWillPauseWhenDucked(true)
        .build()

    /** Whether the current pause was caused by a focus loss (a regain may resume it). */
    private var pausedByFocusLoss = false

    /** Requests the focus for a playback start. A manual play always re-requests. */
    fun request() {
        pausedByFocusLoss = false
        audioManager.requestAudioFocus(focusRequest)
    }

    /** Releases the focus when playback leaves the phone (full stop, cast hand-off). */
    fun abandon() {
        pausedByFocusLoss = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    /** A user-issued pause is never auto-resumed by a later focus regain. */
    fun notifyManualPause() {
        pausedByFocusLoss = false
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss: no regain follows, only a manual play resumes.
                pausedByFocusLoss = false
                if (latestSettings().pauseOnFocusLoss && isPlaying()) onPause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onTransientLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onTransientLoss()

            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocusLoss && latestSettings().resumeOnFocusRegain) {
                    pausedByFocusLoss = false
                    onResume()
                } else {
                    pausedByFocusLoss = false
                }
            }
        }
    }

    /** A transient loss pauses (per the preference) and stays eligible for a regain resume. */
    private fun onTransientLoss() {
        if (latestSettings().pauseOnFocusLoss && isPlaying()) {
            pausedByFocusLoss = true
            onPause()
        }
    }
}
