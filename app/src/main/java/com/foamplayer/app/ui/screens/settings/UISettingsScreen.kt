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

@Composable
fun UISettingsScreen(
    onBack: () -> Unit,
    onNavigateToColors: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val iconStyle by viewModel.iconStyle.collectAsState()

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
            BasicText(text = "Player", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            FoamGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToPlayer() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(text = "Rounding, Layout, & Visuals", style = FoamTheme.typography.body)
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
    }
}
