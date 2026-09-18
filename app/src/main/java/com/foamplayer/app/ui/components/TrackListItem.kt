package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.TimeUtils
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Opacity applied to auxiliary queue tracks. */
private const val AuxiliaryAlpha = 0.55f

/**
 * Corner radius of the full player's hero artwork: half the square art's side
 * (screen width minus the player's 24dp page padding on both sides) times the
 * full-player rounding percentage. Flight-start anchor for [playerFlightArtwork];
 * must stay in sync with TrackHeroPager's fullImageSize/fullRadius.
 */
@Composable
internal fun fullPlayerArtRadius(): Dp =
    (LocalConfiguration.current.screenWidthDp.dp - 48.dp) / 2 * FoamTheme.shapes.fullPlayerRounding

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

/**
 * One item of a track list. At the ROW_* densities it renders the shared
 * [MediaListRow] scaffold — artwork (or track number) + title + "artist •
 * album" (RowBig grows the art to 72dp and adds a "duration • format" third
 * line) — with the active-track highlight, swipe-to-dismiss and drag-handle
 * behaviors layered on top. At the GRID_* densities it renders a square-art
 * [MediaGridCell] with the score icon overlaid on a corner of the artwork.
 *
 * Shared-element keys ("album_art_*", "track_title_*", "track_artist_*")
 * match the mini/full player's artwork and text so the row animates into the
 * player when its track starts playing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TrackListItem(
    track: TrackEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    active: Boolean = false,
    auxiliary: Boolean = false,
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    trackNumberText: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // Deferred paused-art scale for rows that act as the shared-element endpoint of a
    // player-dismiss flight (see [playerFlightArtwork]); only origin-collection list
    // hosts provide it, null keeps the static rest clip everywhere else.
    flightPausedArtScale: (() -> Float)? = null,
    // Mirrors the hero's immersive fade for the flight overlay (see playerFlightArtwork).
    immersiveArt: Boolean = false,
    isSharedTransitionSource: Boolean = false
) {
    val animatedBackgroundColor = activeRowHighlight(active, track.albumArtPath)

    val targetTrackNumberColor = if (active) {
        FoamTheme.colors.textPrimary
    } else {
        FoamTheme.colors.textSecondary
    }

    val animatedTrackNumberColor by animateColorAsState(
        targetValue = targetTrackNumberColor,
        animationSpec = tween(durationMillis = 200, easing = EaseOut),
        label = "activeTrackNumberFade"
    )

    // Long-press opens the track actions modal when FoamNavHost provides one at
    // the root; an explicit [onLongClick] always wins over that default.
    val trackModalState = LocalTrackFoamModalState.current
    val resolvedOnLongClick: (() -> Unit)? = when {
        onLongClick != null -> onLongClick
        trackModalState != null -> {
            val openModal: () -> Unit = { trackModalState.show(track) }
            openModal
        }
        else -> null
    }

    // GRID_* densities: square artwork cell, score overlaid on the art corner.
    // Swipe-to-dismiss / drag handles are row-only affordances (the queue, the
    // only screen using them, does not support densities).
    if (density.isGrid) {
        val sharedArtworkBase: Modifier = Modifier.rowSharedArtwork(
            key = "album_art_${track.id}",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = isSharedTransitionSource
        )
        val gridArtworkModifier = if (isSharedTransitionSource &&
            animatedVisibilityScope != null &&
            flightPausedArtScale != null
        ) {
            sharedArtworkBase
                .playerFlightArtwork(
                    animatedVisibilityScope = animatedVisibilityScope,
                    fullArtRadius = fullPlayerArtRadius(),
                    pausedArtScale = flightPausedArtScale,
                    immersive = immersiveArt
                )
                .background(FoamTheme.colors.surfaceVariant)
        } else {
            sharedArtworkBase
                .clip(AlbumArtShape())
                .background(FoamTheme.colors.surfaceVariant)
        }
        Box(
            modifier = modifier
                .fillMaxWidth()
                // Only auxiliary rows need an alpha layer; the lambda-graphicsLayer
                // variant promotes every row to its own render layer even at 1f.
                .then(if (auxiliary) Modifier.graphicsLayer { alpha = AuxiliaryAlpha } else Modifier)
        ) {
            MediaGridCell(
                onClick = onClick,
                onLongClick = resolvedOnLongClick,
                artworkModifier = gridArtworkModifier,
                artworkContent = { TrackGridArtwork(track) },
                title = track.title,
                subtitle = "${track.artist} • ${track.album}",
                highlightBackground = animatedBackgroundColor,
                titleModifier = Modifier.rowSharedText(
                    key = "track_title_${track.id}",
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    enabled = isSharedTransitionSource
                ),
                subtitleModifier = Modifier.rowSharedText(
                    key = "track_artist_${track.id}",
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    enabled = isSharedTransitionSource
                ),
                artworkOverlay = {
                    if (trackNumberText != null) {
                        GridOverlayChip(modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                            BasicText(
                                text = trackNumberText,
                                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary)
                            )
                        }
                    }
                    if (track.score != 0) {
                        val (scoreIcon, _) = trackScoreVisual(track.score)
                        GridOverlayChip(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                            Icon(
                                imageVector = scoreIcon,
                                contentDescription = null,
                                tint = FoamTheme.colors.textPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            )
        }
        return
    }

    val localDensity = LocalDensity.current

    // --- Swipe-to-dismiss state (only active when onRemove is provided) ---
    val scope = rememberCoroutineScope()

    // Plain snapshot float backing the swipe offset. During the drag it is written
    // directly in the gesture callback (read back in the row's graphicsLayer, so
    // drags cost zero coroutines); the end-of-gesture animations run through an
    // Animatable that feeds this same float. The old backing was an Animatable
    // snapTo'd from a fresh coroutine per drag delta — one Job allocation plus a
    // mutex hop at pointer-event rate (~120/s on high-refresh panels) per row.
    var dismissOffsetValue by remember { mutableFloatStateOf(0f) }
    val gestureAnimJob = remember { mutableStateOf<Job?>(null) }
    var itemWidthPx by remember { mutableFloatStateOf(0f) }
    val latestOnRemove by rememberUpdatedState(onRemove)
    val fallbackDismissThresholdPx = with(localDensity) { SwipeDismissThreshold.toPx() }

    // Distance (in px) the row must be dragged sideways before it is dismissed
    fun swipeThresholdPx(): Float =
        if (itemWidthPx > 0f) itemWidthPx * SwipeDismissThresholdFraction
        else fallbackDismissThresholdPx

    fun animateSwipeTo(target: Float, durationMs: Int, onEnd: () -> Unit = {}) {
        gestureAnimJob.value?.cancel()
        gestureAnimJob.value = scope.launch {
            val animatable = Animatable(dismissOffsetValue)
            animatable.animateTo(target, tween(durationMillis = durationMs)) {
                dismissOffsetValue = value
            }
            onEnd()
        }
    }

    val swipeModifier = if (onRemove != null) {
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = {
                    // Grabbing a row mid return/fly-out animation takes over from it.
                    gestureAnimJob.value?.cancel()
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    dismissOffsetValue += dragAmount
                },
                onDragEnd = {
                    if (abs(dismissOffsetValue) >= swipeThresholdPx()) {
                        // Fling the row fully off-screen, then remove the item
                        val exitDistance =
                            if (itemWidthPx > 0f) itemWidthPx else fallbackDismissThresholdPx
                        val exitTarget =
                            if (dismissOffsetValue > 0f) exitDistance else -exitDistance
                        animateSwipeTo(exitTarget, SwipeDismissDurationMs) {
                            latestOnRemove?.invoke()
                        }
                    } else {
                        // Not dragged far enough: animate back to rest
                        animateSwipeTo(0f, SwipeReturnDurationMs)
                    }
                },
                onDragCancel = {
                    animateSwipeTo(0f, SwipeReturnDurationMs)
                }
            )
        }
    } else {
        Modifier
    }

    // Rows showing a track number have no artwork box (and take no part in the
    // artwork shared-element transition).
    val artworkModifier = if (trackNumberText == null) {
        val sharedArtworkBase = Modifier.rowSharedArtwork(
            key = "album_art_${track.id}",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = isSharedTransitionSource
        )
        if (isSharedTransitionSource &&
            animatedVisibilityScope != null &&
            flightPausedArtScale != null
        ) {
            // Dismiss-flight endpoint: the clip radius must ride the transition (see
            // [playerFlightArtwork]) instead of clipping at the rest radius.
            sharedArtworkBase
                .playerFlightArtwork(
                    animatedVisibilityScope = animatedVisibilityScope,
                    fullArtRadius = fullPlayerArtRadius(),
                    pausedArtScale = flightPausedArtScale,
                    immersive = immersiveArt
                )
                .background(FoamTheme.colors.surfaceVariant)
        } else {
            sharedArtworkBase
                .clip(RoundedCornerShape(listRowArtCornerRadius(density)))
                .background(FoamTheme.colors.surfaceVariant)
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Only auxiliary rows need an alpha layer; the lambda-graphicsLayer
            // variant promotes every row to its own render layer even at 1f.
            .then(if (auxiliary) Modifier.graphicsLayer { alpha = AuxiliaryAlpha } else Modifier)
    ) {
        MediaListRow(
            onClick = onClick,
            onLongClick = resolvedOnLongClick,
            artworkModifier = artworkModifier,
            artworkContent = {
                if (trackNumberText != null) {
                    BasicText(
                        text = trackNumberText,
                        style = FoamTheme.typography.titleMedium.copy(color = animatedTrackNumberColor)
                    )
                } else if (track.albumArtPath != null) {
                    val context = LocalContext.current
                    val albumArtRequest = remember(track.albumArtPath) {
                        ImageRequest.Builder(context)
                            .data(EmbeddedAlbumArt(track.albumArtPath.toUri()))
                            .size(AlbumArtRequestSize)
                            .build()
                    }
                    AlbumArtImage(
                        model = albumArtRequest,
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    BasicText(
                        text = track.format,
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
                    )
                }
            },
            title = track.title,
            subtitle = "${track.artist} • ${track.album}",
            density = density,
            tertiaryText = if (density == MediaDensity.ROW_BIG) {
                "${TimeUtils.formatDurationCompact(track.duration)} • ${track.format}"
            } else {
                null
            },
            highlightBackground = animatedBackgroundColor,
            rowModifier = Modifier
                .onSizeChanged { itemWidthPx = it.width.toFloat() }
                .graphicsLayer {
                    translationX = dismissOffsetValue
                    // Become slightly translucent while swiping: alpha eases
                    // from 1f down to (1 - SwipeMaxFade) at the threshold
                    val threshold = max(swipeThresholdPx(), 1f)
                    val progress = (abs(dismissOffsetValue) / threshold).coerceIn(0f, 1f)
                    alpha = 1f - progress * SwipeMaxFade
                }
                .then(swipeModifier),
            titleModifier = Modifier.rowSharedText(
                key = "track_title_${track.id}",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                enabled = isSharedTransitionSource
            ),
            subtitleModifier = Modifier.rowSharedText(
                key = "track_artist_${track.id}",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                enabled = isSharedTransitionSource
            ),
            trailingContent = {
                // Score indicator (like / super like / dislike): informational
                // only, so it gets no click handling of its own — taps stay on
                // the row. Muted (textSecondary) here, unlike the player/modal
                // where it's white.
                if (track.score != 0) {
                    Spacer(modifier = Modifier.width(12.dp))

                    val (scoreIcon, _) = trackScoreVisual(track.score)
                    Icon(
                        imageVector = scoreIcon,
                        contentDescription = null,
                        tint = FoamTheme.colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(
                    visible = showDragHandle,
                    enter = fadeIn(animationSpec = tween(durationMillis = 200)) +
                            expandHorizontally(animationSpec = tween(durationMillis = 200)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 200)) +
                            shrinkHorizontally(animationSpec = tween(durationMillis = 200))
                ) {
                    Spacer(modifier = Modifier.width(12.dp))

                    val dragHandleInteractionSource = remember { MutableInteractionSource() }
                    Column(
                        modifier = dragHandleModifier
                            .clickable(
                                interactionSource = dragHandleInteractionSource,
                                indication = null
                            ) {/* Consume the click */}
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
        )
    }
}

/** Album-art (or format-letter fallback) filling a grid cell's square artwork box. */
@Composable
private fun TrackGridArtwork(track: TrackEntity) {
    if (track.albumArtPath != null) {
        val context = LocalContext.current
        val albumArtRequest = remember(track.albumArtPath) {
            ImageRequest.Builder(context)
                .data(EmbeddedAlbumArt(track.albumArtPath.toUri()))
                .size(AlbumArtRequestSize)
                .build()
        }
        AlbumArtImage(
            model = albumArtRequest,
            contentDescription = "Album Art",
            modifier = Modifier.fillMaxSize(),
            placeholderSize = 32.dp
        )
    } else {
        BasicText(
            text = track.format,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
        )
    }
}

/** Small rounded translucent chip overlaid on a grid cell's artwork corner (score badge, track number). */
@Composable
private fun GridOverlayChip(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
