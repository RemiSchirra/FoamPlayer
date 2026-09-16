package com.foamplayer.app.ui.screens.settings.lyricsmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.foamplayer.app.R
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Readable / raw toggle for lyrics text: readable strips timestamps and XML
 * (LyricsUtils.displayLyricsText), raw shows the unparsed payload as stored.
 */
@Composable
internal fun LyricsFormatToggle(
    showRaw: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
    ) {
        FormatChip(
            label = stringResource(R.string.lyrics_view_readable),
            isSelected = !showRaw,
            onClick = { onToggle(false) }
        )
        FormatChip(
            label = stringResource(R.string.lyrics_view_raw),
            isSelected = showRaw,
            onClick = { onToggle(true) }
        )
    }
}

@Composable
private fun FormatChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(if (isSelected) FoamTheme.accentColor else FoamTheme.colors.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = FoamDimens.paddingSmall, vertical = FoamDimens.spacingTiny)
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(
                color = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
            )
        )
    }
}
