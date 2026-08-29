package com.foamplayer.app.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.SeekableTransitionState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.extractPaletteColor
import com.foamplayer.app.ui.utils.pingPongMarquee
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Shared layout constants so the header, pager and hero overlay always agree on geometry. */
private object PlayerDimens {
    val horizontalPagePadding = 24.dp
    val pageSpacing = 24.dp
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

private const val immersive = true

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FullPlayerScreen(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionState: SeekableTransitionState<Boolean>
) {
    val state by viewModel.playbackState.collectAsState()
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
    val mainPlayerDrag = Modifier.draggable(
        state = rememberDraggableState { delta -> gestureController.onDrag(delta) },
        orientation = Orientation.Vertical,
        onDragStopped = { v -> gestureController.onDragStopped(v) }
    )
    val headerDrag = Modifier.draggable(
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

    val currentQueueIndex by rememberUpdatedState(state.queueIndex)
    val currentQueueSize by rememberUpdatedState(state.queue.size)

    val context = LocalContext.current
    val colorCache = remember { mutableStateMapOf<String, Color>() }

    LaunchedEffect(pagerState.currentPage, state.queue) {
        val indices = listOf(
            pagerState.currentPage - 1,
            pagerState.currentPage,
            pagerState.currentPage + 1
        )

        val pathsToKeep = indices.mapNotNull { i ->
            state.queue.getOrNull(i)?.albumArtPath
        }.toSet()

        // Purge scrolled-past tracks from memory
        colorCache.keys.retainAll(pathsToKeep)

        // Fetch newly visible paths
        pathsToKeep.forEach { path ->
            if (!colorCache.containsKey(path)) {
                launch {
                    val color = extractPaletteColor(context, path)
                    if (color != null) {
                        colorCache[path] = color
                    }
                }
            }
        }
    }

    LaunchedEffect(pagerState, state.queue, colorCache) {
        snapshotFlow {
            val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val indexA = absoluteOffset.toInt().coerceIn(0, (state.queue.size - 1).coerceAtLeast(0))
            val indexB = (indexA + 1).coerceAtMost((state.queue.size - 1).coerceAtLeast(0))
            val fraction = (absoluteOffset - indexA).coerceIn(0f, 1f)

            val pathA = state.queue.getOrNull(indexA)?.albumArtPath
            val pathB = state.queue.getOrNull(indexB)?.albumArtPath

            val cA = colorCache[pathA]
            val cB = colorCache[pathB]

            if (cA != null) {
                lerp(cA, cB ?: cA, fraction)
            } else null
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

    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()

    // Trigger track change exactly when the user releases their finger
    LaunchedEffect(isDragged) {
        if (!isDragged) {
            val targetPage = pagerState.targetPage
            if (targetPage in 0 until currentQueueSize && targetPage != currentQueueIndex) {
                viewModel.skipToIndex(targetPage)
            }
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { settledPage ->
                if (settledPage in 0 until currentQueueSize && settledPage != currentQueueIndex) {
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

    with(sharedTransitionScope) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FoamTheme.colors.background.copy(alpha = uiAlpha))
        ) {
            val absoluteOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val indexA = absoluteOffset.toInt().coerceIn(0, (state.queue.size - 1).coerceAtLeast(0))
            val indexB = (indexA + 1).coerceAtMost((state.queue.size - 1).coerceAtLeast(0))
            val crossfadeFraction = (absoluteOffset - indexA).coerceIn(0f, 1f)

            AlbumArtBackdrop(
                frontArtPath = state.queue.getOrNull(indexA)?.albumArtPath,
                backArtPath = state.queue.getOrNull(indexB)?.albumArtPath,
                crossfadeFraction = crossfadeFraction,
                immersive = immersive,
                subViewProgress = subViewBlurProgress,
                modifier = Modifier.graphicsLayer { alpha = uiAlpha }
            )

            // This sits above either artwork backdrop but below all player UI,
            // giving Lyrics and Queue a calmer, higher-contrast reading surface.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 20% dark
                    .background(Color.Black.copy(alpha = 0.2f * subViewBlurProgress))
            )

            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    // --- Layer 1: main player (art pager + controls) ---
                    upTransition.AnimatedVisibility(
                        visible = { !it },
                        enter = fadeIn(tween(TRANSITION_DURATION_MS)) +
                                slideInVertically(tween(TRANSITION_DURATION_MS)) { h -> -h / 6 },
                        exit = fadeOut(tween(TRANSITION_DURATION_MS)) +
                                slideOutVertically(tween(TRANSITION_DURATION_MS)) { h -> -h / 6 },
                        modifier = Modifier.fillMaxSize().then(mainPlayerDrag)
                    ) {
                        MainPlayerContent(
                            track = track,
                            state = state,
                            pagerState = pagerState,
                            density = density,
                            configuration = configuration,
                            placeholderColor = placeholderColor,
                            immersive = immersive,
                            outerVisibilityScope = animatedVisibilityScope,
                            headerVisibilityScope = this@AnimatedVisibility,
                            viewModel = viewModel,
                            onDismiss = onDismiss,
                            playerTransitionState = playerTransitionState,
                            upTransitionState = upTransitionState
                        )
                    }

                    // --- Layer 2: mini header + subview (lyrics/queue) ---
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
                            state = state,
                            subViewMode = subViewMode,
                            viewModel = viewModel,
                            headerVisibilityScope = this@AnimatedVisibility,
                            modifier = headerDrag,
                            onCollapse = { gestureController.collapseToMainPlayer() },
                            upTransitionState = upTransitionState
                        )
                    }
                }

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
                        .graphicsLayer { alpha = uiAlpha }
                )
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
    configuration: android.content.res.Configuration,
    placeholderColor: Color,
    immersive: Boolean,
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    playerTransitionState: SeekableTransitionState<Boolean>,
    upTransitionState: SeekableTransitionState<Boolean>
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        /*Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(FoamTheme.colors.borderOverlay)
                    .clickable { onDismiss() }
            )
            Spacer(modifier = Modifier.height(20.dp))
            state.audioOutputInfo?.let { info ->
                AudioFormatBadge(isBitPerfect = info.isBitPerfectMode)
            }
        }*/

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
            positionMs = state.currentPositionMs,
            durationMs = state.durationMs,
            outerVisibilityScope = outerVisibilityScope,
            pagerState = pagerState,
            currentIndex = state.queueIndex,
            onSeek = { positionMs -> viewModel.seekTo(positionMs) },
            onPrevious = { viewModel.previous() },
            onPlayPause = { if (state.isPlaying) viewModel.pause() else viewModel.play() },
            onNext = { viewModel.next() }
        )

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun AudioFormatBadge(isBitPerfect: Boolean) {
    val tint = if (isBitPerfect) FoamTheme.colors.bitPerfectActive else FoamTheme.colors.bitPerfectInactive
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(tint))
        Spacer(modifier = Modifier.width(6.dp))
        BasicText(
            text = if (isBitPerfect) "BIT-PERFECT" else "HI-RES AUDIO",
            style = FoamTheme.typography.monospace.copy(color = tint)
        )
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
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val pageWidthPx = screenWidthPx - with(density) { (PlayerDimens.horizontalPagePadding * 2).toPx() }
    val pageSpacingPx = with(density) { PlayerDimens.pageSpacing.toPx() }
    val anchorPage = queueIndex.coerceAtLeast(0)
    val pagerOffsetFromAnchor = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
    val heroCoverage = (1f - abs(pagerOffsetFromAnchor)).coerceIn(0f, 1f)
    val heroTranslationPx = -pagerOffsetFromAnchor * (pageWidthPx + pageSpacingPx)

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
            beyondViewportPageCount = 1
        ) { page ->
            val pageTrack = queue.getOrNull(page) ?: return@HorizontalPager
            val isAnchorPage = page == anchorPage
            // Classic mode hands the current title to the hero overlay. Immersive
            // mode has no foreground hero card, so the pager owns the title too.
            val pageAlpha = if (immersive) 1f else if (isAnchorPage) 1f - heroCoverage else 1f

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
                            .graphicsLayer { alpha = pageAlpha },
                        contentAlignment = Alignment.Center
                    ) {
                        if (pageTrack.albumArtPath != null) {
                            AsyncImage(
                                model = pageTrack.albumArtPath,
                                contentDescription = "Album Art",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Column(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = pageAlpha }) {
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
        if (heroCoverage > 0f && !immersive) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PlayerDimens.horizontalPagePadding)
                    .graphicsLayer {
                        translationX = heroTranslationPx
                        alpha = heroCoverage
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
                        AsyncImage(
                            model = track.albumArtPath,
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
    positionMs: Long,
    durationMs: Long,
    outerVisibilityScope: AnimatedVisibilityScope,
    pagerState: PagerState,
    currentIndex: Int,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        //Spacer(modifier = Modifier.height(16.dp))

        // Animate the seek bar
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

        val scrollPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
        val distanceFromCurrent = abs(scrollPosition - currentIndex.coerceAtLeast(0))
        val activeFraction = (1f - distanceFromCurrent).coerceIn(0f, 1f)
        val visualProgress = progress * activeFraction

        val dynamicDuration = if (activeFraction == 0f || progress == 0f) {
            300
        } else {
            (300 * activeFraction).toInt().coerceAtLeast(1)
        }

        val animatedProgress by animateFloatAsState(
            targetValue = visualProgress,
            animationSpec = if (visualProgress == 0f) tween(dynamicDuration) else snap(),
            label = "SliderProgress"
        )

        FoamSlider(value = animatedProgress, onValueChange = { new -> onSeek((new * durationMs).toLong()) })
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
