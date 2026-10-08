package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.ui.components.MarqueeText
import com.foamplayer.app.ui.screens.lyrics.LyricsScreen
import com.foamplayer.app.ui.screens.queue.QueueScreen
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.pressShrink
import com.foamplayer.app.ui.utils.rememberPausedArtScale

internal enum class SubViewMode(val labelRes: Int) {
    LYRICS(R.string.lyrics),
    QUEUE(R.string.queue)
}

/**
 * Isolated on purpose, twice over:
 *  - FoamTheme.accentColor changes every frame of a horizontal swipe (it's driven by
 *    viewModel.setDynamicAccentColor, fired every drag frame). Reading it here -- one
 *    leaf per entry -- instead of inline in SubViewSelectorRow's own body confines that
 *    per-frame recomposition to just the selected item's icon, rather than the whole
 *    row (and the row's caller).
 *  - The selection tint tracks the subview transition fraction, which changes every
 *    frame of the drag/settle. It's a draw-phase crossfade: progress() is read inside
 *    the resting icon's graphicsLayer, so it only redraws the icons, where the previous
 *    composition-phase lerp recomposed both selector items on every frame of every
 *    subview transition.
 */
@Composable
private fun SubViewSelectorItem(
    mode: SubViewMode,
    isSelected: Boolean,
    progress: () -> Float,
    onSelect: (SubViewMode) -> Unit
) {
    val baseColor = FoamTheme.colors.textPrimary
    val selectedColor = FoamTheme.accentColor
    val deselectedColor = FoamTheme.colors.textSecondary
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
    val selectorInteractions = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .pressShrink(selectorInteractions)
            .clickable(
                interactionSource = selectorInteractions,
                indication = null
            ) { onSelect(mode) }
            .padding(touchPadding),
        contentAlignment = Alignment.Center
    ) {
        // Selection tint as a draw-phase crossfade: the subview transition fraction changes
        // every frame of the drag/settle, so the interpolation between the resting and
        // selected colors runs in graphicsLayer (redraw-only) instead of composition -- the
        // previous single-icon composition-phase lerp recomposed both selector items on
        // every frame of every subview transition. For two opaque colors, stacking the
        // resting tint at alpha (1 - progress) over the selected tint composites to exactly
        // lerp(selected, resting, progress).
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) selectedColor else deselectedColor,
            modifier = Modifier.size(iconSize)
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = baseColor,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { alpha = 1f - progress().coerceIn(0f, 1f) }
        )
    }
}

