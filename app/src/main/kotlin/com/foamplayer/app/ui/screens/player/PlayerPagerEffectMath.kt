package com.foamplayer.app.ui.screens.player

import androidx.compose.ui.unit.Dp
import com.foamplayer.app.data.settings.PlayerPagerEffect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure geometry for the full player's [PlayerPagerEffect]s.
 *
 * The pager always keeps its stock, one-to-one finger tracking: a drag of `d`
 * pixels moves the pages by `d` pixels. [PlayerPagerEffect.travelFraction] is
 * applied to the pager's *stride* instead of to the visual translation, so a full
 * page advance takes only that fraction of the finger travel while each page keeps
 * its full width. Neighbouring pages therefore overlap more at rest, which is what
 * gives the crossfade/depth/flip/tilt effects their look.
 *
 * Alpha, scale and rotation are functions of [visibility] (1 at the center, 0 one
 * page away), so they follow the drag continuously and return to the identity on
 * the current page.
 */
internal object PlayerPagerEffectMath {
    /** Scale DEPTH's outgoing/incoming pages reach at a full page of separation. */
    const val DEPTH_MIN_SCALE = 0.88f

    /** Rotation FLIP applies to a page at a full page of separation. */
    const val FLIP_DEGREES = 90f

    /** TILT's outgoing yaw and pitch at a full page of separation. */
    const val TILT_YAW_DEGREES = 20f
    const val TILT_PITCH_DEGREES = 10f

    /** Camera-distance multiplier used by the 3D effects to soften perspective. */
    const val CAMERA_DISTANCE_DENSITY_FACTOR = 16f

    /** Signed page offset in strides: 0 = centered, +1 = one page right. */
    fun pageOffset(page: Int, currentPage: Int, offsetFraction: Float): Float =
        page - currentPage - offsetFraction

    /** 1 at the center, 0 one full page away, clamped during overscroll. */
    fun visibility(offset: Float): Float = 1f - abs(offset).coerceIn(0f, 1f)

    /**
     * Gap to give the pager between page slots.
     *
     * Each page keeps its full [pageWidth]; shrinking the slot stride to
     * [PlayerPagerEffect.travelFraction] of the stock one makes one page advance
     * take that fraction of the finger travel, while the pager itself still moves
     * the content one pixel per finger pixel. The result is negative for effects
     * below 100%, i.e. the pages overlap; the pager supports that explicitly.
     *
     * [slideSpacing] is the gap SLIDE uses, so a 100% effect returns it unchanged.
     */
    fun pageSpacing(pageWidth: Dp, slideSpacing: Dp, effect: PlayerPagerEffect): Dp {
        val fullStride = pageWidth + slideSpacing
        return fullStride * effect.travelFraction - pageWidth
    }

    /**
     * Page the pager should settle on after the finger is released at
     * [absoluteOffset] (in continuous page units: `currentPage +
     * currentPageOffsetFraction`).
     *
     * The stock pager decides a slow release by dividing the finger's pixel delta
     * by the *full* page width. With a shortened stride that ratio is
     * `travelFraction * offset`, so the fractional part wraps at the wrong place
     * and a release just past the next page continues to the one after it. Working
     * in page units instead keeps the threshold where it visually belongs: a slow
     * release lands on the nearest page, and a real fling moves exactly one page
     * in the direction it was thrown.
     *
     * [minFlingVelocity] is in pixels per second, matching
     * `PagerDefaults`' gesture-release velocity.
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

    /** Crossfade alpha, or 1 for the untouched SLIDE. */
    fun alpha(effect: PlayerPagerEffect, visibility: Float): Float =
        if (effect == PlayerPagerEffect.SLIDE) 1f else visibility

    /** Symmetric shrink/grow for DEPTH, 1 everywhere else. */
    fun scale(effect: PlayerPagerEffect, visibility: Float): Float =
        if (effect == PlayerPagerEffect.DEPTH) {
            DEPTH_MIN_SCALE + (1f - DEPTH_MIN_SCALE) * visibility
        } else {
            1f
        }

    /** +1 for a page right of center, -1 for a page left of it, 0 at center. */
    fun side(offset: Float): Float = when {
        offset > 0f -> 1f
        offset < 0f -> -1f
        else -> 0f
    }

    /**
     * Yaw for FLIP/TILT. The inner edge (nearest the screen center) stays closer
     * to the viewer while the outer edge recedes, mirrored per side.
     */
    fun rotationY(effect: PlayerPagerEffect, offset: Float, visibility: Float): Float {
        val degrees = when (effect) {
            PlayerPagerEffect.FLIP -> FLIP_DEGREES
            PlayerPagerEffect.TILT -> TILT_YAW_DEGREES
            else -> return 0f
        }
        return degrees * (1f - visibility) * side(offset)
    }

    /** TILT's upward pitch, 0 for every other effect. */
    fun rotationX(effect: PlayerPagerEffect, visibility: Float): Float =
        if (effect == PlayerPagerEffect.TILT) {
            TILT_PITCH_DEGREES * (1f - visibility)
        } else {
            0f
        }

    /** Perspective softening for FLIP/TILT; unused by the flat effects. */
    fun cameraDistance(effect: PlayerPagerEffect, density: Float): Float =
        if (effect == PlayerPagerEffect.FLIP || effect == PlayerPagerEffect.TILT) {
            CAMERA_DISTANCE_DENSITY_FACTOR * density
        } else {
            0f
        }
}
