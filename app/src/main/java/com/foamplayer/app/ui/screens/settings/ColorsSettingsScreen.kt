package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun ColorsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val isAutomaticColorEnabled = settings.automaticColorEnabled
    val accentColorInt = settings.accentColor
    val accentColor = Color(accentColorInt)

    val palette = listOf(
        Color(0xFFFF5252), // Default Red
        Color(0xFFFF4081), // Pink
        Color(0xFFE040FB), // Purple
        Color(0xFF7C4DFF), // Deep Purple
        Color(0xFF536DFE), // Indigo
        Color(0xFF448AFF), // Blue
        Color(0xFF40C4FF), // Light Blue
        Color(0xFF18FFFF), // Cyan
        Color(0xFF64FFDA), // Teal
        Color(0xFF69F0AE), // Green
        Color(0xFFB2FF59), // Light Green
        Color(0xFFEEFF41), // Lime
        Color(0xFFFFFF00), // Yellow
        Color(0xFFFFD740), // Amber
        Color(0xFFFFAB40), // Orange
        Color(0xFFFF6E40)  // Deep Orange
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .padding(horizontal = 16.dp)
    ) {
        item {
            SettingsHeader(title = "Colors", onBack = onBack)
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            BasicText(text = "System Colors", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            FoamSwitchItem(
                title = "Automatic color",
                description = "Extract accent color from album art.",
                checked = isAutomaticColorEnabled,
                onCheckedChange = viewModel::setAutomaticColorEnabled
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (!isAutomaticColorEnabled) {
            item {
                BasicText(text = "Accent Color Palette", style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Using a Column with Rows since LazyVerticalGrid inside LazyColumn is problematic 
                // without fixed height, and we want it to scroll with the list.
                val chunks = palette.chunked(4)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    chunks.forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            rowColors.forEach { color ->
                                val isSelected = color.toArgb() == accentColorInt
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(color)
                                        .border(
                                            width = if (isSelected) 3.dp else 0.dp,
                                            color = if (isSelected) Color.White else Color.Transparent,
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .clickable { viewModel.setAccentColor(color.toArgb()) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                }
                            }
                            // Fill empty slots if row is not full
                            if (rowColors.size < 4) {
                                repeat(4 - rowColors.size) {
                                    Spacer(modifier = Modifier.size(64.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
