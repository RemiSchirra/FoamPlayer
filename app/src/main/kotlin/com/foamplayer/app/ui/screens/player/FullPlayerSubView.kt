package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.Icon
import androidx.compose.material.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.CrossfadingPlayPauseIcon
import com.foamplayer.app.ui.components.MarqueeText
import com.foamplayer.app.ui.components.NoAlbumArtPlaceholder
import com.foamplayer.app.ui.components.albumArtMemoryCacheKey
import com.foamplayer.app.ui.screens.lyrics.LyricsScreen
import com.foamplayer.app.ui.screens.queue.QueueScreen
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamBackground
import com.foamplayer.app.ui.theme.foamSurfaceVariant
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
 *    leaf per entry -- instead of inline in SubViewSelectorItem's own body confines that
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

    val label = stringResource(mode.labelRes)
    val iconSize = 28.dp
    val touchPadding = 10.dp
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
    onPlayOnClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val restInsetPx = with(LocalDensity.current) { SelectorRestInset.toPx() }
    val directionSign = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                translationX = directionSign * restInsetPx * (1f - progress().coerceIn(0f, 1f))
            }
        ) {
            SubViewSelectorItem(
                mode = SubViewMode.LYRICS,
                isSelected = subViewMode == SubViewMode.LYRICS,
                progress = progress,
                onSelect = onSelect
            )
        }

        SubViewSelectorAction(
            icon = FoamTheme.icons.current.cast,
            contentDescription = stringResource(R.string.play_on),
            progress = progress,
            onClick = onPlayOnClick
        )

        Box(
            modifier = Modifier.graphicsLayer {
                translationX = -directionSign * restInsetPx * (1f - progress().coerceIn(0f, 1f))
            }
        ) {
            SubViewSelectorItem(
                mode = SubViewMode.QUEUE,
                isSelected = subViewMode == SubViewMode.QUEUE,
                progress = progress,
                onSelect = onSelect
            )
        }
    }
}

private val SelectorRestInset = 40.dp
private val SelectorIconSize = 28.dp
private val SelectorActionTouchPadding = 12.dp

/** Height of the selector row's content (the tallest item is the "Play on" action). */
internal val SelectorRowHeight = SelectorIconSize + SelectorActionTouchPadding * 2

/**
 * Non-selector action icons living between the lyrics/queue selectors (currently just "Play on"). Same icon/touch sizing as SubViewSelectorItem, but they disappear
 * as the subview opens: [progress] -> 1 fades the icon out (draw-phase alpha) and
 * gates the click once invisible, so a hidden action is never tappable while the
 * lyrics/queue selectors at both ends stay reachable for switching/collapsing.
 * [isDimmed] renders the icon semi-transparent (e.g. repeat disabled); otherwise
 * the icon renders full-opacity primary.
 */
@Composable
private fun SubViewSelectorAction(
    icon: ImageVector,
    contentDescription: String,
    progress: () -> Float,
    onClick: () -> Unit,
    isDimmed: Boolean = false
) {
    val isEnabled by remember(progress) {
        derivedStateOf { progress() < 1f - PlayerDimens.visibilityEpsilon }
    }
    val actionInteractions = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .pressShrink(actionInteractions)
            .clickable(
                interactionSource = actionInteractions,
                indication = null,
                enabled = isEnabled,
                onClick = onClick
            )
            .padding(SelectorActionTouchPadding),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier
                .size(SelectorIconSize)
                .graphicsLayer {
                    alpha = (if (isDimmed) 0.4f else 1f) * (1f - progress().coerceIn(0f, 1f))
                }
        )
    }
}

