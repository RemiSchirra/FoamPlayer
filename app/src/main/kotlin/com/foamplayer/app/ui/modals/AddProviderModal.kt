package com.foamplayer.app.ui.modals

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.net.toUri
import com.foamplayer.app.R
import com.foamplayer.app.ui.screens.settings.SettingsViewModel
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.distant.DistantAuthMethod
import com.foamplayer.distant.DistantFolder
import com.foamplayer.distant.DistantFolderEntry
import com.foamplayer.distant.DistantProviderType
import com.foamplayer.distant.DistantServer
import kotlinx.coroutines.launch

/** The four pages of the add-provider flow. */
private sealed interface ProviderStep {
    val order: Int
    data object Source : ProviderStep { override val order = 0 }
    data object Provider : ProviderStep { override val order = 1 }
    data class Configure(val type: DistantProviderType) : ProviderStep { override val order = 2 }
    data class Folders(
        val server: DistantServer,
        val entries: List<DistantFolderEntry>,
        val selected: Set<String>
    ) : ProviderStep { override val order = 3 }
}

/**
 * Multi-page "add a source" sheet: pick local vs distant, choose a provider,
 * sign in, then tick the folders to scan. Pages slide horizontally like the
 * track detail modal.
 */
@Composable
fun AddProviderModal(
    viewModel: SettingsViewModel,
    visible: Boolean,
    onDismiss: () -> Unit,
    onPickLocalFolder: () -> Unit,
    onServerScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveFailed = stringResource(R.string.provider_error_save)

    var step by remember { mutableStateOf<ProviderStep>(ProviderStep.Source) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            step = ProviderStep.Source
            error = null
            busy = false
        }
    }

    val goBack: () -> Unit = {
        error = null
        step = when (step) {
            is ProviderStep.Folders -> ProviderStep.Provider
            is ProviderStep.Configure -> ProviderStep.Provider
            ProviderStep.Provider -> ProviderStep.Source
            ProviderStep.Source -> ProviderStep.Source
        }
    }

    FoamBottomModal(visible = visible, onDismissRequest = onDismiss) {
        // System back walks the flow backwards instead of closing the sheet.
        BackHandler(enabled = step != ProviderStep.Source, onBack = goBack)
        Column(modifier = Modifier.fillMaxWidth()) {
            val title = when (val s = step) {
                ProviderStep.Source -> stringResource(R.string.choose_source)
                ProviderStep.Provider -> stringResource(R.string.choose_provider)
                is ProviderStep.Configure -> s.type.displayName
                is ProviderStep.Folders -> stringResource(R.string.choose_folders)
            }
            ModalHeader(
                title = title,
                canGoBack = step != ProviderStep.Source,
                onBack = goBack
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            AnimatedContent(
                targetState = step,
                // Key on the step only: ticking a folder (or loading the list)
                // changes the Folders state in place without replaying the
                // slide-in transition.
                contentKey = { it.order },
                transitionSpec = {
                    if (targetState.order >= initialState.order) {
                        (slideInHorizontally(tween(260)) { it } + fadeIn(tween(160))) togetherWith
                                (slideOutHorizontally(tween(260)) { -it } + fadeOut(tween(120)))
                    } else {
                        (slideInHorizontally(tween(260)) { -it } + fadeIn(tween(160))) togetherWith
                                (slideOutHorizontally(tween(260)) { it } + fadeOut(tween(120)))
                    }
                },
                label = "addProviderStep"
            ) { current ->
                when (current) {
                    ProviderStep.Source -> SourceStep(
                        onLocal = {
                            onPickLocalFolder()
                            onDismiss()
                        },
                        onDistant = { step = ProviderStep.Provider }
                    )

                    ProviderStep.Provider -> ProviderStepList(onSelect = { step = ProviderStep.Configure(it) })

                    is ProviderStep.Configure -> ConfigureStep(
                        type = current.type,
                        busy = busy,
                        error = error,
                        viewModel = viewModel,
                        onConnecting = { busy = true; error = null },
                        onError = { busy = false; error = it },
                        onServerReady = { server ->
                            busy = false
                            error = null
                            step = ProviderStep.Folders(server, emptyList(), emptySet())
                        },
                        onSignIn = { url ->
                            error = null
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                                .onFailure { error = it.message }
                        }
                    )

                    is ProviderStep.Folders -> FoldersStep(
                        viewModel = viewModel,
                        step = current,
                        busy = busy,
                        error = error,
                        onLoad = { entries ->
                            step = current.copy(entries = entries)
                        },
                        onLoadError = { error = it },
                        onToggle = { id ->
                            val selected = if (id in current.selected) current.selected - id else current.selected + id
                            step = current.copy(selected = selected)
                        },
                        onDone = {
                            busy = true
                            scope.launch {
                                val folders = current.entries
                                    .filter { it.id in current.selected }
                                    .map { DistantFolder(id = it.id, name = it.name, path = it.path) }
                                val ok = viewModel.saveServerFolders(current.server, folders)
                                busy = false
                                if (ok) {
                                    onServerScanned(current.server.id)
                                    onDismiss()
                                } else {
                                    error = saveFailed
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModalHeader(title: String, canGoBack: Boolean, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (canGoBack) {
            Box(
                modifier = Modifier
                    .size(FoamDimens.iconLarge)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = onBack
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = FoamTheme.colors.textPrimary,
                    modifier = Modifier.size(FoamDimens.iconMedium)
                )
            }
            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        }
        BasicText(text = title, style = FoamTheme.typography.titleMedium)
    }
}

@Composable
private fun SourceStep(onLocal: () -> Unit, onDistant: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ProviderOptionRow(
            title = stringResource(R.string.source_local_folder),
            subtitle = stringResource(R.string.source_local_folder_desc),
            onClick = onLocal,
            titleStyle = FoamTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
        ProviderOptionRow(
            title = stringResource(R.string.source_distant_server),
            subtitle = stringResource(R.string.source_distant_server_desc),
            onClick = onDistant,
            titleStyle = FoamTheme.typography.titleMedium
        )
    }
}

@Composable
private fun ProviderStepList(onSelect: (DistantProviderType) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DistantProviderType.entries.forEach { provider ->
            ProviderOptionRow(
                title = provider.displayName,
                subtitle = null,
                onClick = { onSelect(provider) }
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
        }
    }
}

@Composable
private fun ProviderOptionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    titleStyle: TextStyle = FoamTheme.typography.body
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surfaceVariant)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(FoamDimens.paddingMedium)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(text = title, style = titleStyle)
            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
                )
            }
        }
    }
}

@Composable
private fun ConfigureStep(
    type: DistantProviderType,
    busy: Boolean,
    error: String?,
    viewModel: SettingsViewModel,
    onConnecting: () -> Unit,
    onError: (String) -> Unit,
    onServerReady: (DistantServer) -> Unit,
    onSignIn: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.provider_error_unavailable)
    val timedOut = stringResource(R.string.provider_error_timeout)
    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (type.requiresBaseUrl) {
            ProviderField(baseUrl, { baseUrl = it }, stringResource(R.string.provider_address))
        }

        when (type.authMethod) {
            DistantAuthMethod.TOKEN -> {
                Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
                ProviderField(token, { token = it }, stringResource(R.string.provider_token))
            }

            DistantAuthMethod.CREDENTIALS -> {
                Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
                ProviderField(username, { username = it }, stringResource(R.string.provider_username))
                Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
                ProviderField(password, { password = it }, stringResource(R.string.provider_password), isPassword = true)
            }

            DistantAuthMethod.OAUTH -> Unit
        }

        error?.let {
            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            BasicText(
                text = stringResource(R.string.provider_connection_failed, it),
                style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
            )
        }

        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

        when (type.authMethod) {
            DistantAuthMethod.OAUTH -> ModalButton(
                label = stringResource(R.string.provider_login_with, type.displayName),
                filled = true,
                enabled = !busy,
                onClick = {
                    onConnecting()
                    scope.launch {
                        val url = viewModel.beginOAuth(type, type.displayName, baseUrl)
                        if (url == null) {
                            onError(viewModel.lastDistantError ?: unavailable)
                        } else {
                            onSignIn(url)
                            val server = viewModel.awaitOAuthConnection(type)
                            if (server != null) onServerReady(server) else onError(timedOut)
                        }
                    }
                }
            )

            else -> ModalButton(
                label = stringResource(R.string.connect),
                filled = true,
                enabled = !busy,
                onClick = {
                    onConnecting()
                    scope.launch {
                        val server = viewModel.connectMediaServer(
                            type, type.displayName, baseUrl, username, password, token
                        )
                        if (server != null) onServerReady(server)
                        else onError(viewModel.lastDistantError ?: unavailable)
                    }
                }
            )
        }
    }
}

