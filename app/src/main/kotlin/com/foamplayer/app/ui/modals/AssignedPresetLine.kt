package com.foamplayer.app.ui.modals

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foamplayer.app.audio.dsp.PresetAssignments
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * The EQ preset assignments (track/album/playlist/device), provided once at
 * the navigation root so every screen's modal headers can show the assigned
 * preset without threading the state through each ViewModel.
 */
val LocalDspAssignments = staticCompositionLocalOf { PresetAssignments() }

/**
 * The assigned EQ preset shown in the track/album/playlist long-press modal
 * headers, under the album/artist/name line. Renders nothing when no preset
 * is assigned.
 */
@Composable
internal fun AssignedPresetLine(presetName: String?, modifier: Modifier = Modifier) {
    if (presetName == null) return

    Row(
        modifier = modifier.padding(horizontal = FoamDimens.paddingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = null,
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingTiny))
        BasicText(
            text = presetName,
            style = FoamTheme.typography.caption.copy(
                color = FoamTheme.accentColor,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1
        )
    }
}
