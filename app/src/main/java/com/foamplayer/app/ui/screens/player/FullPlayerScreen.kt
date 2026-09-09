package com.foamplayer.app.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.times
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
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

    // How far a swipe needs to travel to fully open/close the Lyrics/Queue subview.
    // Kept as a fixed distance (not a fraction of screen height) so it stays a quick,
    // tactile flick on every device instead of scaling up on taller screens. 260dp
    // completed too fast (felt twitchy); 420dp gives the finger more room to travel
    // while still being far short of a full-screen swipe.
    val subViewSwipeThreshold = 420.dp
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

internal const val TRANSITION_DURATION_MS = 300

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
    val upDragThresholdPx = with(density) { PlayerDimens.subViewSwipeThreshold.toPx() }.coerceAtLeast(1f)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val gestureController = remember(playerTransitionState, dragThresholdPx, upDragThresholdPx) {
        PlayerGestureController(
            upTransitionState = upTransitionState,
            playerTransitionState = playerTransitionState,
            scope = scope,
            dragThresholdPx = dragThresholdPx,
            upDragThresholdPx = upDragThresholdPx,
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

    // Plain vals, not rememberUpdatedState: FullPlayerScreen already recomposes whenever
    // `state` changes (it's read at the top of this function), so these are always
    // current for anything read directly in this body -- keys, child-composable params,
    // inline checks. Wrapping them in rememberUpdatedState here (as this used to do) and
    // reading that via `by` in the body actually made things worse: rememberUpdatedState
    // only updates its backing state via a SideEffect that runs *after* composition, so
    // on the pass where `state` first changes, the `by` read here still returned the
    // *previous* queueIndex/size -- and then the SideEffect's post-composition write
    // immediately invalidated this same top-level scope again to pick up the real value,
    // turning one track-change into two full FullPlayerScreen recompositions instead of
    // one. (rememberUpdatedState is still the right tool where a value only needs to be
    // read *inside* a long-lived effect/callback without restarting it -- see the
    // settledPage effect below, and TrackChangeCommitEffect -- just not read directly
    // into this composable's own body.)
    val queueIndex = state.queueIndex
    val queueSize = state.queue.size

    // Whether MainPlayerContent (and therefore this pager) is actually laid out right
    // now. Mirrors the `upTransition.AnimatedVisibility(visible = { !it })` condition
    // below that composes it: both branches stay composed during a drag/transition
    // (see PlayerGestureController's class doc), so the pager is trustworthy any time
    // we haven't fully settled into the open subview. Read as a function (not a
    // `val`/derivedStateOf) since it's only ever consulted inside the LaunchedEffects
    // above at the moment of each emission, not something this composable needs to
    // recompose on itself.
    fun isMainPagerVisible() = !upTransitionState.currentState

    val context = LocalContext.current

    // Ensure colors for nearby tracks are available for smooth interpolation.
    // This asks the shared cache to fetch anything missing; it doesn't decode
    // on its own, so it never duplicates work the mini player's own pager
    // (which tracks the same queueIndex) already kicked off for the same art.
    LaunchedEffect(pagerState, state.queue) {
        val queue = state.queue
        snapshotFlow { pagerState.currentPage }
            .collect { currentPage ->
                val indices = listOf(currentPage - 1, currentPage, currentPage + 1)

                val pathsToKeep = indices.mapNotNull { i ->
                    queue.getOrNull(i)?.albumArtPath
                }.toSet()

                pathsToKeep.forEach { path ->
                    AlbumArtPaletteCache.ensure(scope = this, context = context, path = path)
                }
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

    // Locks the bar to 0 only once the finger actually releases -- tweened in animated
    // mode, snapped instantly otherwise -- well before the pager's own fling-to-settle
    // animation visually finishes. Does nothing if the swipe springs back to the same
    // track (targetPage == queueIndex). See TrackChangeCommitEffect's own doc
    // comment for why this is a separate composable rather than an inline LaunchedEffect.
    TrackChangeCommitEffect(
        pagerState = pagerState,
        currentQueueIndex = queueIndex,
        currentQueueSize = queueSize,
        isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
        seekBarProgress = seekBarProgress
    )

    // Single source of truth for track changing upon scroll completion.
    //
    // Guarded on isMainPagerVisible: this pager (and these effects) keep running even
    // while the Lyrics/Queue subview fully covers it and it isn't laid out. An
    // animateScrollToPage issued while hidden can resolve to an unexpected page once
    // the pager is measured again, and without this guard that stray settle would
    // land here and silently skip playback to the wrong track sometime after the
    // subview reopens the main content -- exactly the flakiness of "sometimes it
    // plays the tapped track, sometimes it switches to another one" a moment later.
    val latestQueueIndex = rememberUpdatedState(queueIndex)
    val latestQueueSize = rememberUpdatedState(queueSize)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { settledPage ->
                if (isMainPagerVisible() &&
                    settledPage in 0 until latestQueueSize.value &&
                    settledPage != latestQueueIndex.value
                ) {
                    seekBarProgress.onTrackChangeApplied()
                    viewModel.skipToIndex(settledPage)
                }
            }
    }

    // Backs up the backdrop's crossfade when the queue index changes without a live pager
    // drag driving it -- e.g. tapping a track in the Queue subview, which snaps the hidden
    // pager straight to the new page below instead of animating it. Declared, and therefore
    // launched, before that snap so it reads pagerState.currentPage synchronously while it
    // still holds the *outgoing* track's page, before the sibling effect below moves it.
    // While active this takes over AlbumArtBackdrop's frontArtPath/backArtPath/
    // crossfadeFraction in place of the raw pager-derived values a few lines down, which
    // would otherwise jump to the new art in the same single frame the pager snaps.
    val programmaticBackdropCrossfade = remember { Animatable(1f) }
    var programmaticBackdropFromPath by remember { mutableStateOf<String?>(null) }
    var programmaticBackdropToPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(queueIndex) {
        if (!isMainPagerVisible() &&
            queueIndex in 0 until queueSize &&
            pagerState.currentPage != queueIndex
        ) {
            val fromPath = state.queue.getOrNull(pagerState.currentPage)?.albumArtPath
            val toPath = state.queue.getOrNull(queueIndex)?.albumArtPath
            if (fromPath != toPath) {
                programmaticBackdropFromPath = fromPath
                programmaticBackdropToPath = toPath
                programmaticBackdropCrossfade.snapTo(0f)
                programmaticBackdropCrossfade.animateTo(1f, tween(TRANSITION_DURATION_MS))
            }
        }
    }

    LaunchedEffect(queueIndex) {
        if (queueIndex in 0 until queueSize &&
            pagerState.currentPage != queueIndex &&
            !pagerState.isScrollInProgress
        ) {
            // Only play the animation if we're not in the subview
            if (isMainPagerVisible()) {
                pagerState.animateScrollToPage(
                    page = queueIndex,
                    animationSpec = tween(TRANSITION_DURATION_MS)
                )
            } else {
                pagerState.scrollToPage(queueIndex)
            }
        }
    }

    val uiAlpha by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = { tween(durationMillis = 500, easing = EaseOutCubic) },
        label = "uiAlpha"
    ) { enterExitState ->
        if (enterExitState == EnterExitState.Visible) 1f else 0f
    }

    // Drives the bottom control cluster (selector row rise, mini play button, seek bar).
    // Direction-aware on purpose: opening front-loads with EaseOutCubic so the reveal
    // feels done well before the drag/settle (rawSubViewProgress) actually reaches 1,
    // while closing uses EaseInCubic instead of just running that same curve backwards --
    // it starts the hide slowly and picks up speed toward the end.
    // Memoized against upTransitionState, whose instance never changes across
    // FullPlayerScreen's lifetime -- so this same () -> Float lambda is handed to
    // SubViewSelectorRow and MiniPlayPauseButton on every recomposition instead of a
    // freshly-allocated closure. That lets Compose skip those calls outright during a
    // drag; only the graphicsLayer blocks that actually invoke the lambda redraw per
    // frame. (Previously this was a plain Float val computed directly from
    // upTransitionState.progress(), which forced a fresh closure -- and therefore a
    // forced recompose -- on every one of those child composables every drag frame.)
    val subControlsProgress = remember(upTransitionState) {
        {
            val raw = upTransitionState.progress().coerceIn(0f, 1f)
            if (upTransitionState.targetState) EaseOutCubic.transform(raw) else EaseInCubic.transform(raw)
        }
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

            // True for the duration of the tween kicked off above. While active, it fully
            // owns the backdrop's crossfade inputs below instead of the raw pager-derived
            // ones, since the pager itself has already snapped straight to its destination
            // page by this point and no longer has a useful in-between state to read.
            val isProgrammaticCrossfadeActive = programmaticBackdropToPath != null &&
                    programmaticBackdropCrossfade.value < 1f

            AlbumArtBackdrop(
                frontArtPath = if (isProgrammaticCrossfadeActive) {
                    programmaticBackdropFromPath
                } else {
                    state.queue.getOrNull(indexA)?.albumArtPath
                },
                // The back-art layer gets its own full-screen 20dp blur, purely so it
                // can crossfade in once the user actually starts swiping the pager --
                // it's invisible (alpha 0 via crossfadeFraction) until then. Skipping
                // it while the outer mini<->full hand-off is still animating avoids
                // decoding + blurring a second full-screen image at the exact moment
                // the shared-element animation needs the frame budget; it's cheap to
                // pick back up once the screen has settled and swiping becomes possible.
                backArtPath = if (isProgrammaticCrossfadeActive) {
                    programmaticBackdropToPath
                } else if (playerTransitionState.currentState != playerTransitionState.targetState) {
                    null
                } else {
                    state.queue.getOrNull(indexB)?.albumArtPath
                },
                crossfadeFraction = {
                    if (isProgrammaticCrossfadeActive) {
                        programmaticBackdropCrossfade.value
                    } else {
                        val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        (absoluteOffset - absoluteOffset.toInt()).coerceIn(0f, 1f)
                    }
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
                            screenWidthDp = configuration.screenWidthDp.dp,
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

                // Bottom control cluster for the subview. The mini play button only makes
                // sense once the big PlaybackControls (in MainPlayerContent, above) have
                // scrolled out of view, so it fades in/out with rawSubViewProgress -- the
                // same 0..1 fraction that drives the up-transition drag. It's drawn as a
                // graphicsLayer-only overlay in the same Box as the selector row (not
                // inside it), so its reveal never resizes anything: the row's rise is its
                // own independent translationY, not a reaction to a new sibling.
                Box(modifier = Modifier.fillMaxWidth()) {
                    // uiAlpha itself is a `by` State read (only re-fires during the ~500ms
                    // enter/exit fade, not per drag frame), but wrapping it in
                    // rememberUpdatedState lets the graphicsLayer blocks below read its
                    // latest value without the surrounding Modifier having to be rebuilt
                    // -- which is what would otherwise force these modifiers (and the
                    // remember calls below) to be reallocated on every recomposition.
                    val currentUiAlpha = rememberUpdatedState(uiAlpha)

                    // Built once (keyed only on stable instances) instead of inline in the
                    // call below. An inline `Modifier....graphicsLayer { ... }` literal is
                    // reallocated every recomposition regardless of whether its captures
                    // changed, and a changed Modifier parameter is enough on its own to
                    // stop SubViewSelectorRow from being skipped -- reintroducing the
                    // every-frame recompose even after `progress` was made stable.
                    val selectorRowModifier = remember(subControlsProgress, currentUiAlpha) {
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 24.dp)
                            .graphicsLayer {
                                alpha = currentUiAlpha.value
                                translationY = -PlayerDimens.selectorRowRise.toPx() * subControlsProgress()
                            }
                    }

                    SubViewSelectorRow(
                        subViewMode = subViewMode,
                        progress = subControlsProgress,
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
                        modifier = selectorRowModifier
                    )

                    // Centered between the (now icon-only) Lyrics and Queue buttons. Sits
                    // in the same Box as the row above (not inside it), so
                    // appearing/disappearing can't push the row's layout -- only its own
                    // translationY follows subControlsProgress, on its own path (a bigger
                    // rise than the row). Previous/Next flank the mini play button exactly
                    // like the main player's transport row; their callbacks reuse the same
                    // skip logic (including the seek-bar restart-vs-skip handling for
                    // Previous) so both clusters behave identically.
                    val transportClusterModifier = remember(subControlsProgress) {
                        Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationY = -PlayerDimens.playButtonRise.toPx() * subControlsProgress()
                            }
                    }

                    Row(
                        modifier = transportClusterModifier,
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniTransportButton(
                            icon = FoamTheme.icons.current.previous,
                            contentDescription = stringResource(R.string.previous),
                            progress = subControlsProgress,
                            onClick = {
                                when (
                                    seekBarProgress.onPreviousPressed(
                                        animated = isAnimateSeekBarEnabled,
                                        positionMs = viewModel.positionMs.value
                                    )
                                ) {
                                    PreviousAction.RESTART_CURRENT -> viewModel.seekTo(0L)
                                    PreviousAction.SKIP_TO_PREVIOUS -> viewModel.previous()
                                }
                            }
                        )

                        MiniPlayPauseButton(
                            isPlaying = state.isPlaying,
                            progress = subControlsProgress,
                            onClick = { if (state.isPlaying) viewModel.pause() else viewModel.play() }
                        )

                        MiniTransportButton(
                            icon = FoamTheme.icons.current.next,
                            contentDescription = stringResource(R.string.next),
                            progress = subControlsProgress,
                            onClick = {
                                seekBarProgress.onNextPressed(animated = isAnimateSeekBarEnabled)
                                viewModel.next()
                            }
                        )
                    }
                }
            }

            if (rawSubViewProgress > 0f) {
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
                            alpha = subControlsProgress()
                            translationY = PlayerDimens.seekBarRise.toPx() * (1f - subControlsProgress())
                        }
                )
            }
        }
    }
}
/**
 * Isolated on purpose: fires `onTrackChangeCommitted` the instant a swipe is released
 * past the halfway point, so the seek bar can start animating toward the new track's
 * position immediately, rather than waiting for the pager's fling-to-settle animation
 * to fully finish.
 *
 * `isDragged` has to be read with a plain `by` delegate for `snapshotFlow` below to
 * observe it, and that read has to live *somewhere's* recompose scope. Declaring it
 * directly in FullPlayerScreen's own body put that scope at the top of the whole
 * screen, so every drag start/stop -- i.e. exactly the moment a swipe is released to
 * commit -- forced the entire FullPlayerScreen (header, pager, controls, subview, all
 * of it) to recompose, even though isDragged is only ever consulted right here. Pulling
 * it into this tiny, UI-less composable confines that recomposition to just this
 * function instead.
 *
 * Gating on `isDragged` going from true to false (rather than on `targetPage`
 * directly) matters: PagerState.targetPage already reflects its predicted
 * destination page *while the finger is still down* -- Compose updates it live
 * from drag distance/velocity, well before release -- so collecting it directly
 * fired the settle tween/snap mid-drag instead of on release.
 *
 * Watching the pair, not just `isDragged` alone, matters too: `isDragged` and
 * `targetPage` are separate snapshot values that don't necessarily update in the
 * same frame. On a fast swipe that's already almost all the way to the next
 * track, release can flip `isDragged` to false a frame before `targetPage`
 * catches up to the new page -- a plain `snapshotFlow { isDragged }` reads
 * `targetPage` at that exact (stale) instant and misses the commit entirely,
 * since it only re-fires on the next `isDragged` *change*, not on `targetPage`
 * catching up moments later. Keying the flow on both means a late `targetPage`
 * update still produces a fresh emission to react to.
 */
