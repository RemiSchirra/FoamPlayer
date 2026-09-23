package com.foamplayer.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/** Size of a ScreenTitle's leading screen icon. */
private val ScreenTitleIconSize = 34.dp

/**
 * A screen's top header: the screen's icon in the accent color, the big screen
 * title and, on the right, the screen's own action buttons (via [actions]).
 * An optional [subtitle] composable renders under the title (e.g. the
 * equalizer's DSP status line). The component owns no padding — every screen
 * provides the same 16.dp top inset itself and pads horizontally as needed.
 */
@Composable
fun ScreenTitle(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(ScreenTitleIconSize)
        )

        Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))

        if (subtitle == null) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleLarge.copy(fontSize = 30.sp),
                modifier = Modifier.weight(1f)
            )
        } else {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = FoamTheme.typography.titleLarge.copy(fontSize = 30.sp)
                )
                subtitle()
            }
        }

        actions()
    }
}

/**
 * One circular icon button for a [ScreenTitle]'s action row, styled like the
 * player's icon rows.
 */
@Composable
fun ScreenTitleActionButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = FoamTheme.colors.textPrimary,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(FoamDimens.paddingSmall),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(FoamDimens.iconMedium)
        )
    }
}
