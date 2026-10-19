package com.foamplayer.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import com.foamplayer.app.R
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.distant.DistantServer

/**
 * Folders + scan cards shared by the onboarding Library page and the Library
 * settings screen, so both always render and behave identically.
 */

/**
 * Folders card: "Library Folders" title with an ADD FOLDER accent pill, then
 * the empty state or one row per folder with a remove (×) button.
 */
@Composable
fun LibraryFoldersCard(
    folders: Set<String>,
    servers: List<DistantServer>,
    onAddProvider: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRemoveServer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
            .padding(FoamDimens.paddingMedium)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = stringResource(R.string.library_folders),
                    style = FoamTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                AddProviderButton(onClick = onAddProvider)
            }
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            if (folders.isEmpty() && servers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surfaceVariant)
                        .padding(vertical = FoamDimens.paddingLarge),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = stringResource(R.string.no_folders_selected),
                        style = FoamTheme.typography.body
                    )
                }
            } else {
                // Sorted once per folder-set change: sorting inside the layout
                // scope would re-sort on every recomposition.
                val sortedFolders = remember(folders) { folders.sortedBy { folderDisplayName(it) } }
                sortedFolders.forEach { folder ->
                    FolderRow(folder = folder, onRemove = onRemoveFolder)
                }

                if (servers.isNotEmpty()) {
                    if (folders.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    }
                    BasicText(
                        text = stringResource(R.string.connected_servers),
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                    servers.forEach { server ->
                        ServerRow(server = server, onRemove = onRemoveServer)
                    }
                }
            }
        }
    }
}

/** One connected distant server: each picked folder as "provider/folder", with a remove cross. */
@Composable
private fun ServerRow(server: DistantServer, onRemove: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FoamDimens.spacingTiny)
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surfaceVariant)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(FoamDimens.spacingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (server.folders.isEmpty()) {
                    BasicText(
                        text = server.name,
                        style = FoamTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    server.folders.forEach { folder ->
                        BasicText(
                            text = "${server.name}:${server.credentials.username}/${folder.name}",
                            style = FoamTheme.typography.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
            Box(
                modifier = Modifier
                    .size(FoamDimens.iconMedium)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = { onRemove(server.id) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FoamTheme.icons.current.close,
                    contentDescription = stringResource(R.string.remove),
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(FoamDimens.iconSmall)
                )
            }
        }
    }
}

/**
 * Scan card: a "Scan Media" title, a one-line status, a START SCAN / SCANNING
 * pill and an animated progress bar while scanning.
 */
@Composable
fun LibraryScanCard(
    scanStatus: ScanStatus,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
            .padding(FoamDimens.paddingMedium)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = stringResource(R.string.scan_media),
                        style = FoamTheme.typography.titleMedium
                    )
                    when (val status = scanStatus) {
                        is ScanStatus.Scanning -> ScanningStatusLine(status)
                        is ScanStatus.Finished -> BasicText(
                            text = stringResource(R.string.scan_finished, status.count),
                            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.bitPerfectActive)
                        )
                        else -> BasicText(
                            text = stringResource(R.string.sync_library),
                            style = FoamTheme.typography.body
                        )
                    }
                }

                val isScanning = scanStatus is ScanStatus.Scanning
                ScanPillButton(scanning = isScanning, onClick = onStartScan)
            }

            if (scanStatus is ScanStatus.Scanning) {
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                ScanProgressBar(progress = scanStatus.progress)
            }
        }
    }
}

/**
 * Repair card: the repair title, a one-line explanation and a REPAIR /
 * REPAIRING pill. Runs [com.foamplayer.app.data.repository.PlaylistRepairer],
 * which re-links playlist entries left dangling by moved files between
 * rescans.
 */
@Composable
fun RepairPlaylistsCard(
    running: Boolean,
    onRepair: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
            .padding(FoamDimens.paddingMedium)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = stringResource(R.string.playlist_repair),
                    style = FoamTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                BasicText(
                    text = stringResource(R.string.playlist_repair_description),
                    style = FoamTheme.typography.caption
                )
            }

            Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))
            RepairPillButton(running = running, onClick = onRepair)
        }
    }
}

