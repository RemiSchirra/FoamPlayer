package com.foamplayer.app.ui.modals

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.repository.PlaylistInsertPosition
import com.foamplayer.app.data.repository.TrackMetadataEdit
import com.foamplayer.app.data.scanner.PlaylistCover
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.adaptAccentForTheme
import com.foamplayer.app.ui.theme.foamBackground
import com.foamplayer.app.ui.theme.foamBorderOverlay
import com.foamplayer.app.ui.theme.foamSurface
import com.foamplayer.app.ui.theme.foamSurfaceVariant
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import androidx.core.net.toUri
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.distant.DistantUris
import com.foamplayer.app.ui.components.TrackScoreHeart
import com.foamplayer.app.ui.components.albumArtCornerRadius

/** The track modal's in-sheet pages, slid through in ordinal order (push) or back. */
private enum class TrackModalPage { ACTIONS, DETAILS, EDIT }

/**
 * Holds the track currently shown in a [TrackFoamModal]. A screen owns one of
 * these, calls [show] from a long-press handler, and renders a single
 * [TrackFoamModal] bound to it.
 */
class TrackFoamModalState {
    var track: TrackEntity? by mutableStateOf(null)
        private set

    val isVisible: Boolean
        get() = track != null

    fun show(track: TrackEntity) {
        this.track = track
    }

    fun dismiss() {
        track = null
    }

    /**
     * Replaces the currently shown track with a transformed copy (same id), e.g.
     * after resetting its listen stats, so the open sheet reflects it at once.
     */
    fun updateCurrentTrack(transform: (TrackEntity) -> TrackEntity) {
        val current = track ?: return
        track = transform(current)
    }
}

@Composable
fun rememberTrackFoamModalState(): TrackFoamModalState = remember { TrackFoamModalState() }

/**
 * Bottom sheet shown when the user long-presses a track. Layout, top to bottom:
 * - A header on a darker container: album art with the track title and its
 *   album underneath. It stays mounted across page switches.
 * - The actions page: three side-by-side buttons ("Next" = queue right after
 *   the current track, "Queue" = append to the end of the main queue,
 *   "Playlist" = add to a playlist), then "See Artist" / "See Album" rows and
 *   a final "Details" button.
 * - The details page: the one-line technical spec summary plus the full track
 *   metadata (tags, file info), reached from the Details button with a
 *   push-style slide — the header never leaves the composition, so the
 *   transition reads as one continuous sheet; a Lyrics row opens the lyrics
 *   details sheet on top of the track sheet, an Edit row opens the metadata
 *   edit page (the third in-sheet page), and a Back button returns to the
 *   actions.
 *
 * Every queue/navigation action dismisses the sheet before invoking its
 * callback with the long-pressed track; artist/album can safely push a new
 * screen from there, and "Playlist" typically opens a playlist picker on top.
 */
