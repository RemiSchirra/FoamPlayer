package com.foamplayer.app.ui.screens.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.theme.FoamTheme
import androidx.core.net.toUri
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TrackListItem(
    track: TrackEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    isSharedTransitionSource: Boolean = false
) {
    @Composable
    fun Modifier.sharedArtwork(): Modifier =
        if (!isSharedTransitionSource || sharedTransitionScope == null || animatedVisibilityScope == null) this
        else with(sharedTransitionScope) {
            this@sharedArtwork.sharedElement(
                rememberSharedContentState(key = "album_art_${track.id}"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }

    @Composable
    fun Modifier.sharedText(key: String): Modifier =
        if (!isSharedTransitionSource || sharedTransitionScope == null || animatedVisibilityScope == null) this
        else with(sharedTransitionScope) {
            this@sharedText.sharedBounds(
                rememberSharedContentState(key = key),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }

    val targetBackgroundColor = if (active) {
        FoamTheme.colors.primaryAccent.copy(alpha = 0.4f)
    } else {
        Color.Transparent
    }

    // Animate the color change
    val animatedBackgroundColor by animateColorAsState(
        targetValue = targetBackgroundColor,
        animationSpec = tween(durationMillis = 200, easing = EaseOut),
        label = "activeBackgroundFade"
    )

    val interactionSource = remember { MutableInteractionSource() }

    val density = LocalDensity.current
    val verticalPaddingPx = with(density) { 8.dp.toPx() }

    // Tracks the art box's real, rendered height and top position (in px, relative
    // to the outer container below) each layout pass. While the shared-element
    // transition to/from the full player is running, this box's size *and* position
    // are being driven by the shared transition itself, so reading both every frame
    // lets the highlight follow the artwork exactly instead of being pinned to the
    // Row's own (static) bounds. Defaults match the resting state — art box top
    // equal to the row's vertical padding — so nothing jumps before the first
    // layout pass reports real values.
    var artBoxHeightPx by remember { mutableFloatStateOf(with(density) { 48.dp.toPx() }) }
    var artBoxTopPx by remember { mutableFloatStateOf(verticalPaddingPx) }
    var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val highlightHeight = with(density) { (artBoxHeightPx + verticalPaddingPx * 2f).toDp() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { containerCoordinates = it }
    ) {
        // Drawn behind the row content. Both its height and its vertical offset are
        // sourced from the art box's live measurements (rather than the Row's own,
        // static height), so as the artwork grows/shrinks and moves during the
        // shared-element animation, this rectangle grows and repositions right along
        // with it instead of staying pinned to the row's own bounds.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(highlightHeight)
                .offset { IntOffset(x = 0, y = (artBoxTopPx - verticalPaddingPx).roundToInt()) }
                .clip(RoundedCornerShape(FoamTheme.shapes.miniPlayerRounding + 4.dp))
                .background(animatedBackgroundColor) // Uses the animated color
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FoamTheme.shapes.miniPlayerRounding + 4.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = FoamTheme.colors.textPrimary)
                ) { onClick() }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .sharedArtwork()
                    .onGloballyPositioned { coords ->
                        artBoxHeightPx = coords.size.height.toFloat()
                        containerCoordinates?.let { container ->
                            artBoxTopPx = container.localPositionOf(coords, Offset.Zero).y
                        }
                    }
                    .clip(RoundedCornerShape(FoamTheme.shapes.miniPlayerRounding))
                    .background(FoamTheme.colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (track.albumArtPath != null) {
                    val context = LocalContext.current
                    val albumArtRequest = remember(track.albumArtPath) {
                        ImageRequest.Builder(context)
                            .data(EmbeddedAlbumArt(track.albumArtPath.toUri()))
                            .size(AlbumArtRequestSize)
                            .build()
                    }
                    AsyncImage(
                        model = albumArtRequest,
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    BasicText(
                        text = track.format,
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.primaryAccent)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = track.title,
                    style = FoamTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedText("track_title_${track.id}")
                )
                BasicText(
                    text = "${track.artist} • ${track.album}",
                    style = FoamTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedText("track_artist_${track.id}")
                )
            }

            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    val thickness = 3.dp
                    val length = 16.dp
                    Box(
                        modifier = Modifier
                            .width(length)
                            .height(thickness)
                            .graphicsLayer(rotationZ = 45f)
                            .background(FoamTheme.colors.textSecondary, RoundedCornerShape(thickness / 2))
                    )
                    Box(
                        modifier = Modifier
                            .width(length)
                            .height(thickness)
                            .graphicsLayer(rotationZ = -45f)
                            .background(FoamTheme.colors.textSecondary, RoundedCornerShape(thickness / 2))
                    )
                }
            }

            if (showDragHandle) {
                Spacer(modifier = Modifier.width(if (onRemove != null) 20.dp else 12.dp))

                Column(
                    modifier = dragHandleModifier
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(FoamTheme.colors.textSecondary)
                        )
                    }
                }
            }
        }
    }
}