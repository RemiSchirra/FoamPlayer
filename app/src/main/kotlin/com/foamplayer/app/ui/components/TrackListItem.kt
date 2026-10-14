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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenWith
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.foamplayer.app.R
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.modals.LocalTrackFoamModalState
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
 * times the full-player rounding percentage. Flight-start anchor for
 * [playerFlightArtwork]; must stay in sync with TrackHeroPager.
 */
@Composable
internal fun fullPlayerArtRadius(): Dp =
    (LocalConfiguration.current.screenWidthDp.dp - 48.dp) / 2 * FoamTheme.shapes.fullPlayerRounding

/** Fraction of the row width a swipe must cover before the item is dismissed. */
private const val SwipeDismissThresholdFraction = 0.2f

/** Fallback swipe threshold (dp) used before the row width is measured. */
private val SwipeDismissThreshold = 80.dp

/**
 * Right-swipe travel where the "play next" gesture commits (haptic tick): up
 * to here the row follows the finger 1:1; past it drag resistance ramps up.
 */
private val SwipeNextCommitTravel = 64.dp

/**
 * Right-swipe travel where the resistance ramp ends and the row truly locks:
 * sensitivity eases 1 -> 0 between [SwipeNextCommitTravel] and this distance,
 * and the finger has no effect beyond it.
 */
private val SwipeNextHardStopTravel = 160.dp

/** Size of the icons revealed behind a swiped row. */
private val SwipeActionIconSize = 24.dp

/** Inset of the delete icon from the row's end edge. */
private val SwipeActionIconInset = 24.dp

/** Extra translucency at the dismiss threshold (alpha eases 1f -> 1 - this). */
private const val SwipeMaxFade = 0.3f

/** Duration of the fly-out animation when the item is dismissed. */
private const val SwipeDismissDurationMs = 150

/** Duration of the spring-back animation when the swipe is cancelled. */
private const val SwipeReturnDurationMs = 200