@Composable
fun TrackFoamModal(
    state: TrackFoamModalState,
    onPlayNext: (TrackEntity) -> Unit = {},
    onAddToQueue: (TrackEntity) -> Unit = {},
    onAddToPlaylist: (TrackEntity) -> Unit = {},
    onSeeArtist: (TrackEntity) -> Unit = {},
    onSeeAlbum: (TrackEntity) -> Unit = {},
    onSeeRadio: (TrackEntity) -> Unit = {},
    onOpenLyrics: (TrackEntity) -> Unit = {},
    onApplyMetadata: (TrackEntity, TrackMetadataEdit) -> Unit = { _, _ -> },
    onResetPlayStats: (TrackEntity) -> Unit = {},
    onSetInstrumental: (TrackEntity, Boolean) -> Unit = { _, _ -> },
    scoreOverrides: Map<Long, Int> = emptyMap(),
    existingGenres: List<String> = emptyList(),
    onSetScore: (Long, Int) -> Unit = { _, _ -> },
    useAlbumArtAccent: Boolean = false,
    onDismiss: () -> Unit = { state.dismiss() }
) {
    var lastShownTrack by remember { mutableStateOf<TrackEntity?>(null) }
    SideEffect {
        if (state.track != null) lastShownTrack = state.track
    }
    val displayTrack = state.track ?: lastShownTrack

    val context = LocalContext.current
    LaunchedEffect(useAlbumArtAccent, displayTrack?.albumArtPath) {
        if (useAlbumArtAccent) {
            AlbumArtPaletteCache.ensure(this, context, displayTrack?.albumArtPath)
        }
    }
    val artAccent = if (useAlbumArtAccent) {
        displayTrack?.albumArtPath?.let { AlbumArtPaletteCache.colors[it] }
    } else {
        null
    }
    val accentColor = artAccent?.adaptAccentForTheme(FoamTheme.isDark) ?: FoamTheme.accentColor

    var page by remember { mutableStateOf(TrackModalPage.ACTIONS) }
    LaunchedEffect(state.track?.id) {
        page = TrackModalPage.ACTIONS
    }

    // Horizontal gap kept between the outgoing and incoming pages while they slide
    val pageGapPx = with(LocalDensity.current) { FoamDimens.spacingSmall.roundToPx() }

    fun perform(action: (TrackEntity) -> Unit) {
        val current = state.track ?: return
        state.dismiss()
        action(current)
    }

    FoamBottomModal(
        visible = state.isVisible,
        onDismissRequest = onDismiss
    ) {
        BackHandler(enabled = state.isVisible && page != TrackModalPage.ACTIONS) {
            page = when (page) {
                TrackModalPage.EDIT -> TrackModalPage.DETAILS
                else -> TrackModalPage.ACTIONS
            }
        }

        if (displayTrack != null) {
            Column(
                modifier = Modifier.padding(bottom = FoamDimens.spacingSmall),
                verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
            ) {
                TrackModalHeader(
                    track = displayTrack,
                    accentColor = accentColor,
                    score = scoreOverrides[displayTrack.id] ?: displayTrack.score,
                    onSetScore = { onSetScore(displayTrack.id, it) }
                )

                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        if (targetState.ordinal > initialState.ordinal) {
                            slideInHorizontally { it + pageGapPx } togetherWith
                                    slideOutHorizontally { -it - pageGapPx }
                        } else {
                            slideInHorizontally { -it - pageGapPx } togetherWith
                                    slideOutHorizontally { it + pageGapPx }
                        }
                    },
                    label = "TrackModalPage"
                ) { currentPage ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
                    ) {
                        if (currentPage == TrackModalPage.DETAILS) {
                            val channelLabels = rememberChannelLabels()
                            val trackSummary = remember(displayTrack, channelLabels) {
                                audioSummaryText(displayTrack, channelLabels)
                            }
                            BasicText(
                                text = trackSummary,
                                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
                                modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
                            )

                            TrackDetailsContent(
                                track = displayTrack,
                                accentColor = accentColor,
                                onResetPlayStats = { onResetPlayStats(displayTrack) },
                                onSetInstrumental = { onSetInstrumental(displayTrack, it) }
                            )

                            // Np tag edition for streamed tracks
                            val isDistantTrack = DistantUris.isDistant(displayTrack.path)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .foamSurfaceVariant()
                            ) {
                                ModalRowButton(
                                    label = stringResource(R.string.lyrics),
                                    onClick = { onOpenLyrics(displayTrack) }
                                )
                                if (!isDistantTrack) {
                                    ModalSectionDivider()
                                    ModalRowButton(
                                        label = stringResource(R.string.edit),
                                        onClick = { page = TrackModalPage.EDIT }
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .foamSurfaceVariant()
                            ) {
                                ModalBackButton(onBack = { page = TrackModalPage.ACTIONS })
                            }
                        } else if (currentPage == TrackModalPage.EDIT) {
                            TrackEditContent(
                                track = displayTrack,
                                accentColor = accentColor,
                                existingGenres = existingGenres,
                                onApply = { edit ->
                                    onApplyMetadata(displayTrack, edit)
                                    page = TrackModalPage.DETAILS
                                },
                                onCancel = { page = TrackModalPage.DETAILS }
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .foamSurfaceVariant()
                                    .padding(
                                        horizontal = FoamDimens.paddingSmall,
                                        vertical = FoamDimens.spacingTiny
                                    )
                            ) {
                                ModalActionButton(
                                    icon = FoamTheme.icons.current.next,
                                    label = stringResource(R.string.next),
                                    onClick = { perform(onPlayNext) },
                                    modifier = Modifier.weight(1f)
                                )
                                ModalActionButton(
                                    icon = FoamTheme.icons.current.queue,
                                    label = stringResource(R.string.queue),
                                    onClick = { perform(onAddToQueue) },
                                    modifier = Modifier.weight(1f)
                                )
                                ModalActionButton(
                                    icon = FoamTheme.icons.current.playlists,
                                    label = stringResource(R.string.playlist),
                                    onClick = { perform(onAddToPlaylist) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .foamSurfaceVariant()
                            ) {
                                ModalRowButton(
                                    label = stringResource(R.string.see_artist),
                                    icon = Icons.Rounded.Person,
                                    onClick = { perform(onSeeArtist) }
                                )
                                ModalSectionDivider()
                                ModalRowButton(
                                    label = stringResource(R.string.see_album),
                                    icon = Icons.Rounded.Album,
                                    onClick = { perform(onSeeAlbum) }
                                )
                                ModalSectionDivider()
                                ModalRowButton(
                                    label = stringResource(R.string.see_radio),
                                    icon = FoamTheme.icons.current.radio,
                                    onClick = { perform(onSeeRadio) }
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .foamSurfaceVariant()
                            ) {
                                ModalRowButton(
                                    label = stringResource(R.string.details),
                                    onClick = { page = TrackModalPage.DETAILS }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Provided by [com.foamplayer.app.ui.navigation.FoamNavHost] at the app root so
 * every [com.foamplayer.app.ui.components.TrackListItem] can open the track
 * modal on long-press without each screen threading it through. Screens that
 * pass an explicit onLongClick to the item always take precedence.
 */
val LocalTrackFoamModalState = staticCompositionLocalOf<TrackFoamModalState?> { null }

/**
 * Playlist picker shown after tapping "Playlist" in [TrackFoamModal]: lists the
 * user's playlists with their covers. Toggling a row's checkbox applies the
 * change at once — there is no confirm step.
 *
 * [track] drives visibility the same way [TrackFoamModalState.track] does for
 * the track modal, so the host just points it at the long-pressed track (or
 * nulls it to dismiss). [playlistIdsWithTrack] drives the checkbox states and
 * is expected to stay live, so a toggle is reflected as soon as it persists.
 * [onToggle] reports a row's new checked state.
 *
 * Whole collections (an album/playlist/etc. added at once from the collection
 * modal's "Add to Playlist" row) come through [collection] +
 * [collectionTracks] instead: the header shows the collection's name, a
 * playlist's checkbox is checked only when it already contains EVERY track of
 * the list, and toggling applies to the whole list at once.
 *
 * The header's trailing buttons sit on the title's row, aligned right:
 * - `+` opens [NewPlaylistDialog], a small name-only creation dialog.
 * - The sort button opens [InsertPositionDialog] to choose where toggled
 *   tracks land inside a playlist (start / end / random); the choice feeds
 *   back through [onInsertPositionChange] and drives [onToggle].
 * The sheet ignores the soft keyboard ([liftAboveKeyboard] = false): the
 * keyboard belongs to the name dialog stacked on top, and padding for it here
 * would shove the playlist list up while typing.
 */
@Composable
fun PlaylistPickerModal(
    track: TrackEntity?,
    playlists: List<PlaylistEntity>,
    playlistCovers: Map<Long, PlaylistCover> = emptyMap(),
    playlistIdsWithTrack: Set<Long>,
    insertPosition: PlaylistInsertPosition,
    onToggle: (PlaylistEntity, Boolean) -> Unit,
    onInsertPositionChange: (PlaylistInsertPosition) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    collection: CollectionInfo? = null,
    collectionTracks: List<TrackEntity> = emptyList()
) {
    // Same exit-animation preservation as TrackFoamModal: keep rendering the
    // track while the sheet slides away after a pick or dismissal.
    var lastShownTrack by remember { mutableStateOf<TrackEntity?>(null) }
    SideEffect {
        if (track != null) lastShownTrack = track
    }
    val displayTrack = track ?: lastShownTrack

    // Batch (whole-collection) adds keep their header alive the same way.
    var lastShownCollection by remember { mutableStateOf<CollectionInfo?>(null) }
    SideEffect {
        if (collection != null) lastShownCollection = collection
    }
    val displayCollection = collection ?: lastShownCollection
    val headerTrack = displayTrack ?: displayCollection?.tracks?.firstOrNull()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showInsertPositionDialog by remember { mutableStateOf(false) }

    FoamBottomModal(
        visible = track != null || collection != null,
        onDismissRequest = onDismiss,
        // The name dialog stacked on this sheet owns the soft keyboard; if this
        // sheet padded for the IME it would ride up in lock-step with typing.
        liftAboveKeyboard = false,
        modifier = modifier
    ) {
        if (headerTrack != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
            ) {
                // Title with the header actions trailing on the same row.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = FoamDimens.paddingSmall,
                            vertical = FoamDimens.spacingTiny
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = stringResource(R.string.add_to_playlist),
                        style = FoamTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )

                    PickerHeaderButton(
                        icon = insertPosition.icon,
                        contentDescription = stringResource(R.string.insert_position_title),
                        onClick = { showInsertPositionDialog = true }
                    )

                    Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))

                    PickerHeaderButton(
                        icon = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.create_playlist_title),
                        onClick = { showCreateDialog = true }
                    )
                }

                // Whole-collection add: name the collection under the title.
                if (displayCollection != null) {
                    BasicText(
                        text = displayCollection.title,
                        style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
                        modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
                    )
                }

                if (playlists.isEmpty()) {
                    BasicText(
                        text = stringResource(R.string.no_playlists_yet),
                        style = FoamTheme.typography.body,
                        modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.itemRounding))
                            .foamSurfaceVariant()
                    ) {
                        playlists.forEachIndexed { index, playlist ->
                            if (index > 0) ModalSectionDivider()
                            PlaylistPickerRow(
                                playlist = playlist,
                                cover = playlistCovers[playlist.playlistId],
                                checked = playlist.playlistId in playlistIdsWithTrack,
                                onCheckedChange = { checked -> onToggle(playlist, checked) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        NewPlaylistDialog(
            onConfirm = { name ->
                showCreateDialog = false
                onCreatePlaylist(name)
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    if (showInsertPositionDialog) {
        InsertPositionDialog(
            current = insertPosition,
            onSelect = { position ->
                showInsertPositionDialog = false
                onInsertPositionChange(position)
            },
            onDismiss = { showInsertPositionDialog = false }
        )
    }
}

/**
 * One playlist row in [PlaylistPickerModal]: cover image (custom image, else
 * the automatic first-track fallback, else the first-letter fallback), its
 * name, and a trailing checkbox. The whole row toggles, not just the checkbox.
 */
@Composable
private fun PlaylistPickerRow(
    playlist: PlaylistEntity,
    cover: PlaylistCover?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val coverImageUrl = cover?.imageUrl ?: playlist.imageUrl
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(albumArtCornerRadius(48.dp)))
                .foamSurface(),
            contentAlignment = Alignment.Center
        ) {
            if (coverImageUrl != null) {
                val context = LocalContext.current
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
                        .build()
                }
                AsyncImage(
                    model = coverRequest,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = playlist.name.take(1).uppercase(),
                    style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.accentColor)
                )
            }
        }

        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))

        BasicText(
            text = playlist.name,
            style = FoamTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))

        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 20dp = the checkbox's own drawn size; drops its 48dp built-in touch
            // padding so the row height stays compact (same as DetailCheckboxRow).
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = FoamTheme.accentColor,
                uncheckedColor = FoamTheme.colors.textSecondary
            )
        )
    }
}

/** Inset hairline used between row buttons inside a modal section container. */
@Composable
internal fun ModalSectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium)
            .height(FoamDimens.separatorHeight)
            .foamBorderOverlay()
    )
}

