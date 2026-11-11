package com.foamplayer.app.ui.screens.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Stock pager fling threshold: slower releases snap to the nearest page. */
private val MinFlingVelocityDp = 400.dp

/**
 * Release behaviour for the effect-shortened pager.
 *
 * The stock pager's positional snap target is derived from the finger's pixel
 * delta divided by the full page width, which is inconsistent with the shortened
 * stride used by the effects: releasing just past a page then continues to the
 * following one. This behaviour decides from the page offset itself (already in
 * shortened strides), so a slow release always settles on the nearest page.
 *
 * It also publishes [targetPage] as soon as the target is known, letting the
 * player commit the track on release without waiting for the settle animation.
 */
@Composable
internal fun rememberPagerEffectFlingBehavior(
    state: PagerState,
    targetPage: MutableState<Int?>
): TargetedFlingBehavior {
    val density = LocalDensity.current
    return remember(state, density, targetPage) {
        PagerEffectFlingBehavior(
            state = state,
            minFlingVelocity = with(density) { MinFlingVelocityDp.toPx() },
            targetPage = targetPage
        )
    }
}

private class PagerEffectFlingBehavior(
    private val state: PagerState,
    private val minFlingVelocity: Float,
    private val targetPage: MutableState<Int?>
) : TargetedFlingBehavior {

    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit
    ): Float {
        // The slot stride (page + spacing) is the distance one page index covers.
        val stride = (state.layoutInfo.pageSize + state.layoutInfo.pageSpacing).toFloat()
        if (stride <= 0f || state.pageCount == 0) {
            onRemainingDistanceUpdated(0f)
            return 0f
        }

        val absoluteOffset = state.currentPage + state.currentPageOffsetFraction
        val page = PlayerPagerEffectMath.flingTargetPage(
            absoluteOffset = absoluteOffset,
            velocity = initialVelocity,
            minFlingVelocity = minFlingVelocity,
            pageCount = state.pageCount
        )
        targetPage.value = page

        val distancePx = state.getOffsetDistanceInPages(page) * stride
        onRemainingDistanceUpdated(distancePx)
        if (abs(distancePx) < 0.5f) return 0f

        var consumed = 0f
        val low = minOf(0f, distancePx)
        val high = maxOf(0f, distancePx)
        animate(
            initialValue = 0f,
            targetValue = distancePx,
            initialVelocity = initialVelocity,
            animationSpec = spring(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = 1f
            )
        ) { value, _ ->
            // Never scroll away from the start or past the snap position, even if
            // the release velocity briefly opposes the target (stock behavior).
            val clamped = value.coerceIn(low, high)
            val delta = clamped - consumed
            consumed += scrollBy(delta)
            onRemainingDistanceUpdated(distancePx - consumed)
        }
        onRemainingDistanceUpdated(0f)
        return 0f
    }
}
