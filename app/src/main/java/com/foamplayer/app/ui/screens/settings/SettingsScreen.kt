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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun SettingsScreen(
    onNavigateToAudio: () -> Unit,
    onNavigateToUI: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    onNavigateToTest: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .padding(horizontal = FoamDimens.paddingMedium)
    ) {
        item {
            Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            BasicText(
                text = "Settings",
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
        }

        item {
            SettingsCategoryItem(
                title = "Audio",
                subtitle = "Bit-perfect engine and output options",
                icon = FoamTheme.icons.current.equalizer,
                onClick = onNavigateToAudio
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
        }

        item {
            SettingsCategoryItem(
                title = "User Interface",
                subtitle = "Appearance, icon styles and rounding",
                icon = FoamTheme.icons.current.settings,
                onClick = onNavigateToUI
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
        }

        item {
            SettingsCategoryItem(
                title = "Library",
                subtitle = "Manage music folders and metadata",
                icon = FoamTheme.icons.current.library,
                onClick = onNavigateToLibrary
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
        }

        item {
            SettingsCategoryItem(
                title = "Lyrics",
                subtitle = "Lyrics providers and display settings",
                icon = FoamTheme.icons.current.lyrics,
                onClick = onNavigateToLyrics
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
        }

        item {
            SettingsCategoryItem(
                title = "Components Test",
                subtitle = "Internal UI testing utilities",
                icon = Icons.Default.BugReport,
                onClick = onNavigateToTest
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
        }
    }
}

@Composable
fun SettingsCategoryItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusMedium))
            .background(FoamTheme.colors.surface)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FoamDimens.paddingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(FoamDimens.iconMedium),
                tint = FoamTheme.accentColor
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingStandard))
            Column {
                BasicText(text = title, style = FoamTheme.typography.titleMedium)
                BasicText(text = subtitle, style = FoamTheme.typography.body)
            }
        }
    }
}

@Composable
fun SettingsHeader(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FoamDimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(FoamDimens.headerIconSize)
                .clip(RoundedCornerShape(FoamDimens.headerIconRounding))
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = FoamTheme.colors.textPrimary
            )
        }
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        BasicText(text = title, style = FoamTheme.typography.titleLarge)
    }
}
