package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val isBitPerfectEnabled = settings.bitPerfectEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = "Audio",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium)
        ) {
            item {
                BasicText(text = "Engine Settings", style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FoamDimens.paddingMedium),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            BasicText(
                                text = "Bit-Perfect Output",
                                style = FoamTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                            BasicText(
                                text = "Bypass system resampler for high-res audio on compatible USB DACs.",
                                style = FoamTheme.typography.body
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall / 2))
                                .background(
                                    if (isBitPerfectEnabled) FoamTheme.accentColor
                                    else FoamTheme.colors.surfaceVariant
                                )
                                .clickable { viewModel.setBitPerfectEnabled(!isBitPerfectEnabled) }
                                .padding(horizontal = FoamDimens.spacingMedium, vertical = FoamDimens.spacingSmall)
                        ) {
                            BasicText(
                                text = if (isBitPerfectEnabled) "ON" else "OFF",
                                style = FoamTheme.typography.caption.copy(
                                    color = if (isBitPerfectEnabled) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }
        }
    }
}
