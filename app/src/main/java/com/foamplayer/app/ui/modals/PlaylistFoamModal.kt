package com.foamplayer.app.ui.modals

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.scanner.PlaylistCover
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.albumArtCornerRadius
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.TimeUtils

/** Track count + total duration for a playlist's details page, loaded by the hosting screen. */
data class PlaylistModalStats(
    val playlistId: Long,
    val trackCount: Int,
    val totalDurationMs: Long
)

/**
 * Holds the playlist currently shown in [PlaylistFoamModal]. The screen owns
 * one of these, calls [show] from a long-press handler, and renders a single
 * [PlaylistFoamModal] bound to it.
 */
class PlaylistFoamModalState {
    var playlist: PlaylistEntity? by mutableStateOf(null)
        private set

    val isVisible: Boolean
        get() = playlist != null

    fun show(playlist: PlaylistEntity) {
        this.playlist = playlist
    }

    fun dismiss() {
        playlist = null
    }
}

@Composable
fun rememberPlaylistFoamModalState(): PlaylistFoamModalState = remember { PlaylistFoamModalState() }

/**
 * Bottom sheet shown when the user long-presses a playlist. Same layout
 * language as the collection modal: a header on a darker container, then
 * grouped sections on lighter containers.
 *
 * The header carries the two edit entry points — a pencil chip on top of the
 * cover (opens a file selector to change the image) and a pencil to the right
 * of the name (opens the rename sheet). The actions page holds a Details row
 * plus a Delete row at the end; the details page shows the playlist's
 * metadata with a push-style slide, the header persisting across the switch.
 * Delete dismisses this sheet; the host is expected to then show the
 * confirmation sheet ([DeletePlaylistConfirmModal]) before actually deleting.
 *
 * Every action dismisses the sheet before invoking its callback.
 */
@Composable
fun PlaylistFoamModal(
    state: PlaylistFoamModalState,
    onEditImage: (PlaylistEntity) -> Unit = {},
    onEditName: (PlaylistEntity) -> Unit = {},
    onDelete: (PlaylistEntity) -> Unit = {},
    stats: PlaylistModalStats? = null,
    cover: PlaylistCover? = null,
    onDismiss: () -> Unit = { state.dismiss() }
) {
    // FoamBottomModal stays mounted while its exit animation plays, but
    // state.playlist is already null by then. Keep the last shown playlist
    // (and its stats) around so the sheet keeps rendering while it slides away.
    var lastShownPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    SideEffect {
        if (state.playlist != null) lastShownPlaylist = state.playlist
    }
    val displayPlaylist = state.playlist ?: lastShownPlaylist

    var lastStats by remember { mutableStateOf<PlaylistModalStats?>(null) }
    SideEffect {
        if (stats != null) lastStats = stats
    }
    val displayStats = stats
        ?: lastStats?.takeIf { it.playlistId == displayPlaylist?.playlistId }

    // Details is a second page of the SAME sheet (one Dialog window), so the
    // header can persist across the switch. Reset when the sheet closes or
    // changes playlist.
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(state.playlist) {
        showDetails = false
    }

    // Horizontal gap kept between the outgoing and incoming pages while they
    // slide, so their contents never touch mid-transition.
    val pageGapPx = with(LocalDensity.current) { FoamDimens.spacingSmall.roundToPx() }

    fun perform(action: (PlaylistEntity) -> Unit) {
        val current = state.playlist ?: return
        state.dismiss()
        action(current)
    }

    FoamBottomModal(
        visible = state.isVisible,
        onDismissRequest = onDismiss
    ) {
        if (displayPlaylist != null) {
            Column(
                modifier = Modifier.padding(bottom = FoamDimens.spacingSmall),
                verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
            ) {
                PlaylistModalHeader(
                    playlist = displayPlaylist,
                    cover = cover,
                    onEditImage = { perform(onEditImage) },
                    onEditName = { perform(onEditName) }
                )

                AnimatedContent(
                    targetState = showDetails,
                    transitionSpec = {
                        if (targetState) {
                            slideInHorizontally { it + pageGapPx } togetherWith
                                    slideOutHorizontally { -it - pageGapPx }
                        } else {
                            slideInHorizontally { -it - pageGapPx } togetherWith
                                    slideOutHorizontally { it + pageGapPx }
                        }
                    },
                    label = "PlaylistModalPage"
                ) { isDetails ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
                    ) {
                        if (isDetails) {
                            PlaylistDetailsContent(
                                playlist = displayPlaylist,
                                stats = displayStats
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .background(FoamTheme.colors.surfaceVariant)
                            ) {
                                ModalBackButton(onBack = { showDetails = false })
                            }
                        } else {
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

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                    .background(FoamTheme.colors.surfaceVariant)
                            ) {
                                ModalRowButton(
                                    label = stringResource(R.string.delete),
                                    onClick = { perform(onDelete) }
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
 * Header block: the playlist cover (custom image, else the automatic
 * first-track fallback, else the name initial) with a pencil chip on top of it
 * (change image), and the name with a pencil to its right (rename), on a
 * darker container — matching the collection modal's header.
 */
@Composable
private fun PlaylistModalHeader(
    playlist: PlaylistEntity,
    cover: PlaylistCover?,
    onEditImage: () -> Unit,
    onEditName: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .background(FoamTheme.colors.background)
            .padding(FoamDimens.spacingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val coverImageUrl = cover?.imageUrl ?: playlist.imageUrl
        Box(modifier = Modifier.size(64.dp)) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(albumArtCornerRadius(64.dp)))
                    .background(FoamTheme.colors.surfaceVariant),
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
                        contentDescription = stringResource(R.string.playlist_cover_image),
                        modifier = Modifier.size(64.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    BasicText(
                        text = playlist.name.take(1).uppercase(),
                        style = FoamTheme.typography.titleLarge.copy(color = FoamTheme.accentColor)
                    )
                }
            }

            // Pencil on top of the image: change the cover via a file selector.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(FoamTheme.colors.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = onEditImage
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.edit_cover_image),
                    tint = FoamTheme.colors.textPrimary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = playlist.name,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(FoamDimens.spacingTiny))

            // Pencil on the right of the text: rename via a text-input sheet.
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = onEditName
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.edit_playlist_name),
                    tint = FoamTheme.colors.textSecondary,
                    modifier = Modifier.size(FoamDimens.iconSmall)
                )
            }
        }
    }
}

/** Details page: the playlist's metadata, reusing the shared details section components. */
@Composable
private fun PlaylistDetailsContent(
    playlist: PlaylistEntity,
    stats: PlaylistModalStats?
) {
    DetailsSection(title = stringResource(R.string.details_playlist), accentColor = FoamTheme.accentColor) {
        DetailRow(stringResource(R.string.details_name), playlist.name)
        DetailRow(stringResource(R.string.details_tracks), stats?.trackCount?.toString())
        DetailRow(
            stringResource(R.string.details_duration),
            stats?.totalDurationMs?.takeIf { it > 0 }?.let { TimeUtils.formatDurationLong(it) }
        )
        DetailRow(stringResource(R.string.details_created), TimeUtils.formatDateTime(playlist.createdAt))
    }
}