package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.constants.CollectionHeroLayout
import com.foamplayer.app.constants.CollectionHeroTarget
import com.foamplayer.app.ui.components.CollectionHeroCompactImmersiveHeightFraction
import com.foamplayer.app.ui.components.CollectionHeroImmersiveFadeStartFraction
import com.foamplayer.app.ui.components.CollectionHeroImmersiveHeightFraction
import com.foamplayer.app.ui.components.CollectionHeroImmersiveOverlayFraction
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/** Width of one layout-preview phone thumbnail; the 9:19.5 aspect fixes its height. */
private val CollectionLayoutPreviewWidth = 96.dp

/** Height of one layout-preview phone thumbnail (9:19.5 phone aspect). */
private val CollectionLayoutPreviewHeight = CollectionLayoutPreviewWidth * 19.5f / 9f

/** Corner rounding of the preview phone body. */
private val CollectionLayoutPreviewRounding = 12.dp

@Composable
fun CollectionsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    val onLayoutSelected: (CollectionHeroTarget, CollectionHeroLayout) -> Unit = { target, layout ->
        when (target) {
            CollectionHeroTarget.Default -> viewModel.setCollectionLayoutDefault(layout)
            CollectionHeroTarget.Artist -> viewModel.setCollectionLayoutArtist(layout)
            CollectionHeroTarget.Album -> viewModel.setCollectionLayoutAlbum(layout)
            CollectionHeroTarget.Playlist -> viewModel.setCollectionLayoutPlaylist(layout)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = "Collections",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = CollectionHeroTarget.entries,
                key = { target -> "collection_layout_${target.name}" }
            ) { target ->
                Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
                    BasicText(
                        text = collectionHeroTargetLabel(target),
                        style = FoamTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    CollectionLayoutPicker(
                        selected = settings.collectionHeroLayout(target),
                        onSelect = { onLayoutSelected(target, it) }
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item(key = "collection_layout_bottom_spacer") {
                Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            }
        }
    }
}

private fun collectionHeroTargetLabel(target: CollectionHeroTarget): String = when (target) {
    CollectionHeroTarget.Default -> "Default"
    CollectionHeroTarget.Artist -> "Artist"
    CollectionHeroTarget.Album -> "Album"
    CollectionHeroTarget.Playlist -> "Playlist"
}

/**
 * One section's layout picker: a freely scrollable horizontal list of the four
 * layout previews. Tapping a preview writes the section's DataStore value; the
 * selected one is highlighted with an accent border.
 */
@Composable
private fun CollectionLayoutPicker(
    selected: CollectionHeroLayout,
    onSelect: (CollectionHeroLayout) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall),
        contentPadding = PaddingValues(horizontal = FoamDimens.paddingSmall)
    ) {
        items(CollectionHeroLayout.entries) { layout ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CollectionLayoutPreview(
                    layout = layout,
                    selected = layout == selected,
                    onClick = { onSelect(layout) }
                )
                Spacer(modifier = Modifier.height(4.dp))
                BasicText(
                    text = layout.label,
                    style = FoamTheme.typography.caption.copy(
                        color = if (layout == selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary
                    ),
                    modifier = Modifier.width(CollectionLayoutPreviewWidth)
                )
            }
        }
    }
}

/**
 * Skeleton preview of one hero layout inside a 9:19.5 phone-aspect thumbnail:
 * a rectangle for the artwork, thin lines for the title/stats, one thicker
 * accent line for the play button, and grouped track lines below the hero.
 * The geometry mirrors the real layouts' constants (immersive bleed height,
 * fade span, overlay position; compact split; normal margins).
 */