/**
 * One item of a track list. At the ROW_* densities it renders the shared
 * [MediaListRow] scaffold — artwork (or track number) + title + "artist •
 * album" (RowBig adds a "duration • format" third line) — with the
 * active-track highlight, swipe actions and drag-handle behaviors. At the
 * GRID_* densities it renders a square-art [MediaGridCell] with overlays
 * (score, track number, drag chip) on the artwork.
 *
 * Swipe actions (when [onRemove] and/or [onPlayNext] are set): swiping right
 * follows the finger to a commit distance (haptic tick on crossing), then
 * meets rising resistance up to a hard stop where the row locks, and on
 * release past the commit distance springs back while [onPlayNext] fires;
 * swiping left follows the finger, reveals a delete icon on the right, and on
 * release past the threshold flies the row off-screen and fires [onRemove].
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
    onPlayNext: (() -> Unit)? = null,
    trackNumberText: String? = null,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // Deferred paused-art scale for rows acting as the shared-element endpoint
    // of a player-dismiss flight; null keeps the static rest clip everywhere else.
    flightPausedArtScale: (() -> Float)? = null,
    // Mirrors the hero's immersive fade for the flight overlay.
    immersiveArt: Boolean = false,
    // Whether a player-dismiss artwork flight is currently landing on this
    // list's rows. While this row is the flight's tagged target, the flying
    // artwork casts the shadow itself, so the row's static shadow steps aside.
    // Gated on [isSharedTransitionSource], so untagged rows never read it.
    isDismissFlightActive: () -> Boolean = { false },
    isSharedTransitionSource: Boolean = false
) {
    val animatedBackgroundColor = activeRowHighlight(active, track.albumArtPath)

    // Rows without a track number skip the whole animateColorAsState allocation.
    val animatedTrackNumberColor: Color = if (trackNumberText != null) {
        val targetTrackNumberColor = if (active) {
            FoamTheme.colors.textPrimary
        } else {
            FoamTheme.colors.textSecondary
        }
        animateColorAsState(
            targetValue = targetTrackNumberColor,
            animationSpec = tween(durationMillis = 200, easing = EaseOut),
            label = "activeTrackNumberFade"
        ).value
    } else {
        Color.Transparent
    }

    // Long-press opens the track actions modal when the net root provides one;
    // an explicit [onLongClick] always wins.
    val trackModalState = LocalTrackFoamModalState.current
    val resolvedOnLongClick: (() -> Unit)? = when {
        onLongClick != null -> onLongClick
        trackModalState != null -> {
            val openModal: () -> Unit = { trackModalState.show(track) }
            openModal
        }
        else -> null
    }

    // Static rest elevation of the artwork's drop shadow. Dropped (0.dp) while
    // this row is the TAGGED target of a live player-dismiss flight: the
    // flight's overlay artwork casts the shadow itself and lands at exactly
    // [MediaItemArtElevation], so the static one resumes seamlessly.
    val artworkRestElevation =
        if (trackNumberText == null) MediaItemArtElevation else 0.dp
    val artworkElevation = if (isSharedTransitionSource && isDismissFlightActive()) {
        0.dp
    } else {
        artworkRestElevation
    }

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
                    immersive = immersiveArt,
                    restArtElevation = MediaItemArtElevation
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
                subtitle = stringResource(R.string.artist_album_separator, track.artist, track.album),
                highlightBackground = animatedBackgroundColor,
                artworkElevation = artworkElevation,
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
                    AnimatedVisibility(
                        visible = showDragHandle,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        enter = GridDragHandleEnter,
                        exit = GridDragHandleExit
                    ) {
                        // Reordering starts only from this chip; the no-op
                        // clickable swallows taps so the cell doesn't ripple.
                        val dragHandleInteractionSource = remember { MutableInteractionSource() }
                        GridOverlayChip(
                            modifier = dragHandleModifier.clickable(
                                interactionSource = dragHandleInteractionSource,
                                indication = null
                            ) {/* Consume the click */},
                            contentPadding = 6.dp
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.OpenWith,
                                contentDescription = null,
                                tint = FoamTheme.colors.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            )
        }
        return
    }

    val localDensity = LocalDensity.current

    // --- Swipe actions: right = play next (resisted travel), left = remove ---
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Plain snapshot float backing the swipe offset: written directly in the
    // gesture callback during drags (read back in the graphicsLayer, so drags
    // cost zero coroutines); end-of-gesture animations feed this same float.
    var dismissOffsetValue by remember { mutableFloatStateOf(0f) }
    // Multiplier on the delete icon's alpha. Stays 1f while dragging; animates
    // to 0 the moment the left swipe commits, in parallel with the fly-out.
    var deleteIconFade by remember { mutableFloatStateOf(1f) }
    val gestureAnimJob = remember { mutableStateOf<Job?>(null) }
    var itemWidthPx by remember { mutableFloatStateOf(0f) }
    val latestOnRemove by rememberUpdatedState(onRemove)
    val latestOnPlayNext by rememberUpdatedState(onPlayNext)
    val fallbackDismissThresholdPx = with(localDensity) { SwipeDismissThreshold.toPx() }
    val nextCommitPx = with(localDensity) { SwipeNextCommitTravel.toPx() }
    val nextHardStopPx = with(localDensity) { SwipeNextHardStopTravel.toPx() }
    // Row offset at saturation: integrating the linear sensitivity ramp
    // (1 -> 0 over the commit->hard-stop zone) adds half the zone.
    val nextHardStopOffsetPx = nextCommitPx + (nextHardStopPx - nextCommitPx) / 2f

    fun swipeThresholdPx(): Float =
        if (itemWidthPx > 0f) itemWidthPx * SwipeDismissThresholdFraction
        else fallbackDismissThresholdPx

    /**
     * Maps unclamped right-swipe finger travel to the row's offset: 1:1 up to
     * the commit point, sensitivity linearly easing to 0 across the hard-stop
     * zone, then pinned. A pure function of the total travel, so reversing
     * the finger naturally walks back down the same curve.
     */
    fun nextOffsetPx(travel: Float): Float = when {
        travel <= nextCommitPx -> travel
        travel >= nextHardStopPx -> nextHardStopOffsetPx
        else -> {
            val d = travel - nextCommitPx
            val zone = nextHardStopPx - nextCommitPx
            nextCommitPx + d - d * d / (2f * zone)
        }
    }

    fun animateSwipeTo(
        target: Float,
        durationMs: Int,
        fadeDeleteIcon: Boolean = false,
        onEnd: () -> Unit = {}
    ) {
        gestureAnimJob.value?.cancel()
        gestureAnimJob.value = scope.launch {
            if (fadeDeleteIcon) {
                // Starts immediately on commit, concurrent with the slide.
                launch {
                    Animatable(deleteIconFade).animateTo(0f, tween(durationMillis = durationMs)) {
                        deleteIconFade = value
                    }
                }
            }
            val animatable = Animatable(dismissOffsetValue)
            animatable.animateTo(target, tween(durationMillis = durationMs)) {
                dismissOffsetValue = value
            }
            onEnd()
        }
    }

    val swipeActionsEnabled = onRemove != null || onPlayNext != null

    val swipeModifier = if (swipeActionsEnabled) {
        Modifier.pointerInput(Unit) {
            // One-shot latch per gesture: the commit tick fires on crossing the
            // commit travel, and again only after the finger dips back below it.
            var nextSwipeCommitted = false
            // UNCLAMPED running total of the gesture's horizontal drag, seeded
            // from the offset where the finger grabbed the row.
            var nextDragTotal = 0f
            detectHorizontalDragGestures(
                onDragStart = {
                    // Grabbing a row mid return/fly-out takes over from it.
                    gestureAnimJob.value?.cancel()
                    deleteIconFade = 1f
                    nextSwipeCommitted = false
                    nextDragTotal = dismissOffsetValue
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    nextDragTotal += dragAmount
                    // Right (play next) meets rising resistance past the commit
                    // travel; left (remove) keeps following the finger so it can
                    // fly all the way out.
                    dismissOffsetValue = nextOffsetPx(nextDragTotal)
                    val committed = nextDragTotal >= nextCommitPx - 0.5f
                    if (committed && !nextSwipeCommitted) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    nextSwipeCommitted = committed
                },
                onDragEnd = {
                    when {
                        // Committed: fire the move at once and spring back in parallel —
                        // the entry animates to the top of the list while the row
                        // returns to rest, instead of one after the other.
                        onPlayNext != null && dismissOffsetValue >= nextCommitPx - 0.5f -> {
                            latestOnPlayNext?.invoke()
                            animateSwipeTo(0f, SwipeReturnDurationMs)
                        }
                        // Fling the row fully off-screen, then remove it.
                        dismissOffsetValue <= -swipeThresholdPx() -> {
                            val exitDistance =
                                if (itemWidthPx > 0f) itemWidthPx else fallbackDismissThresholdPx
                            animateSwipeTo(
                                -exitDistance,
                                SwipeDismissDurationMs,
                                fadeDeleteIcon = true
                            ) {
                                latestOnRemove?.invoke()
                            }
                        }
                        else -> animateSwipeTo(0f, SwipeReturnDurationMs)
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

    // The swipe layer only exists where actions are actually swipeable; attaching
    // it unconditionally put a lambda graphicsLayer (its own render node) on every
    // resting row of every list for nothing.
    val swipeRowModifier = if (swipeActionsEnabled) {
        (if (auxiliary) Modifier.graphicsLayer { alpha = AuxiliaryAlpha } else Modifier)
            .onSizeChanged { itemWidthPx = it.width.toFloat() }
            .graphicsLayer {
                val offset = dismissOffsetValue
                translationX = offset
                // Only a left swipe (remove) fades the row; a right swipe
                // (play next) leaves it fully opaque.
                val progress = if (offset < 0f) {
                    abs(offset) / max(swipeThresholdPx(), 1f)
                } else {
                    0f
                }
                alpha = 1f - progress.coerceIn(0f, 1f) * SwipeMaxFade
            }
            .then(swipeModifier)
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
            // Dismiss-flight endpoint: the clip radius must ride the transition
            // instead of clipping at the rest radius.
            sharedArtworkBase
                .playerFlightArtwork(
                    animatedVisibilityScope = animatedVisibilityScope,
                    fullArtRadius = fullPlayerArtRadius(),
                    pausedArtScale = flightPausedArtScale,
                    immersive = immersiveArt,
                    restArtElevation = MediaItemArtElevation
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

    // For swipeable rows the auxiliary dimming applies to the row itself, not
    // the parent, so the icons revealed behind it keep full alpha.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (auxiliary && !swipeActionsEnabled) {
                    Modifier.graphicsLayer { alpha = AuxiliaryAlpha }
                } else {
                    Modifier
                }
            )
    ) {
        if (swipeActionsEnabled) {
            SwipeActionBackdrop(
                offsetPx = { dismissOffsetValue },
                nextRevealPx = { nextCommitPx },
                deleteFadePx = { swipeThresholdPx() },
                deleteCommitFade = { deleteIconFade },
                nextIcon = if (onPlayNext != null) FoamTheme.icons.current.next else null,
                deleteIcon = if (onRemove != null) FoamTheme.icons.current.delete else null,
                modifier = Modifier.matchParentSize()
            )
        }

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
                        contentDescription = stringResource(R.string.album_art),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    NoAlbumArtPlaceholder()
                }
            },
            title = track.title,
            subtitle = stringResource(R.string.artist_album_separator, track.artist, track.album),
            density = density,
            tertiaryText = if (density == MediaDensity.ROW_BIG) {
                "${TimeUtils.formatDurationCompact(track.duration)} • ${track.format}"
            } else {
                null
            },
            artworkElevation = artworkElevation,
            highlightBackground = animatedBackgroundColor,
            rowModifier = swipeRowModifier,
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
                // Score indicator: informational only, so taps stay on the row.
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

/**
 * Backdrop revealed behind a row while it is swiped: the "next" icon centers in
 * the space freed on the left on a right swipe, the delete icon sits near the
 * end edge on a left swipe. Both icons read the swipe offset from their
 * graphicsLayer blocks, so dragging costs no recomposition.
 */
@Composable
private fun SwipeActionBackdrop(
    offsetPx: () -> Float,
    nextRevealPx: () -> Float,
    deleteFadePx: () -> Float,
    deleteCommitFade: () -> Float,
    nextIcon: ImageVector?,
    deleteIcon: ImageVector?,
    modifier: Modifier = Modifier
) {
    if (nextIcon == null && deleteIcon == null) return

    Box(modifier) {
        if (nextIcon != null) {
            Icon(
                imageVector = nextIcon,
                contentDescription = null,
                tint = FoamTheme.accentColor,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer {
                        // Fades in to full opacity at the commit travel; the icon
                        // keeps re-centering while the resisted strip keeps growing.
                        val revealed = offsetPx().coerceAtLeast(0f)
                        alpha = (revealed / nextRevealPx().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        translationX = revealed / 2f - SwipeActionIconSize.toPx() / 2f
                    }
                    .size(SwipeActionIconSize)
            )
        }

        if (deleteIcon != null) {
            Icon(
                imageVector = deleteIcon,
                contentDescription = null,
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = -SwipeActionIconInset)
                    .graphicsLayer {
                        // Reveal follows the drag; the commit fade then takes
                        // over and runs down to 0 alongside the fly-out.
                        alpha = (-offsetPx() / deleteFadePx().coerceAtLeast(1f))
                            .coerceIn(0f, 1f) * deleteCommitFade()
                    }
                    .size(SwipeActionIconSize)
            )
        }
    }
}

/** Album-art (or placeholder) filling a grid cell's square artwork box. */
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
            contentDescription = stringResource(R.string.album_art),
            modifier = Modifier.fillMaxSize(),
            placeholderSize = 32.dp
        )
    } else {
        NoAlbumArtPlaceholder()
    }
}

/** Small rounded translucent chip overlaid on a grid cell's artwork corner. */
@Composable
private fun GridOverlayChip(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 4.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Fade + scale-in of the edit-mode grid drag chip when entering edit mode. */
private val GridDragHandleEnter =
    fadeIn(animationSpec = tween(durationMillis = 200)) +
            scaleIn(initialScale = 0.6f, animationSpec = tween(durationMillis = 200))

/** Fade + scale-out of the edit-mode grid drag chip when leaving edit mode. */
private val GridDragHandleExit =
    fadeOut(animationSpec = tween(durationMillis = 200)) +
            scaleOut(targetScale = 0.6f, animationSpec = tween(durationMillis = 200))