package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.components.FoamNavigationButton
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun LyricsSettingsScreen(
    onBack: () -> Unit,
    onNavigateToProviders: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsHeader(title = "Lyrics", onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))

        FoamNavigationButton(
            title = "Lyrics providers",
            description = "Enable and reorder providers",
            onClick = onNavigateToProviders
        )
    }
}
