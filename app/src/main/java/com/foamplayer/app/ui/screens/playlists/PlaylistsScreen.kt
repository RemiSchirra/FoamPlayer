package com.foamplayer.app.ui.screens.playlists

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.components.AlbumArtBoundsTransform
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.FoamBottomModal
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

// Mirrors CollectionListItem's row geometry so playlist rows are visually
// identical to the app's other list rows.
private val PlaylistArtSize = 48.dp
private val PlaylistHighlightPadding = 6.dp

// Must match TrackListScreen's AlbumArtShape — using the same shape on both ends of the
// shared-element transition means the corners never have to jump between two radii.
private val PlaylistAlbumArtShape = RoundedCornerShape(12.dp)

private val ImageMimeTypes = arrayOf("image/*")

@Composable
fun PlaylistsScreen(
    onPlaylistClick: (PlaylistEntity) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsState()
    val trackCounts by viewModel.trackCounts.collectAsState()
    val createState by viewModel.createState.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()

    var showCreateModal by remember { mutableStateOf(false) }
    var showMoreModal by remember { mutableStateOf(false) }
    // Edition mode placeholder — toggled by the pencil, nothing editable yet.
    var isEditMode by remember { mutableStateOf(false) }

    // Long-press actions (edit image / rename / details / delete), hosted here
    // like the other screens host their long-press modals.
    val playlistModalState = rememberPlaylistFoamModalState()
    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }

    // Cover image picker: armed by the header pencil, the result applies to the
    // playlist it was opened for.
    var imagePickerTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    val context = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = imagePickerTarget
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setPlaylistImage(target.playlistId, uri.toString())
        }
        imagePickerTarget = null
    }

    // Track stats for the modal's details page, loaded for the shown playlist.
    var modalStats by remember { mutableStateOf<PlaylistModalStats?>(null) }
    LaunchedEffect(playlistModalState.playlist?.playlistId) {
        val id = playlistModalState.playlist?.playlistId
        modalStats = null
        if (id != null) {
            val (count, durationMs) = viewModel.playlistTrackStats(id)
            modalStats = PlaylistModalStats(id, count, durationMs)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // Thin header: playlist icon inline with the big title, actions on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = FoamDimens.spacingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FoamTheme.icons.current.playlists,
                contentDescription = null,
                tint = FoamTheme.accentColor,
                modifier = Modifier.size(34.dp)
            )

            Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))

            BasicText(
                text = stringResource(R.string.playlists),
                style = FoamTheme.typography.titleLarge.copy(fontSize = 30.sp),
                modifier = Modifier.weight(1f)
            )

            PlaylistActionButton(
                icon = Icons.Rounded.Edit,
                contentDescription = stringResource(R.string.edit),
                tint = if (isEditMode) FoamTheme.accentColor else FoamTheme.colors.textPrimary
            ) {
                isEditMode = !isEditMode
            }
            PlaylistActionButton(
                icon = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.add)
            ) {
                showCreateModal = true
            }
            PlaylistActionButton(
                icon = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.navbar_item_more)
            ) {
                showMoreModal = true
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
                items(playlists, key = { it.playlistId }) { playlist ->
                    PlaylistItemRow(
                        playlist = playlist,
                        trackCount = trackCounts[playlist.playlistId] ?: 0,
                        onClick = { onPlaylistClick(playlist) },
                        onLongClick = { playlistModalState.show(playlist) },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    CreatePlaylistModal(
        visible = showCreateModal,
        onDismiss = { showCreateModal = false },
        onCreate = { name, m3uUri, folderUri, coverUri ->
            showCreateModal = false
            viewModel.createPlaylistFromSource(name, m3uUri, folderUri, coverUri)
        }
    )

    PlaylistFoamModal(
        state = playlistModalState,
        stats = modalStats,
        onEditImage = { playlist ->
            imagePickerTarget = playlist
            coverPicker.launch(ImageMimeTypes)
        },
        onEditName = { playlist -> renameTarget = playlist },
        onDelete = { playlist -> viewModel.deletePlaylist(playlist.playlistId) },
        onDismiss = { playlistModalState.dismiss() }
    )

    RenamePlaylistModal(
        playlist = renameTarget,
        onRename = { playlistId, newName ->
            renameTarget = null
            viewModel.renamePlaylist(playlistId, newName)
        },
        onDismiss = { renameTarget = null }
    )

    // Placeholder for future playlist-level options (sort, export, ...).
    FoamBottomModal(
        visible = showMoreModal,
        onDismissRequest = { showMoreModal = false }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            BasicText(
                text = stringResource(R.string.more_options),
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
                    .background(FoamTheme.colors.surfaceVariant)
                    .padding(
                        horizontal = FoamDimens.paddingMedium,
                        vertical = FoamDimens.paddingMedium
                    )
            ) {
                BasicText(
                    text = stringResource(R.string.more_options_placeholder),
                    style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary)
                )
            }
        }
    }

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

/** One icon button on the playlists action bar, styled like the player's icon rows. */
@Composable
private fun PlaylistActionButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = FoamTheme.colors.textPrimary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(FoamDimens.paddingSmall),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(FoamDimens.iconMedium)
        )
    }
}

/**
 * TrackListItem/CollectionListItem equivalent for a playlist: 48dp cover (the
 * user-chosen image, or the name's initial as fallback), the name and its
 * track count. Long-press opens the playlist actions modal. When opened, the
 * artwork and title animate into the playlist detail header through the
 * shared-element keys "playlist-art-*" / "playlist-title-*".
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistItemRow(
    playlist: PlaylistEntity,
    trackCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val imageRadius = (PlaylistArtSize / 2) * FoamTheme.shapes.miniPlayerRounding
    val highlightShape = RoundedCornerShape(imageRadius + PlaylistHighlightPadding)

    // Rows taking part in the shared-element transition must use the same fixed corner
    // radius as the playlist detail header; the others round like the other list rows.
    val artShape = PlaylistAlbumArtShape

    @Composable
    fun Modifier.sharedArtwork(): Modifier = with(sharedTransitionScope) {
        sharedElement(
            rememberSharedContentState(key = "playlist-art-${playlist.playlistId}"),
            animatedVisibilityScope = animatedVisibilityScope,
            clipInOverlayDuringTransition = OverlayClip(PlaylistAlbumArtShape),
            boundsTransform = AlbumArtBoundsTransform
        )
    }

    @Composable
    fun Modifier.sharedTitle(): Modifier = with(sharedTransitionScope) {
        sharedBounds(
            rememberSharedContentState(key = "playlist-title-${playlist.playlistId}"),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = AlbumArtBoundsTransform
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(highlightShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = FoamTheme.colors.textPrimary),
                    onLongClick = onLongClick,
                    onClick = onClick
                )
                .padding(
                    horizontal = PlaylistHighlightPadding,
                    vertical = PlaylistHighlightPadding
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(PlaylistArtSize)
                    .sharedArtwork()
                    .clip(artShape)
                    .background(FoamTheme.colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (playlist.imageUrl != null) {
                    val coverRequest = remember(playlist.imageUrl) {
                        ImageRequest.Builder(context)
                            .data(playlist.imageUrl)
                            .size(AlbumArtRequestSize)
                            .build()
                    }
                    AsyncImage(
                        model = coverRequest,
                        contentDescription = stringResource(R.string.playlist_cover_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    BasicText(
                        text = playlist.name.take(1).uppercase(),
                        style = FoamTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = playlist.name,
                    style = FoamTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedTitle()
                )
                BasicText(
                    text = "$trackCount tracks",
                    style = FoamTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
