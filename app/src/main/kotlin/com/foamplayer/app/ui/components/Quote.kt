package com.foamplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * A quote: a piece of text with a vertical bar on its left. The bar matches
 * the height of the text ([IntrinsicSize.Min] + [fillMaxHeight] on a multi-line
 * text block included), is a bit thick and has rounded corners, and is drawn in
 * the theme accent color.
 */
@Composable
fun Quote(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = FoamTheme.typography.body
) {
    Row(modifier = modifier.height(IntrinsicSize.Min)) {
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(FoamTheme.accentColor)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))
        BasicText(text = text, style = style)
    }
}
