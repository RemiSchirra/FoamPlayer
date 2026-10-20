package com.foamplayer.app.ui.modals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foamplayer.app.R
import com.foamplayer.app.data.repository.PlaylistRepairResult
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Result sheet of a playlist repair run (both the playlists screen's
 * "Repair playlists" action and the library settings card open it): how many
 * dangling entries were re-linked, how many stale ones were removed, and how
 * many logged deletions still have no library match.
 */
@Composable
fun RepairPlaylistsResultModal(
    result: PlaylistRepairResult?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    FoamBottomModal(
        visible = result != null,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            BasicText(
                text = stringResource(R.string.playlist_repair),
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingSmall,
                    vertical = FoamDimens.spacingTiny
                )
            )
            BasicText(
                text = when {
                    result == null -> ""
                    !result.hasChanges -> stringResource(R.string.playlist_repair_nothing)
                    else -> stringResource(
                        R.string.playlist_repair_done,
                        result.relinkedCount,
                        result.removedCount,
                        result.unmatchedCount
                    )
                },
                style = FoamTheme.typography.body,
                modifier = Modifier.padding(horizontal = FoamDimens.paddingSmall)
            )
            ModalButton(
                label = stringResource(R.string.ok),
                filled = true,
                onClick = onDismiss
            )
        }
    }
}
