package com.foamplayer.app.ui.screens.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.components.MarkdownText
import com.foamplayer.app.ui.modals.FoamBottomModal
import com.foamplayer.app.ui.modals.ModalButton
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.update.DownloadState
import com.foamplayer.app.update.GitHubRelease

/**
 * Settings > About: current version, available version and its release notes
 * (in a FoamModal), a manual "check for update" entry and the auto-check
 * switch. Downloading an update posts a progress notification and hands the
 * APK to the system installer when ready.
 */
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    viewModel: AboutSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val checking by viewModel.checking.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val releaseNotes by viewModel.releaseNotes.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.settings_about),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            // Version card: the running build, plus the available release when
            // a newer one exists.
            item {
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
                        VersionRow(
                            label = stringResource(R.string.about_current_version),
                            version = viewModel.currentVersion
                        )
                        val available = (uiState as? AboutUiState.Available)?.release
                        available?.let { release ->
                            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                            VersionRow(
                                label = stringResource(R.string.about_available_version),
                                version = release.version
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            // Update status / actions card.
            when (val state = uiState) {
                is AboutUiState.Available -> item {
                    UpdateAvailableCard(
                        release = state.release,
                        downloadState = downloadState,
                        installFile = viewModel.downloadedApkFile(state.release),
                        onShowReleaseNotes = { viewModel.showReleaseNotes(state.release) },
                        onDownload = viewModel::downloadAndInstall,
                        onInstall = viewModel::install,
                        onCancelDownload = viewModel::cancelDownload
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                }

                is AboutUiState.UpToDate -> item {
                    StatusRowCard(
                        text = stringResource(R.string.about_up_to_date),
                        icon = Icons.Rounded.CheckCircle
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                }

                is AboutUiState.Error -> item {
                    StatusRowCard(
                        text = stringResource(R.string.about_check_failed),
                        icon = Icons.Rounded.ErrorOutline
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                }

                else -> Unit
            }

            // Check-for-update row (spinner while a check is in flight).
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    if (checking) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(FoamDimens.paddingMedium),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(FoamDimens.iconSmall),
                                color = FoamTheme.accentColor,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(FoamDimens.spacingStandard))
                            BasicText(
                                text = stringResource(R.string.about_checking),
                                style = FoamTheme.typography.titleMedium
                            )
                        }
                    } else {
                        UpdateRow(
                            title = stringResource(R.string.about_check_for_update),
                            icon = Icons.Rounded.Refresh,
                            onClick = viewModel::checkForUpdate
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            // Auto-check switch.
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    FoamSwitchItem(
                        title = stringResource(R.string.about_auto_check),
                        description = stringResource(R.string.about_auto_check_desc),
                        checked = settings.autoUpdateCheckEnabled,
                        onCheckedChange = viewModel::setAutoUpdateCheckEnabled
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }
        }
    }

    ReleaseNotesModal(
        release = releaseNotes,
        onDismiss = viewModel::dismissReleaseNotes,
        downloadState = downloadState,
        installFile = releaseNotes?.let { viewModel.downloadedApkFile(it) },
        onDownload = { notesRelease -> viewModel.downloadAndInstall(notesRelease) },
        onInstall = viewModel::install,
        onCancel = viewModel::cancelDownload
    )
}

@Composable
private fun VersionRow(label: String, version: String) {
    Column {
        BasicText(text = label, style = FoamTheme.typography.body)
        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
        BasicText(text = version, style = FoamTheme.typography.titleMedium)
    }
}

/** Release-notes shortcut + download/install progress of an available update. */
@Composable
private fun UpdateAvailableCard(
    release: GitHubRelease,
    downloadState: DownloadState,
    installFile: java.io.File?,
    onShowReleaseNotes: () -> Unit,
    onDownload: (GitHubRelease) -> Unit,
    onInstall: (java.io.File) -> Unit,
    onCancelDownload: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Full-bleed clickable row: the ripple is bounded by the card's clip.
            ReleaseNotesEntry(
                release = release,
                onClick = onShowReleaseNotes
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = FoamDimens.paddingMedium,
                        vertical = FoamDimens.spacingMedium
                    )
            ) {
                when (val state = downloadState) {
                    is DownloadState.Downloading -> {
                        DownloadProgressRow(state.progress)
                        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                        ModalButton(
                            label = stringResource(R.string.about_cancel_download),
                            filled = false,
                            onClick = onCancelDownload
                        )
                    }
                    is DownloadState.Completed -> {
                        if (installFile != null) {
                            ModalButton(
                                label = stringResource(R.string.about_install_update),
                                filled = true,
                                onClick = { onInstall(installFile) }
                            )
                        } else {
                            ModalButton(
                                label = stringResource(R.string.about_download_update),
                                filled = true,
                                onClick = { onDownload(release) }
                            )
                        }
                    }
                    is DownloadState.Failed -> {
                        StatusRow(
                            text = stringResource(R.string.about_download_failed),
                            icon = Icons.Rounded.ErrorOutline
                        )
                        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                        ModalButton(
                            label = stringResource(R.string.about_download_update),
                            filled = true,
                            onClick = { onDownload(release) }
                        )
                    }
                    is DownloadState.Idle -> ModalButton(
                        label = stringResource(R.string.about_download_update),
                        filled = true,
                        onClick = { onDownload(release) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesEntry(release: GitHubRelease, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = stringResource(R.string.about_release_notes),
                style = FoamTheme.typography.titleMedium
            )
            release.name?.takeIf { it.isNotBlank() }?.let { title ->
                Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                BasicText(text = title, style = FoamTheme.typography.body)
            }
        }
        Icon(
            imageVector = Icons.Rounded.Description,
            contentDescription = null,
            tint = FoamTheme.colors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun UpdateRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(FoamDimens.iconMedium)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingStandard))
        BasicText(text = title, style = FoamTheme.typography.titleMedium)
    }
}

@Composable
private fun StatusRowCard(text: String, icon: ImageVector) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FoamDimens.paddingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = FoamTheme.accentColor,
                modifier = Modifier.size(FoamDimens.iconSmall)
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingStandard))
            BasicText(text = text, style = FoamTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun StatusRow(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(FoamDimens.iconSmall)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        BasicText(text = text, style = FoamTheme.typography.body)
    }
}

