package com.foamplayer.app.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import androidx.core.net.toUri

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
}

@Composable
fun rememberTrackFoamModalState(): TrackFoamModalState = remember { TrackFoamModalState() }

/**
 * Bottom sheet shown when the user long-presses a track. Layout, top to bottom:
 * - A header on a darker container: album art with the track title and its
 *   album underneath.
 * - Three side-by-side action buttons on a lighter container: "Next" (queue
 *   right after the current track), "Queue" (append to the end of the queue)
 *   and "Playlist" (add to a playlist).
 * - Two full-width navigation buttons on a lighter container: "See Artist"
 *   and "See Album".
 * - A final "Details" button.
 *
 * Every action dismisses the sheet before invoking its callback with the
 * long-pressed track; navigation-style callbacks (artist/album/details) can
 * safely push a new screen from there, and "Playlist" typically opens a
 * playlist picker on top.
 */
@Composable
fun TrackFoamModal(
    state: TrackFoamModalState,
    onPlayNext: (TrackEntity) -> Unit = {},
    onAddToQueue: (TrackEntity) -> Unit = {},
    onAddToPlaylist: (TrackEntity) -> Unit = {},
    onSeeArtist: (TrackEntity) -> Unit = {},
    onSeeAlbum: (TrackEntity) -> Unit = {},
    onDetails: (TrackEntity) -> Unit = {},
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
                TrackModalHeader(track = displayTrack)

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
                    TrackModalActionButton(
                        icon = FoamTheme.icons.current.next,
                        label = "Next",
                        onClick = { perform(onPlayNext) },
                        modifier = Modifier.weight(1f)
                    )
                    TrackModalActionButton(
                        icon = FoamTheme.icons.current.queue,
                        label = "Queue",
                        onClick = { perform(onAddToQueue) },
                        modifier = Modifier.weight(1f)
                    )
                    TrackModalActionButton(
                        icon = FoamTheme.icons.current.playlists,
                        label = "Playlist",
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
                    TrackModalRowButton(
                        label = "See Artist",
                        onClick = { perform(onSeeArtist) }
                    )
                    ModalSectionDivider()
                    TrackModalRowButton(
                        label = "See Album",
                        onClick = { perform(onSeeAlbum) }
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.itemRounding))
                        .background(FoamTheme.colors.surfaceVariant)
                ) {
                    TrackModalRowButton(
                        label = "Details",
                        onClick = { perform(onDetails) }
                    )
                }
            }
        }
    }
}

/**
 * Provided by [com.foamplayer.app.ui.navigation.FoamNavHost] at the app root so
 * every [com.foamplayer.app.ui.screens.library.TrackListItem] can open the track
 * modal on long-press without each screen threading it through. Screens that
 * pass an explicit onLongClick to the item always take precedence.
 */
val LocalTrackFoamModalState = staticCompositionLocalOf<TrackFoamModalState?> { null }

/**
 * Simple playlist picker shown after tapping "Playlist" in [TrackFoamModal]:
 * lists the user's playlists; tapping one appends the track to it.
 *
 * [track] drives visibility the same way [TrackFoamModalState.track] does for
 * the track modal, so the host just points it at the long-pressed track (or
 * nulls it to dismiss).
 */
@Composable
fun PlaylistPickerModal(
    track: TrackEntity?,
    playlists: List<PlaylistEntity>,
    onPick: (PlaylistEntity) -> Unit,
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

    FoamBottomModal(
        visible = track != null,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        if (displayTrack != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
            ) {
                BasicText(
                    text = "Add to Playlist",
                    style = FoamTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        horizontal = FoamDimens.paddingSmall,
                        vertical = FoamDimens.spacingTiny
                    )
                )

                if (playlists.isEmpty()) {
                    BasicText(
                        text = "No playlists created yet.",
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
                            TrackModalRowButton(
                                label = playlist.name,
                                onClick = { onPick(playlist) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Inset hairline used between row buttons inside a modal section container. */
@Composable
private fun ModalSectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium)
            .height(FoamDimens.separatorHeight)
            .background(FoamTheme.colors.borderOverlay)
    )
}

/** Header block: album art on the left, title with the album underneath, on a darker container. */
@Composable
private fun TrackModalHeader(
    track: TrackEntity,
    modifier: Modifier = Modifier
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
                .clip(RoundedCornerShape(12.dp))
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
                    contentDescription = "Album Art",
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = track.format,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
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
    }
}

/** Compact vertical action button (icon over label) used for the horizontal Next/Queue/Playlist row. */
@Composable
private fun TrackModalActionButton(
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
private fun TrackModalRowButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
        BasicText(
            text = label,
            style = FoamTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = FoamTheme.colors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