@Composable
private fun CollectionLayoutPreview(
    layout: CollectionHeroLayout,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(CollectionLayoutPreviewWidth)
            .height(CollectionLayoutPreviewHeight)
            .clip(RoundedCornerShape(CollectionLayoutPreviewRounding))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) FoamTheme.accentColor else FoamTheme.colors.borderOverlay,
                shape = RoundedCornerShape(CollectionLayoutPreviewRounding)
            )
            .background(FoamTheme.colors.surfaceVariant)
            .clickable { onClick() }
    ) {
        when (layout) {
            CollectionHeroLayout.Compact -> CompactPreview()
            CollectionHeroLayout.Immersive -> ImmersivePreview(CollectionHeroImmersiveHeightFraction, centered = true)
            CollectionHeroLayout.CompactImmersive -> ImmersivePreview(
                CollectionHeroCompactImmersiveHeightFraction,
                centered = false
            )
            CollectionHeroLayout.Normal -> NormalPreview()
        }
    }
}

/** Image rectangle color of the skeleton previews (darker than the phone body). */
private val PreviewImageColor: Color
    @Composable get() = FoamTheme.colors.borderOverlay

/** Thin skeleton line. */
@Composable
private fun PreviewLine(width: Dp, thickness: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(width)
            .height(thickness)
            .clip(RoundedCornerShape(thickness / 2))
            .background(FoamTheme.colors.textSecondary.copy(alpha = 0.4f))
    )
}

/** The one thicker line standing in for the play button. */
@Composable
private fun PreviewPlayPill(width: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(FoamTheme.accentColor)
    )
}

/** One grouped track-row skeleton: small square artwork plus two thin lines. */
@Composable
private fun PreviewTrackRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(PreviewImageColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            PreviewLine(width = 52.dp, thickness = 3.dp)
            Spacer(modifier = Modifier.height(3.dp))
            PreviewLine(width = 34.dp, thickness = 3.dp)
        }
    }
}

@Composable
private fun PreviewTracksColumn(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        repeat(3) { PreviewTrackRow() }
    }
}

/** Text/play skeleton block; [centered] mirrors the layout's overlay alignment. */
@Composable
private fun PreviewTextBlock(centered: Boolean) {
    Column(
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        PreviewLine(width = 56.dp, thickness = 5.dp)
        Spacer(modifier = Modifier.height(2.dp))
        PreviewLine(width = 32.dp, thickness = 3.dp)
        Spacer(modifier = Modifier.height(4.dp))
        PreviewPlayPill(width = 36.dp)
    }
}

/** Classic compact split: image left (a third of the width, like the 120dp header), lines + play right. */
@Composable
private fun CompactPreview() {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp)) {
        Spacer(modifier = Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(PreviewImageColor)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f)) {
                PreviewTextBlock(centered = false)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        PreviewTracksColumn()
    }
}

/**
 * Immersive bleed: full-width artwork from the very top of the phone, bottom
 * fading into the body, text block at the overlay fraction (centered for
 * Immersive, left for CompactImmersive), tracks below the image.
 */
@Composable
private fun ImmersivePreview(
    imageHeightFraction: Float,
    centered: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(imageHeightFraction)
                .background(PreviewImageColor)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(imageHeightFraction)
                .background(
                    Brush.verticalGradient(
                        CollectionHeroImmersiveFadeStartFraction to Color.Transparent,
                        1f to FoamTheme.colors.surfaceVariant
                    )
                )
        )
        Column(
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .offset(
                    y = CollectionLayoutPreviewHeight * imageHeightFraction *
                            CollectionHeroImmersiveOverlayFraction
                )
                .padding(horizontal = 5.dp)
        ) {
            PreviewTextBlock(centered = centered)
        }
        PreviewTracksColumn(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = CollectionLayoutPreviewHeight * imageHeightFraction + 8.dp)
                .padding(horizontal = 5.dp)
        )
    }
}

/** Big rounded artwork with normal margins, text block centered underneath. */
@Composable
private fun NormalPreview() {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp)) {
        Spacer(modifier = Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(PreviewImageColor)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PreviewTextBlock(centered = true)
        }
        Spacer(modifier = Modifier.height(8.dp))
        PreviewTracksColumn()
    }
}
