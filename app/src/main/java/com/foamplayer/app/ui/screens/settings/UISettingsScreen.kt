package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.settings.FoamIconStyle
import com.foamplayer.app.ui.components.FoamNavigationButton
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun UISettingsScreen(
    onBack: () -> Unit,
    onNavigateToColors: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onNavigateToNavBar: () -> Unit,
    onNavigateToBackground: () -> Unit,
    onNavigateToCollections: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val iconStyle = settings.iconStyle

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.user_interface),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
                    BasicText(text = stringResource(R.string.appearance), style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                    ) {
                        Column {
                            FoamNavigationButton(
                                title = stringResource(R.string.colors),
                                description = stringResource(R.string.colors_desc),
                                onClick = onNavigateToColors,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .background(FoamTheme.colors.borderOverlay)
                            )
                            FoamNavigationButton(
                                title = stringResource(R.string.player),
                                description = stringResource(R.string.player_desc),
                                onClick = onNavigateToPlayer,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .background(FoamTheme.colors.borderOverlay)
                            )
                            FoamNavigationButton(
                                title = stringResource(R.string.navigation_bar),
                                description = stringResource(R.string.navbar_desc),
                                onClick = onNavigateToNavBar,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .background(FoamTheme.colors.borderOverlay)
                            )
                            FoamNavigationButton(
                                title = stringResource(R.string.background),
                                description = stringResource(R.string.background_desc),
                                onClick = onNavigateToBackground,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .background(FoamTheme.colors.borderOverlay)
                            )
                            FoamNavigationButton(
                                title = stringResource(R.string.collections),
                                description = stringResource(R.string.collections_desc),
                                onClick = onNavigateToCollections,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
                    BasicText(text = stringResource(R.string.language), style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    LanguageSelector(
                        currentLanguage = settings.language,
                        onLanguageSelected = { viewModel.setLanguage(it) }
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
                    BasicText(text = stringResource(R.string.icon_style), style = FoamTheme.typography.titleMedium)
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
}

@Composable
private fun LanguageSelector(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val languages = listOf(
        "auto" to "Automatic",
        "en" to "English",
        "fr" to "Français"
    )
    val currentLabel = languages.find { it.first == currentLanguage }?.second ?: languages[0].second

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
            .clickable { expanded = true }
            .padding(FoamDimens.paddingMedium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(text = currentLabel, style = FoamTheme.typography.body)
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = FoamTheme.colors.textSecondary
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(FoamTheme.colors.surface)
                .width(IntrinsicSize.Max)
        ) {
            languages.forEach { (code, label) ->
                DropdownMenuItem(onClick = {
                    onLanguageSelected(code)
                    expanded = false
                }) {
                    BasicText(
                        text = label,
                        style = FoamTheme.typography.body.copy(
                            color = if (code == currentLanguage) FoamTheme.accentColor else FoamTheme.colors.textPrimary
                        )
                    )
                }
            }
        }
    }
}
