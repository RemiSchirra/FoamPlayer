package com.foamplayer.app.ui.modals

import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.repository.PlaylistInsertPosition
import com.foamplayer.app.data.scanner.PlaylistCover
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.adaptAccentForTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.TimeUtils
import androidx.core.net.toUri
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.TrackScoreHeart
import com.foamplayer.app.ui.components.albumArtCornerRadius
import java.util.Locale

// Hoisted: this was compiled inside simplifiedDisplayPath on every composition pass.
private val StoragePrefixRegex = Regex("^/storage/emulated/\\d+/")

/**
 * Pre-resolved localized channel labels, built once per composition. The
 * mono/stereo words resolve eagerly; multi-channel counts resolve per call
 * (plural selection needs the actual channel count, so it is read at render
 * time from the hosting composable's context).
 */
internal class ChannelLabels(
    val mono: String,
    val stereo: String,
    private val resources: Resources
) {
    fun count(n: Int): String = resources.getQuantityString(R.plurals.channels_count, n, n)
}

@Composable
internal fun rememberChannelLabels(): ChannelLabels {
    val resources = LocalResources.current
    return remember(resources) {
        ChannelLabels(
            mono = resources.getString(R.string.channels_mono),
            stereo = resources.getString(R.string.channels_stereo),
            resources = resources
        )
    }
}

