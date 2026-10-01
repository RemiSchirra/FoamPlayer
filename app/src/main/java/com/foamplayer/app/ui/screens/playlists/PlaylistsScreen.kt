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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenWith
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.scanner.PlaylistCover
import com.foamplayer.app.data.scanner.ScanStatus
import com.foamplayer.app.ui.components.AlbumArtBoundsTransform
import com.foamplayer.app.ui.components.AlbumArtImage
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.AlbumArtShape
import com.foamplayer.app.ui.components.CollectionId
import com.foamplayer.app.ui.modals.DensityOrganisationModal
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.components.MediaGridCell
import com.foamplayer.app.ui.components.MediaItemArtElevation
import com.foamplayer.app.ui.components.MediaListRow
import com.foamplayer.app.ui.components.ScreenTitle
import com.foamplayer.app.ui.components.ScreenTitleActionButton
import com.foamplayer.app.ui.components.activeRowHighlight
import com.foamplayer.app.ui.components.albumArtMemoryCacheKey
import com.foamplayer.app.ui.components.collectionRowFlightArtwork
import com.foamplayer.app.ui.components.rememberCollectionCloseFlightActive
import com.foamplayer.app.ui.components.rememberCollectionFlightProgress
import com.foamplayer.app.ui.components.rememberDragDropGridState
import com.foamplayer.app.ui.components.caseInsensitiveComparator
import com.foamplayer.app.ui.components.rememberSortedList
import com.foamplayer.app.ui.modals.CreatePlaylistModal
import com.foamplayer.app.ui.modals.DeletePlaylistConfirmModal
import com.foamplayer.app.ui.modals.MissingSongsModal
import com.foamplayer.app.ui.modals.MissingSongsUiState
import com.foamplayer.app.ui.modals.PlaylistFoamModal
import com.foamplayer.app.ui.modals.PlaylistModalStats
import com.foamplayer.app.ui.modals.RenamePlaylistModal
import com.foamplayer.app.ui.modals.rememberPlaylistFoamModalState
import com.foamplayer.app.ui.screens.queue.rememberDragDropListState
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.navigation.SharedElementEasing
import com.foamplayer.app.ui.navigation.SharedElementTransitionDurationMs
import com.foamplayer.app.ui.navigation.isActiveSharedCollection
import com.foamplayer.app.ui.theme.FoamTheme

private val textFlightFadeSpec = tween<Float>(
    durationMillis = SharedElementTransitionDurationMs,
    easing = SharedElementEasing
)

private val ImageMimeTypes = arrayOf("image/*")

