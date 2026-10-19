package com.foamplayer.app.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.update.DownloadState
import com.foamplayer.app.update.GitHubRelease
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import java.io.File

/**
 * Global update modals hosted at the nav root:
 * - "New update available": raised by the startup auto-check, shows the
 *   release notes and a download button.
 * - "Install": raised whenever a download completes (startup modal download
 *   or the About screen), offers to launch the system installer.
 *
 * Both are [FoamBottomModal] sheets; the two are never visible together (the
 * install prompt wins, e.g. when the user downloads from the startup modal).
 */
@Composable
fun UpdateFoamModal(
    startupRelease: GitHubRelease?,
    downloadState: DownloadState,
    installFile: File?,
    onDismissStartup: () -> Unit,
    onCancelDownload: () -> Unit,
    onDownload: (GitHubRelease) -> Unit,
    onInstall: (File) -> Unit,
    onDismissInstall: () -> Unit
) {
    val showStartupModal = startupRelease != null &&
            downloadState !is DownloadState.Completed &&
            installFile == null

    // Startup "new update available" sheet.
    FoamBottomModal(
        visible = showStartupModal,
        onDismissRequest = onDismissStartup
    ) {
        val release = startupRelease ?: return@FoamBottomModal
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(R.string.update_available_title),
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
            BasicText(
                text = stringResource(R.string.update_available_version, release.version),
                style = FoamTheme.typography.titleMedium
            )
            release.name?.takeIf { it.isNotBlank() }?.let { title ->
                Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                BasicText(text = title, style = FoamTheme.typography.body)
            }
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(FoamTheme.colors.borderOverlay)
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            ReleaseNotesBody(notes = release.body)

            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            when (downloadState) {
                is DownloadState.Downloading -> {
                    DownloadProgressText(downloadState.progress)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    ModalButton(
                        label = stringResource(R.string.about_cancel_download),
                        filled = false,
                        onClick = onCancelDownload
                    )
                }
                is DownloadState.Completed -> {
                    ModalButton(
                        label = stringResource(R.string.about_install_update),
                        filled = true,
                        onClick = { installFile?.let(onInstall) }
                    )
                }
                is DownloadState.Failed -> {
                    BasicText(
                        text = stringResource(R.string.about_download_failed),
                        style = FoamTheme.typography.body
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    ModalButton(
                        label = stringResource(R.string.about_download_update),
                        filled = true,
                        onClick = { onDownload(release) }
                    )
                }
                is DownloadState.Idle -> {
                    ModalButton(
                        label = stringResource(R.string.about_download_update),
                        filled = true,
                        onClick = { onDownload(release) }
                    )
                }
            }
        }
    }

    // "Install" sheet, raised whenever a download completes.
    FoamBottomModal(
        visible = installFile != null && !showStartupModal,
        onDismissRequest = onDismissInstall
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(R.string.update_install_ready_title),
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            BasicText(
                text = stringResource(R.string.update_install_ready_text),
                style = FoamTheme.typography.body
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            ModalButton(
                label = stringResource(R.string.about_install_update),
                filled = true,
                onClick = { installFile?.let(onInstall) }
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            ModalButton(
                label = stringResource(R.string.update_install_later),
                filled = false,
                onClick = onDismissInstall
            )
        }
    }
}

@Composable
private fun ReleaseNotesBody(notes: String?) {
    val text = notes?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.about_no_release_notes)
    val scrollState = rememberScrollState()
    BasicText(
        text = text,
        style = FoamTheme.typography.body,
        modifier = Modifier
            .height(280.dp)
            .verticalScroll(scrollState)
    )
}

@Composable
private fun DownloadProgressText(progress: Float) {
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
