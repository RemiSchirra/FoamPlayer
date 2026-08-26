package com.foamplayer.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val selectedFolders by viewModel.selectedFolders.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()
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
            items(selectedFolders.toList()) { folder ->
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
