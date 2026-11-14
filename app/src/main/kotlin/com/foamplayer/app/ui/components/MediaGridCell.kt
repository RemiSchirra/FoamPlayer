package com.foamplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme

/** Breathing room around one grid cell's content. */
val MediaGridCellPadding = 6.dp

/**
 * Corner shape of a grid/home cell's press highlight: the radius is the
 * artwork's own percentage-based radius ([AlbumArtShape], see
 * [albumArtCornerRadius]) plus the cell's inner padding, so the highlight
 * hugs the artwork inset by [inset]. The radius is computed at outline time
 * from the measured size — a grid cell's artwork has no fixed size, so a
 * static radius would drift from the art it wraps. [fullyRounded] matches
 * circle artwork (artists).
 */
internal class MediaCellHighlightShape(
    private val inset: Dp,
    private val rounding: Float,
    private val fullyRounded: Boolean = false,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val insetPx = with(density) { inset.toPx() }
        val artSide = (size.width - (insetPx * 2)).coerceAtLeast(0f)
        val radius = if (fullyRounded) {
            (artSide / 2f) + insetPx
        } else {
            (artSide / 2f * rounding.coerceIn(0f, 1f)) + insetPx
        }
        val capped = radius.coerceAtMost(size.minDimension / 2f)
        return Outline.Rounded(RoundRect(rect = size.toRect(), cornerRadius = CornerRadius(capped)))
    }
}

/**
 * Shared grid-cell scaffold for the GRID_* densities of every media list:
 * a square artwork on top, the title underneath, then the optional subtitle —
 * the vertical counterpart of [MediaListRow]. The press/long-press highlight
 * is a card whose corner radius follows the artwork's own radius plus the
 * padding around it (see [MediaCellHighlightShape]); the title and subtitle
 * render outside that clip, so a high rounding never cuts into the text.
 *
 * [artworkModifier] is applied to the artwork box after its aspect ratio and
 * [artworkContent] fills it; [artworkOverlay] draws above the artwork (score
 * badges, track numbers, ...). [highlightBackground] is drawn behind the
 * cell's content, clipped to the same shape as the press ripple.
 */
@Composable
internal fun MediaGridCell(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    artworkModifier: Modifier,
    artworkContent: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    subtitleModifier: Modifier = Modifier,
    artworkElevation: Dp = MediaItemArtElevation,
    artworkShape: Shape = AlbumArtShape(),
    highlightBackground: Color = Color.Transparent,
    /** Steps the static highlight aside while the player-dismiss highlight flight lands (see MediaListRow.playerFlightHighlightTarget). */
    suppressHighlightBackground: Boolean = false,
    artworkOverlay: @Composable BoxScope.() -> Unit = {}
) {
    val cellShape = MediaCellHighlightShape(
        inset = MediaGridCellPadding,
        rounding = FoamTheme.shapes.miniPlayerRounding
    )
    val interactionSource = remember { MutableInteractionSource() }

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(cellShape)
                .then(
                    if (suppressHighlightBackground) {
                        Modifier
                    } else {
                        Modifier.background(highlightBackground, cellShape)
                    }
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onLongClick = onLongClick,
                    onClick = onClick
                )
        )

        Column(modifier = Modifier.padding(MediaGridCellPadding)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(elevation = artworkElevation, shape = artworkShape)
                    .then(artworkModifier),
                contentAlignment = Alignment.Center
            ) {
                artworkContent()
                artworkOverlay()
            }

            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = MediaGridCellPadding)
                    .then(titleModifier)
            )
            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .then(subtitleModifier)
                )
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(cellShape)
                .indication(
                    interactionSource = interactionSource,
                    indication = ripple(color = FoamTheme.colors.textPrimary)
                )
        )
    }
}
