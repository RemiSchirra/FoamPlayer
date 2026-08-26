package com.foamplayer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.foamplayer.app.audio.service.PlaybackService
import com.foamplayer.app.ui.navigation.FoamNavHost
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.settings.SettingsViewModel
import com.foamplayer.app.ui.theme.FoamIconStyle
import com.foamplayer.app.ui.theme.FoamTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

            val context = LocalContext.current
            var extractedColor by remember { mutableStateOf<Color?>(null) }

            LaunchedEffect(currentTrack?.albumArtPath, isAutomaticColor) {
                if (isAutomaticColor && currentTrack?.albumArtPath != null) {
                    withContext(Dispatchers.IO) {
                        val request = ImageRequest.Builder(context)
                            .data(currentTrack.albumArtPath)
                            .allowHardware(false) // Palette needs a software bitmap
                            .build()

                        val result = context.imageLoader.execute(request)
                        if (result is SuccessResult) {
                            val bitmap = (result.drawable as BitmapDrawable).bitmap
                            val palette = Palette.from(bitmap).generate()

                            // Prefer vibrant swatches for an accent color
                            val swatch = palette.vibrantSwatch
                                ?: palette.lightVibrantSwatch
                                ?: palette.mutedSwatch
                                ?: palette.dominantSwatch

                            swatch?.let { s ->
                                val colorInt = s.rgb
                                val hsl = FloatArray(3)
                                ColorUtils.colorToHSL(colorInt, hsl)

                                // Avoid colors that are too dark (L < 0.4)
                                if (hsl[2] < 0.4f) {
                                    hsl[2] = 0.5f
                                }

                                // Also ensure it has some saturation to act as an accent (S < 0.2)
                                if (hsl[1] < 0.2f) {
                                    hsl[1] = 0.4f
                                }

                                val finalColor = Color(ColorUtils.HSLToColor(hsl))
                                withContext(Dispatchers.Main) {
                                    extractedColor = finalColor
                                }
                            }
                        }
                    }
                } else {
                    extractedColor = null
                }
            }

            val themeAccentColor = if (isAutomaticColor) {
                // If auto is on but we haven't extracted or have no track, fallback to manual
                extractedColor ?: Color(manualAccentColor)
            } else {
                Color(manualAccentColor)
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