@Composable
private fun TrackChangeCommitEffect(
    pagerState: PagerState,
    currentQueueIndex: Int,
    currentQueueSize: Int,
    isAnimateSeekBarEnabled: Boolean,
    seekBarProgress: SeekBarProgressState
) {
    // rememberUpdatedState here (rather than keying the LaunchedEffect on these values)
    // keeps the same single, long-lived collector alive across recompositions instead
    // of tearing it down and resubscribing to the interaction source every time the
    // queue index changes -- matching the pattern the original effect relied on.
    val latestQueueIndex by rememberUpdatedState(currentQueueIndex)
    val latestQueueSize by rememberUpdatedState(currentQueueSize)
    val latestIsAnimateSeekBarEnabled by rememberUpdatedState(isAnimateSeekBarEnabled)

    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(pagerState) {
        snapshotFlow { isDragged to pagerState.targetPage }
            .collect { (dragging, targetPage) ->
                if (!dragging &&
                    targetPage in 0 until latestQueueSize &&
                    targetPage != latestQueueIndex
                ) {
                    seekBarProgress.onTrackChangeCommitted(animated = latestIsAnimateSeekBarEnabled)
                }
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
    // A stable Dp instead of the raw platform Configuration object: Configuration
    // isn't a type the Compose compiler can prove stable, so passing it directly
    // made this whole composable (and TrackHeroPager below it) unskippable --
    // any unrelated parent recompose forced a full re-run instead of being able
    // to bail out when nothing it actually reads had changed.
    screenWidthDp: Dp,
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
            screenWidthDp = screenWidthDp,
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
    screenWidthDp: Dp,
    placeholderColor: Color,
    immersive: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope,
    playerTransitionState: SeekableTransitionState<Boolean>,
    upTransitionState: SeekableTransitionState<Boolean>
) {
    val anchorPage = queueIndex.coerceAtLeast(0)

    // NOTE: pagerState.layoutInfo.pageSize/pageSpacing themselves used to be read into
    // plain vals here and only *used* inside the deferred graphicsLayer block below --
    // but pagerState.layoutInfo is itself a snapshot-observable object that gets
    // recomputed on every layout pass of a horizontal drag (its visiblePagesInfo moves
    // continuously), even on frames where pageSize/pageSpacing haven't actually
    // changed. Reading `.layoutInfo` directly at this level subscribed TrackHeroPager's
    // own recompose scope to every one of those layout passes -- i.e. every frame of
    // swiping between tracks -- which is exactly why the pager kept recomposing every
    // frame even after the corner-radius fix. It's now read fresh inside the
    // graphicsLayer block itself, alongside the other deferred pager reads there.

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

    val miniImageSize = 44.dp
    val fullImageSize = screenWidthDp - (PlayerDimens.horizontalPagePadding * 2)

    val miniRadius = (miniImageSize / 2) * FoamTheme.shapes.miniPlayerRounding
    val fullRadius = (fullImageSize / 2) * FoamTheme.shapes.fullPlayerRounding

    // Deferred: a plain function instead of a `val`, read only inside the Hero
    // overlay's graphicsLayer block below (draw phase) rather than here at
    // composition time. playerTransitionState.progress() changes every frame of the
    // outer MiniPlayer<->FullPlayer hand-off, and upTransitionState.progress() changes
    // every frame the user drags into/out of Lyrics/Queue -- reading either directly
    // into a `val` here would invalidate TrackHeroPager's own recompose scope on every
    // one of those frames, which is exactly the "hero pager recomposes every frame"
    // symptom. Calling this from inside graphicsLayer instead redraws the layer
    // without recomposing anything.
    fun animatedCornerRadiusPx(): Float = with(density) {
        val outerRadius = lerp(start = miniRadius, stop = fullRadius, fraction = playerTransitionState.progress())
        val headerRadius = lerp(start = fullRadius, stop = miniRadius, fraction = upTransitionState.progress())
        min(outerRadius, headerRadius).toPx()
    }

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
                            .clip(RoundedCornerShape(fullRadius))
                            .background(placeholderColor)
                            .graphicsLayer { alpha = anchorPageAlpha() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (pageTrack.albumArtPath != null) {
                            val context = LocalContext.current
                            val pageArtRequest = remember(pageTrack.albumArtPath) {
                                ImageRequest.Builder(context)
                                    .data(EmbeddedAlbumArt(pageTrack.albumArtPath.toUri()))
                                    .size(AlbumArtRequestSize)
                                    .crossfade(false)
                                    .build()
                            }
                            AsyncImage(
                                model = pageArtRequest,
                                contentDescription = stringResource(R.string.album_art),
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
                        text = stringResource(R.string.artist_album_separator, pageTrack.artist, pageTrack.album),
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
                        //
                        // pageWidthPx/pageSpacingPx come from the pager's *actual*
                        // measured layout instead of being recomputed from
                        // LocalConfiguration + a hardcoded padding constant. That
                        // manual calculation can drift from what the pager really
                        // laid out (insets, edge-to-edge display, dp->px rounding,
                        // multi-window), so this hero overlay -- a duplicate of the
                        // anchor page's art, translated to sit visually on top of
                        // it -- would end up a few pixels off from the real page
                        // underneath. That drift is invisible near the center of a
                        // swipe but grows with distance, which is why it only
                        // showed up "sometimes": far enough into a swipe, the
                        // hero's copy of the old track's art visibly separates
                        // from the incoming page's real image.
                        val offset = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
                        val pageWidthPx = pagerState.layoutInfo.pageSize.toFloat()
                        val pageSpacingPx = pagerState.layoutInfo.pageSpacing.toFloat()
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
                        .graphicsLayer {
                            shape = RoundedCornerShape(animatedCornerRadiusPx())
                            clip = true
                            alpha = 1f
                        }
                        .background(placeholderColor),
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
                            contentDescription = stringResource(R.string.album_art),
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
                        text = stringResource(R.string.artist_album_separator, track.artist, track.album),
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

/**
 * Isolated on purpose: FoamTheme.accentColor changes every frame of a horizontal
 * swipe (see viewModel.setDynamicAccentColor in the LaunchedEffect above). Reading
 * it directly inside this Box, rather than inline in PlaybackControls' body, keeps
 * that per-frame recomposition confined to just the play button instead of also
 * dragging the Prev/Next icons and shared-element bounds along with it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.PlayPauseButton(
    track: TrackEntity,
    isPlaying: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(64.dp)
            .sharedBounds(
                rememberSharedContentState(key = "play_button_${track.id}"),
                animatedVisibilityScope = outerVisibilityScope
            )
            .clip(CircleShape)
            .background(FoamTheme.accentColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) FoamTheme.icons.current.pause else FoamTheme.icons.current.play,
            contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
            tint = FoamTheme.colors.background,
            modifier = Modifier.size(32.dp)
        )
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
                contentDescription = stringResource(R.string.previous),
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(32.dp).clickable(onClick = onPrevious)
            )
            PlayPauseButton(
                track = track,
                isPlaying = isPlaying,
                outerVisibilityScope = outerVisibilityScope,
                onClick = onPlayPause
            )
            Icon(
                imageVector = FoamTheme.icons.current.next,
                contentDescription = stringResource(R.string.next),
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
private fun MiniPlayPauseButton(
    isPlaying: Boolean,
    progress: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEnabled by remember(progress) { derivedStateOf { progress() > 0.01f } }

    Box(
        modifier = modifier
            .size(50.dp)
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
            modifier = Modifier.size(28.dp)
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
private fun MiniTransportButton(
    icon: ImageVector,
    contentDescription: String,
    progress: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEnabled by remember(progress) { derivedStateOf { progress() > 0.01f } }

    Box(
        modifier = modifier
            .size(44.dp)
            .graphicsLayer { alpha = progress() }
            .clip(CircleShape)
            .clickable(enabled = isEnabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(26.dp)
        )
    }
}