@Composable
private fun DownloadProgressRow(progress: Float) {
    Column {
        BasicText(
            text = stringResource(
                R.string.about_download_progress,
                (progress.coerceIn(0f, 1f) * 100).toInt()
            ),
            style = FoamTheme.typography.body
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FoamDimens.spacingTiny)
                .clip(RoundedCornerShape(FoamDimens.spacingTiny))
                .background(FoamTheme.colors.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(FoamDimens.spacingTiny)
                    .background(FoamTheme.accentColor)
            )
        }
    }
}

/**
 * Release notes bottom sheet ([FoamBottomModal]): tag + title, the release
 * body, and the download / install / cancel action following the download
 * state.
 */
@Composable
private fun ReleaseNotesModal(
    release: GitHubRelease?,
    downloadState: DownloadState,
    installFile: java.io.File?,
    onDismiss: () -> Unit,
    onDownload: (GitHubRelease) -> Unit,
    onInstall: (java.io.File) -> Unit,
    onCancel: () -> Unit
) {
    FoamBottomModal(
        visible = release != null,
        onDismissRequest = onDismiss
    ) {
        if (release == null) return@FoamBottomModal
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = release.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.about_release_notes_title, release.version),
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(FoamTheme.colors.borderOverlay)
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            val scrollState = rememberScrollState()
            val notes = release.body?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.about_no_release_notes)
            MarkdownText(
                markdown = notes,
                modifier = Modifier
                    .height(300.dp)
                    .verticalScroll(scrollState)
            )

            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            when (downloadState) {
                is DownloadState.Downloading -> {
                    DownloadProgressRow(downloadState.progress)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    ModalButton(
                        label = stringResource(R.string.about_cancel_download),
                        filled = false,
                        onClick = onCancel
                    )
                }
                is DownloadState.Completed -> {
                    if (installFile != null) {
                        ModalButton(
                            label = stringResource(R.string.about_install_update),
                            filled = true,
                            onClick = { onInstall(installFile) }
                        )
                    } else {
                        ModalButton(
                            label = stringResource(R.string.about_download_update),
                            filled = true,
                            onClick = { onDownload(release) }
                        )
                    }
                }
                else -> {
                    ModalButton(
                        label = stringResource(R.string.about_download_update),
                        filled = true,
                        onClick = { onDownload(release) }
                    )
                }
            }
        }
    }
}
