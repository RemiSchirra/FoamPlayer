package com.foamplayer.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import com.foamplayer.app.audio.engine.PlaybackState
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.ui.components.MiniPlayer
import com.foamplayer.app.ui.components.ToastStack
import com.foamplayer.app.ui.components.TrackCenterRequest
import com.foamplayer.app.ui.components.collectionHeroOverlayLayer
import com.foamplayer.app.ui.screens.player.MiniPlayerSwipeController
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.screens.player.backdropScrimColor
import com.foamplayer.app.ui.screens.player.progress

/**
 * Bottom chrome of the app: the scrim behind it, the nav bar (docked, floating
 * or attached), the mini player card and the toast stack. Hidden on onboarding.
 *
 * The host owns the state (expansion, presence fraction, geometry) so both the
 * player and the chrome can react to it; this composable only lays it out.
 */
@Composable
internal fun BoxScope.FoamBottomChrome(
    currentRoute: String?,
    navigationBarMode: NavigationBarMode,
    transition: Transition<Boolean>,
    playerTransitionState: SeekableTransitionState<Boolean>,
    isPlayerOpen: Boolean,
    isTransitionAnimated: Boolean,
    navBarExpandFraction: Float,
    playerClosedFraction: () -> Float,
    isBottomChromeLifted: () -> Boolean,
    onToggleNavBarExpand: () -> Unit,
    onNavigate: (String) -> Unit,
    settings: AppSettings,
    hasCurrentTrack: Boolean,
    cardPresenceFraction: () -> Float,
    miniPlayerSwipeController: MiniPlayerSwipeController,
    miniPlayerTopPx: () -> Float?,
    bottomBarClearance: Dp,
    miniPlayerBoxHeight: Dp,
    sharedTransitionScope: SharedTransitionScope,
    playerViewModel: PlayerViewModel,
    onBackgroundTransition: (front: String?, back: String?, fraction: Float) -> Unit,
    onOpenPlayer: () -> Unit,
    trackListTransitionTrackId: Long?,
    centerTrackRequest: TrackCenterRequest?,
    skipMiniPlayerTrackAnimation: Boolean,
    isAnimateSeekBarEnabled: Boolean,
    navBarTransparentWhenPlayerOpen: Boolean,
    toastViewModel: ToastViewModel,
    onNavZoneHeightChange: (Int) -> Unit,
    onMiniPlayerTopChange: (Float) -> Unit,
    onMiniPlayerHeightChange: (Int) -> Unit,
) {
    val livePlaybackState by playerViewModel.playbackState.collectAsState()
    var lastPlayingState by remember { mutableStateOf<PlaybackState?>(null) }
    SideEffect {
        if (livePlaybackState.currentTrack != null) lastPlayingState = livePlaybackState
    }

    if (currentRoute != Screen.Onboarding.route) {
        val bottomScrimColor = backdropScrimColor()
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomBarClearance)
                .background(
                    Brush.verticalGradient(
                        0f to bottomScrimColor.copy(alpha = 0f),
                        1f to bottomScrimColor.copy(alpha = BottomChromeScrimMaxAlpha)
                    )
                )
        )
    }

    // Nav bar + mini player overlay, hidden on onboarding.
    if (currentRoute != Screen.Onboarding.route) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { onNavZoneHeightChange(it.height) }
                .collectionHeroOverlayLayer(
                    sharedTransitionScope = sharedTransitionScope,
                    zIndexInOverlay = BottomChromeNavBarOverlayZIndex,
                    renderInOverlay = isBottomChromeLifted
                )
        ) {
            when (navigationBarMode) {
                NavigationBarMode.DOCKED -> Box(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                ) {
                    FoamNavigationBar(
                        currentRoute = currentRoute,
                        isPlayerOpen = isPlayerOpen,
                        onNavigate = onNavigate,
                        primaryItems = settings.navBarPrimaryOrder,
                        primaryEnabled = settings.navBarPrimaryEnabled,
                        secondaryItems = settings.navBarSecondaryOrder,
                        secondaryEnabled = settings.navBarSecondaryEnabled,
                        connectToMiniPlayer = hasCurrentTrack,
                        expandFraction = navBarExpandFraction,
                        onToggleExpand = onToggleNavBarExpand
                    )
                }
                NavigationBarMode.FLOATING -> Box(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                ) {
                    FloatingNavigationBar(
                        currentRoute = currentRoute,
                        isPlayerOpen = isPlayerOpen,
                        onNavigate = onNavigate,
                        primaryItems = settings.navBarPrimaryOrder,
                        primaryEnabled = settings.navBarPrimaryEnabled,
                        secondaryItems = settings.navBarSecondaryOrder,
                        secondaryEnabled = settings.navBarSecondaryEnabled,
                        attachedToMiniPlayer = false,
                        connectToMiniPlayer = hasCurrentTrack,
                        playerClosedFraction = playerClosedFraction,
                        transitionAnimated = isTransitionAnimated,
                        navBarTransparentWhenPlayerOpen = navBarTransparentWhenPlayerOpen,
                        expandFraction = navBarExpandFraction,
                        onToggleExpand = onToggleNavBarExpand
                    )
                }
                NavigationBarMode.ATTACHED -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    FloatingNavigationBar(
                        currentRoute = currentRoute,
                        isPlayerOpen = isPlayerOpen,
                        onNavigate = onNavigate,
                        primaryItems = settings.navBarPrimaryOrder,
                        primaryEnabled = settings.navBarPrimaryEnabled,
                        secondaryItems = settings.navBarSecondaryOrder,
                        secondaryEnabled = settings.navBarSecondaryEnabled,
                        attachedToMiniPlayer = true,
                        connectToMiniPlayer = hasCurrentTrack,
                        miniPlayerTopPx = miniPlayerTopPx,
                        playerClosedFraction = playerClosedFraction,
                        cardPresenceFraction = cardPresenceFraction,
                        navBarTransparentWhenPlayerOpen = navBarTransparentWhenPlayerOpen,
                        expandFraction = navBarExpandFraction,
                        onToggleExpand = onToggleNavBarExpand
                    )
                }
            }
        }
    }

    val miniPlayerBottomPadding = NavBarDimens.miniPlayerBottomPadding(
        mode = navigationBarMode,
        expandFraction = navBarExpandFraction
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = miniPlayerBottomPadding)
            .collectionHeroOverlayLayer(
                sharedTransitionScope = sharedTransitionScope,
                zIndexInOverlay = BottomChromeMiniPlayerOverlayZIndex,
                renderInOverlay = isBottomChromeLifted
            )
    ) {
        transition.AnimatedVisibility(
            visible = { isFull -> !isFull },
            enter = if (isTransitionAnimated) {
                if (trackListTransitionTrackId != null) {
                    // The artwork is morphing into a collection row instead of
                    // the mini player: fade + slide up from the bottom.
                    fadeIn(tween(SharedElementTransitionDurationMs, easing = SharedElementEasing)) +
                        slideInVertically(
                            tween(SharedElementTransitionDurationMs, easing = SharedElementEasing)
                        ) { it / 2 }
                } else {
                    fadeIn()
                }
            } else {
                EnterTransition.None
            },
            exit = if (isTransitionAnimated) fadeOut() else ExitTransition.None
        ) {
            val miniPlayerVisibilityScope = this
            if (currentRoute != Screen.Onboarding.route &&
                (hasCurrentTrack || cardPresenceFraction() > 0f)
            ) {
                MiniPlayer(
                    playbackStateFlow = playerViewModel.playbackState,
                    positionMsFlow = playerViewModel.positionMs,
                    onPlayPauseClick = {
                        val state = playerViewModel.playbackState.value
                        if (state.isPlaying) playerViewModel.pause() else playerViewModel.play()
                    },
                    onSeek = { progress ->
                        playerViewModel.seekTo(
                            (progress * playerViewModel.playbackState.value.durationMs).toLong()
                        )
                    },
                    onSkipToTrack = { index, forward ->
                        playerViewModel.skipToIndex(index, forward)
                    },
                    onColorChange = { color ->
                        playerViewModel.setDynamicAccentColor(color)
                    },
                    onBackgroundTransition = onBackgroundTransition,
                    onClick = onOpenPlayer,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = miniPlayerVisibilityScope,
                    // Untagged while a row is the flight endpoint or a press
                    // preload is pending (a rest-state pairing would pin the
                    // shared elements and break pager swipes).
                    useSharedElements = trackListTransitionTrackId == null &&
                        centerTrackRequest == null,
                    sharedArtFlightAlpha = {
                        if (settings.immersiveModeEnabled) {
                            1f - playerTransitionState.progress()
                        } else {
                            1f
                        }
                    },
                    isImmersiveEnabled = settings.immersiveModeEnabled,
                    isAnimateSeekBarEnabled = isAnimateSeekBarEnabled,
                    marqueeMode = settings.marqueeMode,
                    skipTrackChangeAnimation = skipMiniPlayerTrackAnimation,
                    showContainer = navigationBarMode != NavigationBarMode.ATTACHED,
                    fallbackPlaybackState = lastPlayingState,
                    modifier = Modifier
                        .graphicsLayer {
                            val presence = cardPresenceFraction()
                            alpha = presence
                            translationY = (1f - presence) * (miniPlayerBoxHeight / 2).toPx()
                        }
                        .onGloballyPositioned { onMiniPlayerTopChange(it.positionInRoot().y) }
                        .onSizeChanged { onMiniPlayerHeightChange(it.height) }
                        .draggable(
                            state = rememberDraggableState { delta -> miniPlayerSwipeController.onDrag(delta) },
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity -> miniPlayerSwipeController.onDragStopped(velocity) }
                        )
                )
            }
        }
    }

    // In-app toast notifications, above the mini player.
    ToastStack(
        toasts = toastViewModel.toastCenter.toasts,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = miniPlayerBottomPadding + miniPlayerBoxHeight + ToastsAboveMiniPlayerGap)
    )
}
