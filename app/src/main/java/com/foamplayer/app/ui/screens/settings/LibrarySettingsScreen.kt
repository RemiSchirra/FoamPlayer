package com.foamplayer.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun LibrarySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val selectedFolders = settings.libraryFolders
    val scanStatus by viewModel.scanStatus.collectAsState()
    val artistSeparators = settings.artistSeparators
    val genreSeparators = settings.genreSeparators
    val context = LocalContext.current

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.addFolder(it.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.library),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium)
        ) {
            // --- Library Folders ---
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(text = stringResource(R.string.library_folders), style = FoamTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                            .background(FoamTheme.accentColor)
                            .clickable { folderPickerLauncher.launch(null) }
                            .padding(horizontal = FoamDimens.spacingMedium, vertical = FoamDimens.spacingSmall)
                    ) {
                        BasicText(
                            text = stringResource(R.string.add_folder), 
                            style = FoamTheme.typography.caption.copy(
                                color = FoamTheme.colors.background,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            }

            if (selectedFolders.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                            .padding(vertical = FoamDimens.paddingLarge),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(text = stringResource(R.string.no_folders_selected), style = FoamTheme.typography.body)
                    }
                }
            } else {
                items(selectedFolders.sorted()) { folder ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = FoamDimens.spacingTiny)
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(FoamDimens.spacingMedium),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicText(
                                text = folder, 
                                style = FoamTheme.typography.caption, 
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                            BasicText(
                                text = stringResource(R.string.remove),
                                style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor, fontWeight = FontWeight.Bold),
                                modifier = Modifier.clickable { viewModel.removeFolder(folder) }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(FoamDimens.spacingLarge)) }

            // --- Metadata Separators ---
            item {
                BasicText(text = stringResource(R.string.metadata_separators), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                
                SeparatorSettingsCard(
                    title = stringResource(R.string.artist_separators),
                    separators = artistSeparators,
                    onAdd = { viewModel.setArtistSeparators(artistSeparators + it) },
                    onRemove = { viewModel.setArtistSeparators(artistSeparators - it) }
                )
                
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                
                SeparatorSettingsCard(
                    title = stringResource(R.string.genre_separators),
                    separators = genreSeparators,
                    onAdd = { viewModel.setGenreSeparators(genreSeparators + it) },
                    onRemove = { viewModel.setGenreSeparators(genreSeparators - it) }
                )
                
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            // --- Library Scan ---
            item {
                BasicText(text = stringResource(R.string.library_scan), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    Column(Modifier.fillMaxWidth().padding(FoamDimens.paddingMedium)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                BasicText(text = stringResource(R.string.scan_media), style = FoamTheme.typography.titleMedium)
                                when (val status = scanStatus) {
                                    is ScanStatus.Scanning -> {
                                        BasicText(
                                            text = stringResource(R.string.scanning_status, status.currentFile, (status.progress * 100).toInt()),
                                            style = FoamTheme.typography.caption
                                        )
                                    }
                                    is ScanStatus.Finished -> {
                                        BasicText(
                                            text = stringResource(R.string.scan_finished, status.count),
                                            style = FoamTheme.typography.caption
                                        )
                                    }
                                    else -> {
                                        BasicText(text = stringResource(R.string.sync_library), style = FoamTheme.typography.body)
                                    }
                                }
                            }
                            
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                                    .background(if (scanStatus is ScanStatus.Scanning) FoamTheme.colors.surfaceVariant else FoamTheme.accentColor)
                                    .clickable(enabled = scanStatus !is ScanStatus.Scanning) { viewModel.startScan() }
                                    .padding(horizontal = FoamDimens.spacingStandard, vertical = FoamDimens.spacingSmall)
                            ) {
                                BasicText(
                                    text = if (scanStatus is ScanStatus.Scanning) stringResource(R.string.scanning_caps) else stringResource(R.string.start_scan),
                                    style = FoamTheme.typography.caption.copy(
                                        color = if (scanStatus is ScanStatus.Scanning) FoamTheme.colors.textSecondary else FoamTheme.colors.background,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                        
                        if (scanStatus is ScanStatus.Scanning) {
                            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.spacingTiny)
                                    .clip(RoundedCornerShape(FoamDimens.spacingTiny / 2))
                                    .background(FoamTheme.colors.surfaceVariant)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth((scanStatus as ScanStatus.Scanning).progress)
                                        .height(FoamDimens.spacingTiny)
                                        .background(FoamTheme.accentColor)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            }
        }
    }
}

@Composable
fun SeparatorSettingsCard(
    title: String,
    separators: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var newSeparator by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
            .padding(FoamDimens.paddingMedium)
    ) {
        Column {
            BasicText(text = title, style = FoamTheme.typography.body.copy(fontWeight = FontWeight.Bold, color = FoamTheme.colors.textPrimary))
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
            
            // Current list
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
            ) {
                separators.sorted().forEach { sep ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(FoamDimens.spacingTiny))
                            .background(FoamTheme.colors.surfaceVariant)
                            .padding(horizontal = FoamDimens.spacingSmall, vertical = FoamDimens.spacingTiny)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(text = sep, style = FoamTheme.typography.caption)
                            Spacer(modifier = Modifier.width(FoamDimens.spacingTiny))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.remove),
                                tint = FoamTheme.accentColor,
                                modifier = Modifier
                                    .size(FoamDimens.iconTiny)
                                    .clickable { onRemove(sep) }
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(FoamDimens.spacingStandard))
            
            // Add new
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(FoamDimens.buttonRounding / 2))
                        .background(FoamTheme.colors.surfaceVariant)
                        .padding(horizontal = FoamDimens.spacingMedium),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (newSeparator.isEmpty()) {
                        BasicText(
                            text = stringResource(R.string.add_separator),
                            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
                        )
                    }
                    BasicTextField(
                        value = newSeparator,
                        onValueChange = { newSeparator = it },
                        textStyle = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary),
                        cursorBrush = SolidColor(FoamTheme.accentColor),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(FoamDimens.buttonRounding / 2))
                        .background(if (newSeparator.isBlank()) FoamTheme.colors.surfaceVariant else FoamTheme.accentColor)
                        .clickable(enabled = newSeparator.isNotBlank()) {
                            onAdd(newSeparator)
                            newSeparator = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add),
                        tint = if (newSeparator.isBlank()) FoamTheme.colors.textSecondary else FoamTheme.colors.background,
                        modifier = Modifier.size(FoamDimens.spacingLarge - FoamDimens.spacingSmall)
                    )
                }
            }
        }
    }
}
