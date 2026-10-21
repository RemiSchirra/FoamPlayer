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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.FoamNavigationButton
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun MiscSettingsScreen(
    onBack: () -> Unit,
    onNavigateToNotification: () -> Unit,
    onNavigateToLastFm: () -> Unit,
    onNavigateToDiscord: () -> Unit,
    viewModel: MiscSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    // Animate show/hide only after a user toggle; first composition renders instantly.
    var animateChanges by remember { mutableStateOf(false) }

    // Re-checked on ON_RESUME so returning from the system permission screen
    // reflects the overlay state immediately.
    var overlayGranted by remember { mutableStateOf(viewModel.canDrawOverlays(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = viewModel.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.settings_misc),
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    FoamNavigationButton(
                        title = stringResource(R.string.notification_entry),
                        description = stringResource(R.string.notification_entry_desc),
                        onClick = onNavigateToNotification
                    )
                }

                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                BasicText(
                    text = stringResource(R.string.lock_screen_section),
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
                        title = stringResource(R.string.display_on_lock_screen),
                        description = stringResource(R.string.display_on_lock_screen_desc),
                        checked = settings.displayOnLockScreen,
                        onCheckedChange = {
                            animateChanges = true
                            viewModel.setDisplayOnLockScreen(it)
                        }
                    )
                }
                SettingsExpandVisibility(
                    visible = settings.displayOnLockScreen && !overlayGranted,
                    animate = animateChanges
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FoamDimens.spacingSmall)
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                            .padding(FoamDimens.paddingMedium)
                    ) {
                        BasicText(
                            text = stringResource(R.string.lock_screen_overlay_hint),
                            style = FoamTheme.typography.body
                        )
                        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                        SettingsActionButton(
                            label = stringResource(R.string.lock_screen_open_permission),
                            onClick = { viewModel.openOverlaySettings(context) }
                        )
                    }
                }
                SettingsExpandVisibility(
                    visible = settings.displayOnLockScreen,
                    animate = animateChanges
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FoamDimens.spacingSmall)
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                    ) {
                        FoamSwitchItem(
                            title = stringResource(R.string.fast_lock_screen),
                            description = stringResource(R.string.fast_lock_screen_desc),
                            checked = settings.fastLockScreenEnabled,
                            onCheckedChange = {
                                animateChanges = true
                                viewModel.setFastLockScreenEnabled(it)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                BasicText(
                    text = stringResource(R.string.integrations_section),
                    style = FoamTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    FoamNavigationButton(
                        title = stringResource(R.string.lastfm_entry),
                        description = stringResource(R.string.lastfm_entry_desc),
                        onClick = onNavigateToLastFm
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FoamDimens.paddingMedium)
                            .height(FoamDimens.separatorHeight)
                            .background(FoamTheme.colors.borderOverlay)
                    )
                    FoamNavigationButton(
                        title = stringResource(R.string.discord_entry),
                        description = stringResource(R.string.discord_entry_desc),
                        onClick = onNavigateToDiscord
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }
        }
    }
}
