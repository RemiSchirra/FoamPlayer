package com.foamplayer.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamBackground
import com.foamplayer.app.ui.theme.foamSurface
import kotlinx.coroutines.delay

/**
 * Settings > Misc > Discord: account connection, Rich Presence and online
 * cover art options.
 */
@Composable
fun DiscordSettingsScreen(
    onBack: () -> Unit,
    viewModel: DiscordSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val session by viewModel.session.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val context = LocalContext.current

    val isDiscordEnabled = settings.discordPresenceEnabled
    val isLoggedIn = session != null

    // Animate show/hide only after a user toggle; first composition renders instantly.
    var animateChanges by remember { mutableStateOf(false) }

    BackHandler(enabled = authState is DiscordAuthUiState.Waiting) {
        viewModel.cancelAuthorization()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .foamBackground()
    ) {
        SettingsHeader(
            title = stringResource(R.string.discord_section),
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
                        .foamSurface()
                ) {
                    FoamSwitchItem(
                        title = stringResource(R.string.discord_presence),
                        description = stringResource(R.string.discord_presence_desc),
                        checked = isDiscordEnabled,
                        onCheckedChange = {
                            animateChanges = true
                            viewModel.setDiscordPresenceEnabled(it)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                SettingsExpandVisibility(
                    visible = isDiscordEnabled,
                    animate = animateChanges
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                                .foamSurface()
                        ) {
                            if (isLoggedIn) {
                                val account = session?.account
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(FoamDimens.paddingMedium)
                                ) {
                                    BasicText(
                                        text = stringResource(
                                            R.string.discord_logged_in_as,
                                            account?.displayName
                                                ?.takeIf { it.isNotBlank() }
                                                ?: account?.username.orEmpty()
                                        ),
                                        style = FoamTheme.typography.titleMedium
                                    )
                                    account?.username?.takeIf { it.isNotBlank() }?.let { username ->
                                        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                                        BasicText(
                                            text = "@$username",
                                            style = FoamTheme.typography.body
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                                    SettingsActionButton(
                                        label = stringResource(R.string.discord_log_out),
                                        onClick = viewModel::disconnect
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(FoamDimens.paddingMedium)
                                ) {
                                    BasicText(
                                        text = stringResource(R.string.discord_not_connected),
                                        style = FoamTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                                    SettingsActionButton(
                                        label = stringResource(R.string.discord_log_in),
                                        enabled = authState !is DiscordAuthUiState.Waiting,
                                        onClick = { viewModel.startAuthorization(context) }
                                    )
                                }
                            }
                        }

                        when (val state = authState) {
                            is DiscordAuthUiState.Waiting -> SettingsStatusRow(
                                text = stringResource(R.string.discord_waiting_authorization),
                                icon = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(FoamDimens.iconSmall),
                                        color = FoamTheme.accentColor,
                                        strokeWidth = 2.dp
                                    )
                                }
                            )
                            is DiscordAuthUiState.Success -> SettingsStatusRow(
                                text = stringResource(R.string.discord_connected),
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = FoamTheme.accentColor,
                                        modifier = Modifier.size(FoamDimens.iconSmall)
                                    )
                                }
                            )
                            is DiscordAuthUiState.Failure -> SettingsStatusRow(
                                text = state.message
                                    ?: stringResource(R.string.discord_authorization_failed),
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = FoamTheme.accentColor,
                                        modifier = Modifier.size(FoamDimens.iconSmall)
                                    )
                                }
                            )
                            is DiscordAuthUiState.Idle -> Unit
                        }

                        LaunchedEffect(authState) {
                            if (authState is DiscordAuthUiState.Success) {
                                delay(SUCCESS_STATE_RESET_DELAY_MS)
                                viewModel.resetAuthState()
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                SettingsExpandVisibility(
                    visible = isDiscordEnabled,
                    animate = animateChanges
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .foamSurface()
                    ) {
                        FoamSwitchItem(
                            title = stringResource(R.string.discord_online_art),
                            description = stringResource(R.string.discord_online_art_desc),
                            checked = settings.discordOnlineArt,
                            onCheckedChange = viewModel::setDiscordOnlineArt
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }
        }
    }
}

private const val SUCCESS_STATE_RESET_DELAY_MS = 2_600L
