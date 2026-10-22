package com.foamplayer.app.ui.screens.lyrics

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Unsynced ("plain") lyrics: a simple lazily-scrolled line list, plus the press feedback the rest
 * of the app uses.
 *
 * Split out of `LyricsEnhanced` because it shares no state with the synced/karaoke branches.
 */

@Immutable
internal data class PlainLyrics(
    val items: List<PlainLyricLine>,
)

@Immutable
internal data class PlainLyricLine(
    val itemId: String,
    val text: String,
    val translation: String? = null,
)

// FoamTheme never installs an Indication, so pressed lyric lines fall back to
// foundation's DefaultDebugIndication — a flat 30% black rectangle overlay — and the
// accompanist library's combinedClickable plays a LongPress buzz for any held press.
// Both lyric branches run under this provider so a press only ripples, never dims or
// vibrates; ripple(color) matches the tint used by every other pressable in the app.
@Composable
internal fun LyricPressFeedback(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIndication provides ripple(color = FoamTheme.colors.textPrimary),
        LocalHapticFeedback provides SilentHapticFeedback,
        content = content,
    )
}

private object SilentHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

@Composable
internal fun PlainLyricsView(
    lines: PlainLyrics,
    listState: LazyListState,
    textColor: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val contentPadding =
        remember {
            PaddingValues(
                top = 120.dp,
                bottom = 96.dp,
            )
        }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            items = lines.items,
            key = { line -> line.itemId },
            contentType = { "plain_lyric_line" },
        ) { line ->
            PlainLyricLineItem(
                line = line,
                textColor = textColor,
                textStyle = textStyle,
            )
        }
    }
}

@Composable
private fun PlainLyricLineItem(
    line: PlainLyricLine,
    textColor: Color,
    textStyle: TextStyle,
) {
    // Pressing (tap or long-press) only plays the default ripple; selection was removed.
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .clickable { }
        .padding(vertical = 8.dp)

    if (line.translation != null) {
        Column(modifier = modifier) {
            Text(
                text = line.text,
                style = textStyle,
                color = textColor,
            )
            Text(
                text = line.translation,
                style = FoamTheme.typography.titleMedium.copy(
                    fontSize = (textStyle.fontSize.value * 0.55f).sp,
                    lineHeight = (textStyle.fontSize.value * 0.75f).sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = textColor.copy(alpha = 0.76f),
                modifier = Modifier.padding(top = (textStyle.fontSize.value * 0.3f).dp),
            )
        }
    } else {
        Text(
            text = line.text,
            style = textStyle,
            color = textColor,
            modifier = modifier,
        )
    }
}
