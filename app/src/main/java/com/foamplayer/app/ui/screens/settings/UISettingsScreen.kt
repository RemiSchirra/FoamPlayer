package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.theme.FoamIconStyle
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt

@Composable
fun UISettingsScreen(
    onBack: () -> Unit,
    onNavigateToColors: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val iconStyle by viewModel.iconStyle.collectAsState()
    val miniRounding by viewModel.miniPlayerRounding.collectAsState()
    val fullRounding by viewModel.fullPlayerRounding.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .padding(horizontal = 16.dp)
    ) {
        item {
            SettingsHeader(title = "User Interface", onBack = onBack)
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            BasicText(text = "Colors", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            FoamGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToColors() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(text = "Accent Colors & Themes", style = FoamTheme.typography.body)
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = FoamTheme.colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            BasicText(text = "Icon Style", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FoamIconStyle.entries.forEach { style ->
                    val isSelected = style == iconStyle
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) FoamTheme.colors.primaryAccent
                                else FoamTheme.colors.surfaceVariant
                            )
                            .clickable { viewModel.setIconStyle(style) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val iconSet = when (style) {
                                FoamIconStyle.SOLID -> FoamTheme.icons.solid
                                FoamIconStyle.OUTLINE -> FoamTheme.icons.outline
                                FoamIconStyle.FLAT -> FoamTheme.icons.flat
                            }
                            Icon(
                                imageVector = iconSet.play,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            BasicText(
                                text = style.label,
                                style = FoamTheme.typography.body.copy(
                                    color = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
                                )
                            )
                        }
                    }
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
