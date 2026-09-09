package com.foamplayer.app.ui.screens.library

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.theme.FoamTheme
import androidx.core.net.toUri
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.launch

/** Size of the album art thumbnail at rest (not actively playing). */
private val AlbumArtSize = 48.dp

/**
 * Padding added around the row content, and the amount by which the
 * active-track selection rectangle is inset/expanded beyond that content.
 */
private val HighlightPadding = 6.dp

/** Opacity applied to auxiliary queue tracks. */
private const val AuxiliaryAlpha = 0.55f

/**
 * Fraction of the row width the row must be dragged sideways before the
 * swipe gesture dismisses (removes) the item.
 */
private const val SwipeDismissThresholdFraction = 0.2f

/** Fallback swipe threshold (in dp) used before the row width is measured. */
private val SwipeDismissThreshold = 80.dp

/**
 * Extra translucency applied while swiping: the row's alpha eases from 1f
 * down to (1 - SwipeMaxFade) — i.e. 0.7f — as it reaches the threshold.
 */
private const val SwipeMaxFade = 0.3f

/** Duration of the fly-out animation when the item is dismissed. */
private const val SwipeDismissDurationMs = 150

/** Duration of the spring-back animation when the swipe is cancelled. */
private const val SwipeReturnDurationMs = 200

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TrackListItem(
    track: TrackEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    auxiliary: Boolean = false,
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    suppressItemAnimations: Boolean = false,
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
        FoamTheme.accentColor.copy(alpha = 0.4f)
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
    val selectionHighlightPaddingPx = with(density) { HighlightPadding.toPx() }

    val restingArtHeightPx = with(density) { AlbumArtSize.toPx() }
    var artBoxHeightPx by remember { mutableFloatStateOf(restingArtHeightPx) }
    var artBoxTopPx by remember { mutableFloatStateOf(selectionHighlightPaddingPx) }
    var artistTextBottomPx by remember {
        mutableFloatStateOf(selectionHighlightPaddingPx + restingArtHeightPx)
    }
    var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val imageRadius = (AlbumArtSize / 2) * FoamTheme.shapes.miniPlayerRounding
    val highlightRadius = imageRadius + HighlightPadding
    val highlightShape = RoundedCornerShape(highlightRadius)
    val highlightCornerRadiusPx = with(density) { highlightRadius.toPx() }

    val rowAlpha = if (auxiliary) AuxiliaryAlpha else 1f

    // --- Swipe-to-dismiss state (only active when onRemove is provided) ---
    val scope = rememberCoroutineScope()
    val dismissOffset = remember { Animatable(0f) }
    var itemWidthPx by remember { mutableFloatStateOf(0f) }
    val latestOnRemove by rememberUpdatedState(onRemove)
    val fallbackDismissThresholdPx = with(density) { SwipeDismissThreshold.toPx() }

    // Distance (in px) the row must be dragged sideways before it is dismissed
    fun swipeThresholdPx(): Float =
        if (itemWidthPx > 0f) itemWidthPx * SwipeDismissThresholdFraction
        else fallbackDismissThresholdPx

    val swipeModifier = if (onRemove != null) {
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        dismissOffset.snapTo(dismissOffset.value + dragAmount)
                    }
                },
                onDragEnd = {
                    if (abs(dismissOffset.value) >= swipeThresholdPx()) {
                        // Fling the row fully off-screen, then remove the item
                        val exitDistance =
                            if (itemWidthPx > 0f) itemWidthPx else fallbackDismissThresholdPx
                        val exitTarget =
                            if (dismissOffset.value > 0f) exitDistance else -exitDistance
                        scope.launch {
                            dismissOffset.animateTo(
                                exitTarget,
                                tween(durationMillis = SwipeDismissDurationMs)
                            )
                            latestOnRemove?.invoke()
                        }
                    } else {
                        // Not dragged far enough: animate back to rest
                        scope.launch {
                            dismissOffset.animateTo(
                                0f,
                                tween(durationMillis = SwipeReturnDurationMs)
                            )
                        }
                    }
                },
                onDragCancel = {
                    scope.launch {
                        dismissOffset.animateTo(
                            0f,
                            tween(durationMillis = SwipeReturnDurationMs)
                        )
                    }
                }
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = rowAlpha }
            .then(
                if (active) {
                    Modifier.onGloballyPositioned { containerCoordinates = it }
                } else {
                    Modifier
                }
            )
    ) {
        // Active rectangle indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(AlbumArtSize + 2 * HighlightPadding)
                .drawBehind {
                    val contentBottomPx = max(
                        artBoxTopPx + artBoxHeightPx,
                        artistTextBottomPx
                    )
                    val targetHeightPx = (contentBottomPx - artBoxTopPx) + selectionHighlightPaddingPx * 2f
                    val topPx = artBoxTopPx - selectionHighlightPaddingPx
                    drawRoundRect(
                        color = animatedBackgroundColor,
                        topLeft = Offset(0f, topPx),
                        size = Size(size.width, targetHeightPx),
                        cornerRadius = CornerRadius(highlightCornerRadiusPx, highlightCornerRadiusPx)
                    )
                }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { itemWidthPx = it.width.toFloat() }
                .graphicsLayer {
                    translationX = dismissOffset.value
                    // Become slightly translucent while swiping: alpha eases
                    // from 1f down to (1 - SwipeMaxFade) at the threshold
                    val threshold = max(swipeThresholdPx(), 1f)
                    val progress = (abs(dismissOffset.value) / threshold).coerceIn(0f, 1f)
                    alpha = 1f - progress * SwipeMaxFade
                }
                .then(swipeModifier)
                .clip(highlightShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = FoamTheme.colors.textPrimary)
                ) { onClick() }
                .padding(horizontal = HighlightPadding, vertical = HighlightPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(AlbumArtSize)
                    .sharedArtwork()
                    .then(
                        if (active) {
                            Modifier.onGloballyPositioned { coords ->
                                artBoxHeightPx = coords.size.height.toFloat()
                                containerCoordinates?.let { container ->
                                    artBoxTopPx = container.localPositionOf(coords, Offset.Zero).y
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
                    .clip(RoundedCornerShape(imageRadius))
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
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
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
                    modifier = Modifier
                        .sharedText("track_artist_${track.id}")
                        .then(
                            if (active) {
                                Modifier.onGloballyPositioned { coords ->
                                    containerCoordinates?.let { container ->
                                        artistTextBottomPx = container.localPositionOf(
                                            coords,
                                            Offset(0f, coords.size.height.toFloat())
                                        ).y
                                    }
                                }
                            } else {
                                Modifier
                            }
                        )
                )
            }

            if (showDragHandle) {
                Spacer(modifier = Modifier.width(12.dp))

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
