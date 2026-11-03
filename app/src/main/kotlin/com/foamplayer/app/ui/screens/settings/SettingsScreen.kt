package com.foamplayer.app.ui.screens.settings

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
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.BuildConfig
import com.foamplayer.app.R
import com.foamplayer.app.data.backup.BackupFeature
import com.foamplayer.app.ui.components.FoamCard
import com.foamplayer.app.ui.modals.BackupLoadingModal
import com.foamplayer.app.ui.modals.BackupModalType
import com.foamplayer.app.ui.modals.BackupResultModal
import com.foamplayer.app.ui.modals.ExportSettingsModal
import com.foamplayer.app.ui.modals.ImportSettingsModal
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamBackground
import com.foamplayer.app.ui.theme.foamBackgroundTopFade

@Composable
fun SettingsScreen(
    onNavigateToAudio: () -> Unit,
    onNavigateToUI: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    onNavigateToMisc: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToTest: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var openBackupModal by remember { mutableStateOf(BackupModalType.NONE) }
    val backupResult by viewModel.backupResult.collectAsState()
    val importRunning by viewModel.importRunning.collectAsState()
    val backupRunning = viewModel.backupRunning
    val backupManager = viewModel.backupManager

    SettingsPage(title = stringResource(R.string.settings)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(
                top = SettingsHeaderZoneHeight + FoamDimens.paddingMedium,
                bottom = LocalBottomBarClearance.current
            )
        ) {
            item {
                SettingsCategoryItem(
                    title = stringResource(R.string.audio),
                    icon = FoamTheme.icons.current.equalizer,
                    onClick = onNavigateToAudio
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                SettingsCategoryItem(
                    title = stringResource(R.string.user_interface),
                    icon = FoamTheme.icons.current.ui,
                    onClick = onNavigateToUI
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                SettingsCategoryItem(
                    title = stringResource(R.string.library),
                    icon = FoamTheme.icons.current.library,
                    onClick = onNavigateToLibrary
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                SettingsCategoryItem(
                    title = stringResource(R.string.lyrics),
                    icon = FoamTheme.icons.current.lyrics,
                    onClick = onNavigateToLyrics
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                SettingsCategoryItem(
                    title = stringResource(R.string.settings_misc),
                    icon = FoamTheme.icons.current.misc,
                    onClick = onNavigateToMisc
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            item {
                SettingsCategoryItem(
                    title = stringResource(R.string.settings_about),
                    icon = FoamTheme.icons.current.info,
                    onClick = onNavigateToAbout
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            // Developer entry: compiled in only with devMode=true (BuildConfig.DEV_MODE).
            if (BuildConfig.DEV_MODE && onNavigateToTest != null) {
                item {
                    SettingsCategoryItem(
                        title = stringResource(R.string.test_components),
                        icon = Icons.Default.BugReport,
                        onClick = onNavigateToTest
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
                }
            }

            item {
                BackupLinkRow(
                    icon = Icons.Outlined.FileUpload,
                    label = stringResource(R.string.settings_export),
                    onClick = { openBackupModal = BackupModalType.EXPORT }
                )
            }

            item {
                BackupLinkRow(
                    icon = Icons.Outlined.FileDownload,
                    label = stringResource(R.string.settings_import),
                    onClick = { openBackupModal = BackupModalType.IMPORT }
                )
            }
        }
    }

    ExportSettingsModal(
        visible = openBackupModal == BackupModalType.EXPORT,
        onDismiss = { openBackupModal = BackupModalType.NONE },
        onExport = { folderUri, features ->
            openBackupModal = BackupModalType.NONE
            viewModel.exportSettings(folderUri, features)
        },
        isRunning = backupRunning
    )

    ImportSettingsModal(
        visible = openBackupModal == BackupModalType.IMPORT,
        onDismiss = { openBackupModal = BackupModalType.NONE },
        onImport = { fileUris, features ->
            openBackupModal = BackupModalType.NONE
            viewModel.importSettings(fileUris, features)
        },
        isRunning = backupRunning,
        probeFeatures = { uris ->
            val union = mutableSetOf<BackupFeature>()
            uris.forEach { uri ->
                union += backupManager.detectFeatures(uri).orEmpty()
            }
            union
        }
    )

    BackupLoadingModal(
        visible = importRunning && openBackupModal == BackupModalType.NONE
    )

    BackupResultModal(
        result = backupResult,
        onDismiss = viewModel::consumeBackupResult
    )
}

@Composable
fun SettingsCategoryItem(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    FoamCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FoamDimens.spacingStandard),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(FoamDimens.iconMedium),
                tint = FoamTheme.accentColor
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingStandard))
            Column {
                BasicText(text = title, style = FoamTheme.typography.titleMedium)
            }
        }
    }
}

/** Height of the header row: icon slot + its vertical padding. */
private val SettingsHeaderRowHeight =
    FoamDimens.headerIconSize + FoamDimens.paddingMedium * 2

/** Extra distance below the header row over which the fade reaches zero alpha. */
private val SettingsHeaderFadeTail = FoamDimens.paddingSmall

/**
 * Height of the pinned header zone on settings pages: the header row plus the
 * fade tail. Scrollable content reserves this as its top padding (inside the
 * scroll) so items rest below the fade at rest and dissolve into the header
 * when scrolled up.
 */
val SettingsHeaderZoneHeight = SettingsHeaderRowHeight + SettingsHeaderFadeTail

/**
 * Standard settings page scaffold: [content] fills the whole page so rows
 * scroll UNDER the header, while the pinned header row sits on a fade of the
 * theme background — fully opaque at the top of the page, dissolving to
 * nothing at the bottom of the zone — so scrolling rows stay visible instead
 * of being clipped by the header. Content must reserve
 * [SettingsHeaderZoneHeight] as its top padding (inside the scroll).
 */
@Composable
fun SettingsPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize().foamBackground()) {
        content()
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(SettingsHeaderZoneHeight)
                // Fade behind the header: background color, 100% opaque at the
                // top of the page, 0% by the bottom of the zone. The tint
                // resolves in the draw pass (see [foamBackgroundTopFade]), so
                // accent changes only redraw the fade.
                .foamBackgroundTopFade()
        ) {
            SettingsHeader(
                title = title,
                onBack = onBack,
                trailing = trailing,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = FoamDimens.paddingMedium)
            )
        }
    }
}

/**
 * The settings header row (title, optional back button and trailing slot)
 * pinned by [SettingsPage] over the page's background fade.
 */
@Composable
fun SettingsHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FoamDimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(FoamDimens.headerIconSize)
                    .clip(RoundedCornerShape(FoamDimens.headerIconRounding))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = FoamTheme.colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        }
        BasicText(text = title, style = FoamTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (trailing != null) {
            trailing()
        }
    }
}

/** Plain icon + text link (no card) used for the export / import entries. */
@Composable
private fun BackupLinkRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(
                horizontal = FoamDimens.paddingMedium,
                vertical = FoamDimens.spacingMedium
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(FoamDimens.iconMedium),
            tint = FoamTheme.colors.textPrimary
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingStandard))
        BasicText(text = label, style = FoamTheme.typography.titleMedium)
    }
}
