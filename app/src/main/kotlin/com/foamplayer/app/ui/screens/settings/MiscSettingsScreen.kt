package com.foamplayer.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.FoamNavigationButton
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.delay

@Composable
fun MiscSettingsScreen(
    onBack: () -> Unit,
    onNavigateToNotification: () -> Unit,
    viewModel: MiscSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val session by viewModel.discordSession.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val context = LocalContext.current

    val isDiscordEnabled = settings.discordPresenceEnabled
    val isLoggedIn = session != null

    // Animate show/hide only once the user has flipped a switch; on first
    // composition (and while settings load) sections appear/disappear instantly.
    var animateChanges by remember { mutableStateOf(false) }

    // Re-checked whenever the screen comes back to the foreground so
    // returning from the system permission screen instantly reflects the
    // (revoked or granted) overlay state.
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

    BackHandler(enabled = authState is DiscordAuthUiState.Waiting) {
        viewModel.cancelAuthorization()
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
                        DiscordActionButton(
                            label = stringResource(R.string.lock_screen_open_permission),
                            onClick = { viewModel.openOverlaySettings(context) }
                        )
                    }
                }
                // Fast lock: end the lock-display session after 5s of inactivity;
                // only meaningful while the lock-screen display itself is on.
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
                    text = stringResource(R.string.discord_section),
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
                                .background(FoamTheme.colors.surface)
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
                                    DiscordActionButton(
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
                                    DiscordActionButton(
                                        label = stringResource(R.string.discord_log_in),
                                        enabled = authState !is DiscordAuthUiState.Waiting,
                                        onClick = { viewModel.startAuthorization(context) }
                                    )
                                }
                            }
                        }

                        when (val state = authState) {
                            is DiscordAuthUiState.Waiting -> DiscordStatusRow(
                                text = stringResource(R.string.discord_waiting_authorization),
                                icon = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(FoamDimens.iconSmall),
                                        color = FoamTheme.accentColor,
                                        strokeWidth = 2.dp
                                    )
                                }
                            )
                            is DiscordAuthUiState.Success -> DiscordStatusRow(
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
                            is DiscordAuthUiState.Failure -> DiscordStatusRow(
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
                            .background(FoamTheme.colors.surface)
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

@Composable
private fun SettingsExpandVisibility(
    visible: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (animate) fadeIn() + expandVertically() else EnterTransition.None,
        exit = if (animate) fadeOut() + shrinkVertically() else ExitTransition.None,
        content = content
    )
}

@Composable
private fun DiscordActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(
                if (enabled) FoamTheme.accentColor else FoamTheme.colors.surfaceVariant
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingSmall),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.titleMedium.copy(
                color = if (enabled) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun DiscordStatusRow(
    text: String,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FoamDimens.spacingMedium)
            .padding(horizontal = FoamDimens.spacingTiny),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        BasicText(text = text, style = FoamTheme.typography.body)
    }
}

private const val SUCCESS_STATE_RESET_DELAY_MS = 2_600L