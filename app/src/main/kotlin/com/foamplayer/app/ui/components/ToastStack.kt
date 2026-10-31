package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.foamplayer.app.toast.ToastCenter
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurface
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * The stack of in-app toasts, shown just above the mini player / navigation
 * area. Purely presentational: the host passes the toast list from
 * [ToastCenter.toasts] and positions the stack with [modifier] (typically
 * `Modifier.align(Alignment.BottomCenter)` inside the root box).
 *
 * Each toast grows into place (slide up from below, scale up, fade in) and
 * leaves by shrinking while sliding up and fading out. Toasts stay composed
 * while their exit animation plays, so a removed pill animates out instead of
 * vanishing.
 */
@Composable
fun ToastStack(
    toasts: StateFlow<List<ToastCenter.Toast>>,
    modifier: Modifier = Modifier,
) {
    val currentToasts by toasts.collectAsState()
    ToastStack(currentToasts, modifier)
}

/**
 * Overload taking the toast list directly, for previewing and for hosts that
 * already collect [ToastCenter.toasts] higher in the tree.
 */
@Composable
fun ToastStack(
    toasts: List<ToastCenter.Toast>,
    modifier: Modifier = Modifier,
) {
    val items = remember { mutableStateListOf<DisplayedToast>() }

    LaunchedEffect(toasts) {
        val liveIds = toasts.mapTo(HashSet()) { it.id }
        val knownIds = items.mapTo(HashSet()) { it.toast.id }

        toasts.filter { it.id !in knownIds }.forEach { toast ->
            items.add(
                DisplayedToast(
                    toast = toast,
                    state = MutableTransitionState(false).apply { targetState = true }
                )
            )
        }

        items.forEach { item ->
            if (item.toast.id !in liveIds) item.state.targetState = false
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier.padding(horizontal = ToastScreenEdgePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items.toList().forEach { item ->
                key(item.toast.id) {
                    // Remove the item once its exit animation has fully finished.
                    LaunchedEffect(item) {
                        snapshotFlow { item.state.isIdle && !item.state.currentState }
                            .first { it }
                        items.remove(item)
                    }
                    AnimatedVisibility(
                        visibleState = item.state,
                        enter = fadeIn(ToastFadeSpec) +
                                slideInVertically(ToastSlideSpec) { it } +
                                expandVertically(ToastSizeSpec, expandFrom = Alignment.Bottom) +
                                scaleIn(
                                    initialScale = ToastMinScale,
                                    animationSpec = ToastFadeSpec,
                                    transformOrigin = ToastTransformOrigin
                                ),
                        exit = fadeOut(ToastFadeSpec) +
                                slideOutVertically(ToastSlideSpec) { -it } +
                                shrinkVertically(ToastSizeSpec, shrinkTowards = Alignment.Bottom) +
                                scaleOut(
                                    targetScale = ToastMinScale,
                                    animationSpec = ToastFadeSpec,
                                    transformOrigin = ToastTransformOrigin
                                ),
                    ) {
                        // Spacing lives inside the animated content so the gap
                        // collapses together with the pill.
                        Box(modifier = Modifier.padding(top = ToastSpacing)) {
                            ToastPill(item.toast.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToastPill(text: String) {
    Box(
        modifier = Modifier
            .widthIn(max = ToastMaxWidth)
            .clip(RoundedCornerShape(FoamTheme.shapes.cardRounding))
            .foamSurface(alpha = 0.92f)
            .padding(horizontal = ToastHorizontalPadding, vertical = ToastVerticalPadding)
    ) {
        Text(
            text = text,
            style = FoamTheme.typography.body,
            textAlign = TextAlign.Center,
            color = FoamTheme.colors.textPrimary
        )
    }
}

/**
 * Display model for one toast in the stack. Wraps [ToastCenter.Toast] with the
 * pill's own animation [state]: false -> true plays the enter animation,
 * true -> false plays the exit animation, after which the item is removed.
 */
private class DisplayedToast(
    val toast: ToastCenter.Toast,
    val state: MutableTransitionState<Boolean>,
)

private val ToastFadeSpec = tween<Float>(ToastAnimDurationMs)
private val ToastSlideSpec = tween<IntOffset>(ToastAnimDurationMs)
private val ToastSizeSpec = tween<IntSize>(ToastAnimDurationMs)

/** Start/end scale of the pill's pop-in / shrink-out animation. */
private const val ToastMinScale = 0.8f

/** Pill grows from / shrinks toward its bottom edge, where it sits on the stack. */
private val ToastTransformOrigin = TransformOrigin(0.5f, 1f)

private val ToastHorizontalPadding = 16.dp

/** Screen-edge padding around the stack, so toasts never touch the sides. */
private val ToastScreenEdgePadding = 24.dp

/** Long toasts wrap instead of stretching across the screen. */
private val ToastMaxWidth = 360.dp
private val ToastVerticalPadding = 10.dp
private val ToastSpacing = 8.dp
private const val ToastAnimDurationMs = 200