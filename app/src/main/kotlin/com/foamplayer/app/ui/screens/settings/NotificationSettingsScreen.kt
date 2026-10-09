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
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.settings.NotificationExtraButton
import com.foamplayer.app.ui.components.FoamEnumItem
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Subscreen of Settings > Misc: picks which two actions sit next to the fixed
 * Previous / Play-Pause / Next controls of the playback notification.
 */
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: MiscSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.notification_section),
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
                BasicText(
                    text = stringResource(R.string.notification_buttons_desc),
                    style = FoamTheme.typography.body
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                FoamEnumItem(
                    title = stringResource(R.string.notification_button_left),
                    description = stringResource(R.string.notification_button_left_desc),
                    options = NotificationExtraButton.entries.toTypedArray(),
                    selectedOption = settings.notificationButtonLeft,
                    onOptionSelected = { viewModel.setNotificationButtonLeft(it) },
                    getLabel = { option -> optionLabel(option) },
                    getIcon = { option -> optionIcon(option) }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                FoamEnumItem(
                    title = stringResource(R.string.notification_button_right),
                    description = stringResource(R.string.notification_button_right_desc),
                    options = NotificationExtraButton.entries.toTypedArray(),
                    selectedOption = settings.notificationButtonRight,
                    onOptionSelected = { viewModel.setNotificationButtonRight(it) },
                    getLabel = { option -> optionLabel(option) },
                    getIcon = { option -> optionIcon(option) }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }
        }
    }
}

@Composable
private fun optionLabel(option: NotificationExtraButton): String = when (option) {
    NotificationExtraButton.NONE -> stringResource(R.string.notification_button_none)
    NotificationExtraButton.REPEAT -> stringResource(R.string.notification_button_repeat)
    NotificationExtraButton.SHUFFLE -> stringResource(R.string.notification_button_shuffle)
    NotificationExtraButton.CLOSE -> stringResource(R.string.notification_button_close)
    NotificationExtraButton.SCORE -> stringResource(R.string.notification_button_score)
}

private fun optionIcon(option: NotificationExtraButton): ImageVector = when (option) {
    NotificationExtraButton.NONE -> Icons.Rounded.Block
    NotificationExtraButton.REPEAT -> Icons.Rounded.Repeat
    NotificationExtraButton.SHUFFLE -> Icons.Rounded.Shuffle
    NotificationExtraButton.CLOSE -> Icons.Rounded.Close
    NotificationExtraButton.SCORE -> Icons.Rounded.Favorite
}