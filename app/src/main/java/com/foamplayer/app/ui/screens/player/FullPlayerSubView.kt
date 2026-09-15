package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.screens.lyrics.LyricsScreen
import com.foamplayer.app.ui.screens.queue.QueueScreen
import com.foamplayer.app.ui.theme.FoamTheme

internal enum class SubViewMode(val labelRes: Int) {
    LYRICS(R.string.lyrics),
    QUEUE(R.string.queue)
}

/**
 * Isolated on purpose: FoamTheme.accentColor changes every frame of a horizontal
 * swipe (it's driven by viewModel.setDynamicAccentColor, fired every drag frame).
 * Reading it here -- one leaf per entry -- instead of inline in
 * SubViewSelectorRow's own body confines that per-frame recomposition to just the
 * selected item's icon, rather than the whole row (and the row's caller).
 */
@Composable
private fun SubViewSelectorItem(
    mode: SubViewMode,
    isSelected: Boolean,
    progress: () -> Float,
    onSelect: (SubViewMode) -> Unit
) {
    val targetColor = if (isSelected) FoamTheme.accentColor else FoamTheme.colors.textSecondary
    val color = lerp(FoamTheme.colors.textPrimary, targetColor, progress().coerceIn(0f, 1f))
    val icon = when (mode) {
        SubViewMode.LYRICS -> FoamTheme.icons.current.lyrics
        SubViewMode.QUEUE -> FoamTheme.icons.current.queue
    }

    // TODO : For some reason the queue icon is smaller than lyrics icon, so sizes are hardcoded
    val label = stringResource(mode.labelRes)
    val iconSize = when (mode) {
        SubViewMode.LYRICS -> 24.dp
        SubViewMode.QUEUE -> 28.dp
    }
    val touchPadding = when (mode) {
        SubViewMode.LYRICS -> 12.dp
        SubViewMode.QUEUE -> 10.dp
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onSelect(mode) }
            .padding(touchPadding),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(iconSize))
    }
}

@Composable
internal fun SubViewSelectorRow(
    subViewMode: SubViewMode,
    progress: () -> Float,
    onSelect: (SubViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        SubViewMode.entries.forEach { mode ->
            SubViewSelectorItem(
                mode = mode,
                isSelected = mode == subViewMode,
                progress = progress,
                onSelect = onSelect
            )
        }
    }
}

