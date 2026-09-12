package com.foamplayer.app.ui.screens.playlists

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun PlaylistsScreen(
    onPlaylistClick: (PlaylistEntity) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsState()
    val createState by viewModel.createState.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()
    var showCreateModal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = stringResource(R.string.playlists),
                style = FoamTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(FoamTheme.accentColor)
                    .clickable { showCreateModal = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                BasicText(
                    text = stringResource(R.string.create_playlist),
                    style = FoamTheme.typography.body.copy(color = FoamTheme.colors.background)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = stringResource(R.string.no_playlists_yet),
                    style = FoamTheme.typography.body
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(playlists) { playlist ->
                    PlaylistItemRow(
                        playlist = playlist,
                        onClick = { onPlaylistClick(playlist) }
                    )
                }
            }
        }
    }

    CreatePlaylistModal(
        visible = showCreateModal,
        onDismiss = { showCreateModal = false },
        onCreate = { name, m3uUri, folderUri ->
            showCreateModal = false
            viewModel.createPlaylistFromSource(name, m3uUri, folderUri)
        }
    )

    val missingState = when (val state = createState) {
        is CreatePlaylistState.M3uSongsMissing ->
            MissingSongsUiState(
                playlistName = state.playlistName,
                missing = state.missing,
                folderUri = null,
                isScanning = false,
                scanProgress = 0f
            )
        is CreatePlaylistState.FolderSongsMissing ->
            MissingSongsUiState(
                playlistName = state.name,
                missing = state.missing,
                folderUri = state.folderUri,
                isScanning = false,
                scanProgress = 0f
            )
        is CreatePlaylistState.ImportingFolder ->
            MissingSongsUiState(
                playlistName = state.name,
                missing = state.missing,
                folderUri = state.folderUri,
                isScanning = true,
                scanProgress = (scanStatus as? ScanStatus.Scanning)?.progress ?: 0f
            )
        CreatePlaylistState.Idle -> null
    }

    missingState?.let { state ->
        MissingSongsModal(
            state = state,
            onDismiss = { if (!state.isScanning) viewModel.cancelMissingSongs() },
            onKeep = { viewModel.dismissMissingSongs() },
            onCancel = { viewModel.cancelMissingSongs() },
            onImportFolder = { viewModel.importMissingFolderAndCreate() }
        )
    }
}

@Composable
fun PlaylistItemRow(playlist: PlaylistEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(FoamTheme.colors.surfaceVariant)
                .padding(12.dp)
        ) {
            BasicText(
                text = "📁",
                style = FoamTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        BasicText(
            text = playlist.name,
            style = FoamTheme.typography.titleMedium
        )
    }
}