/** Layer 2 content: mini header (draggable handle) plus the lyrics or queue subview. */
@Composable
internal fun SharedTransitionScope.SubViewContent(
    track: TrackEntity,
    subViewMode: SubViewMode,
    viewModel: PlayerViewModel,
    isPlaying: () -> Boolean,
    isPlaybackActive: () -> Boolean,
    isImmersiveEnabled: Boolean,
    marqueeMode: MarqueeMode,
    headerVisibilityScope: AnimatedVisibilityScope,
    headerArtAlpha: () -> Float,
    modifier: Modifier,
    onCollapse: () -> Unit,
    upTransitionState: SeekableTransitionState<Boolean>
) {
    Column(modifier = Modifier.fillMaxSize()) {
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
                    fadeIn(tween(FoamMotion.Duration.SMOOTH)) togetherWith
                            fadeOut(tween(FoamMotion.Duration.SMOOTH))
                },
                label = "SubViewContent"
            ) { mode ->
                when (mode) {
                    SubViewMode.LYRICS -> LyricsScreen(
                        backgroundColor = Color.Transparent,
                        dragModifier = modifier
                    )
                    SubViewMode.QUEUE -> QueueScreen(viewModel = viewModel, backgroundColor = Color.Transparent)
                }
            }
        }
    }
}

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

    val heroPausedArtScale by rememberPausedArtScale(isPlaybackActive)

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
    val containerRadius = miniRadius + 8.dp
    val headerInteractions = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(FoamDimens.paddingSmall)
            .graphicsLayer {
                shape = RoundedCornerShape(containerRadius)
                clip = true
            }
            .foamBackground(alpha = 0.2f)
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
                (fadeIn(tween(FoamMotion.Duration.SMOOTH)) +
                        slideInVertically(tween(FoamMotion.Duration.SMOOTH)) { height -> height / 3 }) togetherWith
                        fadeOut(tween(FoamMotion.Duration.SMOOTH))
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
                            shadowElevation = if (!isImmersiveEnabled) {
                                imageShadowElevationPx()
                            } else {
                                0f
                            }
                        }
                        .foamSurfaceVariant()
                ) {
                    if (headerTrack.albumArtPath != null) {
                        val context = LocalContext.current
                        val headerArtRequest = remember(headerTrack.albumArtPath) {
                            ImageRequest.Builder(context)
                                .data(EmbeddedAlbumArt(headerTrack.albumArtPath.toUri()))
                                .size(AlbumArtRequestSize)
                                .crossfade(false)
                                .placeholderMemoryCacheKey(
                                    albumArtMemoryCacheKey(headerTrack.albumArtPath)
                                )
                                .memoryCacheKey(albumArtMemoryCacheKey(headerTrack.albumArtPath))
                                .build()
                        }
                        AsyncImage(
                            model = headerArtRequest,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        NoAlbumArtPlaceholder()
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    MarqueeText(
                        text = headerTrack.title,
                        style = FoamTheme.typography.titleMedium,
                        mode = marqueeMode,
                        modifier = Modifier.sharedBounds(rememberSharedContentState(key = "track_title_header"), animatedVisibilityScope = headerVisibilityScope)
                    )
                    MarqueeText(
                        text = stringResource(R.string.artist_album_separator, headerTrack.artist, headerTrack.album),
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                        mode = marqueeMode,
                        modifier = Modifier.sharedBounds(rememberSharedContentState(key = "track_artist_header"), animatedVisibilityScope = headerVisibilityScope)
                    )
                }
            }
        }
        CollapseAccentIcon()
    }
}

/**
 * Isolated so the per-frame accent read recomposes only the icon: accentColor
 * changes every frame of a hero pager swipe (viewModel.setDynamicAccentColor
 * fires per drag frame), and reading it inline in the header body would
 * recompose the header (and its sharedBounds/marquee children) at display
 * frequency. Same pattern as SubViewSelectorItem above.
 */
@Composable
private fun CollapseAccentIcon() {
    Icon(
        imageVector = Icons.Rounded.KeyboardArrowDown,
        contentDescription = stringResource(R.string.collapse),
        tint = FoamTheme.accentColor,
        modifier = Modifier.size(28.dp)
    )
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
    isLoading: () -> Boolean,
    progress: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = PlayerDimens.miniPlayButtonSize,
    iconSize: Dp = PlayerDimens.miniPlayIconSize
) {
    // Read in this leaf's scope: a play/pause flip recomposes only this button.
    val isPlaying = isPlaying()
    val isLoading = isLoading()
    val isEnabled by remember(progress) { derivedStateOf { progress() > PlayerDimens.visibilityEpsilon } }
    val playInteractions = remember { MutableInteractionSource() }

    // Same scale+fade convention as the full player's play/pause crossfade.
    val loadingFraction by animateFloatAsState(
        targetValue = if (isLoading) 1f else 0f,
        animationSpec = tween(durationMillis = FoamMotion.Duration.STANDARD),
        label = "miniPlayLoadingCrossfade"
    )
    val description = stringResource(
        when {
            isLoading -> R.string.loading
            isPlaying -> R.string.pause
            else -> R.string.play
        }
    )

    Box(
        modifier = modifier
            .pressShrink(playInteractions)
            .size(size)
            .graphicsLayer { alpha = progress() }
            .semantics { contentDescription = description }
            .clickable(
                interactionSource = playInteractions,
                indication = null,
                enabled = isEnabled && !isLoading,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        CrossfadingPlayPauseIcon(
            isPlaying = isPlaying,
            loadingFraction = { loadingFraction },
            tint = FoamTheme.colors.textPrimary,
            iconSize = iconSize
        )
        if (isLoading || loadingFraction > 0f) {
            CircularProgressIndicator(
                color = FoamTheme.colors.textPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer {
                        alpha = loadingFraction
                        val circleScale = 0.2f + 0.8f * loadingFraction
                        scaleX = circleScale
                        scaleY = circleScale
                    }
            )
        }
    }
}


/**
 * Previous/Next flank of MiniPlayPauseButton in the subview's transport cluster.
 * Same deferred-`progress` pattern as MiniPlayPauseButton: alpha lives inside the
 * graphicsLayer block (redraws without recomposing) and clicks are gated on the
 * cluster actually being visible; the caller hands this the same memoized lambda
 * instance so the cluster is skipped most frames.
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