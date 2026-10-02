package com.foamplayer.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.foamplayer.app.audio.service.PlaybackService
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.data.settings.ThemeMode
import com.foamplayer.app.ui.components.LocalDefaultCollectionHeroLayout
import com.foamplayer.app.ui.navigation.FoamNavHost
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.settings.SettingsViewModel
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.LocalAutomaticColorEnabled
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Single activity hosting the whole Compose UI and the media playback session. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private companion object {
        val TRANSPARENT = android.graphics.Color.TRANSPARENT
        /** Inactivity after which the lock-display session closes (Fast lock). */
        const val FAST_LOCK_TIMEOUT_MS = 5_000L
    }

    /**
     * Synchronously-readable mirror of the persisted theme mode. DataStore's
     * first emission is async, so without this the UI would seed with the
     * default (dark) palette for the first frames after a force-close -- a
     * dark flash for Light-theme users. Written on every theme change.
     */
    private val themeModePrefs by lazy {
        getSharedPreferences("theme_mirror", MODE_PRIVATE)
    }

    private fun readMirroredThemeMode(): ThemeMode =
        themeModePrefs.getString("mode", null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.FOLLOW_SYSTEM

    private fun writeMirroredThemeMode(mode: ThemeMode) {
        themeModePrefs.edit().putString("theme_mirror", mode.name).apply()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startPlaybackService()
        }
    }

    /**
     * Lock-screen display session, started by [PlaybackService] when it
     * brings the app over the keyguard (screen event + playing) and ended by
     * an unlock, the queue emptying, or Fast lock (5s of inactivity).
     * Sticky on purpose: pausing the song must NOT drop the already-shown
     * app back behind the lock screen, and pressing play from the lock-screen
     * notification (while the app was never shown) must NOT raise it — the
     * flag only turns on via the screen-event launch, never from a
     * playback-state change.
     */
    private var lockDisplaySessionActive = false

    /** Active Fast lock countdown; null while the timer is not running. */
    private var fastLockJob: Job? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                lockDisplaySessionActive = false
                cancelFastLock()
                applyShowWhenLocked()
            }
        }
    }

    private fun applyShowWhenLocked() {
        val show = lockDisplaySessionActive &&
                settingsViewModel.settings.value.displayOnLockScreen &&
                playerViewModel.hasCurrentTrack.value
        setShowWhenLocked(show)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge so the backdrop can cover the whole screen; Compose re-adds
        // insets where interactive elements need them.
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handleIntent(intent)
        // Permissions are requested on onboarding; afterwards they're auto-requested once.
        lifecycleScope.launch {
            settingsViewModel.onboardingCompleted.collect { completed ->
                if (completed == true) checkAndRequestPermissions()
            }
        }
        // Re-evaluate the keyguard flag when its inputs move: a launched
        // session with the option off or an emptied queue ends it. Play/pause
        // while the session is active keeps the flag (applyShowWhenLocked
        // only reads hasCurrentTrack, not isPlaying).
        lifecycleScope.launch {
            combine(
                settingsViewModel.settings,
                playerViewModel.hasCurrentTrack
            ) { settings, hasTrack -> settings to hasTrack }.collect { (settings, hasTrack) ->
                if (!hasTrack) {
                    lockDisplaySessionActive = false
                    cancelFastLock()
                }
                applyShowWhenLocked()
                // Fast lock may be toggled mid-session: arm or disarm live.
                if (lockDisplaySessionActive && settings.fastLockScreenEnabled) {
                    restartFastLock()
                } else {
                    cancelFastLock()
                }
            }
        }

        setContent {
            val settings by settingsViewModel.settings.collectAsState()
            val onboardingCompleted by settingsViewModel.onboardingCompleted.collectAsState()
            val systemDark = isSystemInDarkTheme()
            // DataStore's first emission hasn't landed until onboardingCompleted
            // (same flow) turns non-null; before that `settings` still holds
            // defaults, so the mirror must not be clobbered with them.
            val settingsLoaded = onboardingCompleted != null

            LaunchedEffect(settings.language) {
                val appLocale: LocaleListCompat = if (settings.language == "auto") {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(settings.language)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
            }

            LaunchedEffect(settings.themeMode, settingsLoaded) {
                if (settingsLoaded) writeMirroredThemeMode(settings.themeMode)
            }

            LaunchedEffect(settings.themeMode, systemDark) {
                val dark = when (settings.themeMode) {
                    ThemeMode.FOLLOW_SYSTEM -> systemDark
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                }
                val barStyle = if (dark) {
                    SystemBarStyle.dark(TRANSPARENT)
                } else {
                    SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                AppCompatDelegate.setDefaultNightMode(
                    when (settings.themeMode) {
                        ThemeMode.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    }
                )
            }

            FoamApp(
                playerViewModel = playerViewModel,
                settingsViewModel = settingsViewModel,
                seededThemeMode = readMirroredThemeMode()
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The service only delivers this intent when starting the lock-screen
        // display (or a warm-start media-notification tap): a screen-event launch
        // opens a display session; a plain notification tap does not.
        if (intent.getBooleanExtra(LockDisplayLaunch, false)) {
            lockDisplaySessionActive = true
            applyShowWhenLocked()
            restartFastLock()
        }
        handleIntent(intent)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // Every touch/key while the lock-display session is up restarts the
        // Fast lock countdown, keeping the player visible while the user is
        // actively interacting.
        if (lockDisplaySessionActive) restartFastLock()
    }

    /**
     * Fast lock: while the app shows over the keyguard, count [FAST_LOCK_TIMEOUT_MS]
     * of no user input, then drop the task behind the lock screen (the system lock
     * screen takes over again).
     */
    private fun restartFastLock() {
        if (!settingsViewModel.settings.value.fastLockScreenEnabled) return
        fastLockJob?.cancel()
        fastLockJob = lifecycleScope.launch {
            delay(FAST_LOCK_TIMEOUT_MS)
            fastLockJob = null
            endLockDisplaySession()
        }
    }

    private fun cancelFastLock() {
        fastLockJob?.cancel()
        fastLockJob = null
    }

    private fun endLockDisplaySession() {
        lockDisplaySessionActive = false
        cancelFastLock()
        applyShowWhenLocked()
        // Send the task behind the keyguard; the system lock screen is visible again.
        moveTaskToBack(true)
    }

    @OptIn(UnstableApi::class)
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false) == true) {
            playerViewModel.requestOpenPlayer()
        }
    }

    override fun onDestroy() {
        cancelFastLock()
        runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startPlaybackService()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /** Media read permission for the running Android version, plus notifications on 13+. */
    private fun requiredPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @OptIn(UnstableApi::class)
    private fun startPlaybackService() {
        startService(Intent(this, PlaybackService::class.java))
    }
}

/** Extra on the launcher intent marking a lock-screen-display launch. */
const val LockDisplayLaunch = "com.foamplayer.app.extra.LOCK_DISPLAY_LAUNCH"

/** Root composable: applies theme, accent color and locale, then the nav host. */
@Composable
private fun FoamApp(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel,
    seededThemeMode: ThemeMode
) {
    val settings by settingsViewModel.settings.collectAsState()
    val onboardingCompleted by settingsViewModel.onboardingCompleted.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val settingsLoaded = onboardingCompleted != null
    val themeMode = if (settingsLoaded) settings.themeMode else seededThemeMode
    val darkTheme = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val albumArtPath by playerViewModel.currentAlbumArtPath.collectAsState()
    val dynamicAccentColor by playerViewModel.dynamicAccentColor.collectAsState()

    val accentColor = rememberAccentColor(
        isAutomaticColor = settings.automaticColorEnabled,
        manualAccentColor = settings.accentColor,
        dynamicAccentColor = dynamicAccentColor,
        albumArtPath = albumArtPath
    )

    CompositionLocalProvider(
        LocalAutomaticColorEnabled provides settings.automaticColorEnabled,
        LocalDefaultCollectionHeroLayout provides settings.collectionHeroLayout(CollectionHeroTarget.Default)
    ) {
        FoamTheme(
            darkTheme = darkTheme,
            iconStyle = settings.iconStyle,
            miniPlayerRounding = settings.miniPlayerRounding / 100f,
            fullPlayerRounding = settings.fullPlayerRounding / 100f,
            accentColor = accentColor
        ) {
            if (onboardingCompleted == null) {
                // Hold a blank frame while settings load, never flash onboarding over
                // an existing user's library.
                Box(modifier = Modifier.fillMaxSize().background(FoamTheme.colors.background))
            } else {
                FoamNavHost(
                    modifier = Modifier.fillMaxSize(),
                    startOnOnboarding = onboardingCompleted == false,
                    playerViewModel = playerViewModel
                )
            }
        }
    }
}

/**
 * Resolves the active accent color: the manual pick, or the palette color
 * extracted from the current album art when automatic color is on.
 */
@Composable
private fun rememberAccentColor(
    isAutomaticColor: Boolean,
    manualAccentColor: Int,
    dynamicAccentColor: Color?,
    albumArtPath: String?
): Color {
    val context = LocalContext.current
    val effectiveArtPath = if (isAutomaticColor) albumArtPath else null

    LaunchedEffect(effectiveArtPath) {
        if (effectiveArtPath != null) {
            AlbumArtPaletteCache.ensure(scope = this, context = context, path = effectiveArtPath)
        }
    }
    val extractedColor = effectiveArtPath?.let { AlbumArtPaletteCache.colors[it] }

    when {
        !isAutomaticColor -> return Color(manualAccentColor)
        dynamicAccentColor != null -> return dynamicAccentColor
        else -> {
            val animatedColor by animateColorAsState(
                targetValue = extractedColor ?: Color(manualAccentColor),
                animationSpec = tween(durationMillis = 500),
                label = "AccentColorFade"
            )
            return animatedColor
        }
    }
}