@Composable
private fun FoldersStep(
    viewModel: SettingsViewModel,
    step: ProviderStep.Folders,
    busy: Boolean,
    error: String?,
    onLoad: (List<DistantFolderEntry>) -> Unit,
    onLoadError: (String) -> Unit,
    onToggle: (String) -> Unit,
    onDone: () -> Unit
) {
    val unavailable = stringResource(R.string.provider_error_unavailable)
    LaunchedEffect(step.server.id) {
        val folders = viewModel.listFolders(step.server)
        if (folders == null) onLoadError(viewModel.lastDistantError ?: unavailable) else onLoad(folders)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (step.entries.isEmpty()) {
            BasicText(
                text = stringResource(R.string.scanning_caps),
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
            )
        } else {
            step.entries.forEach { entry ->
                SelectableFolderRow(
                    name = entry.name,
                    hint = entry.hint,
                    checked = entry.id in step.selected,
                    onToggle = { onToggle(entry.id) }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            BasicText(
                text = stringResource(R.string.provider_connection_failed, it),
                style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
            )
        }

        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
        ModalButton(
            label = stringResource(R.string.add),
            filled = true,
            enabled = !busy && step.selected.isNotEmpty(),
            onClick = onDone
        )
    }
}

@Composable
private fun SelectableFolderRow(
    name: String,
    hint: String?,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surfaceVariant)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onToggle
            )
            .padding(FoamDimens.paddingMedium)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(text = name, style = FoamTheme.typography.body)
                if (hint != null) {
                    BasicText(
                        text = hint,
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
                    )
                }
            }
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = FoamTheme.accentColor,
                    checkmarkColor = FoamTheme.colors.background,
                    uncheckedColor = FoamTheme.colors.textSecondary
                )
            )
        }
    }
}

@Composable
private fun ProviderField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    isPassword: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(FoamTheme.colors.surfaceVariant)
            .padding(horizontal = FoamDimens.paddingLarge, vertical = FoamDimens.spacingStandard),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            BasicText(
                text = hint,
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(FoamTheme.accentColor),
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (isPassword) {
                KeyboardOptions(keyboardType = KeyboardType.Password)
            } else {
                KeyboardOptions.Default
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
