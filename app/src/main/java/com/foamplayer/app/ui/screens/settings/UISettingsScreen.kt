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
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.data.settings.FoamIconStyle
import com.foamplayer.app.ui.components.FoamNavigationButton
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun UISettingsScreen(
    onBack: () -> Unit,
    onNavigateToColors: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onNavigateToBackground: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val iconStyle = settings.iconStyle

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        item {
            Box(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
                SettingsHeader(title = "User Interface", onBack = onBack)
            }
            Spacer(modifier = Modifier.height(FoamDimens.spacingStandard))
        }

        item {
            Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
                BasicText(text = "Appearance", style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    Column {
                        FoamNavigationButton(
                            title = "Colors",
                            description = "Accent color and palette",
                            onClick = onNavigateToColors,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FoamDimens.separatorHeight)
                                .background(FoamTheme.colors.borderOverlay)
                        )
                        FoamNavigationButton(
                            title = "Player",
                            description = "Layout and visuals",
                            onClick = onNavigateToPlayer,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(FoamDimens.separatorHeight)
                                .background(FoamTheme.colors.borderOverlay)
                        )
                        FoamNavigationButton(
                            title = "Background",
                            description = "Dynamic background effects",
                            onClick = onNavigateToBackground,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
        }

        item {
            Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
                BasicText(text = "Icon Style", style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
                ) {
                    FoamIconStyle.entries.forEach { style ->
                        val isSelected = style == iconStyle
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                                .background(
                                    if (isSelected) FoamTheme.accentColor
                                    else FoamTheme.colors.surface
                                )
                                .clickable { viewModel.setIconStyle(style) }
                                .padding(vertical = FoamDimens.paddingMedium),
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
                                    modifier = Modifier.size(FoamDimens.iconSmall),
                                    tint = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
                                )
                                Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                                BasicText(
                                    text = style.label,
                                    style = FoamTheme.typography.body.copy(
                                        color = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
        }
    }
}
