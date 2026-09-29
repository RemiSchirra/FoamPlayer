package com.foamplayer.app.ui.modals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * "Rename Playlist" sheet: a single text input prefilled with the playlist's
 * current name, plus Save/Cancel. Shown by the pencil next to the playlist
 * name in [PlaylistFoamModal].
 *
 * [playlist] drives visibility the same way the other modal states do, so the
 * host just points it at the playlist being renamed (or nulls it to dismiss).
 *
 * The keyboard never covers the input: the hosting [FoamBottomModal] pads
 * itself above the IME insets (and sets ADJUST_RESIZE on its window), so the
 * sheet rides up above the keyboard while typing. The field is auto-focused
 * so the keyboard is already up when the sheet settles; Done submits.
 */
@Composable
fun RenamePlaylistModal(
    playlist: PlaylistEntity?,
    onRename: (playlistId: Long, newName: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Same exit-animation preservation as the other modals: keep rendering the
    // playlist while the sheet slides away after a save or dismissal.
    var lastShown by remember { mutableStateOf<PlaylistEntity?>(null) }
    SideEffect {
        if (playlist != null) lastShown = playlist
    }
    val display = playlist ?: lastShown

    // Prefilled with the CURRENT name. Keyed on the stored name, not the field
    // value, so user edits survive recompositions but a re-open (or a rename)
    // re-seeds the field with the fresh name; the key is stable while the sheet
    // plays its exit animation, so the text never visibly clears mid-slide.
    var name by remember(display?.name.orEmpty()) { mutableStateOf(display?.name.orEmpty()) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(playlist) {
        if (playlist != null) focusRequester.requestFocus()
    }

    FoamBottomModal(
        visible = playlist != null,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        if (display != null) {
            Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
                BasicText(
                    text = stringResource(R.string.rename_playlist_title),
                    style = FoamTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        horizontal = FoamDimens.paddingSmall,
                        vertical = FoamDimens.spacingTiny
                    )
                )

                PlaylistNameField(
                    value = name,
                    onValueChange = { name = it },
                    focusRequester = focusRequester,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (name.isNotBlank()) onRename(display.playlistId, name.trim()) }
                    )
                )

                ModalButton(
                    label = stringResource(R.string.save),
                    filled = true,
                    onClick = { if (name.isNotBlank()) onRename(display.playlistId, name.trim()) }
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
