package com.foamplayer.app.ui.screens.playlists

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.components.AlbumArtBoundsTransform
import com.foamplayer.app.ui.components.AlbumArtImage
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.AlbumArtShape
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.components.DensityOrganisationModal
import com.foamplayer.app.ui.components.MediaGridCell
import com.foamplayer.app.ui.components.MediaListRow
import com.foamplayer.app.ui.components.activeRowHighlight
import com.foamplayer.app.ui.components.rememberDragDropGridState
import com.foamplayer.app.ui.components.caseInsensitiveComparator
import com.foamplayer.app.ui.components.rememberSortedList
import com.foamplayer.app.ui.screens.queue.rememberDragDropListState
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme

// The playlist row shares the app-wide list row geometry through MediaListRow /
// MediaGridCell; artwork corners come from the shared percentage-based AlbumArtShape().

private val ImageMimeTypes = arrayOf("image/*")

@Composable
fun PlaylistsScreen(
    onPlaylistClick: (PlaylistEntity) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    activeCollectionId: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    onDensityChange: ((MediaDensity) -> Unit)? = null,
    sortMode: CollectionSortMode = CollectionSortMode.CUSTOM,
    onSortChange: ((CollectionSortMode) -> Unit)? = null,
    sortInverted: Boolean = false,
    onSortInvertedChange: ((Boolean) -> Unit)? = null,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsState()
    val trackCounts by viewModel.trackCounts.collectAsState()
    val createState by viewModel.createState.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()

    var showCreateModal by remember { mutableStateOf(false) }
    var showMoreModal by remember { mutableStateOf(false) }
    // Edition mode: toggled by the pencil — a 2D drag handle appears on every
    // playlist and they can be dragged around to reorder them.
    var isEditMode by remember { mutableStateOf(false) }

    // The playlist list has its own user-defined order (drag & drop writes
    // sortOrder): reordering is only meaningful in CUSTOM, so any other sort
    // exits edit mode and the pencil below is not rendered until then.
    LaunchedEffect(sortMode) {
        if (sortMode != CollectionSortMode.CUSTOM) {
            isEditMode = false
        }
    }

    // In edit mode, back means "leave edit mode", not "leave the screen".
    BackHandler(enabled = isEditMode) {
        isEditMode = false
    }

    // The user-defined order (or the modal's sort) the rows display.
    val sortedPlaylists = rememberSortedList(playlists, sortMode, sortInverted) {
        caseInsensitiveComparator { it.name }
    }

    // Edit-mode drag & drop: a local display list absorbs the swaps live; the
    // DB (and the Room flow) is only written once the drag ends. While a drag
    // is in flight, incoming playlist emissions are ignored so they can't
    // fight the reorder.
    var displayedPlaylists by remember { mutableStateOf(playlists) }
    var dragOriginIndex by remember { mutableStateOf<Int?>(null) }
    // True once the finger lifts: the item keeps flying to its slot (settle
    // animation) but the lift scale already animates back down in parallel.
    var dragReleased by remember { mutableStateOf(false) }
    LaunchedEffect(sortedPlaylists) {
        if (dragOriginIndex == null) {
            displayedPlaylists = sortedPlaylists
        }
    }

    // Plain (non-saveable) list states: the screen always opens at the top of
    // the list instead of restoring a stale scroll position from the nav
    // back stack (rememberLazyListState restores via rememberSaveable).
    val lazyListState = remember { LazyListState() }
    val lazyGridState = remember { LazyGridState() }

    // The trailing spacer item must never take part in a swap.
    val dragDropOnMove: (Int, Int) -> Unit = { from, to ->
        displayedPlaylists = displayedPlaylists.toMutableList().apply {
            add(to, removeAt(from))
        }
    }
    val listDragState = rememberDragDropListState(
        lazyListState = lazyListState,
        canMove = { _, to -> to < displayedPlaylists.size },
        onMove = dragDropOnMove
    )
    val gridDragState = rememberDragDropGridState(
        gridState = lazyGridState,
        canMove = { _, to -> to < displayedPlaylists.size },
        onMove = dragDropOnMove
    )

    // Persists the completed drag: the display list already holds the final
    // order, so writing it back as the new sortOrder assignment is enough.
    // When the custom order is displayed inverted, the canonical (stored)
    // order is the displayed list's reverse.
    fun persistReorder(finalIndex: Int?, originIndex: Int?) {
        if (originIndex == null || finalIndex == null || originIndex == finalIndex) return
        val displayedOrder = displayedPlaylists.map { it.playlistId }
        viewModel.reorderPlaylists(if (sortInverted) displayedOrder.asReversed() else displayedOrder)
    }

    // Long-press actions (edit image / rename / details / delete), hosted here
    // like the other screens host their long-press modals.
    val playlistModalState = rememberPlaylistFoamModalState()
    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<PlaylistEntity?>(null) }

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

            if (sortMode == CollectionSortMode.CUSTOM) {
                PlaylistActionButton(
                    icon = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = if (isEditMode) FoamTheme.accentColor else FoamTheme.colors.textPrimary
                ) {
                    isEditMode = !isEditMode
                }
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
        } else if (density.isGrid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(density.gridColumns),
                state = lazyGridState,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(displayedPlaylists, key = { _, playlist -> playlist.playlistId }) { index, playlist ->
                    val isDragging = gridDragState.isItemDragging(index)
                    val currentIndex by rememberUpdatedState(index)
                    val liftScale by animateFloatAsState(
                        targetValue = if (isDragging && !dragReleased) DragLiftScale else 1f,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                        label = "playlistDragLift"
                    )

                    PlaylistItemRow(
                        playlist = playlist,
                        trackCount = trackCounts[playlist.playlistId] ?: 0,
                        onClick = { if (!isEditMode) onPlaylistClick(playlist) },
                        onLongClick = { playlistModalState.show(playlist) },
                        active = CollectionId.playlist(playlist.playlistId) == activeCollectionId,
                        lifted = isDragging && !dragReleased,
                        showDragHandle = isEditMode,
                        density = density,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier
                            .let { if (isDragging) it else it.animateItem() }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                val itemInfo = lazyGridState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == index }
                                translationX = if (isDragging && itemInfo != null) {
                                    (gridDragState.draggingItemOffset.x - itemInfo.offset.x).toFloat()
                                } else {
                                    0f
                                }
                                translationY = if (isDragging && itemInfo != null) {
                                    (gridDragState.draggingItemOffset.y - itemInfo.offset.y).toFloat()
                                } else {
                                    0f
                                }
                                scaleX = liftScale
                                scaleY = liftScale
                            }
                            .then(
                                if (isEditMode) {
                                    Modifier.playlistEditDrag(
                                        playlistId = playlist.playlistId,
                                        onDragStart = {
                                            dragOriginIndex = currentIndex
                                            dragReleased = false
                                            gridDragState.onDragStart(currentIndex)
                                        },
                                        onDrag = gridDragState::onDrag,
                                        onDragEnd = {
                                            val to = gridDragState.draggedIndex
                                            // Scale-down starts with the release; the
                                            // settle animation flies the item to its slot.
                                            dragReleased = true
                                            gridDragState.onDragEnd()
                                            persistReorder(to, dragOriginIndex)
                                            dragOriginIndex = null
                                        },
                                        onDragCancel = {
                                            dragReleased = true
                                            gridDragState.onDragCancel()
                                            dragOriginIndex = null
                                            displayedPlaylists = sortedPlaylists
                                        }
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }

                item(key = "playlists_grid_spacer", span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(LocalBottomBarClearance.current))
                }
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(displayedPlaylists, key = { _, playlist -> playlist.playlistId }) { index, playlist ->
                    val isDragging = listDragState.isItemDragging(index)
                    val currentIndex by rememberUpdatedState(index)
                    val liftScale by animateFloatAsState(
                        targetValue = if (isDragging && !dragReleased) DragLiftScale else 1f,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                        label = "playlistDragLift"
                    )

                    PlaylistItemRow(
                        playlist = playlist,
                        trackCount = trackCounts[playlist.playlistId] ?: 0,
                        onClick = { if (!isEditMode) onPlaylistClick(playlist) },
                        onLongClick = { playlistModalState.show(playlist) },
                        active = CollectionId.playlist(playlist.playlistId) == activeCollectionId,
                        lifted = isDragging && !dragReleased,
                        showDragHandle = isEditMode,
                        density = density,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier
                            .let { if (isDragging) it else it.animateItem() }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) {
                                    val itemInfo = lazyListState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == index }
                                    if (itemInfo != null) {
                                        listDragState.draggingItemTop - itemInfo.offset
                                    } else {
                                        0f
                                    }
                                } else {
                                    0f
                                }
                                scaleX = liftScale
                                scaleY = liftScale
                            }
                            .then(
                                if (isEditMode) {
                                    Modifier.playlistEditDrag(
                                        playlistId = playlist.playlistId,
                                        onDragStart = {
                                            dragOriginIndex = currentIndex
                                            dragReleased = false
                                            listDragState.onDragStart(currentIndex)
                                        },
                                        onDrag = listDragState::onDrag,
                                        onDragEnd = {
                                            val to = listDragState.draggedIndex
                                            // Scale-down starts with the release; the
                                            // settle animation flies the item to its slot.
                                            dragReleased = true
                                            listDragState.onDragEnd()
                                            persistReorder(to, dragOriginIndex)
                                            dragOriginIndex = null
                                        },
                                        onDragCancel = {
                                            dragReleased = true
                                            listDragState.onDragCancel()
                                            dragOriginIndex = null
                                            displayedPlaylists = sortedPlaylists
                                        }
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }

                item(key = "playlists_list_spacer") { Spacer(modifier = Modifier.height(LocalBottomBarClearance.current)) }
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
        onDelete = { playlist -> deleteConfirmTarget = playlist },
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

    DeletePlaylistConfirmModal(
        playlist = deleteConfirmTarget,
        onConfirm = { playlistId ->
            deleteConfirmTarget = null
            viewModel.deletePlaylist(playlistId)
        },
        onDismiss = { deleteConfirmTarget = null }
    )

    // More-options sheet behind the 3-dots button: holds the sort radio list
    // (custom order first — the drag & drop order) with its Inverse toggle,
    // and the Organisation row, which slides in the density radio list for the
    // playlists view.
    DensityOrganisationModal(
        visible = showMoreModal,
        currentDensity = density,
        onSelectDensity = { onDensityChange?.invoke(it) },
        sortOptions = if (onSortChange != null) {
            listOf(CollectionSortMode.CUSTOM, CollectionSortMode.NAME, CollectionSortMode.RANDOM)
        } else {
            emptyList()
        },
        currentSort = sortMode,
        onSelectSort = { onSortChange?.invoke(it) },
        sortInverted = sortInverted,
        onToggleSortInverted = { onSortInvertedChange?.invoke(it) },
        onDismiss = { showMoreModal = false }
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
 * TrackListItem equivalent for a playlist: cover (the user-chosen image, or
 * the name's initial as fallback), the name and its track count — rendered
 * through the shared row/grid scaffolds at the view's [density]. Long-press
 * opens the playlist actions modal. When opened, the artwork and title animate
 * into the playlist detail header through the shared-element keys
 * "playlist-art-*" / "playlist-title-*". [active] draws the "now playing"
 * highlight when the queue was started from this playlist. [showDragHandle]
 * shows the edit-mode 2D drag handle (four direction arrows).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistItemRow(
    playlist: PlaylistEntity,
    trackCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    active: Boolean = false,
    lifted: Boolean = false,
    showDragHandle: Boolean = false,
    density: MediaDensity,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val highlightBackground = activeRowHighlight(active, playlist.imageUrl, isEmbeddedArt = false)

    // Elevation shadow under the cover while the row is lifted by a drag;
    // animates in step with the lift scale.
    val artworkElevation by animateDpAsState(
        targetValue = if (lifted) DragArtworkElevation else 0.dp,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "playlistArtworkElevation"
    )

    @Composable
    fun Modifier.sharedArtwork(): Modifier = with(sharedTransitionScope) {
        sharedElement(
            rememberSharedContentState(key = "playlist-art-${playlist.playlistId}"),
            animatedVisibilityScope = animatedVisibilityScope,
            clipInOverlayDuringTransition = OverlayClip(AlbumArtShape()),
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

    @Composable
    fun PlaylistArtwork() {
        if (playlist.imageUrl != null) {
            val coverRequest = remember(playlist.imageUrl) {
                ImageRequest.Builder(context)
                    .data(playlist.imageUrl)
                    .size(AlbumArtRequestSize)
                    .build()
            }
            AlbumArtImage(
                model = coverRequest,
                contentDescription = stringResource(R.string.playlist_cover_image),
                modifier = Modifier.fillMaxSize(),
                placeholderSize = if (density.isGrid) 32.dp else 20.dp
            )
        } else {
            BasicText(
                text = playlist.name.take(1).uppercase(),
                style = FoamTheme.typography.titleMedium
            )
        }
    }

    if (density.isGrid) {
        MediaGridCell(
            modifier = modifier,
            onClick = onClick,
            onLongClick = onLongClick,
            artworkModifier = Modifier
                .sharedArtwork()
                .shadow(elevation = artworkElevation, shape = AlbumArtShape())
                .clip(AlbumArtShape())
                .background(FoamTheme.colors.surfaceVariant),
            artworkContent = { PlaylistArtwork() },
            artworkOverlay = {
                AnimatedVisibility(
                    visible = showDragHandle,
                    modifier = Modifier.align(Alignment.TopEnd),
                    enter = DragHandleEnter,
                    exit = DragHandleExit
                ) {
                    Box(
                        modifier = Modifier
                            .padding(6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenWith,
                            contentDescription = null,
                            tint = FoamTheme.colors.textPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            },
            title = playlist.name,
            subtitle = "$trackCount tracks",
            highlightBackground = highlightBackground,
            titleModifier = Modifier.sharedTitle()
        )
    } else {
        MediaListRow(
            modifier = modifier,
            onClick = onClick,
            onLongClick = onLongClick,
            artworkModifier = Modifier
                .sharedArtwork()
                .shadow(elevation = artworkElevation, shape = AlbumArtShape())
                .clip(AlbumArtShape())
                .background(FoamTheme.colors.surfaceVariant),
            artworkContent = { PlaylistArtwork() },
            title = playlist.name,
            subtitle = "$trackCount tracks",
            density = density,
            highlightBackground = highlightBackground,
            titleModifier = Modifier.sharedTitle(),
            trailingContent = {
                AnimatedVisibility(
                    visible = showDragHandle,
                    enter = DragHandleEnter,
                    exit = DragHandleExit
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenWith,
                        contentDescription = null,
                        tint = FoamTheme.colors.textSecondary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(20.dp)
                    )
                }
            }
        )
    }
}

/** Scale-up applied to a playlist while it is being dragged, for a "lifted" feel. */
private const val DragLiftScale = 1.05f

/** Shadow elevation given to a dragged playlist's cover while it is lifted. */
private val DragArtworkElevation: Dp = 8.dp

/** Fade + scale-in of the edit-mode drag handle when entering edit mode. */
private val DragHandleEnter =
    fadeIn(animationSpec = tween(durationMillis = 200)) +
        scaleIn(initialScale = 0.6f, animationSpec = tween(durationMillis = 200))

/** Fade + scale-out of the edit-mode drag handle when leaving edit mode. */
private val DragHandleExit =
    fadeOut(animationSpec = tween(durationMillis = 200)) +
        scaleOut(targetScale = 0.6f, animationSpec = tween(durationMillis = 200))

/** Whole-row drag gesture driving edit-mode reordering (swaps come from the drag-drop states). */
private fun Modifier.playlistEditDrag(
    playlistId: Long,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
): Modifier = pointerInput(playlistId) {
    detectDragGestures(
        onDragStart = { onDragStart() },
        onDrag = { change, dragAmount ->
            change.consume()
            onDrag(dragAmount)
        },
        onDragEnd = onDragEnd,
        onDragCancel = onDragCancel
    )
}
