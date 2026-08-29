package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt

@Composable
fun PlayerSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val miniRounding by viewModel.miniPlayerRounding.collectAsState()
    val fullRounding by viewModel.fullPlayerRounding.collectAsState()
    val isImmersiveEnabled by viewModel.isImmersiveModeEnabled.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .padding(horizontal = 16.dp)
    ) {
        item {
            SettingsHeader(title = "Player Settings", onBack = onBack)
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            BasicText(text = "Visuals", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(text = "Immersive Mode", style = FoamTheme.typography.body)
                        BasicText(
                            text = "Expand album art backdrop to fill the screen",
                            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
                        )
                    }
                    Switch(
                        checked = isImmersiveEnabled,
                        onCheckedChange = { viewModel.setImmersiveModeEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = FoamTheme.colors.primaryAccent,
                            checkedTrackColor = FoamTheme.colors.primaryAccent.copy(alpha = 0.5f),
                            uncheckedThumbColor = FoamTheme.colors.textSecondary,
                            uncheckedTrackColor = FoamTheme.colors.surfaceVariant
                        )
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            BasicText(text = "Image Rounding", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BasicText(text = "Mini Player", style = FoamTheme.typography.body)
                            BasicText(text = "${miniRounding}dp", style = FoamTheme.typography.monospace)
                        }
                        Slider(
                            value = miniRounding.toFloat(),
                            onValueChange = { viewModel.setMiniPlayerRounding(it.roundToInt()) },
                            valueRange = 0f..32f,
                            steps = 7,
                            colors = SliderDefaults.colors(
                                thumbColor = FoamTheme.colors.primaryAccent,
                                activeTrackColor = FoamTheme.colors.primaryAccent,
                                inactiveTrackColor = FoamTheme.colors.surfaceVariant
                            )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BasicText(text = "Full Player", style = FoamTheme.typography.body)
                            BasicText(text = "${fullRounding}dp", style = FoamTheme.typography.monospace)
                        }
                        Slider(
                            value = fullRounding.toFloat(),
                            onValueChange = { viewModel.setFullPlayerRounding(it.roundToInt()) },
                            valueRange = 0f..64f,
                            steps = 7,
                            colors = SliderDefaults.colors(
                                thumbColor = FoamTheme.colors.primaryAccent,
                                activeTrackColor = FoamTheme.colors.primaryAccent,
                                inactiveTrackColor = FoamTheme.colors.surfaceVariant
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