/**
 * The playlists screen: every user playlist as a row or grid cell (per view
 * density), with edit-mode drag reordering (custom order only), create / rename /
 * delete modals and M3U / folder import flows with the missing-songs report.
 */
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
    val playlistCovers by viewModel.playlistCovers.collectAsState()
    val createState by viewModel.createState.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()

    var showCreateModal by remember { mutableStateOf(false) }
    var showMoreModal by remember { mutableStateOf(false) }
    var isEditMode by remember { mutableStateOf(false) }

    // Reordering is only meaningful in CUSTOM (the drag & drop order).
    LaunchedEffect(sortMode) {
        if (sortMode != CollectionSortMode.CUSTOM) {
            isEditMode = false
        }
    }

    // In edit mode, back means "leave edit mode", not "leave the screen".
    BackHandler(enabled = isEditMode) {
        isEditMode = false
    }

    val sortedPlaylists = rememberSortedList(playlists, sortMode, sortInverted) {
        caseInsensitiveComparator { it.name }
    }

    // Edit-mode drag & drop: a local display list absorbs the swaps live; the
    // DB is written once the drag ends. Incoming emissions are ignored while
    // a drag is in flight so they can't fight the reorder.
    var displayedPlaylists by remember { mutableStateOf(sortedPlaylists) }
    var dragOriginIndex by remember { mutableStateOf<Int?>(null) }
    var dragReleased by remember { mutableStateOf(false) }
    LaunchedEffect(sortedPlaylists) {
        if (dragOriginIndex == null) {
            displayedPlaylists = sortedPlaylists
        }
    }

    // Non-saveable list states: always open at the top instead of restoring
    // a stale scroll position from the nav back stack.
    val lazyListState = remember { LazyListState() }
    val lazyGridState = remember { LazyGridState() }

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

    // Persists the completed drag; an inverted display reverses to the canonical order.
    fun persistReorder(finalIndex: Int?, originIndex: Int?) {
        if (originIndex == null || finalIndex == null || originIndex == finalIndex) return
        val displayedOrder = displayedPlaylists.map { it.playlistId }
        viewModel.reorderPlaylists(if (sortInverted) displayedOrder.asReversed() else displayedOrder)
    }

    val playlistModalState = rememberPlaylistFoamModalState()
    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<PlaylistEntity?>(null) }

    // Cover image picker: armed by the modal's pencil, the result applies to
    // the playlist it was opened for.
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
            .padding(top = 16.dp, start = 8.dp, end = 8.dp)
    ) {
        ScreenTitle(
            icon = FoamTheme.icons.current.playlists,
            title = stringResource(R.string.playlists)
        ) {
            if (sortMode == CollectionSortMode.CUSTOM) {
                ScreenTitleActionButton(
                    icon = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = if (isEditMode) FoamTheme.accentColor else FoamTheme.colors.textPrimary
                ) {
                    isEditMode = !isEditMode
                }
            }
            ScreenTitleActionButton(
                icon = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.add)
            ) {
                showCreateModal = true
            }
            ScreenTitleActionButton(
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
                    val inFlight = isActiveSharedCollection("playlist", "${playlist.playlistId}")
                    val currentIndex by rememberUpdatedState(index)
                    val liftScale by animateFloatAsState(
                        targetValue = if (isDragging && !dragReleased) DragLiftScale else 1f,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                        label = "playlistDragLift"
                    )

                    PlaylistItemRow(
                        playlist = playlist,
                        trackCount = trackCounts[playlist.playlistId] ?: 0,
                        cover = playlistCovers[playlist.playlistId],
                        onClick = { if (!isEditMode) onPlaylistClick(playlist) },
                        onLongClick = { playlistModalState.show(playlist) },
                        active = CollectionId.playlist(playlist.playlistId) == activeCollectionId,
                        lifted = isDragging && !dragReleased,
                        showDragHandle = isEditMode,
                        dragHandleModifier = if (isEditMode) {
                            // Reordering starts only from the drag chip.
                            Modifier.pointerInput(playlist.playlistId) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragOriginIndex = currentIndex
                                        dragReleased = false
                                        gridDragState.onDragStart(currentIndex)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        gridDragState.onDrag(dragAmount)
                                    },
                                    onDragEnd = {
                                        val to = gridDragState.draggedIndex
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
                            }
                        } else {
                            Modifier
                        },
                        density = density,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier
                            // The row in a shared-element flight must not also be driven by
                            // lazy-item placement/fade animations.
                            .let { if (isDragging || inFlight) it else it.animateItem() }
                            .zIndex(if (isDragging) 1f else 0f)
                            // Only add a render layer while a drag is lifting the item.
                            .then(
                                if (isDragging || liftScale != 1f) {
                                    Modifier.graphicsLayer {
                                        val itemInfo = if (isDragging) {
                                            lazyGridState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.index == index }
                                        } else {
                                            null
                                        }
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
                    val inFlight = isActiveSharedCollection("playlist", "${playlist.playlistId}")
                    val currentIndex by rememberUpdatedState(index)
                    val liftScale by animateFloatAsState(
                        targetValue = if (isDragging && !dragReleased) DragLiftScale else 1f,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                        label = "playlistDragLift"
                    )

                    PlaylistItemRow(
                        playlist = playlist,
                        trackCount = trackCounts[playlist.playlistId] ?: 0,
                        cover = playlistCovers[playlist.playlistId],
                        onClick = { if (!isEditMode) onPlaylistClick(playlist) },
                        onLongClick = { playlistModalState.show(playlist) },
                        active = CollectionId.playlist(playlist.playlistId) == activeCollectionId,
                        lifted = isDragging && !dragReleased,
                        showDragHandle = isEditMode,
                        density = density,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier
                            // The row in a shared-element flight must not also be driven by
                            // lazy-item placement/fade animations.
                            .let { if (isDragging || inFlight) it else it.animateItem() }
                            .zIndex(if (isDragging) 1f else 0f)
                            // Only add a render layer while a drag is lifting the item.
                            .then(
                                if (isDragging || liftScale != 1f) {
                                    Modifier.graphicsLayer {
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
                                } else {
                                    Modifier
                                }
                            )
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
        cover = playlistModalState.playlist?.let { playlistCovers[it.playlistId] },
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

    // More-options sheet: the sort radio list (custom order first) with its
    // Inverse toggle, and the Organisation row for this view's density.
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

/**
 * One playlist row/cell: cover (custom image, else the automatic first-track
 * fallback, else the name-initial monogram), name and track count through the
 * shared row/grid scaffolds. Long-press opens the playlist actions modal; the
 * artwork and title join the shared-element flight into the playlist detail
 * header when clicked. [active] draws the "now playing" highlight when the
 * queue was started from this playlist; [showDragHandle] shows the edit-mode
 * 2D drag handle.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistItemRow(
    playlist: PlaylistEntity,
    trackCount: Int,
    cover: PlaylistCover?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    active: Boolean = false,
    lifted: Boolean = false,
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    density: MediaDensity,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverImageUrl = cover?.imageUrl ?: playlist.imageUrl
    val highlightBackground = activeRowHighlight(
        active,
        coverImageUrl,
        isEmbeddedArt = cover?.isEmbeddedArt ?: false
    )

    val isActiveCollection = isActiveSharedCollection("playlist", "${playlist.playlistId}")
    val flight = LocalCollectionHeroLayoutLookup
        .current(CollectionHeroTarget.Playlist)
        .isImmersive

    val closeFlightActive by rememberCollectionCloseFlightActive(
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
    val flightProgress = if (isActiveCollection) {
        rememberCollectionFlightProgress(
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope
        )
    } else {
        null
    }
    val staticArtElevation = when {
        !isActiveCollection -> MediaItemArtElevation
        !flight -> if (closeFlightActive) 0.dp else MediaItemArtElevation
        closeFlightActive && flightProgress != null ->
            MediaItemArtElevation * flightProgress.value
        else -> MediaItemArtElevation
    }

    val artworkElevation by animateDpAsState(
        targetValue = if (lifted) DragArtworkElevation else 0.dp,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "playlistArtworkElevation"
    )

    @Composable
    fun Modifier.sharedArtworkModifier(): Modifier {
        if (!isActiveCollection) {
            return Modifier
                .clip(AlbumArtShape())
                .background(FoamTheme.colors.surfaceVariant)
        }
        return Modifier
            .collectionRowFlightArtwork(
                key = "playlist-art-${playlist.playlistId}",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                enabled = true,
                restShape = AlbumArtShape(),
                immersiveFlightHero = flight,
                castsFlightShadow = true
            )
            .background(FoamTheme.colors.surfaceVariant)
    }
    @Composable
    fun Modifier.sharedTitle(): Modifier =
        if (!isActiveSharedCollection("playlist", "${playlist.playlistId}")) {
            Modifier
        } else {
            with(sharedTransitionScope) {
                sharedBounds(
                    rememberSharedContentState(key = "playlist-title-${playlist.playlistId}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    enter = fadeIn(textFlightFadeSpec),
                    exit = fadeOut(textFlightFadeSpec),
                    boundsTransform = AlbumArtBoundsTransform
                )
            }
        }

    @Composable
    fun PlaylistArtwork() {
        if (coverImageUrl != null) {
            val isEmbedded = cover?.isEmbeddedArt ?: false
            val coverRequest = remember(coverImageUrl, isEmbedded) {
                ImageRequest.Builder(context)
                    .data(
                        if (isEmbedded) {
                            EmbeddedAlbumArt(coverImageUrl.toUri())
                        } else {
                            coverImageUrl
                        }
                    )
                    .size(AlbumArtRequestSize)
                    // The destination hero reads the cover under this shared key;
                    // caching under it here keeps a close flight's first frames
                    // on the image instead of the placeholder note.
                    .memoryCacheKey(albumArtMemoryCacheKey(coverImageUrl))
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
                .sharedArtworkModifier(),
            artworkElevation = staticArtElevation,
            artworkContent = { PlaylistArtwork() },
            artworkOverlay = {
                AnimatedVisibility(
                    visible = showDragHandle,
                    modifier = Modifier.align(Alignment.TopEnd),
                    enter = DragHandleEnter,
                    exit = DragHandleExit
                ) {
                    val dragHandleInteractionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = dragHandleModifier
                            .clickable(
                                interactionSource = dragHandleInteractionSource,
                                indication = null
                            ) {/* Consume the click */}
                            .padding(6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenWith,
                            contentDescription = null,
                            tint = FoamTheme.colors.textPrimary,
                            modifier = Modifier.size(20.dp)
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
                .sharedArtworkModifier(),
            artworkElevation = staticArtElevation,
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