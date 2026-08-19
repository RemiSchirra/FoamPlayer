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
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.foamplayer.app.ui.theme.FoamIconStyle
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val isBitPerfectEnabled by viewModel.isBitPerfectEnabled.collectAsState()
    val iconStyle by viewModel.iconStyle.collectAsState()
    val miniRounding by viewModel.miniPlayerRounding.collectAsState()
    val fullRounding by viewModel.fullPlayerRounding.collectAsState()
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
            Spacer(modifier = Modifier.height(16.dp))
            BasicText(
                text = "Settings & Audio Path",
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // --- Audio Engine Settings ---
        item {
            BasicText(text = "Audio Engine", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = "Prefer Bit-Perfect Output",
                            style = FoamTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        BasicText(
                            text = "Bypass AudioFlinger hardware resampler on compatible USB DACs.",
                            style = FoamTheme.typography.body
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isBitPerfectEnabled) FoamTheme.colors.bitPerfectActive
                                else FoamTheme.colors.surfaceVariant
                            )
                            .clickable { viewModel.setBitPerfectEnabled(!isBitPerfectEnabled) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        BasicText(
                            text = if (isBitPerfectEnabled) "ENABLED" else "DISABLED",
                            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.background)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // --- UI Customization ---
        item {
            BasicText(text = "UI Customization", style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    BasicText(text = "Icon Style", style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FoamIconStyle.entries.forEach { style ->
                            val isSelected = style == iconStyle
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) FoamTheme.colors.primaryAccent
                                        else FoamTheme.colors.surfaceVariant
                                    )
                                    .clickable { viewModel.setIconStyle(style) }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val iconSet = when (style) {
                                        FoamIconStyle.SOLID -> FoamTheme.icons.solid
                                        FoamIconStyle.OUTLINE -> FoamTheme.icons.outline
                                        FoamIconStyle.FLAT -> FoamTheme.icons.flat
                                    }
                                    Icon(
                                        imageVector = iconSet.play,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    BasicText(
                                        text = style.label,
                                        style = FoamTheme.typography.body.copy(
                                            color = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
                                        )
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    BasicText(text = "Image Rounding", style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BasicText(text = "Mini Player", style = FoamTheme.typography.body)
                            BasicText(text = "${miniRounding}dp", style = FoamTheme.typography.monospace)
                        }
                        Slider(
                            value = miniRounding.toFloat(),
                            onValueChange = { viewModel.setMiniPlayerRounding(it.roundToInt()) },
                            valueRange = 0f..32f,
                            steps = 7,
                            colors = SliderDefaults.colors(
                                thumbColor = FoamTheme.colors.primaryAccent,
                                activeTrackColor = FoamTheme.colors.primaryAccent,
                                inactiveTrackColor = FoamTheme.colors.surfaceVariant
                            )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BasicText(text = "Full Player", style = FoamTheme.typography.body)
                            BasicText(text = "${fullRounding}dp", style = FoamTheme.typography.monospace)
                        }
                        Slider(
                            value = fullRounding.toFloat(),
                            onValueChange = { viewModel.setFullPlayerRounding(it.roundToInt()) },
                            valueRange = 0f..64f,
                            steps = 7,
                            colors = SliderDefaults.colors(
                                thumbColor = FoamTheme.colors.primaryAccent,
                                activeTrackColor = FoamTheme.colors.primaryAccent,
                                inactiveTrackColor = FoamTheme.colors.surfaceVariant
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
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
                        Modifier.fillMaxWidth(),
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
                Column(Modifier.fillMaxWidth()) {
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
                        Spacer(modifier = Modifier.height(8.dp))
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
