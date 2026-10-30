package com.foamplayer.app.ui.modals

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.data.backup.BackupFeature
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamBackground
import com.foamplayer.app.ui.theme.foamSurfaceVariant

/** Which backup sheet is open on the main settings screen. */
enum class BackupModalType { NONE, EXPORT, IMPORT }

/**
 * The Export sheet: pick a destination folder and tick the features to write;
 * everything goes into a single `foam_backup.json`.
 */
@Composable
fun ExportSettingsModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    onExport: (folderUri: String, features: Set<BackupFeature>) -> Unit,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    var folderUri by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(BackupFeature.entries.toSet()) }

    LaunchedEffect(visible) {
        if (visible) {
            folderUri = null
            folderName = null
            selected = BackupFeature.entries.toSet()
        }
    }

    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            folderUri = uri.toString()
            folderName = backupFolderDisplayName(uri)
        }
    }

    BackupModalScaffold(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_export),
        modifier = modifier
    ) {
        // Picker card; the Export button sits BELOW it (spaced by the
        // scaffold's Column), never inside the card.
        PickerCard {
            PickerRow(
                title = stringResource(R.string.settings_export_folder),
                subtitle = folderName ?: stringResource(R.string.settings_export_folder_hint),
                selected = folderUri != null,
                onClick = { folderPicker.launch(null) },
                onReset = {
                    folderUri = null
                    folderName = null
                }
            )
        }

        FeatureList(
            title = stringResource(R.string.settings_export_features),
            selected = selected,
            enabled = BackupFeature.entries.toSet(),
            onToggle = { feature, checked ->
                selected = if (checked) selected + feature else selected - feature
            }
        )

        ModalButton(
            label = stringResource(R.string.settings_export),
            filled = true,
            enabled = folderUri != null && selected.isNotEmpty() && !isRunning,
            onClick = { onExport(folderUri.orEmpty(), selected) }
        )
    }
}

/**
 * The playlist export sheet: pick a destination folder, then Export writes
 * one .m3u8 file per playlist into it (or just the shown playlist's file).
 */
@Composable
fun ExportPlaylistModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    onExport: (folderUri: String) -> Unit,
    isRunning: Boolean,
    title: String,
    modifier: Modifier = Modifier
) {
    var folderUri by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(visible) {
        if (visible) {
            folderUri = null
            folderName = null
        }
    }

    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            folderUri = uri.toString()
            folderName = backupFolderDisplayName(uri)
        }
    }

    BackupModalScaffold(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        modifier = modifier
    ) {
        PickerCard {
            PickerRow(
                title = stringResource(R.string.settings_export_folder),
                subtitle = folderName ?: stringResource(R.string.playlist_export_folder_hint),
                selected = folderUri != null,
                onClick = { folderPicker.launch(null) },
                onReset = {
                    folderUri = null
                    folderName = null
                }
            )
        }

        ModalButton(
            label = stringResource(R.string.settings_export),
            filled = true,
            enabled = folderUri != null && !isRunning,
            onClick = { onExport(folderUri.orEmpty()) }
        )
    }
}

/**
 * The Import sheet: pick one or more JSON files, then tick which features to
 * apply. The feature list only appears once a file is selected, and features
 * the backup doesn't contain are shown disabled (darkened, untoggleable).
 *
 * [probeFeatures] inspects the picked files on the manager's side and returns
 * the union of the features they carry.
 */
@Composable
fun ImportSettingsModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    onImport: (fileUris: List<String>, features: Set<BackupFeature>) -> Unit,
    isRunning: Boolean,
    probeFeatures: suspend (List<String>) -> Set<BackupFeature>,
    modifier: Modifier = Modifier
) {
    var fileUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var fileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var available by remember { mutableStateOf<Set<BackupFeature>>(emptySet()) }
    var selected by remember { mutableStateOf<Set<BackupFeature>>(emptySet()) }

    LaunchedEffect(visible) {
        if (visible) {
            fileUris = emptyList()
            fileNames = emptyList()
            available = emptySet()
            selected = emptySet()
        }
    }

    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            fileUris = uris.map { it.toString() }
            fileNames = uris.map { uri -> queryBackupFileName(context, uri) ?: uri.lastPathSegment.orEmpty() }
        }
    }

    // Probe the picked files once for the features they contain; the union
    // across files is what the checkboxes reflect. Everything else is disabled.
    LaunchedEffect(fileUris) {
        if (fileUris.isEmpty()) {
            available = emptySet()
            selected = emptySet()
        } else {
            available = probeFeatures(fileUris)
            selected = available
        }
    }

    BackupModalScaffold(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_import),
        modifier = modifier
    ) {
        PickerCard {
            PickerRow(
                title = stringResource(R.string.settings_import_files),
                subtitle = fileNames.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ?: stringResource(R.string.settings_import_files_hint),
                selected = fileUris.isNotEmpty(),
                onClick = { filePicker.launch(arrayOf("*/*")) },
                onReset = {
                    fileUris = emptyList()
                    fileNames = emptyList()
                }
            )
        }

        if (fileUris.isNotEmpty()) {
            FeatureList(
                title = stringResource(R.string.settings_import_features),
                selected = selected,
                enabled = available,
                onToggle = { feature, checked ->
                    selected = if (checked) selected + feature else selected - feature
                }
            )
        }

        ModalButton(
            label = stringResource(R.string.settings_import),
            filled = true,
            enabled = fileUris.isNotEmpty() && selected.isNotEmpty() && !isRunning,
            onClick = { onImport(fileUris, selected) }
        )
    }
}

