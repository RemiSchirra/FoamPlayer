package com.foamplayer.app

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.core.content.edit

/** Single activity hosting the whole Compose UI and the media playback session. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private companion object {
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT

        /** Inactivity after which the lock-display session closes (Fast lock). */
        const val FAST_LOCK_TIMEOUT_MS = 5_000L

        /** Grace period for the keyguard state to settle after a wake (see [onLockDisplayLaunch]). */
        const val KEYGUARD_SETTLE_MS = 400L
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
        themeModePrefs.edit { putString("mode", mode.name) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Media notifications are exempt from POST_NOTIFICATIONS, so a denied
        // notification permission must NOT keep the playback service -- and
        // with it the media session and the notification -- from running. Only
        // the audio read permission gates starting the service; re-check the
        // live grant state rather than the callback map, which only contains
        // the permissions this particular launch (e.g. a re-request of
        // POST_NOTIFICATIONS alone with audio already granted).
        if (ContextCompat.checkSelfPermission(this, audioReadPermission()) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startPlaybackServiceAfterRestore()
        }
    }

    /**
     * Lock-screen display session. ONE decision point: the screen-event launch
     * from [PlaybackService] (screen wakes while a song is playing and the
     * device is locked). After that the state is fixed until an explicit close
     * trigger:
     *  - pausing while the overlay is up (the overlay is what's on screen, so
     *    any pause reaching the engine is "from the app overlay");
     *  - Fast lock: [FAST_LOCK_TIMEOUT_MS] with no user input;
     *  - unlock ([Intent.ACTION_USER_PRESENT]);
     *  - the queue emptying, or the display option being switched off.
     *
     * Playing from the lock-screen notification NEVER opens a session (no
     * screen event happens), and a screen-off does not close one.
     *
     * Closing the session must restore the surface the user had before the
     * screen locked -- exactly like the system lock screen does. Two rules
     * make that work:
     *  - the keyguard flag alone ("drop back") is done by clearing
     *    show-when-locked: the keyguard covers the app again, no task
     *    bookkeeping is touched;
     *  - when the user's own foreground app before the lock was NOT this one
     *    ([leftAppByUser]), the task is REMOVED ([finishAndRemoveTask]) so the
     *    unlock (or the next look at the phone) returns to that app.
     *    moveTaskToBack must never be used here: while the keyguard is up it
     *    parks the LAUNCHER behind it, so unlocking afterwards landed on the
     *    home screen instead of the previously open app.
     */
    private var lockDisplaySessionActive = false

    /**
     * Whether the user's foreground app before the screen locked was something
     * other than this one: true after they navigated away by choice
     * ([onUserLeaveHint] -- home, recents, another app; screen-off does NOT
     * call it), false while the app is the surface they were using. Decides
     * whether ending the lock-display session removes the task (their app
     * comes back) or keeps it (the app is what they were using).
     *
     * Delegates to the process-wide [AppForegroundState] so the service can read
     * it before deciding whether to raise the lock-screen player.
     */
    private var leftAppByUser: Boolean
        get() = !AppForegroundState.isInForeground
        set(value) { AppForegroundState.isInForeground = !value }

    /** Marks the resume that follows a lock-display launch (not a user entry). */
    private var lockLaunchInFlight = false

    /** Active Fast lock countdown; null while the timer is not running. */
    private var fastLockJob: Job? = null

    /**
     * True while the window is pre-flagged show-when-locked ahead of a wake.
     * Armed on ACTION_SCREEN_OFF (the app is the foreground surface, a song is
     * playing and the option is on) so the window is ALREADY showing over the
     * keyguard the instant the screen turns on -- instead of being started by
     * the service after the broadcast, which is what read as a delay. Cleared
     * whenever the display session closes.
     */
    private var lockDisplayPreArmed = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            // Arm for the coming wake while the screen is off, so the window is
            // in place before the screen turns on. Gated on the same conditions
            // the service uses (foreground surface, playing, option on).
            if (AppForegroundState.isInForeground &&
                settingsViewModel.settings.value.displayOnLockScreen &&
                playerViewModel.hasCurrentTrack.value &&
                (playerViewModel.isPlaying.value || playerViewModel.isPlaybackRequested.value)
            ) {
                lockDisplayPreArmed = true
                applyShowWhenLocked()
            }
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            val sessionWasActive = lockDisplaySessionActive
            lockDisplaySessionActive = false
            lockDisplayPreArmed = false
            cancelFastLock()
            applyShowWhenLocked()
            // Unlocking hands the screen back to whatever the user was doing
            // before the lock-display session: when that was another app (the
            // player was a glanceable overlay, like the system lock screen),
            // remove the task so the unlock lands there instead of leaving the
            // player in front.
            if (sessionWasActive && leftAppByUser) dismissLockDisplayTask()
        }
    }

    /**
     * Mirrors the display state onto the window. The session opens after the
     * service verified the keyguard / setting / track, and the pre-arm is set on
     * screen-off while the same conditions hold, so either flag marks the window
     * show-when-locked -- immediately, without waiting for the asynchronously
     * loaded settings/track flows (which would leave the first frames behind the
     * keyguard and read as a delay).
     */
    private fun applyShowWhenLocked() {
        setShowWhenLocked(lockDisplaySessionActive || lockDisplayPreArmed)
    }

    /** True while the keyguard is showing (device locked). */
    private fun isDeviceLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /**
     * Removes this task without an animation, restoring the system's previous
     * top task behind it (the user's app, or the keyguard itself when still
     * locked). Never use moveTaskToBack for this: behind a keyguard it resumes
     * the launcher, so the next unlock landed on the home screen.
     */
    private fun dismissLockDisplayTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        finishAndRemoveTask()
    }

    /** Common path for a screen-event launch: opens (or re-arms) the session. */
    private fun onLockDisplayLaunch() {
        lockLaunchInFlight = true
        lockDisplayPreArmed = false
        lockDisplaySessionActive = true
        applyShowWhenLocked()
        restartFastLock()
        if (!isDeviceLocked()) {
            lifecycleScope.launch {
                delay(KEYGUARD_SETTLE_MS)
                if (lockDisplaySessionActive && !isDeviceLocked()) {
                    lockDisplaySessionActive = false
                    cancelFastLock()
                    applyShowWhenLocked()
                    if (leftAppByUser) dismissLockDisplayTask()
                }
            }
        }
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
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // The initial intent is handled HERE, before the window draws: a cold
        // start straight over the keyguard (the service revived the process)
        // must show over the lock screen from the first frame, and the
        // show-when-locked flag set below is what puts it there. onNewIntent
        // only covers the warm start.
        handleIntent(intent)
        if (intent?.getBooleanExtra(LockDisplayLaunch, false) == true) {
            // A cold start with the launch extra is service-initiated: the
            // service only sends it while the app is in the foreground (see
            // [AppForegroundState]), so [leftAppByUser] already holds the right
            // value and must not be clobbered here.
            onLockDisplayLaunch()
        }
        // Permissions are requested on onboarding; afterwards they're auto-requested once.
        lifecycleScope.launch {
            settingsViewModel.onboardingCompleted.collect { completed ->
                if (completed == true) checkAndRequestPermissions()
            }
        }
        // Re-evaluate the keyguard flag when its inputs move: a launched session
        // with the option off, or an emptied queue, ends it. A pause while the
        // session is up is handled by the dedicated collector below (buffering
        // must not count).
        lifecycleScope.launch {
            combine(
                settingsViewModel.settings,
                playerViewModel.hasCurrentTrack
            ) { settings, hasTrack -> settings to hasTrack }.collect { (settings, hasTrack) ->
                val settingsLoaded = settingsViewModel.onboardingCompleted.value != null
                if (!hasTrack) {
                    endLockDisplaySession()
                } else if (settingsLoaded && lockDisplaySessionActive && !settings.displayOnLockScreen) {
                    // The display option was switched off mid-session.
                    endLockDisplaySession()
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
        // Pause while the lock-display session is up closes it: the app IS the
        // screen (showing over the keyguard), so every pause that reaches the
        // engine is "from the app overlay". Gated on playWhenReady, NOT on
        // isPlaying — isPlaying flickers false while the player is buffering
        // between tracks (playWhenReady stays true), and that must never drop
        // the overlay mid-transition with no way back until the next wake.
        lifecycleScope.launch {
            playerViewModel.isPlaying.collect { playing ->
                if (!playing && lockDisplaySessionActive &&
                    !playerViewModel.isPlaybackRequested.value
                ) {
                    endLockDisplaySession()
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

    override fun onStart() {
        super.onStart()
        startPlaybackServiceAfterRestore()
    }

    /**
     * Starts [PlaybackService] only after the startup queue restore
     * (PlayerViewModel.init) has settled: with a saved session, starting the
     * service before the engine rebuilt the queue would present an EMPTY
     * session. Once settled, the service either finds the restored queue
     * (media button + notification controls recover, paused) or nothing
     * (fresh install / cleared queue).
     */
    private fun startPlaybackServiceAfterRestore() {
        lifecycleScope.launch {
            playerViewModel.sessionRestoreSettled.first { it }
            if (playerViewModel.hasCurrentTrack.value) {
                startPlaybackService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        when {
            // The resume of a lock-display launch: the system brought the app
            // forward, the user did not choose it -- keep [leftAppByUser] as-is.
            lockLaunchInFlight -> lockLaunchInFlight = false
            // A resume while the keyguard is up is the lock-display surface
            // becoming visible again (a wake of an ongoing session): no user
            // entry, keep the flag. Any other resume is the user entering the
            // app by choice (icon, notification, recents): the app is their
            // foreground surface again.
            !isDeviceLocked() -> leftAppByUser = false
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        leftAppByUser = true
        // Pre-arm for the wake
        if (lockDisplayPreArmed) {
            lockDisplayPreArmed = false
            applyShowWhenLocked()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isDeviceLocked()) leftAppByUser = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The service only delivers this intent when starting the lock-screen
        // display (or a warm-start media-notification tap): a screen-event
        // launch opens a display session; a plain notification tap does not.
        if (intent.getBooleanExtra(LockDisplayLaunch, false)) {
            onLockDisplayLaunch()
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

    /** True while a volume key the app took over is held, so its ACTION_UP is swallowed too. */
    private var volumeKeyHandled = false

    /**
     * Hardware volume keys belong to whatever plays the sound, not to the phone:
     * while casting they move the receiver's own volume, and while the phone's
     * output is pinned in front of an attached Bluetooth headset they move the
     * phone speaker (see [PlayerViewModel.adjustOutputVolume]). Everything else is
     * left to the platform, which already targets the playing device and shows its
     * volume panel.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val volumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volumeKey) {
            if (event.action == KeyEvent.ACTION_UP && volumeKeyHandled) {
                volumeKeyHandled = false
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                volumeKeyHandled = playerViewModel.adjustOutputVolume(
                    up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
                )
                if (volumeKeyHandled) return true
            }
        }
        return super.dispatchKeyEvent(event)
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
        val sessionWasActive = lockDisplaySessionActive
        lockDisplaySessionActive = false
        lockDisplayPreArmed = false
        cancelFastLock()
        applyShowWhenLocked()
        // Clearing show-when-locked is enough for the system lock screen to
        // take over. When the user's own foreground app before the lock was
        // something else, the task is removed as well so the next unlock lands
        // in THAT app instead of leaving this one in front (and instead of
        // parking the launcher, which is what moveTaskToBack did).
        if (sessionWasActive && leftAppByUser && isDeviceLocked()) {
            dismissLockDisplayTask()
        }
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
        runCatching { unregisterReceiver(screenStateReceiver) }
        super.onDestroy()
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startPlaybackServiceAfterRestore()
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

    /** The media read permission that gates playback (and thus the service) on this OS level. */
    private fun audioReadPermission(): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }

    @OptIn(UnstableApi::class)
    private fun startPlaybackService() {
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
    }
}

/** Extra on the launcher intent marking a lock-screen-display launch. */
const val LockDisplayLaunch = "com.foamplayer.app.extra.LOCK_DISPLAY_LAUNCH"

/**
 * Process-wide foreground flag shared by [MainActivity] and
 * [com.foamplayer.app.audio.service.PlaybackService]. True while the user's
 * current foreground surface is this app: set on a real user entry (onResume
 * with the keyguard down) and cleared when they navigate away
 * (onUserLeaveHint). Screen-off neither sets nor clears it, and it survives
 * activity recreation, so the service can tell "the app was on screen when the
 * phone locked" (raise the lock-screen player) from "the app was in the
 * background" (leave the system lock screen untouched).
 */
object AppForegroundState {
    @Volatile
    var isInForeground: Boolean = false
}

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