@Composable
internal fun SubViewSelectorRow(
    subViewMode: SubViewMode,
    progress: () -> Float,
    onSelect: (SubViewMode) -> Unit,
    audioInfoText: String?,
    onAudioInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SubViewMode.entries.forEach { mode ->
                SubViewSelectorItem(
                    mode = mode,
                    isSelected = mode == subViewMode,
                    progress = progress,
                    onSelect = onSelect
                )
            }
        }

        AudioInfoChip(
            text = audioInfoText,
            progress = progress,
            onClick = onAudioInfoClick,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * Pill between the lyrics and queue selector icons showing the playing track's
 * audio format and bitrate; opens the audio-info modal. Deferred-`progress`
 * pattern like the transport buttons: the subview transition fraction is read
 * inside graphicsLayer (redraw-only) and the clickable is gated through a
 * derivedStateOf, so drag frames don't recompose the chip. The alpha runs
 * INVERSE to that of the transport cluster, which fades into this chip's exact
 * spot when the subview opens -- the two must never be visible together.
 */
@Composable
private fun AudioInfoChip(
    text: String?,
    progress: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (text == null) return

    val isEnabled by remember(progress) {
        derivedStateOf { progress() < 1f - PlayerDimens.visibilityEpsilon }
    }
    val chipInteractions = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .graphicsLayer { alpha = (1f - progress()).coerceIn(0f, 1f) }
            .pressShrink(chipInteractions)
            .clickable(
                interactionSource = chipInteractions,
                indication = null,
                enabled = isEnabled,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .clip(CircleShape)
            .background(FoamTheme.colors.background.copy(alpha = 0.2f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = text,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary),
            maxLines = 1
        )
    }
}

/** Layer 2 content: mini header (draggable handle) plus the lyrics or queue subview. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.SubViewContent(
    track: TrackEntity,
    subViewMode: SubViewMode,
    viewModel: PlayerViewModel,
    // Raw isPlaying: read only by the play/pause button leaf inside MiniPlayerHeader's
    // caller (see below), so a playback flip doesn't recompose this subtree.
    isPlaying: () -> Boolean,
    // Current paused shrink of the full player hero (isPlaying || isPlaybackRequested
    // feeds rememberPausedArtScale): the subview header does NOT shrink at rest on
    // play/pause, but mid-flight this header's art IS what the shared-element overlay
    // renders, so it departs at the hero's current paused scale and relaxes to 1f.
    isPlaybackActive: () -> Boolean,
    isImmersiveEnabled: Boolean,
    marqueeMode: MarqueeMode,
    headerVisibilityScope: AnimatedVisibilityScope,
    headerArtAlpha: () -> Float,
    modifier: Modifier,
    onCollapse: () -> Unit,
    upTransitionState: SeekableTransitionState<Boolean>
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = FoamDimens.paddingMedium)) {
        Column(modifier = Modifier.fillMaxWidth().then(modifier)) {
            MiniPlayerHeader(
                track = track,
                isPlaybackActive = isPlaybackActive,
                isImmersiveEnabled = isImmersiveEnabled,
                marqueeMode = marqueeMode,
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
                    SubViewMode.LYRICS -> LyricsScreen(
                        backgroundColor = Color.Transparent,
                        // Same collapse-drag as the header: keeps swipe-back working in the
                        // lyrics states that render no scrollable (loading / not found).
                        dragModifier = modifier
                    )
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
    isPlaybackActive: () -> Boolean,
    isImmersiveEnabled: Boolean,
    marqueeMode: MarqueeMode,
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

    // The full player hero's current paused shrink (see rememberPausedArtScale). This
    // header rests at scale 1f -- a play/pause flip must NOT animate this header's art
    // -- but mid-flight this header's art IS what the shared-element overlay renders,
    // so it departs at the hero's CURRENT paused scale and relaxes to 1f along the
    // same eased flight fraction the shadow below rides, or an up-morph taken while
    // paused pops the art 3% at hand-off.
    val heroPausedArtScale by rememberPausedArtScale(isPlaybackActive)

    // Deferred reads: the transition fraction is evaluated inside the graphicsLayer
    // blocks below, so the subview drag animates the corner radii per frame without
    // recomposing this header (which used to re-run on every drag frame just to
    // rebuild a RoundedCornerShape for .clip()).
    // The overlay records the shared element layer at the interpolated bounds each
    // frame, so this radius lives in that changing coordinate space: it has to lerp
    // from the hero-end full radius down to this header's mini radius, eased in the
    // direction of travel to keep pace with the fast-start spring bounds. A constant
    // mini radius here goes nearly square the moment the interpolated bounds exceed
    // the header's 48dp.
    val density = LocalDensity.current
    val miniRadiusPx = with(density) { miniRadius.toPx() }
    val fullRadiusPx = with(density) { fullRadius.toPx() }
    val imageRadiusPx = remember(miniRadiusPx, fullRadiusPx, upTransitionState) {
        {
            val raw = upTransitionState.progress().coerceIn(0f, 1f)
            val fraction = if (upTransitionState.targetState) {
                EaseOutCubic.transform(raw)
            } else {
                EaseInCubic.transform(raw)
            }
            lerpFloat(
                start = fullRadiusPx,
                stop = miniRadiusPx,
                fraction = fraction
            )
        }
    }
    // Shadow elevation for the art during the up-morph. While the bounds animation is
    // active, the shared-element overlay renders ONLY the target entry's content
    // (SharedElementEntry.shouldRenderAtAll) -- on the open swipe that is THIS header's
    // art, not the hero's -- so the full player's shadow has to live here too, or it
    // drops the frame the morph takes over. The elevation starts at the hero's full
    // value (the interpolated bounds begin at the hero's full-size art) and fades to
    // zero as the art settles into this shadowless 48dp slot, so the up/down swipe
    // fades the shadow progressively. Same easing as the radius above so both chase
    // the flight in lockstep.
    val fullArtElevationPx = with(density) { PlayerDimens.fullPlayerArtElevation.toPx() }
    val imageShadowElevationPx = remember(fullArtElevationPx, upTransitionState) {
        {
            val raw = upTransitionState.progress().coerceIn(0f, 1f)
            val fraction = if (upTransitionState.targetState) {
                EaseOutCubic.transform(raw)
            } else {
                EaseInCubic.transform(raw)
            }
            fullArtElevationPx * (1f - fraction)
        }
    }
    // Paused-shrink departure, eased by the same flight fraction as the shadow: the
    // hero-end (fraction 0 toward this slot) renders the hero's paused scale, this
    // slot's own rest (fraction 1) is unscaled 1f.
    val imageScalePx = remember(heroPausedArtScale, upTransitionState) {
        {
            val raw = upTransitionState.progress().coerceIn(0f, 1f)
            val fraction = if (upTransitionState.targetState) {
                EaseOutCubic.transform(raw)
            } else {
                EaseInCubic.transform(raw)
            }
            1f + (heroPausedArtScale - 1f) * (1f - fraction)
        }
    }
    // The container is not a shared element (it only slides/fades with the header), so its
    // rounding stays fixed at the resting art-radius + padding relationship -- the same
    // formula as the bottom mini player's container -- instead of chasing the animated
    // shared-element radius during the flight.
    val containerRadius = miniRadius + 8.dp
    val headerInteractions = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .graphicsLayer {
                shape = RoundedCornerShape(containerRadius)
                clip = true
            }
            .background(FoamTheme.colors.background.copy(alpha = 0.2f))
            .clickable(
                interactionSource = headerInteractions,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onBack
            )
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
                            scaleX = imageScalePx()
                            scaleY = imageScalePx()
                            // Classic player only: in immersive the headerArtAlpha above
                            // fades this art, and a layer's own alpha does not fade its
                            // RenderNode shadow, so the shadow is kept out of that path.
                            shadowElevation = if (!isImmersiveEnabled) {
                                imageShadowElevationPx()
                            } else {
                                0f
                            }
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
                    MarqueeText(
                        text = headerTrack.title,
                        style = FoamTheme.typography.titleMedium,
                        mode = marqueeMode,
                        modifier = Modifier.sharedBounds(rememberSharedContentState(key = "track_title_header_${headerTrack.id}"), animatedVisibilityScope = headerVisibilityScope)
                    )
                    MarqueeText(
                        text = "${headerTrack.artist} - ${headerTrack.album}",
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                        mode = marqueeMode,
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
    isPlaying: () -> Boolean,
    progress: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Read in this leaf's scope: a play/pause flip recomposes only this button.
    val isPlaying = isPlaying()
    val isEnabled by remember(progress) { derivedStateOf { progress() > PlayerDimens.visibilityEpsilon } }
    val playInteractions = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .pressShrink(playInteractions)
            .size(PlayerDimens.miniPlayButtonSize)
            .graphicsLayer { alpha = progress() }
            .clip(CircleShape)
            .background(FoamTheme.accentColor)
            .clickable(
                interactionSource = playInteractions,
                indication = null,
                enabled = isEnabled,
                onClick = onClick
            ),
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
    val transportInteractions = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .pressShrink(transportInteractions)
            .size(PlayerDimens.miniTransportButtonSize)
            .graphicsLayer { alpha = progress() }
            .clip(CircleShape)
            .clickable(
                interactionSource = transportInteractions,
                indication = null,
                enabled = isEnabled,
                onClick = onClick
            ),
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