/** Localized channel layout label ("Mono" / "Stereo" / "N channels"). */
private fun formatChannels(channels: Int, labels: ChannelLabels): String = when (channels) {
    1 -> labels.mono
    2 -> labels.stereo
    else -> labels.count(channels)
}

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
 *   details sheet on top of the track sheet, and a Back button returns to the
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
    onOpenLyrics: (TrackEntity) -> Unit = {},
    onResetPlayStats: (TrackEntity) -> Unit = {},
    onSetInstrumental: (TrackEntity, Boolean) -> Unit = { _, _ -> },
    scoreOverrides: Map<Long, Int> = emptyMap(),
    onSetScore: (Long, Int) -> Unit = { _, _ -> },
    useAlbumArtAccent: Boolean = false,
    onDismiss: () -> Unit = { state.dismiss() }
) {
    // FoamBottomModal stays mounted while its exit animation plays, but state.track
    // is already null by then. Keep the last shown track around so the sheet keeps
    // rendering its content while it slides away instead of collapsing to empty.
    var lastShownTrack by remember { mutableStateOf<TrackEntity?>(null) }
    SideEffect {
        if (state.track != null) lastShownTrack = state.track
    }
    val displayTrack = state.track ?: lastShownTrack

    // Accent for the shared header + details page: the viewed track's own album
    // art color when the automatic-color setting is on, else the app accent.
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

    // Details is a second page of the SAME sheet (one Dialog window), so the
    // header can genuinely persist across the switch instead of being cloned
    // into another dialog. Reset when the sheet closes or changes track (keyed
    // on the id: stat updates copy the track but keep the details page open).
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(state.track?.id) {
        showDetails = false
    }

    // Horizontal gap kept between the outgoing and incoming pages while they
    // slide (both travel one width + gap in lockstep), so their contents never
    // touch mid-transition; at rest neither offset applies.
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
        if (displayTrack != null) {
            Column(
                modifier = Modifier.padding(bottom = FoamDimens.spacingSmall),
                verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
            ) {
                // Shared across both pages: it stays mounted while the content
                // below it animates, so it reads as one continuous header.
                TrackModalHeader(
                    track = displayTrack,
                    accentColor = accentColor,
                    score = scoreOverrides[displayTrack.id] ?: displayTrack.score,
                    onSetScore = { onSetScore(displayTrack.id, it) }
                )

                AnimatedContent(
                    targetState = showDetails,
                    transitionSpec = {
                        if (targetState) {
                            // Push: details slides in from the right...
                            slideInHorizontally { it + pageGapPx } togetherWith
                                    slideOutHorizontally { -it - pageGapPx }
                        } else {
                            // ...pop: back to the actions.
                            slideInHorizontally { -it - pageGapPx } togetherWith
                                    slideOutHorizontally { it + pageGapPx }
                        }
                    },
                    label = "TrackModalPage"
                ) { isDetails ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
                    ) {
                        if (isDetails) {
                            // Pure formatting over the track; the modal recomposes per
                            // animation frame while the dynamic accent animates.
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

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .background(FoamTheme.colors.surfaceVariant)
                            ) {
                                ModalRowButton(
                                    label = stringResource(R.string.lyrics),
                                    onClick = { onOpenLyrics(displayTrack) }
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .background(FoamTheme.colors.surfaceVariant)
                            ) {
                                ModalBackButton(onBack = { showDetails = false })
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .background(FoamTheme.colors.surfaceVariant)
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
                                    .background(FoamTheme.colors.surfaceVariant)
                            ) {
                                ModalRowButton(
                                    label = stringResource(R.string.see_artist),
                                    onClick = { perform(onSeeArtist) }
                                )
                                ModalSectionDivider()
                                ModalRowButton(
                                    label = stringResource(R.string.see_album),
                                    onClick = { perform(onSeeAlbum) }
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .background(FoamTheme.colors.surfaceVariant)
                            ) {
                                ModalRowButton(
                                    label = stringResource(R.string.details),
                                    onClick = { showDetails = true }
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
    modifier: Modifier = Modifier
) {
    // Same exit-animation preservation as TrackFoamModal: keep rendering the
    // track while the sheet slides away after a pick or dismissal.
    var lastShownTrack by remember { mutableStateOf<TrackEntity?>(null) }
    SideEffect {
        if (track != null) lastShownTrack = track
    }
    val displayTrack = track ?: lastShownTrack

    var showCreateDialog by remember { mutableStateOf(false) }
    var showInsertPositionDialog by remember { mutableStateOf(false) }

    FoamBottomModal(
        visible = track != null,
        onDismissRequest = onDismiss,
        // The name dialog stacked on this sheet owns the soft keyboard; if this
        // sheet padded for the IME it would ride up in lock-step with typing.
        liftAboveKeyboard = false,
        modifier = modifier
    ) {
        if (displayTrack != null) {
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
                            .background(FoamTheme.colors.surfaceVariant)
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
                .background(FoamTheme.colors.surface),
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
            .background(FoamTheme.colors.borderOverlay)
    )
}

/** Header block: album art on the left, title with the album underneath, score heart on the right, on a darker container. */
@Composable
private fun TrackModalHeader(
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
            .background(FoamTheme.colors.background)
            .padding(FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(albumArtCornerRadius(64.dp)))
                .background(FoamTheme.colors.surfaceVariant),
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

/** Full-width single-line button with a trailing chevron, used for See Artist / See Album / Details. */
@Composable
internal fun ModalRowButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
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

/**
 * Full metadata sheet for a track. Also embedded as the second page of
 * [TrackFoamModal] (see its details button), where the header persists across
 * the switch; this standalone hosting is kept for direct use.
 * Same visual language: the track header on a darker container, then grouped
 * label/value sections on lighter containers.
 */
@Composable
fun TrackDetailsModal(
    track: TrackEntity?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onResetPlayStats: (TrackEntity) -> Unit = {},
    onSetInstrumental: (TrackEntity, Boolean) -> Unit = { _, _ -> },
    scoreOverrides: Map<Long, Int> = emptyMap(),
    onSetScore: (Long, Int) -> Unit = { _, _ -> },
    useAlbumArtAccent: Boolean = false
) {
    // Same exit-animation preservation as the other modals in this file.
    var lastShownTrack by remember { mutableStateOf<TrackEntity?>(null) }
    SideEffect {
        if (track != null) lastShownTrack = track
    }
    val displayTrack = track ?: lastShownTrack

    // Accent texts take the color of the VIEWED track's own album art (when the
    // automatic-color setting is on), not the playing track's dynamic accent.
    // Extraction runs through the shared palette cache; until it lands -- or when
    // the setting is off -- the app accent is used instead.
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

    FoamBottomModal(
        visible = track != null,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
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

                val channelLabels = rememberChannelLabels()
                BasicText(
                    text = remember(displayTrack, channelLabels) { audioSummaryText(displayTrack, channelLabels) },
                    style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
                )

                TrackDetailsContent(
                    track = displayTrack,
                    accentColor = accentColor,
                    onResetPlayStats = { onResetPlayStats(displayTrack) },
                    onSetInstrumental = { onSetInstrumental(displayTrack, it) }
                )
            }
        }
    }
}

/** The Tags + File sections shared by the standalone details modal and the details page of [TrackFoamModal]. */
@Composable
private fun TrackDetailsContent(
    track: TrackEntity,
    accentColor: Color,
    onResetPlayStats: () -> Unit,
    onSetInstrumental: (Boolean) -> Unit
) {
    val hasStats = track.playCount > 0 || track.lastPlayedAt != null

    DetailsSection(
        title = stringResource(R.string.details_stats),
        accentColor = accentColor,
        trailing = {
            if (hasStats) {
                TrackStatsResetButton(onClick = onResetPlayStats)
            }
        }
    ) {
        DetailRow(stringResource(R.string.details_listens), track.playCount.takeIf { it > 0 }?.toString())
        DetailRow(
            stringResource(R.string.details_last_played),
            track.lastPlayedAt?.let { TimeUtils.formatDateTime(it) }
        )
    }

    DetailsSection(title = stringResource(R.string.details_tags), accentColor = accentColor) {
        DetailRow(stringResource(R.string.details_track_number), track.trackNumber?.toString())
        DetailRow(stringResource(R.string.details_disc_number), track.discNumber?.toString())
        DetailRow(stringResource(R.string.details_year), track.year?.toString())
        DetailRow(stringResource(R.string.details_genre), track.genre)
        DetailRow(stringResource(R.string.details_artist), track.artist)
        DetailRow(stringResource(R.string.details_album_artist), track.albumArtist)
        DetailRow(stringResource(R.string.details_album), track.album)
        DetailRow(stringResource(R.string.details_composer), track.composer)
        DetailCheckboxRow(
            label = stringResource(R.string.instrumental),
            checked = track.instrumental,
            onCheckedChange = onSetInstrumental
        )
    }

    DetailsSection(title = stringResource(R.string.details_file), accentColor = accentColor) {
        DetailStackedValue(stringResource(R.string.details_file_path), simplifiedDisplayPath(track.path), accentColor)
        DetailRow(stringResource(R.string.details_format), track.format)
        DetailRow(
            stringResource(R.string.album_art),
            if (track.albumArtPath != null) stringResource(R.string.embedded_in_file) else null
        )
    }
}

/** One-line technical summary of a track; [labels] carries the localized channel words. */
internal fun audioSummaryText(track: TrackEntity, labels: ChannelLabels): String = listOfNotNull(
    TimeUtils.formatDurationLong(track.duration),
    formatSampleRate(track.sampleRate),
    "${track.bitDepth} bit",
    formatChannels(track.channels, labels),
    track.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
    formatFileSize(track.size).takeIf { track.size > 0 }
).joinToString("  ·  ")

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

@Composable
internal fun DetailsSection(
    title: String,
    accentColor: Color,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .background(FoamTheme.colors.surfaceVariant)
            .padding(vertical = FoamDimens.spacingTiny)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = FoamDimens.paddingMedium,
                    vertical = FoamDimens.spacingTiny
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = title,
                style = FoamTheme.typography.caption.copy(color = accentColor),
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        content()
    }
}

/** Small cross button that resets a track's listen counter and last-listen date. */
@Composable
private fun TrackStatsResetButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(FoamDimens.iconMedium)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.reset_listen_stats),
            tint = FoamTheme.colors.textSecondary,
            modifier = Modifier.size(FoamDimens.iconSmall)
        )
    }
}

/** Detail row whose value is a checkbox (user-set booleans like "Instrumental"). */
@Composable
private fun DetailCheckboxRow(
    label: String,
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
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingTiny),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 20dp = the checkbox's own drawn size; drops its 48dp built-in
            // touch padding so the row height matches the other detail rows.
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = FoamTheme.accentColor,
                uncheckedColor = FoamTheme.colors.textSecondary
            )
        )
    }
}

