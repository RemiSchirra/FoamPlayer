package com.foamplayer.app.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Shared state for the shrink effect behind modals.
 * [FoamBottomModal](com.foamplayer.app.ui.modals.FoamBottomModal) renders in
 * its own Dialog window, so the app root does the scaling: it hosts the
 * content in [FoamBackdropHost] and reads the state the modal writes.
 */
@Stable
class FoamBackdropState {
    /** True while any FoamModal is open. */
    var active by mutableStateOf(false)
        private set

    /**
     * Live sheet presence while dragging: 1 = full size, 0 = gone. Non-null
     * overrides the tween so the scale follows the finger; null hands
     * control back to the tween.
     */
    var dragPresence by mutableStateOf<Float?>(null)

    private var depth = 0

    /** Opens; nested modals keep the shrink until the last one leaves. */
    fun acquire() {
        depth++
        active = true
    }

    /** Closes; restores the scale once the last modal has gone. */
    fun release() {
        depth = (depth - 1).coerceAtLeast(0)
        if (depth == 0) active = false
    }
}

/** Provided by [FoamBackdropHost] at the app root; inherited by modal Dialogs. */
val LocalFoamBackdropState = staticCompositionLocalOf { FoamBackdropState() }

/** Scale the backdrop content shrinks to while a modal is open. */
private const val FoamBackdropShrunkScale = 0.95f

/**
 * Root surface for everything behind FoamModal dialogs. Scales the hosted
 * content down to [FoamBackdropShrunkScale] while a modal is open (rounded
 * corners appear as it shrinks), follows [FoamBackdropState.dragPresence]
 * frame-by-frame during drags, and eases back to full size on close.
 *
 * The animation lives here rather than in the modal so it always completes:
 * this scope stays composed even if the modal unmounts mid-exit.
 */
@Composable
fun FoamBackdropHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val backdrop = remember { FoamBackdropState() }

    CompositionLocalProvider(LocalFoamBackdropState provides backdrop) {
        val scale by animateFloatAsState(
            targetValue = if (backdrop.active) FoamBackdropShrunkScale else 1f,
            animationSpec = if (backdrop.active) {
                tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.IN_OUT)
            } else {
                tween(FoamMotion.Duration.STANDARD, easing = FoamMotion.Easings.IN_OUT)
            },
            label = "FoamBackdropScale"
        )

        Box(
            modifier = modifier
                .fillMaxSize()
                .foamBackground()
                .graphicsLayer {
                    val presence = backdrop.dragPresence
                    val live = if (presence != null) {
                        FoamBackdropShrunkScale +
                            (1f - FoamBackdropShrunkScale) * (1f - presence)
                    } else {
                        scale
                    }
                    scaleX = live
                    scaleY = live
                    val progress =
                        ((1f - live) / (1f - FoamBackdropShrunkScale)).coerceIn(0f, 1f)
                    if (progress > 0f) {
                        shape = RoundedCornerShape(FoamDimens.cornerRadiusLarge * progress)
                        clip = true
                    } else {
                        clip = false
                    }
                }
        ) {
            content()
        }
    }
}
