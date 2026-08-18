package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.screens.lyrics.LyricsScreen
import com.foamplayer.app.ui.screens.queue.QueueScreen
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.abs
import kotlinx.coroutines.delay

enum class SubViewMode(val label: String) {
    LYRICS("Lyrics"),
    QUEUE("Queue")
}

/** Shared layout constants so the header, pager and hero overlay always agree on geometry. */
private object PlayerDimens {
    val horizontalPagePadding = 24.dp
    val pageSpacing = 24.dp
    val artCornerRadius = 24.dp
}

private const val TRANSITION_DURATION_MS = 400


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

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val scope = rememberCoroutineScope()

    val dragThresholdPx = screenHeightPx.coerceAtLeast(1f)
    val gestureController = remember(playerTransitionState, dragThresholdPx) {
        PlayerGestureController(
            upTransitionState = upTransitionState,
            playerTransitionState = playerTransitionState,
            scope = scope,
            dragThresholdPx = dragThresholdPx,
            onDismiss = onDismiss
        )
    }

    // Isolated draggable modifier to prevent interference with scrollable lists below it.
    val dragModifier = Modifier.draggable(
        state = rememberDraggableState { delta -> gestureController.onDrag(delta) },
        orientation = Orientation.Vertical,
        onDragStopped = { velocity -> gestureController.onDragStopped(velocity) }
    )

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

    LaunchedEffect(state.queueIndex) {
        if (state.queueIndex >= 0 && state.queueIndex < state.queue.size && state.queueIndex != pagerState.currentPage) {
            pagerState.animateScrollToPage(state.queueIndex)
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != state.queueIndex && pagerState.settledPage >= 0 && pagerState.settledPage < state.queue.size) {
            viewModel.skipToIndex(pagerState.settledPage)
        }
    }

    val uiAlpha by animatedVisibilityScope.transition.animateFloat(label = "uiAlpha") { enterExitState ->
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
                modifier = Modifier.graphicsLayer { alpha = uiAlpha }
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
                        modifier = Modifier.fillMaxSize().then(dragModifier)
                    ) {
                        MainPlayerContent(
                            track = track,
                            state = state,
                            pagerState = pagerState,
                            density = density,
                            configuration = configuration,
                            placeholderColor = placeholderColor,
                            outerVisibilityScope = animatedVisibilityScope,
                            headerVisibilityScope = this@AnimatedVisibility,
                            viewModel = viewModel,
                            onDismiss = onDismiss
                        )
                    }

                    // --- Layer 2: mini header + subview (lyrics/queue) ---
                    upTransition.AnimatedVisibility(
                        visible = { it },
                        enter = fadeIn(tween(TRANSITION_DURATION_MS)) +
                                slideInVertically(tween(TRANSITION_DURATION_MS)) { h -> h / 6 },
                        exit = fadeOut(tween(TRANSITION_DURATION_MS)) +
                                slideOutVertically(tween(TRANSITION_DURATION_MS)) { h -> h / 6 },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        SubViewContent(
                            track = track,
                            state = state,
                            subViewMode = subViewMode,
                            viewModel = viewModel,
                            headerVisibilityScope = this@AnimatedVisibility,
                            dragModifier = dragModifier,
                            onCollapse = { gestureController.collapseToMainPlayer() }
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

/** Background layer: crossfading, heavily blurred album art behind a dark scrim. */
@Composable
private fun AlbumArtBackdrop(
    frontArtPath: String?,
    backArtPath: String?,
    crossfadeFraction: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(FoamTheme.colors.background))
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.8f }) {
            if (frontArtPath != null) {
                AsyncImage(
                    model = frontArtPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(20.dp),
                    contentScale = ContentScale.Crop
                )
            }
            if (backArtPath != null && backArtPath != frontArtPath) {
                AsyncImage(
                    model = backArtPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(20.dp).graphicsLayer { alpha = crossfadeFraction },
                    contentScale = ContentScale.Crop
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
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
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        }

        Spacer(modifier = Modifier.height(24.dp))

        TrackHeroPager(
            track = track,
            queue = state.queue,
            queueIndex = state.queueIndex,
            pagerState = pagerState,
            density = density,
            configuration = configuration,
            placeholderColor = placeholderColor,
            outerVisibilityScope = outerVisibilityScope,
            headerVisibilityScope = headerVisibilityScope
        )

        Spacer(modifier = Modifier.height(16.dp))

        PlaybackControls(
            track = track,
            isPlaying = state.isPlaying,
            positionMs = state.currentPositionMs,
            durationMs = state.durationMs,
            onSeek = { positionMs -> viewModel.seekTo(positionMs) },
            onPrevious = { viewModel.previous() },
            onPlayPause = { if (state.isPlaying) viewModel.pause() else viewModel.play() },
            onNext = { viewModel.next() }
        )

        Spacer(modifier = Modifier.weight(1f))
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
    outerVisibilityScope: AnimatedVisibilityScope,
    headerVisibilityScope: AnimatedVisibilityScope
) {
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val pageWidthPx = screenWidthPx - with(density) { (PlayerDimens.horizontalPagePadding * 2).toPx() }
    val pageSpacingPx = with(density) { PlayerDimens.pageSpacing.toPx() }
    val anchorPage = queueIndex.coerceAtLeast(0)
    val pagerOffsetFromAnchor = pagerState.currentPage - anchorPage + pagerState.currentPageOffsetFraction
    val heroCoverage = (1f - abs(pagerOffsetFromAnchor)).coerceIn(0f, 1f)
    val heroTranslationPx = -pagerOffsetFromAnchor * (pageWidthPx + pageSpacingPx)

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
            val pageAlpha = if (isAnchorPage) 1f - heroCoverage else 1f

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(PlayerDimens.artCornerRadius))
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
                Spacer(modifier = Modifier.height(24.dp))
                Column(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = pageAlpha }) {
                    BasicText(
                        text = pageTrack.title,
                        style = FoamTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    BasicText(
                        text = "${pageTrack.artist} — ${pageTrack.album}",
                        style = FoamTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Hero overlay — the ONLY composable carrying the shared-element modifiers.
        if (heroCoverage > 0f) {
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
                        .clip(RoundedCornerShape(PlayerDimens.artCornerRadius))
                        .background(placeholderColor),
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

fun Modifier.pingPongMarquee(
    speedPxPerSecond: Float = 75f,
    pauseDuration: Long = 1500L
): Modifier = composed {
    val scrollState = rememberScrollState()

    LaunchedEffect(scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            val scrollDurationMillis = ((scrollState.maxValue / speedPxPerSecond) * 1000).toInt()
            delay(pauseDuration)
            while (true) {
                scrollState.animateScrollTo(
                    value = scrollState.maxValue,
                    animationSpec = tween(durationMillis = scrollDurationMillis, easing = EaseInOut)
                )
                delay(pauseDuration)
                scrollState.animateScrollTo(
                    value = 0,
                    animationSpec = tween(durationMillis = scrollDurationMillis, easing = EaseInOut)
                )
                delay(pauseDuration)
            }
        }
    }
    this.horizontalScroll(scrollState, enabled = false)
}

@Composable
private fun PlaybackControls(
    track: TrackEntity,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText(text = track.format, style = FoamTheme.typography.caption)
                BasicText(text = "${track.bitrate} kbps", style = FoamTheme.typography.monospace)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        FoamSlider(value = progress, onValueChange = { new -> onSeek((new * durationMs).toLong()) })
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

@Composable
private fun SubViewSelectorRow(
    selected: SubViewMode?,
    onSelect: (SubViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        SubViewMode.values().forEach { mode ->
            val color = if (selected != null) {
                if (mode == selected) FoamTheme.colors.primaryAccent else FoamTheme.colors.textSecondary
            } else {
                FoamTheme.colors.textPrimary
            }
            
            val icon = when(mode) {
                SubViewMode.LYRICS -> FoamTheme.icons.current.lyrics
                SubViewMode.QUEUE -> FoamTheme.icons.current.queue
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelect(mode) }.padding(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = mode.label,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicText(
                    text = mode.label,
                    style = FoamTheme.typography.body.copy(color = color)
                )
            }
        }
    }
}

/** Layer 2 content: mini header (draggable handle) plus the lyrics/queue subview. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.SubViewContent(
    track: TrackEntity,
    state: PlaybackState,
    subViewMode: SubViewMode,
    viewModel: PlayerViewModel,
    headerVisibilityScope: AnimatedVisibilityScope,
    dragModifier: Modifier,
    onCollapse: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
        // Draggable zone limited to the header to prevent scroll conflicts below.
        Column(modifier = Modifier.fillMaxWidth().then(dragModifier)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(FoamTheme.colors.borderOverlay)
                    .clickable { onCollapse() }
            )

            MiniPlayerHeader(
                track = track,
                headerVisibilityScope = headerVisibilityScope,
                onBack = onCollapse
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = subViewMode,
                transitionSpec = {
                    fadeIn(tween(TRANSITION_DURATION_MS)) togetherWith fadeOut(tween(TRANSITION_DURATION_MS))
                },
                label = "SubViewContent"
            ) { mode ->
                when (mode) {
                    SubViewMode.LYRICS -> LyricsScreen(
                        rawLyrics = state.currentLyrics,
                        currentPositionMs = state.currentPositionMs,
                        onSeek = { positionMs -> viewModel.seekTo(positionMs) },
                        onClose = {},
                        backgroundColor = Color.Transparent
                    )
                    SubViewMode.QUEUE -> QueueScreen(
                        viewModel = viewModel,
                        showTitle = false,
                        backgroundColor = Color.Transparent
                    )
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
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .clickable { onBack() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .sharedElement(
                    rememberSharedContentState(key = "album_art_header_${track.id}"),
                    animatedVisibilityScope = headerVisibilityScope
                )
                .clip(RoundedCornerShape(8.dp))
                .background(FoamTheme.colors.surfaceVariant)
        ) {
            if (track.albumArtPath != null) {
                AsyncImage(
                    model = track.albumArtPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = track.title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBounds(
                    rememberSharedContentState(key = "track_title_header_${track.id}"),
                    animatedVisibilityScope = headerVisibilityScope
                )
            )
            BasicText(
                text = track.artist,
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBounds(
                    rememberSharedContentState(key = "track_artist_header_${track.id}"),
                    animatedVisibilityScope = headerVisibilityScope
                )
            )
        }

        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Collapse",
            tint = FoamTheme.colors.primaryAccent,
            modifier = Modifier.size(28.dp)
        )
    }
}