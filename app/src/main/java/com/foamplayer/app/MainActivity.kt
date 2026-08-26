package com.foamplayer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.audio.service.PlaybackService
import com.foamplayer.app.ui.navigation.FoamNavHost
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.settings.SettingsViewModel
import com.foamplayer.app.ui.theme.FoamIconStyle
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.extractPaletteColor
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            startPlaybackService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val playerViewModel: PlayerViewModel = hiltViewModel()

            val iconStyle by settingsViewModel.iconStyle.collectAsState(initial = FoamIconStyle.FLAT)
            val miniRounding by settingsViewModel.miniPlayerRounding.collectAsState(initial = 8)
            val fullRounding by settingsViewModel.fullPlayerRounding.collectAsState(initial = 24)
            val isAutomaticColor by settingsViewModel.isAutomaticColorEnabled.collectAsState(initial = true)
            val manualAccentColor by settingsViewModel.accentColor.collectAsState(initial = 0xFFFF5252.toInt())

            val playbackState by playerViewModel.playbackState.collectAsState()
            val currentTrack = playbackState.currentTrack

            val dynamicAccentColor by playerViewModel.dynamicAccentColor.collectAsState()

            val context = LocalContext.current
            var extractedColor by remember { mutableStateOf<Color?>(null) }

            LaunchedEffect(currentTrack?.albumArtPath, isAutomaticColor) {
                extractedColor = if (isAutomaticColor && currentTrack?.albumArtPath != null) {
                    extractPaletteColor(context, currentTrack.albumArtPath)
                } else {
                    null
                }
            }

            val animatedManualColor by animateColorAsState(
                targetValue = Color(manualAccentColor),
                animationSpec = tween(durationMillis = 500),
                label = "ManualColorFade"
            )

            val baseTargetColor = extractedColor ?: Color(manualAccentColor)
            val animatedBaseColor by animateColorAsState(
                targetValue = baseTargetColor,
                animationSpec = tween(durationMillis = 500),
                label = "BaseColorFade"
            )

            val themeAccentColor = if (isAutomaticColor) {
                dynamicAccentColor ?: animatedBaseColor
            } else {
                animatedManualColor
            }

            FoamTheme(
                iconStyle = iconStyle,
                miniPlayerRounding = miniRounding.dp,
                fullPlayerRounding = fullRounding.dp,
                accentColor = themeAccentColor
            ) {
                FoamNavHost(modifier = Modifier.fillMaxSize())
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startPlaybackService()
        }
    }

    private fun startPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        startService(intent)
    }
}