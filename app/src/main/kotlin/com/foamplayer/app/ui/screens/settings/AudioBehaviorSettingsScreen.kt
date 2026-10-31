package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamBackground
import com.foamplayer.app.ui.theme.foamBorderOverlay
import com.foamplayer.app.ui.theme.foamSurface

/**
 * Behavior settings (subscreen of Settings > Audio): automatic playback
 * resumption on app start and on output-device connection, plus automatic
 * pausing when the output device in use is disconnected.
 */
@Composable
fun AudioBehaviorSettingsScreen(
    onBack: () -> Unit,
    viewModel: AudioSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .foamBackground()
    ) {
        SettingsHeader(
            title = stringResource(R.string.audio_behavior),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            item {
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .foamSurface()
                ) {
                    Column {
                        FoamSwitchItem(
                            title = stringResource(R.string.resume_on_startup),
                            description = stringResource(R.string.resume_on_startup_desc),
                            checked = settings.resumeOnStartup,
                            onCheckedChange = { viewModel.setResumeOnStartup(it) }
                        )
                        SectionDivider()
                        FoamSwitchItem(
                            title = stringResource(R.string.resume_on_device_connected),
                            description = stringResource(R.string.resume_on_device_connected_desc),
                            checked = settings.resumeOnDeviceConnected,
                            onCheckedChange = { viewModel.setResumeOnDeviceConnected(it) }
                        )
                        SectionDivider()
                        FoamSwitchItem(
                            title = stringResource(R.string.pause_on_device_disconnected),
                            description = stringResource(R.string.pause_on_device_disconnected_desc),
                            checked = settings.pauseOnDeviceDisconnected,
                            onCheckedChange = { viewModel.setPauseOnDeviceDisconnected(it) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.paddingExtraLarge))
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .foamBorderOverlay()
    )
}
