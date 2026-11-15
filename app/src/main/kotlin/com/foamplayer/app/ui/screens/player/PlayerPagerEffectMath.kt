package com.foamplayer.app.ui.screens.player

import androidx.compose.ui.unit.Dp
import com.foamplayer.app.data.settings.PlayerPagerEffect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Geometry for the full player's [PlayerPagerEffect]s.
 *
 * The pager keeps stock 1:1 finger tracking; an effect only shortens the slot
 * stride so one page advance takes its [PlayerPagerEffectSpec.travelFraction] of
 * the finger travel. Pages overlap, and the spec's scale, opacity and rotation
 * are layered on top.
 */
internal object PlayerPagerEffectMath {
    /** Perspective softening for the 3D effects. */
    const val CAMERA_DISTANCE_DENSITY_FACTOR = 16f

    /** Signed page offset in strides: 0 is centered, +1 is one page right. */
    fun pageOffset(page: Int, currentPage: Int, offsetFraction: Float): Float =
        page - currentPage - offsetFraction

    /** 1 at the center, 0 one full page away; clamped during overscroll. */
    fun visibility(offset: Float): Float = 1f - abs(offset).coerceIn(0f, 1f)

    /**
     * Gap between pager slots. Shrinking the stride to the effect's travel keeps
     * the pager's 1:1 tracking while pages overlap (negative spacing).
     * [slideSpacing] is returned unchanged at 100%.
     */
    fun pageSpacing(pageWidth: Dp, slideSpacing: Dp, effect: PlayerPagerEffect): Dp {
        val fullStride = pageWidth + slideSpacing
        return fullStride * effect.spec.travelFraction - pageWidth
    }

    /**
     * Page to settle on after a release at [absoluteOffset] (`currentPage +
     * currentPageOffsetFraction`). A slow release takes the nearest page, a fling
     * exactly one page in its direction.
     *
     * The stock pager divides the finger delta by the full page width, which
     * misreads the shortened stride and overshoots; this works in pages.
     */
    fun flingTargetPage(
        absoluteOffset: Float,
        velocity: Float,
        minFlingVelocity: Float,
        pageCount: Int
    ): Int {
        if (pageCount <= 0) return 0
        val target = if (abs(velocity) >= minFlingVelocity) {
            if (velocity > 0f) {
                floor(absoluteOffset).toInt() + 1
            } else {
                ceil(absoluteOffset).toInt() - 1
            }
        } else {
            absoluteOffset.roundToInt()
        }
        return target.coerceIn(0, pageCount - 1)
    }

    /** Layer values for a page [offset] strides away from the centered one. */
    fun layer(effect: PlayerPagerEffect, offset: Float): PlayerPagerLayer {
        val spec = effect.spec
        val progress = 1f - visibility(offset)
        return PlayerPagerLayer(
            alpha = 1f + (spec.targetOpacity - 1f) * progress,
            scale = 1f + (spec.targetScale - 1f) * progress,
            rotationY = spec.targetRotationY * progress * side(offset),
            rotationX = spec.targetRotationX * progress
        )
    }

    /** Perspective for the 3D effects; 0 for the flat ones. */
    fun cameraDistance(effect: PlayerPagerEffect, density: Float): Float =
        if (effect.spec.is3d) CAMERA_DISTANCE_DENSITY_FACTOR * density else 0f

    /** +1 right of center, -1 left of it, 0 at center. */
    private fun side(offset: Float): Float = when {
        offset > 0f -> 1f
        offset < 0f -> -1f
        else -> 0f
    }
}

/** Alpha, scale and rotation applied to one pager page. */
internal data class PlayerPagerLayer(
    val alpha: Float,
    val scale: Float,
    val rotationY: Float,
    val rotationX: Float
)
