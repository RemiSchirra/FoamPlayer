package com.foamplayer.app.ui.theme

import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Shared state for the shrink effect behind modals.
 * [FoamBottomModal](com.foamplayer.app.ui.modals.FoamBottomModal) renders in
 * its own Dialog window, so the app content does the scaling: [FoamBackdropHost]
 * provides this state at the app root, and the screen content applies it via
 * [foamBackdropScale] — on the screen content only, so the backdrop image
 * layers stay full-bleed behind it.
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

/** Scale the screen content shrinks to while a modal is open. */
private const val FoamBackdropShrunkScale = 0.95f

/**
 * Root surface for everything behind FoamModal dialogs. Provides the backdrop
 * state to the app (and, inherited, to the modal Dialogs) and hosts the
 * content on the plain theme background. The shrink itself lives on the
 * screen content via [foamBackdropScale] — not here — so the background
 * (base color and image layers) never scales and no black border can show
 * around the shrunk content.
 */
@Composable
fun FoamBackdropHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val backdrop = remember { FoamBackdropState() }

    CompositionLocalProvider(LocalFoamBackdropState provides backdrop) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .foamBackground()
        ) {
            content()
            // The modal scrim lives here, in the activity window, rather than
            // inside the modal Dialog: the dialog window shrinks to fit the
            // soft keyboard, so anything drawn in it stops at the keyboard's
            // top edge and the strip the keyboard is animating through would
            // stay undimmed. This window never resizes for the IME.
            Box(modifier = Modifier.fillMaxSize().foamBackdropDim())
        }
    }
}

/**
 * Scale applied to the screen content while a modal is open: tweens down to
 * [FoamBackdropShrunkScale] (rounded corners appear as it shrinks), follows
 * [FoamBackdropState.dragPresence] frame-by-frame during sheet drags, and
 * eases back to full size on close.
 *
 * Apply to the screen content only — never to the backdrop image layers — so
 * the background always fills the screen edge-to-edge. The animated value is
 * only read in the draw phase, so the animation never recomposes the scaled
 * content.
 */
@Composable
fun Modifier.foamBackdropScale(): Modifier {
    val backdrop = LocalFoamBackdropState.current
    val scale by animateFloatAsState(
        targetValue = if (backdrop.active) FoamBackdropShrunkScale else 1f,
        animationSpec = if (backdrop.active) {
            tween(FoamMotion.Duration.SMOOTH, easing = FoamMotion.Easings.IN_OUT)
        } else {
            tween(FoamMotion.Duration.STANDARD, easing = FoamMotion.Easings.IN_OUT)
        },
        label = "FoamBackdropScale"
    )
    return graphicsLayer {
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
}

/** Scale the screen content shrinks to while the full player is open. */
private const val FoamPlayerShrunkScale = 0.95f

/**
 * Progressive shrink of the screen content behind the full player, easing
 * back on close. Uses the player's own content-fade curves (EaseOutCubic on
 * open, EaseInCubic on close) so the recede stays in sync with the player.
 * [playerProgress] (0 = closed, 1 = open) is read in the draw phase, so
 * drags scrub it without recomposing.
 *
 * Screen content only — never the player, the bottom chrome or the
 * full-bleed backdrop layers.
 */
fun Modifier.foamPlayerOpenScale(
    playerOpen: Boolean,
    playerProgress: () -> Float
): Modifier = graphicsLayer {
    val raw = playerProgress().coerceIn(0f, 1f)
    val completion = if (playerOpen) {
        EaseOutCubic.transform(raw)
    } else {
        EaseInCubic.transform(raw)
    }
    val scale = 1f - (1f - FoamPlayerShrunkScale) * completion
    scaleX = scale
    scaleY = scale
    if (completion > 0f) {
        shape = RoundedCornerShape(FoamDimens.cornerRadiusLarge * completion)
        clip = true
    } else {
        clip = false
    }
}

/** Opacity of the dim behind a modal sheet once fully open. */
private const val FoamBackdropScrimAlpha = 0.55f

/**
 * Full-screen dim behind modal sheets, drawn by [FoamBackdropHost] at the app
 * root instead of by the sheet's own Dialog window — see the comment there.
 * Fades with [FoamBackdropState.active] and follows
 * [FoamBackdropState.dragPresence] frame-by-frame while a sheet is dragged
 * down. The live value is read in the draw phase, so drags and fades never
 * recompose anything.
 */
@Composable
fun Modifier.foamBackdropDim(): Modifier {
    val backdrop = LocalFoamBackdropState.current
    val fade by animateFloatAsState(
        targetValue = if (backdrop.active) 1f else 0f,
        animationSpec = if (backdrop.active) {
            FoamMotion.Spec.sheetFadeIn
        } else {
            FoamMotion.Spec.sheetFadeOut
        },
        label = "FoamBackdropDim"
    )
    return drawBehind {
        val presence = backdrop.dragPresence ?: fade
        drawRect(Color.Black.copy(alpha = presence.coerceIn(0f, 1f) * FoamBackdropScrimAlpha))
    }
}
