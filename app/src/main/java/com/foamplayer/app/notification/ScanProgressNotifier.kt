package com.foamplayer.app.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.foamplayer.app.MainActivity
import com.foamplayer.app.R
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ArtistImageScanner
import com.foamplayer.app.data.scanner.ScanStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mirrors the library scan ([TrackRepository.scanStatus]) and the artist image
 * scan ([ArtistImageScanner.state]) as progress notifications in the shade, so
 * long scans stay visible (and report their completion) outside of Settings.
 */
@Singleton
class ScanProgressNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackRepository: TrackRepository,
    private val artistImageScanner: ArtistImageScanner
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var finishedDismissJob: Job? = null

    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun start() {
        createChannel()
        scope.launch { observeLibraryScan() }
        scope.launch { observeArtistScan() }
    }

    private suspend fun observeLibraryScan() {
        var lastUpdateAt = 0L
        trackRepository.scanStatus.collect { status ->
            finishedDismissJob?.cancel()
            when (status) {
                is ScanStatus.Scanning -> {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdateAt < UPDATE_INTERVAL_MS && status.progress < 1f) return@collect
                    lastUpdateAt = now
                    val text = if (status.progress >= 1f) {
                        context.getString(R.string.scan_finishing)
                    } else {
                        status.currentFile
                    }
                    notify(LIBRARY_NOTIFICATION_ID) {
                        setContentTitle(context.getString(R.string.notif_scan_library_title))
                            .setContentText(text)
                            .setProgress(100, (status.progress * 100).toInt().coerceIn(0, 100), false)
                            .setOngoing(true)
                    }
                }
                is ScanStatus.Finished -> {
                    notify(LIBRARY_NOTIFICATION_ID) {
                        setContentTitle(context.getString(R.string.notif_scan_finished_title))
                            .setContentText(context.getString(R.string.scan_finished, status.count))
                            .setProgress(0, 0, false)
                            .setOngoing(false)
                            .setAutoCancel(true)
                    }
                    finishedDismissJob = scope.launch {
                        delay(FINISHED_NOTIFICATION_DURATION_MS)
                        NotificationManagerCompat.from(context).cancel(LIBRARY_NOTIFICATION_ID)
                    }
                }
                ScanStatus.Idle -> NotificationManagerCompat.from(context).cancel(LIBRARY_NOTIFICATION_ID)
            }
        }
    }

    private suspend fun observeArtistScan() {
        artistImageScanner.state.collect { state ->
            if (!state.running) {
                NotificationManagerCompat.from(context).cancel(ARTIST_NOTIFICATION_ID)
                return@collect
            }
            val percent = if (state.total > 0) (state.found * 100 / state.total) else 0
            notify(ARTIST_NOTIFICATION_ID, android.R.drawable.stat_sys_download) {
                setContentTitle(context.getString(R.string.notif_resolve_artists_title))
                    .setContentText(context.getString(R.string.artist_images_found, state.found, state.total))
                    .setProgress(100, percent, state.total == 0)
                    .setOngoing(true)
            }
        }
    }

    /**
     * Posts a scan-channel notification. [iconRes] picks the small icon: the
     * static music note by default, or the platform's stock animated download
     * icon ([android.R.drawable.stat_sys_download]) for the artist image scan.
     */
    @SuppressLint("MissingPermission")
    private inline fun notify(
        id: Int,
        iconRes: Int = R.drawable.ic_music_note,
        build: NotificationCompat.Builder.() -> Unit
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, SCAN_CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setColor(ContextCompat.getColor(context, R.color.notification_color))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .apply(build)
            .build()
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SCAN_CHANNEL_ID,
                context.getString(R.string.scan_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.scan_notification_channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val SCAN_CHANNEL_ID = "foam_scan_channel"
        const val LIBRARY_NOTIFICATION_ID = 3001
        const val ARTIST_NOTIFICATION_ID = 3002

        /** Cap status-bar churn; [ScanStatus] updates arrive per batch of files. */
        const val UPDATE_INTERVAL_MS = 500L

        /** How long the "scan finished" summary stays before auto-dismissing. */
        const val FINISHED_NOTIFICATION_DURATION_MS = 5_000L
    }
}
