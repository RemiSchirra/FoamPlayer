package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.ui.modals.FoamBottomModal
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * The "more" menu of the presets pill: rename + assign rows for the currently
 * applied preset, opened by the more button left of the pill. Rendered only
 * when a preset is active (the custom state has nothing to rename or assign).
 */
@Composable
internal fun PresetMenuModal(
    visible: Boolean,
    presetName: String?,
    onRename: () -> Unit,
    onAssign: () -> Unit,
    onDismiss: () -> Unit
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = FoamTheme.colors.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                BasicText(
                    text = presetName.orEmpty(),
                    style = FoamTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            PresetMenuRow(
                icon = Icons.Rounded.Edit,
                label = stringResource(R.string.eq_preset_menu_rename),
                onClick = onRename
            )
            PresetMenuRow(
                icon = Icons.Rounded.Tune,
                label = stringResource(R.string.eq_preset_menu_assign),
                onClick = onAssign
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** One menu row: leading icon + label, opening its sheet. */
@Composable
private fun PresetMenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        BasicText(
            text = label,
            style = FoamTheme.typography.body.copy(
                fontWeight = FontWeight.Medium,
                color = FoamTheme.colors.textPrimary
            ),
            maxLines = 1
        )
    }
}