/** Loading sheet shown while an import is being applied. */
@Composable
fun BackupLoadingModal(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = {},
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = FoamDimens.spacingExtraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingStandard)
        ) {
            CircularProgressIndicator(
                color = FoamTheme.accentColor,
                modifier = Modifier.size(FoamDimens.iconLarge)
            )
            BasicText(
                text = stringResource(R.string.settings_backup_importing),
                style = FoamTheme.typography.body
            )
        }
    }
}

/** Small confirmation sheet shown after an export/import finishes. */
@Composable
fun BackupResultModal(
    result: com.foamplayer.app.data.backup.BackupResult?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    FoamBottomModal(
        visible = result != null,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            BasicText(
                text = stringResource(
                    if (result?.isSuccess == true) R.string.settings_backup_done
                    else R.string.settings_backup_failed
                ),
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingSmall,
                    vertical = FoamDimens.spacingTiny
                )
            )
            BasicText(
                text = when {
                    result == null -> ""
                    result.isSuccess -> result.details.orEmpty()
                    else -> result.errorMessage.orEmpty()
                },
                style = FoamTheme.typography.body,
                modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
            )
            ModalButton(
                label = stringResource(R.string.ok),
                filled = true,
                onClick = onDismiss
            )
        }
    }
}

/**
 * Shared sheet chrome for the backup pickers: title, then [content] laid out
 * in a spaced column -- rows go in cards, and the confirm button sits OUTSIDE
 * them so the card never runs into the button (same layout as the playlist
 * sheets in [CreatePlaylistModal]).
 */
@Composable
private fun BackupModalScaffold(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingSmall,
                    vertical = FoamDimens.spacingTiny
                )
            )

            content()
        }
    }
}

/** Rounded surfaceVariant card wrapping a picker row, as on the playlist sheets. */
@Composable
private fun PickerCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .foamSurfaceVariant()
    ) {
        content()
    }
}

/** Section header above the feature checkboxes. */
@Composable
private fun FeatureList(
    title: String,
    selected: Set<BackupFeature>,
    enabled: Set<BackupFeature>,
    onToggle: (BackupFeature, Boolean) -> Unit
) {
    BasicText(
        text = title,
        style = FoamTheme.typography.titleMedium,
        modifier = Modifier.padding(
            horizontal = FoamDimens.paddingSmall,
            vertical = FoamDimens.spacingTiny
        )
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .foamSurfaceVariant()
    ) {
        BackupFeature.entries.forEachIndexed { index, feature ->
            if (index > 0) ModalSectionDivider()
            FeatureCheckRow(
                title = featureLabel(feature),
                checked = feature in selected,
                enabled = feature in enabled,
                onCheckedChange = { onToggle(feature, it) }
            )
        }
    }
}

@Composable
private fun featureLabel(feature: BackupFeature): String = when (feature) {
    BackupFeature.APP_SETTINGS -> stringResource(R.string.settings_backup_feature_app)
    BackupFeature.MUSIC_FOLDERS -> stringResource(R.string.settings_backup_feature_folders)
    BackupFeature.EQUALIZER_PRESETS -> stringResource(R.string.settings_backup_feature_eq)
    BackupFeature.PLAYLISTS -> stringResource(R.string.settings_backup_feature_playlists)
    BackupFeature.SONG_STATISTICS -> stringResource(R.string.settings_backup_feature_stats)
    BackupFeature.SEARCH_HISTORY -> stringResource(R.string.settings_backup_feature_search)
}

/** Checkbox row used for the feature list; disabled rows are dimmed and inert. */
@Composable
private fun FeatureCheckRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingStandard),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 2.dp,
                    color = if (checked) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
                    shape = RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))

        BasicText(
            text = title,
            style = FoamTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Selectable row used for the folder / file pickers and the single-file
 * toggle: a colored dot marks a selection, and a clear button appears once
 * something is picked.
 */
@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .foamBackground(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
                modifier = Modifier.size(FoamDimens.iconSmall)
            )
        }

        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = subtitle,
                style = FoamTheme.typography.body.copy(
                    color = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = onReset
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.clear_selection),
                    tint = FoamTheme.colors.textSecondary,
                    modifier = Modifier.size(FoamDimens.iconSmall)
                )
            }
        }
    }
}

private fun queryBackupFileName(context: Context, uri: Uri): String? =
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

private fun backupFolderDisplayName(uri: Uri): String {
    if (uri.scheme != "content") return uri.lastPathSegment ?: uri.toString()
    return runCatching {
        val treeId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        Uri.decode(treeId.substringAfter(':')).ifBlank { uri.toString() }
    }.getOrDefault(uri.toString())
}
