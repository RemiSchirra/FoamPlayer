package com.foamplayer.app.ui.modals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * "Delete Playlist" confirmation sheet: a title, a warning naming the playlist
 * (its tracks stay in the library), then a filled Delete button and a flat
 * Cancel. Shown by the Delete row in [PlaylistFoamModal].
 *
 * [playlist] drives visibility the same way the other modal states do, so the
 * host just points it at the playlist being deleted (or nulls it to dismiss).
 * Deletion only happens through [onConfirm]; dismissing never deletes.
 */
@Composable
fun DeletePlaylistConfirmModal(
    playlist: PlaylistEntity?,
    onConfirm: (playlistId: Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Same exit-animation preservation as the other modals: keep rendering the
    // playlist while the sheet slides away after a confirm or dismissal.
    var lastShown by remember { mutableStateOf<PlaylistEntity?>(null) }
    SideEffect {
        if (playlist != null) lastShown = playlist
    }
    val display = playlist ?: lastShown

    FoamBottomModal(
        visible = playlist != null,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        if (display != null) {
            Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
                BasicText(
                    text = stringResource(R.string.delete_playlist_title),
                    style = FoamTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        horizontal = FoamDimens.paddingSmall,
                        vertical = FoamDimens.spacingTiny
                    )
                )

                BasicText(
                    text = stringResource(R.string.delete_playlist_message, display.name),
                    style = FoamTheme.typography.body,
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
                )

                ModalButton(
                    label = stringResource(R.string.delete),
                    filled = true,
                    onClick = { onConfirm(display.playlistId) }
                )
                ModalButton(
                    label = stringResource(R.string.cancel),
                    filled = false,
                    onClick = onDismiss
                )
            }
        }
    }
}
