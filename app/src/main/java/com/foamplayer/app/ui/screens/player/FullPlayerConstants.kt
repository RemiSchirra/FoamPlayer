package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.ui.unit.dp

/** Shared layout constants so the header, pager and hero overlay always agree on geometry. */
internal object PlayerDimens {
    val horizontalPagePadding = 24.dp
    val pageSpacing = 24.dp

    // Bottom control cluster in the subview (selector row + mini play button + seek bar).
    val selectorRowRise = 36.dp
    val playButtonRise = 28.dp
    val seekBarRise = 12.dp

    val subViewSwipeThreshold = 420.dp

    /** Generic 24dp spacing rhythm shared by header, slider row and transport cluster. */
    val screenPadding = 24.dp

    /** Vertical padding around the bottom PlaybackProgressSlider row. */
    val seekBarRowVerticalPadding = 12.dp

    /** Gap between the subview seek bar's timings and the navbar below. */
    val seekBarRowBottomPadding = 4.dp

    /** Gap between the lyrics/queue selector row and the navbar when the subview is closed. */
    val selectorRowBottomPadding = 4.dp

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

/** Fraction of the open/subview-close flight over which the immersive art fades out (hero side). */
internal const val immersiveArtFadeSpan = 0.6f

/** Hero art shrink while playback is paused (also applied to the pager page art beneath it). */
internal const val PausedArtScale = 0.97f
