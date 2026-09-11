package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import kotlin.math.abs

/**
 * A modal sheet docked to the bottom of the screen (flush with the bottom edge,
 * inset from the sides, only the TOP corners rounded), styled after
 * [com.foamplayer.app.ui.navigation.FoamNavigationBar]'s docked look.
 *
 * Sizing / gesture behavior:
 * - Defaults to wrapping its [content], capped at roughly the middle of the
 *   screen. Content that fits under that cap just shows at its natural size.
 * - If content overflows that cap, dragging/scrolling UP grows the sheet the
 *   rest of the way to almost the top of the screen *before* the content
 *   itself starts scrolling -- once it's fully expanded, any remaining
 *   overflow falls back to a normal internal scroll.
 * - When the content is scrolled back to its top, continuing to drag/scroll
 *   DOWN progressively shrinks the sheet again and dismisses it, rather than
 *   fighting with the content scroll.
 * - Releasing mid-drag (with or without a flick) springs to the nearest
 *   resting point: dismissed, half-expanded, or fully expanded.
 *
 * Rendered in a full-screen [Dialog] so it always overlays the whole screen
 * regardless of where it's called from (e.g. from inside a nav bar that isn't
 * itself full-size).
 */
@Composable
fun FoamBottomModal(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = FoamDimens.cornerRadiusLarge,
    topEdgeMargin: Dp = FoamDimens.paddingLarge,
    horizontalMargin: Dp = FoamDimens.paddingMedium,
    content: @Composable () -> Unit
) {
    // Keep the Dialog mounted while the exit animation plays. currentState only
    // catches up with targetState once AnimatedVisibility finishes animating out,
    // so this is what lets the sheet slide/fade away instead of just vanishing.
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = visible

    if (!transitionState.currentState && !transitionState.targetState) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // The platform Dialog window dims the screen on its own by default. Left
        // alone, that shows up as an instant half-dim UNDER our own animated
        // scrim fade -- i.e. the fade appears to happen "twice". Kill the native
        // dim/animation and let our own scrim be the only thing drawing it.
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                window.setWindowAnimations(0)
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

            val fullPx = with(density) { (maxHeight - topInset - topEdgeMargin).coerceAtLeast(0.dp).toPx() }
            val middlePx = with(density) { (maxHeight / 2f).coerceAtMost(maxHeight - topInset - topEdgeMargin).coerceAtLeast(0.dp).toPx() }

            // Current sheet height cap, in px. Starts half-expanded; content that
            // fits underneath it just wraps to its natural (smaller) size.
            var capPx by remember { mutableFloatStateOf(middlePx) }
            val scrollState = rememberScrollState()

            // Drag-to-dismiss now springs capPx down to 0 (see settle() below), so
            // without this a subsequent re-open would start from a collapsed sheet.
            LaunchedEffect(visible) {
                if (visible) capPx = middlePx
            }

            // Scrim darkness tracks the sheet's live position instead of only the
            // open/closed boolean, so dragging the sheet down progressively fades
            // the background instead of leaving it fully dark until the drag
            // finishes.
            val scrimAlpha = (capPx / middlePx).coerceIn(0f, 1f) * 0.55f

            suspend fun settle(flingVelocityY: Float) {
                val anchors = floatArrayOf(0f, middlePx, fullPx)
                val projected = (capPx - flingVelocityY * 0.15f).coerceIn(0f, fullPx)
                val target = anchors.minByOrNull { abs(it - projected) } ?: middlePx
                // Always spring capPx the rest of the way, including down to 0.
                // This keeps the motion on a single continuous, velocity-matched
                // curve -- switching mid-drag to the Dialog's fixed-duration exit
                // transition instead is what caused the visible speed "jump" on
                // release.
                animate(
                    initialValue = capPx,
                    targetValue = target,
                    initialVelocity = -flingVelocityY,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) { value, _ -> capPx = value }
                if (target <= 0f) {
                    // capPx has already sprung to 0 by this point, so the sheet is
                    // visually gone and the scrim (tied to capPx below) has already
                    // faded out -- this just tears down the Dialog itself.
                    onDismissRequest()
                }
            }

            val nestedScrollConnection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        val dy = available.y
                        // Dragging up: grow the sheet toward "full" first, before
                        // letting the content itself scroll.
                        if (dy < 0f) {
                            val room = fullPx - capPx
                            if (room > 0f) {
                                val consumed = if (-dy < room) dy else -room
                                capPx -= consumed
                                return Offset(0f, consumed)
                            }
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        val dy = available.y
                        // Dragging down and the content had nothing left to give
                        // (already at its top): shrink the sheet instead.
                        if (dy > 0f && capPx > 0f) {
                            val consumed2 = if (dy < capPx) dy else capPx
                            capPx -= consumed2
                            return Offset(0f, consumed2)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        val fullyExpandedMidContent = capPx >= fullPx - 0.5f && scrollState.value > 0
                        return if (fullyExpandedMidContent) {
                            Velocity.Zero
                        } else {
                            settle(available.y)
                            Velocity(0f, available.y)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visibleState = transitionState,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrimAlpha))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissRequest
                        )
                )
            }

            AnimatedVisibility(
                visibleState = transitionState,
                enter = slideInVertically(tween(280)) { fullHeight -> fullHeight },
                exit = slideOutVertically(tween(220)) { fullHeight -> fullHeight },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalMargin)
                        .heightIn(max = with(density) { capPx.toDp() })
                        .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius))
                        .background(FoamTheme.colors.surface)
                        // Swallow taps here so they don't fall through to the scrim below.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .navigationBarsPadding()
                        .nestedScroll(nestedScrollConnection)
                        // With a bounded max height this only kicks in once content
                        // actually overflows what capPx currently allows.
                        .verticalScroll(scrollState)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FoamDimens.spacingSmall, bottom = FoamDimens.spacingTiny),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = FoamTheme.icons.current.dragHandle,
                            contentDescription = null,
                            tint = FoamTheme.colors.textSecondary
                        )
                    }

                    Box(
                        modifier = Modifier.padding(
                            horizontal = FoamDimens.paddingMedium,
                            vertical = FoamDimens.paddingSmall
                        )
                    ) {
                        content()
                    }
                }
            }
        }
    }
}