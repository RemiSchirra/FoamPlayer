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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.components.FoamGlassCard
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .padding(horizontal = 16.dp)
    ) {
        item {
            SettingsHeader(title = "Library", onBack = onBack)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // --- Library Folders ---
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(text = "Library Folders", style = FoamTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(FoamTheme.colors.primaryAccent)
                        .clickable { folderPickerLauncher.launch(null) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    BasicText(text = "Add Folder", style = FoamTheme.typography.body.copy(color = FoamTheme.colors.background))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (selectedFolders.isEmpty()) {
            item {
                FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        BasicText(text = "No folders selected", style = FoamTheme.typography.body)
                    }
                }
            }
        } else {
            items(selectedFolders.sorted()) { folder ->
                FoamGlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(text = folder, style = FoamTheme.typography.caption, modifier = Modifier.weight(1f))
                        BasicText(
                            text = "Remove",
                            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.primaryAccent),
                            modifier = Modifier.clickable { viewModel.removeFolder(folder) }
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        // --- Metadata Separators ---
        item {
            BasicText(text = "Metadata Separators", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            
            SeparatorSettingsCard(
                title = "Artist Separators",
                separators = artistSeparators,
                onAdd = { viewModel.setArtistSeparators(artistSeparators + it) },
                onRemove = { viewModel.setArtistSeparators(artistSeparators - it) }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SeparatorSettingsCard(
                title = "Genre Separators",
                separators = genreSeparators,
                onAdd = { viewModel.setGenreSeparators(genreSeparators + it) },
                onRemove = { viewModel.setGenreSeparators(genreSeparators - it) }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
        }

        // --- Library Scan ---
        item {
            BasicText(text = "Library Scan", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(text = "Scan Media", style = FoamTheme.typography.titleMedium)
                            when (val status = scanStatus) {
                                is ScanStatus.Scanning -> {
                                    BasicText(
                                        text = "Scanning: ${status.currentFile} (${(status.progress * 100).toInt()}%)",
                                        style = FoamTheme.typography.caption
                                    )
                                }
                                is ScanStatus.Finished -> {
                                    BasicText(
                                        text = "Scan finished: ${status.count} tracks found",
                                        style = FoamTheme.typography.caption
                                    )
                                }
                                else -> {}
                            }
                        }
                        
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (scanStatus is ScanStatus.Scanning) FoamTheme.colors.surfaceVariant else FoamTheme.colors.primaryAccent)
                                .clickable(enabled = scanStatus !is ScanStatus.Scanning) { viewModel.startScan() }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            BasicText(
                                text = if (scanStatus is ScanStatus.Scanning) "Scanning..." else "Full Scan",
                                style = FoamTheme.typography.body.copy(color = if (scanStatus is ScanStatus.Scanning) FoamTheme.colors.textSecondary else FoamTheme.colors.background)
                            )
                        }
                    }
                    
                    if (scanStatus is ScanStatus.Scanning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(FoamTheme.colors.surfaceVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((scanStatus as ScanStatus.Scanning).progress)
                                    .height(4.dp)
                                    .background(FoamTheme.colors.primaryAccent)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
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

    FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            BasicText(text = title, style = FoamTheme.typography.body)
            Spacer(modifier = Modifier.height(8.dp))
            
            // Current list
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                separators.sorted().forEach { sep ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(FoamTheme.colors.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(text = sep, style = FoamTheme.typography.caption)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = FoamTheme.colors.primaryAccent,
                                modifier = Modifier
                                    .size(12.dp)
                                    .clickable { onRemove(sep) }
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Add new
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FoamTheme.colors.surfaceVariant)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (newSeparator.isEmpty()) {
                        BasicText(
                            text = "Add separator...",
                            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
                        )
                    }
                    BasicTextField(
                        value = newSeparator,
                        onValueChange = { newSeparator = it },
                        textStyle = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary),
                        cursorBrush = SolidColor(FoamTheme.colors.primaryAccent),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (newSeparator.isBlank()) FoamTheme.colors.surfaceVariant else FoamTheme.colors.primaryAccent)
                        .clickable(enabled = newSeparator.isNotBlank()) {
                            onAdd(newSeparator)
                            newSeparator = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add",
                        tint = if (newSeparator.isBlank()) FoamTheme.colors.textSecondary else FoamTheme.colors.background,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
