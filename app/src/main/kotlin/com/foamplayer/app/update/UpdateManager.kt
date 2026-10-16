package com.foamplayer.app.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.foamplayer.app.BuildConfig
import com.foamplayer.app.MainActivity
import com.foamplayer.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Result of an update check against the GitHub latest release. */
sealed interface UpdateCheckResult {
    /** A release newer than the running [versionName] exists. */
    data class UpdateAvailable(val release: GitHubRelease) : UpdateCheckResult

    /** The latest release tag matches (or is older than) the running version. */
    data object UpToDate : UpdateCheckResult

    /** The network call failed (offline, rate limit, unexpected payload). */
    data object Error : UpdateCheckResult
}

/** Download progress states surfaced in the About screen. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data object Completed : DownloadState
    data object Failed : DownloadState
}

/**
 * In-app updater: checks GitHub for the latest release, downloads its APK into
 * the app's private files dir while mirroring progress as a notification, then
 * hands the file to the system package installer.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The running build's version, e.g. "1.1.0-alpha". */
    val currentVersion: String = BuildConfig.VERSION_NAME

    private val currentVersionCode: Int = BuildConfig.VERSION_CODE

    /** Live download state for the About screen. */
    val downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)

    /** True while a check is in flight (drives the About screen's spinner). */
    val checking = MutableStateFlow(false)

    private var downloadJob: Job? = null

    /**
     * Release found by the silent startup auto-check; a global "new update
     * available" modal is hosted at the nav root while this is non-null. The
     * About screen's manual checks don't touch it.
     */
    val startupRelease = MutableStateFlow<GitHubRelease?>(null)

    /**
     * Set whenever a download completes (from any entry point) until the user
     * dismisses or acts on the global "install" modal.
     */
    val installPrompt = MutableStateFlow<File?>(null)

    /** Cached latest release so the About screen can re-open its notes modal. */
    var lastCheckResult: UpdateCheckResult? = null
        private set

    /** Fetches the latest GitHub release and compares it with the running build. */
    suspend fun checkForUpdate(): UpdateCheckResult {
        checking.value = true
        try {
            val release = GitHubReleasesApi.latestRelease()
            val result = when {
                release == null || release.tagName.isNullOrBlank() -> UpdateCheckResult.Error
                release.isNewerThan(currentVersion, currentVersionCode) ->
                    UpdateCheckResult.UpdateAvailable(release)
                else -> UpdateCheckResult.UpToDate
            }
            lastCheckResult = result
            return result
        } finally {
            checking.value = false
        }
    }

    /**
     * Startup auto-check: like [checkForUpdate], but an available update also
     * raises the global "new update available" modal via [startupRelease].
     */
    suspend fun checkForUpdateOnStartup(): UpdateCheckResult =
        checkForUpdate().also { result ->
            if (result is UpdateCheckResult.UpdateAvailable) {
                startupRelease.value = result.release
            }
        }

    /**
     * Downloads the release's preferred APK asset and fires the system install
     * prompt when it completes. Progress is mirrored in [downloadState] and in
     * a notification-shade progress notification.
     */
    fun downloadAndInstall(release: GitHubRelease) {
        val asset = release.preferredApkAsset() ?: return
        if (downloadJob?.isActive == true) return

        downloadState.value = DownloadState.Downloading(0f)
        createChannel()
        postProgressNotification(0f, indeterminate = true)

        downloadJob = scope.launch {
            try {
                val file = apkFile(release)
                GitHubReleasesApi.downloadApk(
                    url = asset.browserDownloadUrl ?: return@launch,
                    destination = file,
                    expectedSize = asset.size
                ) { progress ->
                    downloadState.value = DownloadState.Downloading(progress)
                    postProgressNotification(progress, indeterminate = false)
                }
                downloadState.value = DownloadState.Completed
                // Download finished: dismiss the progress notification and
                // raise the global "install" prompt (both entry points).
                NotificationManagerCompat.from(context).cancel(DOWNLOAD_NOTIFICATION_ID)
                installPrompt.value = file
            } catch (_: Exception) {
                downloadState.value = DownloadState.Failed
                NotificationManagerCompat.from(context).cancel(DOWNLOAD_NOTIFICATION_ID)
            }
        }
    }

    /** Cancels an in-flight download and clears its notification. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadState.value = DownloadState.Idle
        NotificationManagerCompat.from(context).cancel(DOWNLOAD_NOTIFICATION_ID)
    }

    /** Dismisses the startup "update available" modal without downloading. */
    fun dismissStartupRelease() {
        startupRelease.value = null
    }

    /** Dismisses the global "install" modal (the APK stays on disk). */
    fun dismissInstallPrompt() {
        installPrompt.value = null
    }

    /** Fires ACTION_INSTALL_PACKAGE with the downloaded APK via FileProvider. */
    fun installApk(file: File) {
        context.startActivity(
            installIntent(file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** The downloaded APK of [release], if it is still on disk. */
    fun downloadedApk(release: GitHubRelease): File? =
        apkFile(release).takeIf { it.exists() && it.length() > 0 }

    private fun apkFile(release: GitHubRelease): File =
        File(context.getExternalFilesDir("updates"), "foamplayer-${release.version}.apk")

    @SuppressLint("MissingPermission")
    private fun postProgressNotification(progress: Float, indeterminate: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(ContextCompat.getColor(context, R.color.notification_color))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setContentTitle(context.getString(R.string.notif_update_downloading_title))
            .setContentText(
                if (indeterminate) {
                    context.getString(R.string.notif_update_preparing)
                } else {
                    context.getString(R.string.notif_update_progress, percent)
                }
            )
            .setProgress(100, percent, indeterminate)
            .build()
        try {
            manager.notify(DOWNLOAD_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
    }

    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            UPDATE_CHANNEL_ID,
            context.getString(R.string.update_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.update_notification_channel_desc)
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private companion object {
        const val UPDATE_CHANNEL_ID = "foam_update_channel"
        const val DOWNLOAD_NOTIFICATION_ID = 4001
    }
}

/**
 * Semantic version comparison: "1.2.3" against "1.2.10", with pre-release
 * suffixes ("1.2.0-alpha") sorted before their stable counterpart, matching
 * GitHub's tag conventions. Falls back to a string inequality check when
 * either side is not parseable.
 */
fun GitHubRelease.isNewerThan(versionName: String, versionCode: Int): Boolean {
    val latest = parseVersion(version) ?: return version != versionName
    val current = parseVersion(versionName) ?: return version != versionName
    return compareVersions(latest, current) > 0
}

private data class ParsedVersion(
    val numbers: List<Int>,
    val preRelease: String?
)

private fun parseVersion(raw: String): ParsedVersion? {
    val cleaned = raw.trim().removePrefix("v").removePrefix("V")
    if (cleaned.isEmpty()) return null
    val dash = cleaned.indexOf('-')
    val core = if (dash >= 0) cleaned.substring(0, dash) else cleaned
    val preRelease = if (dash >= 0) cleaned.substring(dash + 1) else null
    val numbers = core.split('.').map { part ->
        part.filter { it.isDigit() }.toIntOrNull() ?: return null
    }
    if (numbers.isEmpty()) return null
    return ParsedVersion(numbers, preRelease?.takeIf(String::isNotBlank))
}

private fun compareVersions(a: ParsedVersion, b: ParsedVersion): Int {
    val length = maxOf(a.numbers.size, b.numbers.size)
    for (index in 0 until length) {
        val left = a.numbers.getOrElse(index) { 0 }
        val right = b.numbers.getOrElse(index) { 0 }
        if (left != right) return left.compareTo(right)
    }
    // Same numeric core: stable (no suffix) outranks any pre-release, and
    // pre-release tags compare lexically (alpha < beta < rc1...).
    return when {
        a.preRelease == null && b.preRelease == null -> 0
        a.preRelease == null -> 1
        b.preRelease == null -> -1
        else -> a.preRelease.compareTo(b.preRelease)
    }
}
