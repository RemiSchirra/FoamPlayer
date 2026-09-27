package com.foamplayer.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.ui.theme.FoamTheme

/** Horizontal/vertical breathing room around one grid cell's content. */
val MediaGridCellPadding = 6.dp

/**
 * Shared grid-cell scaffold for the GRID_* densities of every media list
 * (TrackListItem, CollectionListItem, ...): a square artwork on top, the
 * title underneath, then the optional subtitle — the vertical counterpart of
 * [MediaListRow]. The press/long-press highlight is a rounded card around the
 * whole cell.
 *
 * [artworkModifier] is applied to the square artwork box right after its
 * aspect ratio (shared-element modifier, clip, background) and
 * [artworkContent] fills it; [artworkOverlay] draws above the artwork,
 * aligned within it (score badges, track numbers, ...). The artwork casts a
 * rest drop shadow at [artworkElevation] with [artworkShape] (circular for
 * fully round artwork, so the shadow follows the rest look instead of peeking
 * out behind its corners); 0.dp [artworkElevation] opts out of the shadow
 * entirely. [highlightBackground] is drawn behind the cell's content, clipped
 * to the exact same rounded shape as the press ripple (used for the
 * active-track highlight).
 */
@OptIn(ExperimentalFoundationApi::class)
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
    artworkOverlay: @Composable BoxScope.() -> Unit = {}
) {
    val cellShape = RoundedCornerShape(listRowArtCornerRadius() + MediaGridCellPadding)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cellShape)
            .background(highlightBackground, cellShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(MediaGridCellPadding)
    ) {
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
}