/** Header block: album art on the left, title with the album underneath, score heart on the right, on a darker container. */
@Composable
internal fun TrackModalHeader(
    track: TrackEntity,
    modifier: Modifier = Modifier,
    accentColor: Color = FoamTheme.accentColor,
    score: Int = 0,
    onSetScore: (Int) -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .foamBackground()
            .padding(FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(albumArtCornerRadius(64.dp)))
                .foamSurfaceVariant(),
            contentAlignment = Alignment.Center
        ) {
            if (track.albumArtPath != null) {
                val context = LocalContext.current
                val albumArtRequest = remember(track.albumArtPath) {
                    ImageRequest.Builder(context)
                        .data(EmbeddedAlbumArt(track.albumArtPath.toUri()))
                        .size(AlbumArtRequestSize)
                        .build()
                }
                AsyncImage(
                    model = albumArtRequest,
                    contentDescription = stringResource(R.string.album_art),
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = track.format,
                    style = FoamTheme.typography.caption.copy(color = accentColor)
                )
            }
        }

        Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = track.title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = track.album,
                style = FoamTheme.typography.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The EQ preset assigned to this track, if any.
            AssignedPresetLine(
                presetName = LocalDspAssignments.current.trackPresets[track.id],
                modifier = Modifier.padding(top = FoamDimens.spacingTiny)
            )
        }

        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))

        TrackScoreHeart(
            score = score,
            onSetScore = onSetScore,
            iconSize = 24.dp,
            touchTarget = 36.dp
        )
    }
}

/** Compact vertical action button (icon over label) used for the horizontal Next/Queue/Playlist row. */
@Composable
internal fun ModalActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(vertical = FoamDimens.paddingSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingTiny)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(FoamDimens.iconMedium)
        )
        BasicText(
            text = label,
            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary)
        )
    }
}

/** Full-width single-line button with a trailing chevron and an optional leading icon, used for See Artist / See Album / Details. */
@Composable
internal fun ModalRowButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))
        }
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = label,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = FoamTheme.colors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Row button with a leading back arrow, used to return from the details page. */
@Composable
internal fun ModalBackButton(onBack: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onBack
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = null,
            tint = FoamTheme.colors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        BasicText(
            text = stringResource(R.string.back),
            style = FoamTheme.typography.titleMedium
        )
    }
}
