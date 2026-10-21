package com.foamplayer.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.ripple
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.lastfm.LastFmCredentialSource
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.modals.LastFmCredentialsModal
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.delay

/**
 * Settings > Misc > Last.fm: connect an account and enable scrobbling. Tracks
 * are submitted once they pass the listening threshold configured in
 * Settings > Library.
 */
@Composable
fun LastFmSettingsScreen(
    onBack: () -> Unit,
    viewModel: LastFmSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val session by viewModel.session.collectAsState()
    val credentials by viewModel.credentials.collectAsState()
    val customApiKey by viewModel.customApiKey.collectAsState()
    val customApiSecret by viewModel.customApiSecret.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val context = LocalContext.current
    val isConfigured = credentials.isConfigured
    var showCredentialsModal by remember { mutableStateOf(false) }

    BackHandler(enabled = authState is LastFmAuthUiState.Waiting) {
        viewModel.cancelAuthorization()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.lastfm_section),
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
                    ApiKeyRow(
                        title = stringResource(R.string.lastfm_api_key),
                        description = when {
                            credentials.source == LastFmCredentialSource.APP &&
                                    !credentials.isConfigured ->
                                stringResource(R.string.lastfm_api_key_app_missing)
                            credentials.source == LastFmCredentialSource.APP ->
                                stringResource(R.string.lastfm_api_key_app)
                            else -> stringResource(R.string.lastfm_api_key_custom)
                        },
                        onClick = { showCredentialsModal = true }
                    )
                }

                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FoamDimens.paddingMedium)
                    ) {
                        if (session != null) {
                            val username = session?.username?.takeIf { it.isNotBlank() }
                            BasicText(
                                text = if (username != null) {
                                    stringResource(R.string.lastfm_logged_in_as, username)
                                } else {
                                    stringResource(R.string.lastfm_connected)
                                },
                                style = FoamTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                            SettingsActionButton(
                                label = stringResource(R.string.lastfm_log_out),
                                onClick = viewModel::disconnect
                            )
                        } else {
                            BasicText(
                                text = stringResource(R.string.lastfm_not_connected),
                                style = FoamTheme.typography.titleMedium
                            )
                            if (!isConfigured) {
                                Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                                BasicText(
                                    text = stringResource(R.string.lastfm_not_configured),
                                    style = FoamTheme.typography.body
                                )
                            }
                            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                            SettingsActionButton(
                                label = stringResource(R.string.lastfm_log_in),
                                enabled = isConfigured &&
                                        authState !is LastFmAuthUiState.Waiting,
                                onClick = { viewModel.startAuthorization(context) }
                            )
                        }
                    }
                }

                when (val state = authState) {
                    is LastFmAuthUiState.Waiting -> SettingsStatusRow(
                        text = stringResource(R.string.lastfm_waiting_authorization),
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(FoamDimens.iconSmall),
                                color = FoamTheme.accentColor,
                                strokeWidth = 2.dp
                            )
                        }
                    )
                    is LastFmAuthUiState.Success -> SettingsStatusRow(
                        text = stringResource(R.string.lastfm_connected),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = FoamTheme.accentColor,
                                modifier = Modifier.size(FoamDimens.iconSmall)
                            )
                        }
                    )
                    is LastFmAuthUiState.Failure -> SettingsStatusRow(
                        text = state.message
                            ?: stringResource(R.string.lastfm_authorization_failed),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = FoamTheme.accentColor,
                                modifier = Modifier.size(FoamDimens.iconSmall)
                            )
                        }
                    )
                    is LastFmAuthUiState.Idle -> Unit
                }

                LaunchedEffect(authState) {
                    if (authState is LastFmAuthUiState.Success) {
                        delay(SUCCESS_STATE_RESET_DELAY_MS)
                        viewModel.resetAuthState()
                    }
                }

                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    FoamSwitchItem(
                        title = stringResource(R.string.lastfm_scrobble),
                        description = if (session != null) {
                            stringResource(
                                R.string.lastfm_scrobble_desc,
                                settings.listenedThresholdPercent
                            )
                        } else {
                            stringResource(R.string.lastfm_scrobble_connect_first)
                        },
                        checked = settings.lastFmScrobblingEnabled,
                        onCheckedChange = viewModel::setScrobblingEnabled
                    )
                }
            }
        }
    }

    LastFmCredentialsModal(
        visible = showCredentialsModal,
        currentSource = credentials.source,
        appCredentialsConfigured = viewModel.appCredentialsConfigured,
        customApiKey = customApiKey,
        customApiSecret = customApiSecret,
        onDismiss = { showCredentialsModal = false },
        onUseAppCredentials = viewModel::useAppCredentials,
        onSaveCustomCredentials = viewModel::saveCustomCredentials
    )
}

/**
 * Header row that opens the credential sheet: title + current source, with a
 * downward chevron since the choice opens a bottom sheet rather than a screen.
 */
@Composable
private fun ApiKeyRow(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = title, style = FoamTheme.typography.titleMedium)
            BasicText(text = description, style = FoamTheme.typography.body)
        }
        Spacer(modifier = Modifier.width(FoamDimens.spacingStandard))
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = FoamTheme.colors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

private const val SUCCESS_STATE_RESET_DELAY_MS = 2_600L
