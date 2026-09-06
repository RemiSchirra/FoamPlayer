package com.foamplayer.app.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.PreviousAction
import com.foamplayer.app.ui.components.SeekBarProgressState
import com.foamplayer.app.ui.components.onSeekBarReleased
import com.foamplayer.app.ui.components.rememberSeekBarProgressState
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import com.foamplayer.app.ui.utils.pingPongMarquee
import kotlin.math.abs
import androidx.core.net.toUri

/** Shared layout constants so the header, pager and hero overlay always agree on geometry. */
private object PlayerDimens {
    val horizontalPagePadding = 24.dp
    val pageSpacing = 24.dp

    // Bottom control cluster in the subview (selector row + mini play button + seek bar).
    // The rise distances are purely cosmetic transforms (see subControlsProgress usage
    // below) -- they never change layout size, only where things are drawn.
    val selectorRowRise = 20.dp
    val playButtonRise = 28.dp
    val seekBarRise = 12.dp
}

/**
 * Calculates absolute completion (0 = closed/false, 1 = open/true)
 */
internal fun SeekableTransitionState<Boolean>.progress(): Float =
    if (currentState == targetState) {
        if (currentState) 1f else 0f
    } else {
        if (targetState) fraction else 1f - fraction
    }

internal const val TRANSITION_DURATION_MS = 400

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FullPlayerScreen(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionState: SeekableTransitionState<Boolean>,
    isAnimateSeekBarEnabled: Boolean = false
) {
    val state by viewModel.playbackState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val isImmersiveEnabled = settings.immersiveModeEnabled
    val track = state.currentTrack ?: return

    var subViewMode by remember { mutableStateOf(SubViewMode.LYRICS) }

    val upTransitionState = remember { SeekableTransitionState(false) }
    val upTransition = rememberTransition(upTransitionState, label = "UpTransition")
    // This is seekable, so it follows the finger while the user drags into/out of
    // Lyrics or Queue. Blur quickly on the way up; use a curve on the way back so
    // the sharp hero returns early instead of waiting for the final part of a drag.
    val rawSubViewProgress = upTransitionState.progress()
    val subViewBlurProgress = if (upTransitionState.targetState) {
        (rawSubViewProgress * 2.5f).coerceIn(0f, 1f)
    } else {
        rawSubViewProgress * rawSubViewProgress
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val scope = rememberCoroutineScope()

    val dragThresholdPx = screenHeightPx.coerceAtLeast(1f)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val gestureController = remember(playerTransitionState, dragThresholdPx) {
        PlayerGestureController(
            upTransitionState = upTransitionState,
            playerTransitionState = playerTransitionState,
            scope = scope,
            dragThresholdPx = dragThresholdPx,
            onDismiss = { currentOnDismiss.value() }
        )
    }

    BackHandler(enabled = upTransitionState.currentState || upTransitionState.targetState) {
        gestureController.collapseToMainPlayer()
    }

    // Isolated draggable modifier to prevent interference with scrollable lists below it.
    // Main content and the subview are mutually exclusive (AnimatedVisibility toggles
    // between them), so both call sites below share this one Modifier/DraggableState
    // instead of each allocating an identical copy.
    val playerDrag = Modifier.draggable(
        state = rememberDraggableState { delta -> gestureController.onDrag(delta) },
        orientation = Orientation.Vertical,
        onDragStopped = { v -> gestureController.onDragStopped(v) }
    )

    // Intercept over-scrolls when lists (queue/lyrics) are at their top boundary.
    val nestedScrollConnection = remember(gestureController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero

                val upState = gestureController.upTransitionState
                val isMidDrag = upState.currentState != upState.targetState

                // If the panel is partially dragged down, intercept all scroll to continue moving it
                if (isMidDrag && available.y != 0f) {
                    gestureController.onDrag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero

                // available.y > 0 means unconsumed downward scroll (list is at the top)
                if (available.y > 0f) {
                    gestureController.onDrag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val upState = gestureController.upTransitionState
                val isMidDrag = upState.currentState != upState.targetState

                // Upon finger lift mid-drag, commit the fling velocity to the gesture controller
                if (isMidDrag) {
                    gestureController.onDragStopped(available.y)
                    return Velocity(0f, available.y)
                }
                return Velocity.Zero
            }
        }
    }

    // True while a shared-element hand-off is actively animating (open/close/drag), on
    // either the outer mini<->full transition or the inner up (header/subview) one.
    // The art placeholder box paints an opaque surfaceVariant background behind the
    // AsyncImage; while the box itself is being resized/translated as part of the
    // shared-element animation that flat color is visible as a flash, so it's dropped
    // to transparent for the duration and restored once things settle.
    val isArtTransitionAnimating = playerTransitionState.currentState != playerTransitionState.targetState ||
            upTransitionState.currentState != upTransitionState.targetState
    val placeholderColor = if (isArtTransitionAnimating) Color.Transparent else FoamTheme.colors.surfaceVariant

    val pagerState = rememberPagerState(
        initialPage = state.queueIndex.coerceAtLeast(0),
        pageCount = { state.queue.size }
    )

    // Shared with PlaybackProgressSlider (renders it) and the Previous/Next handlers
    // (which trigger its settle animation). liveProgress is the same
    // position/duration * pager-active-fraction computation this screen already had;
    // it's read lazily every time `.progress` is accessed, not just once here.
    val seekBarProgress = rememberSeekBarProgressState(
        positionMsFlow = viewModel.positionMs,
        liveProgress = { livePositionMs ->
            val progress = if (state.durationMs > 0) livePositionMs.toFloat() / state.durationMs else 0f

            val activeFraction = if (isAnimateSeekBarEnabled) {
                val scrollPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
                val distanceFromCurrent = abs(scrollPosition - state.queueIndex.coerceAtLeast(0))
                (1f - distanceFromCurrent).coerceIn(0f, 1f)
            } else {
                1f
            }

            progress * activeFraction
        }
    )

    val currentQueueIndex by rememberUpdatedState(state.queueIndex)
    val currentQueueSize by rememberUpdatedState(state.queue.size)

    val context = LocalContext.current

    // Ensure colors for nearby tracks are available for smooth interpolation.
    // This asks the shared cache to fetch anything missing; it doesn't decode
    // on its own, so it never duplicates work the mini player's own pager
    // (which tracks the same queueIndex) already kicked off for the same art.
    LaunchedEffect(pagerState.currentPage, state.queue) {
        val indices = listOf(
            pagerState.currentPage - 1,
            pagerState.currentPage,
            pagerState.currentPage + 1
        )

        val pathsToKeep = indices.mapNotNull { i ->
            state.queue.getOrNull(i)?.albumArtPath
        }.toSet()

        pathsToKeep.forEach { path ->
            AlbumArtPaletteCache.ensure(scope = this, context = context, path = path)
        }
    }

    // Fires every frame the pager is mid-drag, by design -- the accent color should
    // track the swipe continuously, not jump at settle. See
    // AlbumArtPaletteCache.interpolatedColor for the shared calculation (this used to
    // be a local copy of the same index/lerp math that also lived in MiniPlayer).
    LaunchedEffect(pagerState, state.queue) {
        // Capture the queue locally so the snapshotFlow below only observes the
        // pager's page/offset and the palette cache. Reading `state.queue` directly
        // would pull the whole PlaybackState (a new object every 200ms position
        // tick) into the observed set, re-running this effect on every tick while
        // idle even though the queue itself is unchanged.
        val queue = state.queue
        snapshotFlow {
            AlbumArtPaletteCache.interpolatedColor(
                currentPage = pagerState.currentPage,
                currentPageOffsetFraction = pagerState.currentPageOffsetFraction,
                pageCount = queue.size
            ) { page -> queue.getOrNull(page)?.albumArtPath }
        }.collect { interpolatedColor ->
            viewModel.setDynamicAccentColor(interpolatedColor)
        }
    }

    // 3. Clean up and hand rendering authority back to MainActivity when collapsed
    DisposableEffect(Unit) {
        onDispose {
            viewModel.setDynamicAccentColor(null)
        }
    }

    // Lock the bar to 0 only once the finger actually releases -- tweened in animated
    // mode, snapped instantly otherwise -- well before the pager's own fling-to-settle
    // animation visually finishes. Does nothing if the swipe springs back to the same
    // track (targetPage == currentQueueIndex).
    //
    // Gating on `isDragged` going from true to false (rather than on `targetPage`
    // directly) matters: PagerState.targetPage already reflects its predicted
    // destination page *while the finger is still down* -- Compose updates it live
    // from drag distance/velocity, well before release -- so collecting it directly
    // fired the settle tween/snap mid-drag instead of on release.
    //
    // Watching the pair, not just `isDragged` alone, matters too: `isDragged` and
    // `targetPage` are separate snapshot values that don't necessarily update in the
    // same frame. On a fast swipe that's already almost all the way to the next
    // track, release can flip `isDragged` to false a frame before `targetPage`
    // catches up to the new page -- a plain `snapshotFlow { isDragged }` reads
    // `targetPage` at that exact (stale) instant and misses the commit entirely,
    // since it only re-fires on the next `isDragged` *change*, not on `targetPage`
    // catching up moments later. Keying the flow on both means a late `targetPage`
    // update still produces a fresh emission to react to.
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pagerState) {
        snapshotFlow { isDragged to pagerState.targetPage }
            .collect { (dragging, targetPage) ->
                if (!dragging && targetPage in 0 until currentQueueSize && targetPage != currentQueueIndex) {
                    seekBarProgress.onTrackChangeCommitted(animated = isAnimateSeekBarEnabled)
                }
            }
    }

    // Single source of truth for track changing upon scroll completion
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { settledPage ->
                if (settledPage in 0 until currentQueueSize && settledPage != currentQueueIndex) {
                    seekBarProgress.onTrackChangeApplied()
                    viewModel.skipToIndex(settledPage)
                }
            }
    }

    LaunchedEffect(currentQueueIndex) {
        if (currentQueueIndex in 0 until currentQueueSize && pagerState.currentPage != currentQueueIndex) {
            pagerState.animateScrollToPage(currentQueueIndex)
        }
    }

    val uiAlpha by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = { tween(durationMillis = 500, easing = EaseOutCubic) },
        label = "uiAlpha"
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 1f else 0f
    }

    val subControlsProgress = if (upTransitionState.targetState) {
        EaseOutCubic.transform(rawSubViewProgress.coerceIn(0f, 1f))
    } else {
        EaseInCubic.transform(rawSubViewProgress.coerceIn(0f, 1f))
    }

    with(sharedTransitionScope) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = uiAlpha }
                .background(FoamTheme.colors.background)
        ) {
            val indexA by remember(pagerState, state.queue.size) {
                derivedStateOf {
                    val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    absoluteOffset.toInt().coerceIn(0, (state.queue.size - 1).coerceAtLeast(0))
                }
            }

            val indexB by remember(pagerState, state.queue.size) {
                derivedStateOf {
                    val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    (absoluteOffset.toInt() + 1).coerceAtMost((state.queue.size - 1).coerceAtLeast(0))
                }
            }

            AlbumArtBackdrop(
                frontArtPath = state.queue.getOrNull(indexA)?.albumArtPath,
                // The back-art layer gets its own full-screen 20dp blur, purely so it
                // can crossfade in once the user actually starts swiping the pager --
                // it's invisible (alpha 0 via crossfadeFraction) until then. Skipping
                // it while the outer mini<->full hand-off is still animating avoids
                // decoding + blurring a second full-screen image at the exact moment
                // the shared-element animation needs the frame budget; it's cheap to
                // pick back up once the screen has settled and swiping becomes possible.
                backArtPath = if (playerTransitionState.currentState != playerTransitionState.targetState) {
                    null
                } else {
                    state.queue.getOrNull(indexB)?.albumArtPath
                },
                crossfadeFraction = {
                    val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                    (absoluteOffset - absoluteOffset.toInt()).coerceIn(0f, 1f)
                },
                immersive = isImmersiveEnabled,
                subViewProgress = subViewBlurProgress,
                dynamicBackgroundEnabled = settings.dynamicBackgroundEnabled,
                backgroundBlur = settings.backgroundBlur.dp,
                // No alpha modifier here: the enclosing Box above already applies
                // `.graphicsLayer { alpha = uiAlpha }` to this whole subtree -- adding
                // it again here was multiplying uiAlpha by itself, fading the backdrop
                // out non-linearly relative to the rest of the screen.
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.2f * subViewBlurProgress }
                    .background(Color.Black)
            )

            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    upTransition.AnimatedVisibility(
                        visible = { !it },
                        enter = fadeIn(tween(TRANSITION_DURATION_MS)) +
                                slideInVertically(tween(TRANSITION_DURATION_MS)) { h -> -h / 6 },
                        exit = fadeOut(tween(TRANSITION_DURATION_MS)) +
                                slideOutVertically(tween(TRANSITION_DURATION_MS)) { h -> -h / 6 },
                        modifier = Modifier.fillMaxSize().then(playerDrag)
                    ) {
                        MainPlayerContent(
                            track = track,
                            state = state,
                            pagerState = pagerState,
                            density = density,
                            configuration = configuration,
                            placeholderColor = placeholderColor,
                            immersive = isImmersiveEnabled,
                            outerVisibilityScope = animatedVisibilityScope,
                            headerVisibilityScope = this@AnimatedVisibility,
                            viewModel = viewModel,
                            playerTransitionState = playerTransitionState,
                            upTransitionState = upTransitionState,
                            seekBarProgress = seekBarProgress,
                            isAnimateSeekBarEnabled = isAnimateSeekBarEnabled
                        )
                    }

                    upTransition.AnimatedVisibility(
                        visible = { it },
                        enter = fadeIn(tween(TRANSITION_DURATION_MS)) +
                                slideInVertically(tween(TRANSITION_DURATION_MS)) { h -> h / 6 },
                        exit = fadeOut(tween(TRANSITION_DURATION_MS)) +
                                slideOutVertically(tween(TRANSITION_DURATION_MS)) { h -> h / 6 },
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(nestedScrollConnection)
                    ) {
                        SubViewContent(
                            track = track,
                            subViewMode = subViewMode,
                            viewModel = viewModel,
                            headerVisibilityScope = this@AnimatedVisibility,
                            modifier = playerDrag,
                            onCollapse = { gestureController.collapseToMainPlayer() },
                            upTransitionState = upTransitionState
                        )
                    }
                }

                // Controls for the subview
                Box(modifier = Modifier.fillMaxWidth()) {
                    SubViewSelectorRow(
                        selected = if (upTransitionState.targetState) subViewMode else null,
                        onSelect = { mode ->
                            if (upTransitionState.targetState) {
                                if (subViewMode == mode) {
                                    gestureController.collapseToMainPlayer()
                                } else {
                                    subViewMode = mode
                                }
                            } else {
                                subViewMode = mode
                                gestureController.expandToSubView()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 24.dp)
                            .graphicsLayer {
                                alpha = uiAlpha
                                translationY = -PlayerDimens.selectorRowRise.toPx() * subControlsProgress
                            }
                    )

                    MiniPlayPauseButton(
                        isPlaying = state.isPlaying,
                        progress = subControlsProgress,
                        onClick = { if (state.isPlaying) viewModel.pause() else viewModel.play() },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationY = -PlayerDimens.playButtonRise.toPx() * subControlsProgress
                            }
                    )
                }
            }

            // Mini seek bar, drawn as a true overlay on the outer screen Box (a sibling of
            // the Column above, not a child of it) so it never takes up Column layout space.
            // Previously this lived in a reserved-height Box at the bottom of the Column,
            // which permanently shrank the weighted content area above by that height --
            // shifting the selector row's resting position up even at rawSubViewProgress
            // == 0. Overlaying it here instead means the Column's layout, and therefore the
            // selector row's default position, is completely unaffected; only this slider's
            // own alpha/translationY move it in and out at the very bottom of the screen.
            PlaybackProgressSlider(
                durationMs = state.durationMs,
                seekBarProgress = seekBarProgress,
                isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                onSeek = { positionMs -> viewModel.seekTo(positionMs) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .graphicsLayer {
                        alpha = subControlsProgress
                        translationY = PlayerDimens.seekBarRise.toPx() * (1f - subControlsProgress)
                    }
            )
        }
    }
}
/** Layer 1 content: handle, hi-res badge, the track hero pager, playback controls, subview shortcuts. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.MainPlayerContent(
    track: TrackEntity,
    state: PlaybackState,
    pagerState: PagerState,
    density: Density,
    configuration: android.content.res.Configuration,
    placeholderColor: Color,
    immersive: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope,
    viewModel: PlayerViewModel,
    playerTransitionState: SeekableTransitionState<Boolean>,
    upTransitionState: SeekableTransitionState<Boolean>,
    seekBarProgress: SeekBarProgressState,
    isAnimateSeekBarEnabled: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        TrackHeroPager(
            track = track,
            queue = state.queue,
            queueIndex = state.queueIndex,
            pagerState = pagerState,
            density = density,
            configuration = configuration,
            placeholderColor = placeholderColor,
            immersive = immersive,
            outerVisibilityScope = outerVisibilityScope,
            headerVisibilityScope = headerVisibilityScope,
            playerTransitionState = playerTransitionState,
            upTransitionState = upTransitionState
        )

        Spacer(modifier = Modifier.weight(1f))

        PlaybackControls(
            track = track,
            isPlaying = state.isPlaying,
            durationMs = state.durationMs,
            outerVisibilityScope = outerVisibilityScope,
            seekBarProgress = seekBarProgress,
            isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
            onSeek = { positionMs -> viewModel.seekTo(positionMs) },
            onPrevious = {
                when (
                    seekBarProgress.onPreviousPressed(
                        animated = isAnimateSeekBarEnabled,
                        // Same reasoning as liveProgress above: state.currentPositionMs
                        // is now a coarse snapshot, not a live value. This needs the
                        // actual current position to decide restart-vs-skip correctly.
                        positionMs = viewModel.positionMs.value
                    )
                ) {
                    PreviousAction.RESTART_CURRENT -> viewModel.seekTo(0L)
                    PreviousAction.SKIP_TO_PREVIOUS -> viewModel.previous()
                }
            },
            onPlayPause = { if (state.isPlaying) viewModel.pause() else viewModel.play() },
            onNext = {
                seekBarProgress.onNextPressed(animated = isAnimateSeekBarEnabled)
                viewModel.next()
            }
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}

/**
 * Renders the album art + title/artist for the current queue, and drives the transition
 * between them and the mini header when swiping up.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.TrackHeroPager(
    track: TrackEntity,
    queue: List<TrackEntity>,
    queueIndex: Int,
    pagerState: PagerState,
    density: Density,
    configuration: android.content.res.Configuration,
    placeholderColor: Color,
    immersive: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope,
    playerTransitionState: SeekableTransitionState<Boolean>,
    upTransitionState: SeekableTransitionState<Boolean>
) {
    val anchorPage = queueIndex.coerceAtLeast(0)

    // Read the pager's *actual* measured page width/spacing instead of
    // recomputing them from LocalConfiguration + a hardcoded padding constant.
    // That manual calculation can drift from what the pager really laid out
    // (insets, edge-to-edge display, dp->px rounding, multi-window), so the
    // hero overlay below -- a duplicate of the anchor page's art, translated
    // to sit visually on top of it -- would end up a few pixels off from the
    // real page underneath. That drift is invisible near the center of a
    // swipe but grows with distance, which is why it only showed up
    // "sometimes": far enough into a swipe, the hero's copy of the old
    // track's art visibly separates from the incoming page's real image.
    val pageWidthPx = pagerState.layoutInfo.pageSize.toFloat()
    val pageSpacingPx = pagerState.layoutInfo.pageSpacing.toFloat()

    // Whether the hero overlay needs to be composed at all. This is read
    // directly (not deferred) because it's a real composition decision --
    // but wrapped in derivedStateOf so it only triggers recomposition when
    // the hero crosses fully-covered <-> partially-visible, not on every
    // fractional pixel of the drag. The continuous alpha/position values
    // themselves are read inside graphicsLayer blocks below, which redraw
    // on the layout/draw phase without recomposing this function.
    val showHero by remember(anchorPage, immersive) {
        derivedStateOf {
            if (immersive) {
                false
            } else {
                val offset = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                abs(offset) < 1f
            }
        }
    }

    // Track the outer screen transition (MiniPlayer <-> FullPlayer)
    val outerRadius = lerp(
        start = FoamTheme.shapes.miniPlayerRounding,
        stop = FoamTheme.shapes.fullPlayerRounding,
        fraction = playerTransitionState.progress()
    )

    val headerRadius = lerp(
        start = FoamTheme.shapes.fullPlayerRounding,
        stop = FoamTheme.shapes.miniPlayerRounding,
        fraction = upTransitionState.progress()
    )

    val animatedCornerRadius = min(outerRadius, headerRadius)

    Box(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = PlayerDimens.horizontalPagePadding),
            pageSpacing = PlayerDimens.pageSpacing,
            beyondViewportPageCount = 0,
            key = { page -> queue.getOrNull(page)?.id ?: page }
        ) { page ->
            val pageTrack = queue.getOrNull(page) ?: return@HorizontalPager
            val isAnchorPage = page == anchorPage
            // Classic mode hands the current title to the hero overlay. Immersive
            // mode has no foreground hero card, so the pager owns the title too.
            // Read inside graphicsLayer (deferred) rather than as a captured
            // Float, so redraws don't force this Column to recompose every frame.
            fun anchorPageAlpha(): Float =
                if (immersive || !isAnchorPage) {
                    1f
                } else {
                    val offset = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                    abs(offset).coerceIn(0f, 1f)
                }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (immersive) {
                    // Reserve the classic artwork footprint so the title and
                    // controls do not move, but do not compose another art/card.
                    Spacer(modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(FoamTheme.shapes.fullPlayerRounding))
                            .background(placeholderColor)
                            .graphicsLayer { alpha = anchorPageAlpha() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (pageTrack.albumArtPath != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(pageTrack.albumArtPath)
                                    .size(AlbumArtRequestSize)
                                    .crossfade(false)
                                    .build(),
                                contentDescription = "Album Art",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Column(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = anchorPageAlpha() }) {
                    BasicText(
                        text = pageTrack.title,
                        style = FoamTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (immersive && pageTrack.id == track.id) {
                            Modifier
                                .sharedBounds(
                                    rememberSharedContentState(key = "track_title_${track.id}"),
                                    animatedVisibilityScope = outerVisibilityScope
                                )
                                .sharedBounds(
                                    rememberSharedContentState(key = "track_title_header_${track.id}"),
                                    animatedVisibilityScope = headerVisibilityScope
                                )
                                .pingPongMarquee()
                        } else {
                            Modifier
                        }
                    )
                    BasicText(
                        text = "${pageTrack.artist} — ${pageTrack.album}",
                        style = FoamTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (immersive && pageTrack.id == track.id) {
                            Modifier
                                .sharedBounds(
                                    rememberSharedContentState(key = "track_artist_${track.id}"),
                                    animatedVisibilityScope = outerVisibilityScope
                                )
                                .sharedBounds(
                                    rememberSharedContentState(key = "track_artist_header_${track.id}"),
                                    animatedVisibilityScope = headerVisibilityScope
                                )
                                .pingPongMarquee()
                        } else {
                            Modifier
                        }
                    )
                }
            }
        }

        // Hero overlay
        // In immersive mode the artwork is rendered by AlbumArtBackdrop; rendering
        // this card as well creates a duplicate (and can show as a black image).
        if (showHero) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PlayerDimens.horizontalPagePadding)
                    .graphicsLayer {
                        // Deferred reads: this layer redraws every frame of the
                        // drag without recomposing the Column (and its marquee
                        // text / sharedElement registrations) around it.
                        val offset = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                        translationX = -offset * (pageWidthPx + pageSpacingPx)
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .sharedElement(
                            rememberSharedContentState(key = "album_art_${track.id}"),
                            animatedVisibilityScope = outerVisibilityScope
                        )
                        .sharedElement(
                            rememberSharedContentState(key = "album_art_header_${track.id}"),
                            animatedVisibilityScope = headerVisibilityScope
                        )
                        .clip(RoundedCornerShape(animatedCornerRadius))
                        .background(placeholderColor)
                        .graphicsLayer { alpha = 1f },
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
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    BasicText(
                        text = track.title,
                        style = FoamTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .sharedBounds(
                                rememberSharedContentState(key = "track_title_${track.id}"),
                                animatedVisibilityScope = outerVisibilityScope
                            )
                            .sharedBounds(
                                rememberSharedContentState(key = "track_title_header_${track.id}"),
                                animatedVisibilityScope = headerVisibilityScope
                            )
                            .pingPongMarquee()
                    )
                    BasicText(
                        text = "${track.artist} — ${track.album}",
                        style = FoamTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .sharedBounds(
                                rememberSharedContentState(key = "track_artist_${track.id}"),
                                animatedVisibilityScope = outerVisibilityScope
                            )
                            .sharedBounds(
                                rememberSharedContentState(key = "track_artist_header_${track.id}"),
                                animatedVisibilityScope = headerVisibilityScope
                            )
                            .pingPongMarquee()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.PlaybackControls(
    track: TrackEntity,
    isPlaying: Boolean,
    durationMs: Long,
    outerVisibilityScope: AnimatedVisibilityScope,
    seekBarProgress: SeekBarProgressState,
    isAnimateSeekBarEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // seekBarProgress.progress tracks pagerState.currentPageOffsetFraction under
        // the hood, which changes on essentially every frame of a horizontal swipe.
        // Reading it here, in PlaybackControls' own body, would force this whole
        // composable (including the Prev/Play/Next Row and its shared-element bounds)
        // to recompose on every drag pixel. Reading it inside a dedicated leaf
        // composable instead confines that recomposition to just the slider.
        PlaybackProgressSlider(
            durationMs = durationMs,
            seekBarProgress = seekBarProgress,
            isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
            onSeek = onSeek
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FoamTheme.icons.current.previous,
                contentDescription = "Previous",
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(32.dp).clickable(onClick = onPrevious)
            )
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .sharedBounds(
                        rememberSharedContentState(key = "play_button_${track.id}"),
                        animatedVisibilityScope = outerVisibilityScope
                    )
                    .clip(CircleShape)
                    .background(FoamTheme.colors.primaryAccent)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) FoamTheme.icons.current.pause else FoamTheme.icons.current.play,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = FoamTheme.colors.background,
                    modifier = Modifier.size(32.dp)
                )
            }
            Icon(
                imageVector = FoamTheme.icons.current.next,
                contentDescription = "Next",
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(32.dp).clickable(onClick = onNext)
            )
        }
    }
}

/**
 * Isolated on purpose: this is the one piece of PlaybackControls that legitimately needs
 * to update every frame of a horizontal swipe (fading the slider's progress out as its
 * page scrolls away from center). Keeping it in its own composable means that per-frame
 * recomposition stops here instead of propagating up into PlaybackControls' Row of
 * buttons/shared-element bounds.
 */
@Composable
private fun PlaybackProgressSlider(
    durationMs: Long,
    seekBarProgress: SeekBarProgressState,
    isAnimateSeekBarEnabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    FoamSlider(
        value = seekBarProgress.progress,
        onValueChange = { new ->
            // A fresh manual drag always wins over any settle tween in flight.
            seekBarProgress.onDragging()
            onSeek((new * durationMs).toLong())
        },
        modifier = modifier.onSeekBarReleased {
            seekBarProgress.onSeekDragFinished(
                animated = isAnimateSeekBarEnabled,
                releasedAt = seekBarProgress.progress
            )
        }
    )
}

@Composable
private fun MiniPlayPauseButton(
    isPlaying: Boolean,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(50.dp)
            .graphicsLayer { alpha = progress }
            .clip(CircleShape)
            .background(FoamTheme.colors.primaryAccent)
            .clickable(enabled = progress > 0.01f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) FoamTheme.icons.current.pause else FoamTheme.icons.current.play,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = FoamTheme.colors.background,
            modifier = Modifier.size(28.dp)
        )
    }
}