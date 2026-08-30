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
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun SettingsScreen(
    onNavigateToAudio: () -> Unit,
    onNavigateToUI: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToTest: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            BasicText(
                text = "Settings",
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            SettingsCategoryItem(
                title = "Audio",
                subtitle = "Bit-perfect, engine options",
                icon = FoamTheme.icons.current.equalizer,
                onClick = onNavigateToAudio
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            SettingsCategoryItem(
                title = "User Interface",
                subtitle = "Icon styles, rounding, themes",
                icon = FoamTheme.icons.current.settings,
                onClick = onNavigateToUI
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            SettingsCategoryItem(
                title = "Library",
                subtitle = "Manage folders, scan media",
                icon = FoamTheme.icons.current.library,
                onClick = onNavigateToLibrary
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            SettingsCategoryItem(
                title = "Components Test",
                subtitle = "Test UI components (Slider, Switch)",
                icon = Icons.Default.BugReport,
                onClick = onNavigateToTest
            )
            Spacer(modifier = Modifier.height(12.dp))
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
    FoamGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = FoamTheme.colors.primaryAccent
            )
            Spacer(modifier = Modifier.width(16.dp))
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
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = FoamTheme.colors.textPrimary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        BasicText(text = title, style = FoamTheme.typography.titleLarge)
    }
}