/** REPAIR <-> REPAIRING pill; disabled (surfaceVariant) while repairing. */
@Composable
private fun RepairPillButton(running: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(if (running) FoamTheme.colors.surfaceVariant else FoamTheme.accentColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                enabled = !running,
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.spacingStandard, vertical = FoamDimens.spacingSmall)
    ) {
        BasicText(
            text = if (running) stringResource(R.string.playlist_repairing_caps) else stringResource(R.string.playlist_repair_caps),
            style = FoamTheme.typography.caption.copy(
                color = if (running) FoamTheme.colors.textSecondary else FoamTheme.colors.background,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

/**
 * One-line scan status: "song name (xx%)" — the song name ellipsizes when it
 * runs out of width; the percentage, split into its own text, always stays
 * visible.
 */
@Composable
private fun ScanningStatusLine(status: ScanStatus.Scanning) {
    if (status.progress >= 1f) {
        BasicText(
            text = stringResource(R.string.scan_finishing),
            style = FoamTheme.typography.caption
        )
        return
    }

    val percent = (status.progress * 100).toInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = status.currentFile,
            style = FoamTheme.typography.caption,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        BasicText(
            text = stringResource(R.string.scanning_status_percent, percent),
            style = FoamTheme.typography.caption,
            maxLines = 1
        )
    }
}

/** One folder row: name on a surfaceVariant pill with a circular remove cross. */
@Composable
private fun FolderRow(folder: String, onRemove: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FoamDimens.spacingTiny)
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surfaceVariant)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(FoamDimens.spacingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = folderDisplayName(folder),
                style = FoamTheme.typography.caption,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
            // Circular clip bounds the ripple around the cross.
            Box(
                modifier = Modifier
                    .size(FoamDimens.iconMedium)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = { onRemove(folder) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FoamTheme.icons.current.close,
                    contentDescription = stringResource(R.string.remove),
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(FoamDimens.iconSmall)
                )
            }
        }
    }
}

/** Accent ADD PROVIDER pill. */
@Composable
private fun AddProviderButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(FoamTheme.accentColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.spacingMedium, vertical = FoamDimens.spacingSmall)
    ) {
        BasicText(
            text = stringResource(R.string.add_provider),
            style = FoamTheme.typography.caption.copy(
                color = FoamTheme.colors.background,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

/** START SCAN <-> SCANNING pill; disabled (surfaceVariant) while scanning. */
@Composable
private fun ScanPillButton(scanning: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(if (scanning) FoamTheme.colors.surfaceVariant else FoamTheme.accentColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                enabled = !scanning,
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.spacingStandard, vertical = FoamDimens.spacingSmall)
    ) {
        BasicText(
            text = if (scanning) stringResource(R.string.scanning_caps) else stringResource(R.string.start_scan),
            style = FoamTheme.typography.caption.copy(
                color = if (scanning) FoamTheme.colors.textSecondary else FoamTheme.colors.background,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

/** Full-width accent progress bar with the animated scan progress. */
@Composable
private fun ScanProgressBar(progress: Float) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200),
        label = "libraryScanProgress"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FoamDimens.spacingTiny)
            .clip(RoundedCornerShape(FoamDimens.spacingTiny / 2))
            .background(FoamTheme.colors.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(FoamDimens.spacingTiny)
                .background(FoamTheme.accentColor)
        )
    }
}

/**
 * Extracts a human-readable name from a SAF tree URI such as
 * "content://com.android.externalstorage.documents/tree/primary%3AMusic/Jazz".
 * Returns the decoded path after the volume prefix (e.g. "Music/Jazz"),
 * falling back to the raw URI when the format is unexpected.
 */
internal fun folderDisplayName(folderUri: String): String {
    val segments = try {
        folderUri.toUri().pathSegments
    } catch (_: Exception) {
        return folderUri
    }
    val treeIndex = segments.indexOf("tree")
    if (treeIndex == -1 || treeIndex >= segments.lastIndex) return folderUri
    val treePath = segments[treeIndex + 1]
    val relativePath = treePath.substringAfter(':', missingDelimiterValue = "")
    if (relativePath.isNotBlank()) return relativePath
    val volume = treePath.substringBefore(':', missingDelimiterValue = "")
    return volume.ifBlank { folderUri }
}