@Composable
internal fun DetailRow(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingTiny),
        verticalAlignment = Alignment.Top
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary),
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        BasicText(
            text = value.takeUnless { it.isNullOrBlank() } ?: "—",
            style = FoamTheme.typography.body.copy(
                color = FoamTheme.colors.textPrimary,
                textAlign = TextAlign.End
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

/** Label above the value, for long content (file paths) that should wrap freely. */
@Composable
internal fun DetailStackedValue(
    label: String,
    value: String,
    accentColor: Color = FoamTheme.accentColor
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingTiny)
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
        BasicText(
            text = value,
            style = FoamTheme.typography.monospace.copy(color = accentColor)
        )
    }
}

private fun formatSampleRate(hz: Int): String {
    val khz = hz / 1000.0
    val value = if (khz % 1.0 == 0.0) {
        String.format(Locale.getDefault(), "%.0f", khz)
    } else {
        String.format(Locale.getDefault(), "%.1f", khz)
    }
    return "$value kHz"
}

/**
 * Turns the stored playback URI into a short, readable path that starts at the
 * scanned folder, e.g. "library/subfolder/name of the song.mp3".
 *
 * SAF content URIs carry the scanned tree root and the document id as separate
 * segments (".../tree/primary%3AMusic/document/primary%3AMusic%2FSub%2Fsong.mp3");
 * pulling both through DocumentsContract decodes the %2F/%3A/%20 escapes, and the
 * document path is then made relative to the tree and prefixed with the scanned
 * folder's own name. Legacy file:// paths are decoded and trimmed of
 * device-specific storage prefixes. Falls back to the raw path on any surprise.
 */
internal fun simplifiedDisplayPath(path: String): String {
    return runCatching {
        val uri = path.toUri()
        when (uri.scheme) {
            "content" -> {
                val docPath = DocumentsContract.getDocumentId(uri)
                    .substringAfter(':')
                    .trimStart('/')
                val treePath = runCatching { DocumentsContract.getTreeDocumentId(uri) }
                    .getOrNull()
                    ?.substringAfter(':')
                    ?.trimStart('/')
                val treeName = treePath?.substringAfterLast('/')

                val relative = when {
                    treePath.isNullOrEmpty() -> docPath
                    docPath.startsWith("$treePath/") -> docPath.removePrefix("$treePath/")
                    else -> docPath.substringAfterLast('/')
                }

                listOfNotNull(
                    treeName?.takeIf { it.isNotBlank() },
                    relative.takeIf { it.isNotBlank() }
                ).joinToString("/").ifEmpty { path }
            }
            "file" -> {
                val decoded = Uri.decode(uri.path ?: return@runCatching path)
                decoded
                    .replace(StoragePrefixRegex, "")
                    .removePrefix("/sdcard/")
                    .trimStart('/')
                    .ifEmpty { path }
            }
            else -> Uri.decode(path)
        }
    }.getOrDefault(path)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    } else {
        String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    }
}