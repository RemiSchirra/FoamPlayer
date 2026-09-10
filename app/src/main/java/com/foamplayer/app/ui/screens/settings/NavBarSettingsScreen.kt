package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.ui.components.FoamEnumItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun NavBarSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.navigation_bar),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                FoamEnumItem(
                    title = stringResource(R.string.navbar_mode),
                    description = stringResource(R.string.navbar_mode_desc),
                    options = NavigationBarMode.entries.toTypedArray(),
                    selectedOption = settings.navigationBarMode,
                    onOptionSelected = { viewModel.setNavigationBarMode(it) },
                    getLabel = { mode ->
                        when (mode) {
                            NavigationBarMode.DOCKED -> stringResource(R.string.navbar_mode_docked)
                            NavigationBarMode.FLOATING -> stringResource(R.string.navbar_mode_floating)
                            NavigationBarMode.ATTACHED -> stringResource(R.string.navbar_mode_attached)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
                    BasicText(
                        text = stringResource(R.string.transparency),
                        style = FoamTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                    ) {
                        FoamSwitchItem(
                            title = stringResource(R.string.transparent_when_player_open),
                            description = stringResource(R.string.transparent_when_player_open_desc),
                            checked = settings.navBarTransparentWhenPlayerOpen,
                            onCheckedChange = { viewModel.setNavBarTransparentWhenPlayerOpen(it) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }
        }
    }
}