/** Layer 2 content: mini header (draggable handle) plus the lyrics or queue subview. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.SubViewContent(
    track: TrackEntity,
    subViewMode: SubViewMode,
    viewModel: PlayerViewModel,
    headerVisibilityScope: AnimatedVisibilityScope,
    headerArtAlpha: () -> Float,
    modifier: Modifier,
    onCollapse: () -> Unit,
    upTransitionState: SeekableTransitionState<Boolean>
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
        Column(modifier = Modifier.fillMaxWidth().then(modifier)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(FoamTheme.colors.borderOverlay)
                    .clickable(onClick = onCollapse)
            )
            MiniPlayerHeader(
                track = track,
                headerVisibilityScope = headerVisibilityScope,
                headerArtAlpha = headerArtAlpha,
                onBack = onCollapse,
                upTransitionState = upTransitionState
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = subViewMode,
                transitionSpec = {
                    fadeIn(androidx.compose.animation.core.tween(TRANSITION_DURATION_MS)) togetherWith
                            fadeOut(androidx.compose.animation.core.tween(TRANSITION_DURATION_MS))
                },
                label = "SubViewContent"
            ) { mode ->
                when (mode) {
                    SubViewMode.LYRICS -> LyricsScreen(backgroundColor = Color.Transparent)
                    SubViewMode.QUEUE -> QueueScreen(viewModel = viewModel, showTitle = false, backgroundColor = Color.Transparent)
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.MiniPlayerHeader(
    track: TrackEntity,
    headerVisibilityScope: AnimatedVisibilityScope,
    headerArtAlpha: () -> Float,
    onBack: () -> Unit,
    upTransitionState: SeekableTransitionState<Boolean>
) {
    val configuration = LocalConfiguration.current
    val miniImageSize = 48.dp
    val fullImageSize = configuration.screenWidthDp.dp - 48.dp

    val miniRadius = (miniImageSize / 2) * FoamTheme.shapes.miniPlayerRounding
    val fullRadius = (fullImageSize / 2) * FoamTheme.shapes.fullPlayerRounding

    // Deferred reads: the transition fraction is evaluated inside the graphicsLayer
    // blocks below, so the subview drag animates the corner radii per frame without
    // recomposing this header (which used to re-run on every drag frame just to
    // rebuild a RoundedCornerShape for .clip()).
    val density = LocalDensity.current
    val miniRadiusPx = with(density) { miniRadius.toPx() }
    val fullRadiusPx = with(density) { fullRadius.toPx() }
    val imageRadiusPx = remember(miniRadiusPx, fullRadiusPx, upTransitionState) {
        {
            lerpFloat(
                start = fullRadiusPx,
                stop = miniRadiusPx,
                fraction = EaseOutCubic.transform(upTransitionState.progress())
            )
        }
    }
    val containerPadding = 8.dp
    val containerPaddingPx = with(density) { containerPadding.toPx() }
    val containerRadiusPx = remember(imageRadiusPx, containerPaddingPx) {
        { imageRadiusPx() + containerPaddingPx }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .graphicsLayer {
                shape = RoundedCornerShape(containerRadiusPx())
                clip = true
            }
            .background(Color.Black.copy(alpha = 0.2f))
            .clickable(onClick = onBack)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Animate the song coming in
        AnimatedContent(
            targetState = track,
            transitionSpec = {
                (fadeIn(androidx.compose.animation.core.tween(TRANSITION_DURATION_MS)) +
                        slideInVertically(androidx.compose.animation.core.tween(TRANSITION_DURATION_MS)) { height -> height / 3 }) togetherWith
                        fadeOut(androidx.compose.animation.core.tween(TRANSITION_DURATION_MS))
            },
            contentKey = { it.id },
            modifier = Modifier.weight(1f),
            label = "MiniPlayerHeaderTrackInfo"
        ) { headerTrack ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .sharedElement(rememberSharedContentState(key = "album_art_header_${headerTrack.id}"), animatedVisibilityScope = headerVisibilityScope)
                        .graphicsLayer {
                            shape = RoundedCornerShape(imageRadiusPx())
                            clip = true
                            alpha = headerArtAlpha()
                        }
                        .background(FoamTheme.colors.surfaceVariant)
                ) {
                    headerTrack.albumArtPath?.let { path ->
                        AsyncImage(model = EmbeddedAlbumArt(path.toUri()),
                            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = headerTrack.title,
                        style = FoamTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedBounds(rememberSharedContentState(key = "track_title_header_${headerTrack.id}"), animatedVisibilityScope = headerVisibilityScope)
                    )
                    BasicText(
                        text = headerTrack.artist,
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.sharedBounds(rememberSharedContentState(key = "track_artist_header_${headerTrack.id}"), animatedVisibilityScope = headerVisibilityScope)
                    )
                }
            }
        }
        Icon(imageVector = Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.collapse), tint = FoamTheme.accentColor, modifier = Modifier.size(28.dp))
    }
}

/**
 * Isolated on purpose: `progress` is a lambda rather than a Float, read only where it's
 * actually needed -- inside the graphicsLayer block (deferred, redraws without
 * recomposing) and inside a derivedStateOf for the clickable-enabled gate (which only
 * recomposes when it crosses the 0.01f threshold, not on every drag frame -- the same
 * pattern TrackHeroPager's `showHero` uses). The caller hands this the same memoized
 * lambda instance on every recomposition, so this whole composable is skipped most
 * frames instead of re-running just to update an alpha value.
 */
@Composable
internal fun MiniPlayPauseButton(
    isPlaying: Boolean,
    progress: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEnabled by remember(progress) { derivedStateOf { progress() > PlayerDimens.visibilityEpsilon } }

    Box(
        modifier = modifier
            .size(PlayerDimens.miniPlayButtonSize)
            .graphicsLayer { alpha = progress() }
            .clip(CircleShape)
            .background(FoamTheme.accentColor)
            .clickable(enabled = isEnabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) FoamTheme.icons.current.pause else FoamTheme.icons.current.play,
            contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
            tint = FoamTheme.colors.background,
            modifier = Modifier.size(PlayerDimens.miniPlayIconSize)
        )
    }
}


/**
 * Previous/Next flank of MiniPlayPauseButton in the subview's transport cluster.
 * Same deferred-`progress` pattern as MiniPlayPauseButton: alpha lives inside the
 * graphicsLayer block (redraws without recomposing) and clicks are gated on the
 * cluster actually being visible; the caller hands this the same memoized lambda
 * instance so the whole cluster is skipped most frames.
 */
@Composable
internal fun MiniTransportButton(
    icon: ImageVector,
    contentDescription: String,
    progress: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEnabled by remember(progress) { derivedStateOf { progress() > PlayerDimens.visibilityEpsilon } }

    Box(
        modifier = modifier
            .size(PlayerDimens.miniTransportButtonSize)
            .graphicsLayer { alpha = progress() }
            .clip(CircleShape)
            .clickable(enabled = isEnabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(PlayerDimens.miniTransportIconSize)
        )
    }
}
