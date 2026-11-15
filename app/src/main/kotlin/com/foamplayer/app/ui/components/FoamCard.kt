package com.foamplayer.app.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurface

/**
 * The standard settings card: rounded corners on the tint-aware theme surface.
 * Centralizes the card corner radius ([FoamDimens.cornerRadiusMedium]) and the
 * surface treatment shared by the settings screens and the cards they embed.
 *
 * Content provides its own padding; pass [contentPadding] to inset the content
 * inside the card — the surface and click area always cover the full card.
 * When [onClick] is set the whole card is clickable, using [indication] as the
 * ripple (pass `null` to suppress the ripple).
 */
@Composable
fun FoamCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    indication: Indication? = ripple(color = FoamTheme.colors.textPrimary),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusMedium))
            .foamSurface()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = indication,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(contentPadding),
        content = content
    )
}
