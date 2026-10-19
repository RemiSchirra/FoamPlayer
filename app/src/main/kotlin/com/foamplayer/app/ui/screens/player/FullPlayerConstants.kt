package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.ui.unit.dp

/** Shared layout constants so the header, pager and hero overlay always agree on geometry. */
internal object PlayerDimens {
    val horizontalPagePadding = 24.dp
    val pageSpacing = 24.dp

    /** Drop shadow elevation behind the full player's art while immersive mode is off. */
    val fullPlayerArtElevation = 8.dp

    // Bottom control cluster in the subview (selector row + mini play button + seek bar).
    val selectorRowRise = 12.dp

    /**
     * The rise the bottom cluster's layout height (selector row slot) was originally sized
     * for. The selector row is moved by translation only, so when [selectorRowRise] drops
     * below this, the lyrics/queue content would stop short of the lowered row; it is
     * extended downward by the difference to keep its bottom edge (and fade) hugging the row.
     */
    val selectorRowRiseLayoutBasis = 36.dp
    val subViewContentBottomExtension = (selectorRowRiseLayoutBasis - selectorRowRise).coerceAtLeast(0.dp)
    val seekBarRise = 12.dp

    /** Extra downward nudge of the subview seek bar toward the navbar once the subview is open. */
    val seekBarNavbarDrop = 8.dp

    val subViewSwipeThreshold = 420.dp

    /** Generic 24dp spacing rhythm shared by header, slider row and transport cluster. */
    val screenPadding = 24.dp

    /** Vertical padding around the bottom PlaybackProgressSlider row. */
    val seekBarRowVerticalPadding = 12.dp

    /** Gap between the subview seek bar's timings and the navbar below. */
    val seekBarRowBottomPadding = 0.dp

    /** Gap between the lyrics/queue selector row and the navbar when the subview is closed. */
    val selectorRowBottomPadding = 4.dp

    /**
     * The transport cluster is bottom-aligned in the same box as the selector row, with the
     * same bottom padding, so rising by the same amount keeps its bottom edge level with the
     * selector buttons' bottom edge.
     */
    val playButtonRise = selectorRowRise

    /**
     * Extra lift for the transport cluster so the play button circle's bottom edge lands on
     * the bottom of the lyrics/queue *icons*, not on the bottom of their touch targets. The
     * lyrics icon is 24dp inside a 48dp touch target (12dp padding), so the visible glyph
     * ends 12dp above the selector row's bottom edge. Set to 0.dp to align with the touch
     * targets instead.
     */
    val transportBottomInset = 12.dp

    /**
     * How far the selector row sinks below its layout slot at rest (main player), landing
     * its icons just above the navbar's surface. Unwound as the subview opens to make
     * room for the seek bar, and cancelled by the navbar's extra-row growth (fed in as
     * [FullPlayerScreen.navbarExpandHeight]).
     */
    val selectorRowNavbarDrop = 12.dp

    /** Space between the seek bar and its position/duration labels. */
    val seekBarLabelSpacing = 4.dp

    /** Space between the seek bar and the transport row in PlaybackControls. */
    val controlsSpacing = 48.dp

    /** Extra space under the main playback controls before the screen edge. */
    val controlsBottomSpacer = 48.dp

    /** Header blocks slide by h / headerSlideDivisor during the up-transition. */
    const val headerSlideDivisor = 6

    /** Black scrim strength over the backdrop while the Lyrics/Queue subview is up. */
    const val scrimMaxAlpha = 0.2f

    /** Backdrop blur completes at 1 / subViewBlurRushFactor of the up-drag. */
    const val subViewBlurRushFactor = 2.5f

    /** Faded controls stop being clickable below this alpha. */
    const val visibilityEpsilon = 0.01f

    // Transport / control sizes.
    val playButtonSize = 64.dp
    val playButtonIconSize = 64.dp
    val miniPlayButtonSize = 50.dp
    val miniPlayIconSize = 28.dp
    val miniTransportButtonSize = 44.dp
    val miniTransportIconSize = 26.dp
    val transportIconSize = 44.dp
    val transportSpacing = 12.dp
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

/** Down-swipe velocity (px/s) at which a dismiss gesture commits by flick. */
internal const val DownSwipeCommitVelocityPxPerSec = 800f

/** Fraction of the open/subview-close flight over which the immersive art fades out (hero side). */
internal const val immersiveArtFadeSpan = 0.6f

/** Hero art shrink while playback is paused (also applied to the pager page art beneath it). */
internal const val PausedArtScale = 0.97f