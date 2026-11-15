package com.foamplayer.app.ui.theme

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion tokens for the whole app.
 *
 * Motion policy:
 *  1. Gesture-driven / interactive motion -> [Springs]
 *  2. Frequently retargeted state -> [Springs]
 *  3. One-shot enter/exit choreography -> [tween]
 *  4. Time-synchronized / derived motion -> [tween]
 */
object FoamMotion {
    /** Duration scale (ms) used for tween. */
    object Duration {
        /** Press feedback, dismiss, quick fades. */
        const val SNAP = 150

        /** Small fades, toasts, returns, swaps. */
        const val SWIFT = 200

        /** Control crossfades, nav-bar slots, modal fades, settles, volume glide. */
        const val STANDARD = 250

        /** Surface/sheet motion, player transition, onboarding, lyric blur. */
        const val SMOOTH = 300

        /** Screen fades and shared-element flights (finish on the same frame). */
        const val FLIGHT = 400

        /** Slow expressive moments: canvas fade, accent color, chart growth, focus scroll. */
        const val EXPRESSIVE = 500

        /** Programmatic long flights (mini<->full close), thumb dwell. */
        const val EXTENDED = 700
    }

    /** Canonical easing curves. */
    object Easings {
        /** Material standard; also the shared-element curve. */
        val STANDARD = FastOutSlowInEasing

        /** Symmetric in-out (marquee travel, backdrop scale, player transition). */
        val IN_OUT = EaseInOut

        /** Front-loaded exits (nav fade-out, leaving art). */
        val EXIT = EaseOut

        /** Decelerating fling tails. */
        val DECELERATE = LinearOutSlowInEasing
    }

    /** Canonical spring configs. */
    object Springs {
        /** Modal sheet drag settle. */
        val SHEET_SETTLE = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )

        /** Control-position settles (transport cluster). */
        val CONTROL = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )

        /** Smooth list auto-scrolls and scale pops. */
        val LIST_SCROLL = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        )

        /** List scroll-seeks (a touch of bounce reads as intent). */
        val LIST_SEEK = spring<Float>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium,
        )

        /** Active-line pop-in. */
        val POP = spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        )
    }

    /** Composite tween specs shared across 3+ call sites (immutable, safe to share). */
    object Spec {
        /**
         * Shared-element artwork flight. Same duration/easing as the screen fades
         * so background fades and artwork flights finish on the same frame —
         * see [Duration.FLIGHT].
         */
        val sharedElementFlight = tween<Float>(Duration.FLIGHT, easing = Easings.STANDARD)

        /** Fade-in of screen-level nav transitions. */
        val screenFadeIn = tween<Float>(Duration.FLIGHT)

        /** Fade-out of screen-level nav transitions — front-loaded. */
        val screenFadeOut = tween<Float>(Duration.SWIFT, easing = Easings.EXIT)

        /** Bottom-sheet modal scrim fade-in. */
        val sheetFadeIn = tween<Float>(Duration.STANDARD)

        /** Bottom-sheet modal scrim fade-out. */
        val sheetFadeOut = tween<Float>(Duration.SWIFT)

        /** Bottom-sheet modal rise. */
        val sheetRiseIn = tween<Float>(Duration.SMOOTH)

        /** Bottom-sheet modal fall. */
        val sheetRiseOut = tween<Float>(Duration.STANDARD)

        /** Onboarding pager slide. */
        val onboardingPageSlide = tween<Float>(Duration.SMOOTH, easing = Easings.STANDARD)
    }
}
