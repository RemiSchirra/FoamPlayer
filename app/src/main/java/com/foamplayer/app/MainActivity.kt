package com.foamplayer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.media3.common.util.UnstableApi
import com.foamplayer.app.audio.service.PlaybackService
import com.foamplayer.app.ui.navigation.FoamNavHost
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.settings.SettingsViewModel
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.LocalAutomaticColorEnabled
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startPlaybackService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system status bar and gesture/navigation bar so the
        // album-art backdrop can cover the full screen. Compose content re-adds
        // the insets as padding (statusBarsPadding / navigationBarsPadding) where
        // interactive elements need them, so their effective padding is unchanged.
        enableEdgeToEdge()
        checkAndRequestPermissions()
        handleIntent(intent)

        setContent {
            val settings by settingsViewModel.settings.collectAsState()

            LaunchedEffect(settings.language) {
                val appLocale: LocaleListCompat = if (settings.language == "auto") {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(settings.language)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
            }

            FoamApp(playerViewModel = playerViewModel, settingsViewModel = settingsViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @OptIn(UnstableApi::class)
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false) == true) {
            playerViewModel.requestOpenPlayer()
        }
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

@Composable
private fun FoamApp(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel
) {
    val settings by settingsViewModel.settings.collectAsState()

    val playbackState by playerViewModel.playbackState.collectAsState()
    val dynamicAccentColor by playerViewModel.dynamicAccentColor.collectAsState()

    val accentColor = rememberAccentColor(
        isAutomaticColor = settings.automaticColorEnabled,
        manualAccentColor = settings.accentColor,
        dynamicAccentColor = dynamicAccentColor,
        albumArtPath = playbackState.currentTrack?.albumArtPath
    )

    CompositionLocalProvider(
        LocalAutomaticColorEnabled provides settings.automaticColorEnabled
    ) {
        FoamTheme(
            iconStyle = settings.iconStyle,
            miniPlayerRounding = settings.miniPlayerRounding / 100f,
            fullPlayerRounding = settings.fullPlayerRounding / 100f,
            accentColor = accentColor
        ) {
            FoamNavHost(
                modifier = Modifier.fillMaxSize(),
                playerViewModel = playerViewModel
            )
        }
    }
}

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
