package com.foamplayer.app.ui.modals

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.foamplayer.app.R
import com.foamplayer.app.data.repository.PlaylistInsertPosition
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurface

/**
 * Small plain-[Dialog] (not a FoamModal sheet) asking only for a playlist
 * name; opened by the `+` button in the add-to-playlist picker's header.
 * Create submits, Cancel / outside tap / back dismiss. The field is
 * auto-focused so the keyboard is already up when the dialog settles.
 */
@Composable
fun NewPlaylistDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(FoamDimens.itemRounding))
                .foamSurface()
                .padding(FoamDimens.spacingMedium),
            verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
        ) {
            BasicText(
                text = stringResource(R.string.create_playlist_title),
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
                    onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }
                )
            )

            Row(horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
                ModalButton(
                    label = stringResource(R.string.cancel),
                    filled = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                ModalButton(
                    label = stringResource(R.string.create),
                    filled = true,
                    enabled = name.isNotBlank(),
                    onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Small plain-[Dialog] (not a FoamModal sheet) choosing where tracks land
 * when added to a playlist: start, end or a random slot. Selecting an option
 * reports it through [onSelect] and closes; the current value is highlighted
 * with a check.
 */
@Composable
fun InsertPositionDialog(
    current: PlaylistInsertPosition,
    onSelect: (PlaylistInsertPosition) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(FoamDimens.itemRounding))
                .foamSurface()
                .padding(vertical = FoamDimens.spacingSmall),
            verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingTiny)
        ) {
            BasicText(
                text = stringResource(R.string.insert_position_title),
                style = FoamTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = FoamDimens.paddingMedium,
                    vertical = FoamDimens.spacingTiny
                )
            )

            PlaylistInsertPosition.entries.forEach { position ->
                InsertPositionRow(
                    position = position,
                    selected = position == current,
                    onClick = { onSelect(position) }
                )
            }
        }
    }
}

@Composable
private fun InsertPositionRow(
    position: PlaylistInsertPosition,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = position.icon,
            contentDescription = null,
            tint = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
            modifier = Modifier.size(FoamDimens.iconMedium)
        )

        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))

        BasicText(
            text = stringResource(position.labelRes),
            style = FoamTheme.typography.titleMedium.copy(
                color = if (selected) FoamTheme.accentColor else FoamTheme.colors.textPrimary
            ),
            modifier = Modifier.weight(1f)
        )

        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = FoamTheme.accentColor,
                modifier = Modifier.size(FoamDimens.iconSmall)
            )
        }
    }
}

/**
 * Compact CIRCULAR icon button used in the picker header, aligned with the
 * title row. The circle clip bounds the ripple too, so presses read as a
 * round highlight rather than a rounded square.
 */
@Composable
internal fun PickerHeaderButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(FoamDimens.iconMedium)
        )
    }
}

/** Icon representing where an added track lands: up arrow = start, down = end, shuffle = random. */
internal val PlaylistInsertPosition.icon: ImageVector
    get() = when (this) {
        PlaylistInsertPosition.START -> Icons.Rounded.ArrowUpward
        PlaylistInsertPosition.END -> Icons.Rounded.ArrowDownward
        PlaylistInsertPosition.RANDOM -> Icons.Rounded.Shuffle
    }

private val PlaylistInsertPosition.labelRes: Int
    get() = when (this) {
        PlaylistInsertPosition.START -> R.string.insert_at_start
        PlaylistInsertPosition.END -> R.string.insert_at_end
        PlaylistInsertPosition.RANDOM -> R.string.insert_at_random
    }
