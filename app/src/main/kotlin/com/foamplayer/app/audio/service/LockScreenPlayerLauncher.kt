package com.foamplayer.app.audio.service

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import com.foamplayer.app.MainActivity
import com.foamplayer.app.LockDisplayLaunch

/**
 * Brings the main app over the keyguard while a song is playing.
 *
 * The dependable path is a direct activity start: with the "Display over
 * other apps" (SYSTEM_ALERT_WINDOW) permission granted, background activity
 * launches are exempt from the Android 10+ restrictions, so the service may
 * bring [MainActivity] to the front the moment the screen turns off / on
 * while locked — no dependency on how the OEM lock screen schedules
 * full-screen intents. The activity then holds its own `showWhenLocked`
 * flag for the rest of the session, so pausing never drops it and a
 * notification play never raises it.
 *
 * The start carries [PlaybackService.EXTRA_OPEN_PLAYER] so the navigation
 * host snaps the full player open with no animation, and the
 * [LockDisplayLaunch] extra marking this as a lock-display session start.
 *
 * When the overlay permission is missing, the launch fails silently (no
 * fallback notification: the media notification must stay alone on the lock
 * screen).
 */
object LockScreenPlayerLauncher {

    /** True when this app may start activities from the background. */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** Brings the app (full player showing) over the keyguard; false when not permitted. */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun launch(context: Context): Boolean {
        if (!canDrawOverlays(context)) return false
        return runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(PlaybackService.EXTRA_OPEN_PLAYER, true)
                    putExtra(LockDisplayLaunch, true)
                },
                // No enter/exit animation: the window must appear fully drawn
                // over the keyguard instead of crossfading into place.
                ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
            )
        }.isSuccess
    }

    /** Opens the system screen granting the overlay permission. */
    fun openOverlaySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.fromParts("package", context.packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }
}
