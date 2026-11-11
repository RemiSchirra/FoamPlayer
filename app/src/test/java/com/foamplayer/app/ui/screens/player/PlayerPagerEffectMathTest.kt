package com.foamplayer.app.ui.screens.player

import androidx.compose.ui.unit.dp
import com.foamplayer.app.data.settings.PlayerPagerEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure geometry behind the full player's track-pager effects: travel
 * fractions, stride, fling target, and the layer values derived from each spec.
 */
class PlayerPagerEffectMathTest {

    private val offsets = listOf(-1f, -0.5f, 0f, 0.5f, 1f)
    private val nonSlideEffects = PlayerPagerEffect.entries
        .filter { it != PlayerPagerEffect.SLIDE }

    // --------------------------------------------------------------- page offsets

    @Test
    fun `page offset is continuous across the current page`() {
        assertEquals(1.25f, PlayerPagerEffectMath.pageOffset(4, 3, -0.25f), 1e-6f)
        assertEquals(0.25f, PlayerPagerEffectMath.pageOffset(3, 3, -0.25f), 1e-6f)
        assertEquals(-0.75f, PlayerPagerEffectMath.pageOffset(3, 3, 0.75f), 1e-6f)
    }

    @Test
    fun `visibility fades from centre to one page away and clamps beyond`() {
        assertEquals(1f, PlayerPagerEffectMath.visibility(0f), 1e-6f)
        assertEquals(0.5f, PlayerPagerEffectMath.visibility(0.5f), 1e-6f)
        assertEquals(0.5f, PlayerPagerEffectMath.visibility(-0.5f), 1e-6f)
        assertEquals(0f, PlayerPagerEffectMath.visibility(1f), 1e-6f)
        assertEquals(0f, PlayerPagerEffectMath.visibility(-1f), 1e-6f)
        assertEquals(0f, PlayerPagerEffectMath.visibility(1.4f), 1e-6f)
        assertEquals(0f, PlayerPagerEffectMath.visibility(-2f), 1e-6f)
    }

    // ------------------------------------------------------------------ effect specs

    private fun assertSpec(
        effect: PlayerPagerEffect,
        travel: Float,
        scale: Float = 1f,
        opacity: Float = 1f,
        rotationY: Float = 0f,
        rotationX: Float = 0f
    ) {
        val spec = effect.spec
        assertEquals("$effect travel", travel, spec.travelFraction, 1e-6f)
        assertEquals("$effect scale", scale, spec.targetScale, 1e-6f)
        assertEquals("$effect opacity", opacity, spec.targetOpacity, 1e-6f)
        assertEquals("$effect rotationY", rotationY, spec.targetRotationY, 1e-6f)
        assertEquals("$effect rotationX", rotationX, spec.targetRotationX, 1e-6f)
        assertEquals("$effect 3d", rotationY != 0f || rotationX != 0f, spec.is3d)
    }

    @Test
    fun `effect specs match the product spec`() {
        assertSpec(PlayerPagerEffect.SLIDE, travel = 1f)
        assertSpec(PlayerPagerEffect.CROSSFADE, travel = 0.5f, opacity = 0f)
        assertSpec(PlayerPagerEffect.DEPTH, travel = 0.5f, scale = 0.88f, opacity = 0f)
        assertSpec(PlayerPagerEffect.FLIP, travel = 0.6f, opacity = 0f, rotationY = 90f)
        assertSpec(
            PlayerPagerEffect.TILT,
            travel = 0.8f,
            opacity = 0f,
            rotationY = 20f,
            rotationX = 10f
        )
    }

    @Test
    fun `only flip and tilt are 3d`() {
        for (effect in nonSlideEffects) {
            assertEquals(
                "$effect",
                effect == PlayerPagerEffect.FLIP || effect == PlayerPagerEffect.TILT,
                effect.spec.is3d
            )
        }
        assertFalse(PlayerPagerEffect.SLIDE.spec.is3d)
    }

    // ----------------------------------------------------------------- page stride

    @Test
    fun `slide keeps the stock page spacing unchanged`() {
        val pageWidth = 320.dp
        val slideSpacing = 8.dp
        val spacing = PlayerPagerEffectMath.pageSpacing(pageWidth, slideSpacing, PlayerPagerEffect.SLIDE)
        assertEquals(slideSpacing.value, spacing.value, 1e-3f)
    }

    @Test
    fun `page stride is the configured fraction of the slide stride`() {
        val pageWidth = 320f
        val slideSpacing = 8f
        val slideStride = pageWidth + slideSpacing
        for (effect in PlayerPagerEffect.entries) {
            val spacing = PlayerPagerEffectMath.pageSpacing(pageWidth.dp, slideSpacing.dp, effect)
            val stride = pageWidth + spacing.value
            assertEquals(
                "$effect stride",
                slideStride * effect.spec.travelFraction,
                stride,
                1e-3f
            )
        }
    }

    @Test
    fun `reduced effects overlap their neighbours`() {
        val pageWidth = 320.dp
        val slideSpacing = 8.dp
        for (effect in nonSlideEffects) {
            val spacing = PlayerPagerEffectMath.pageSpacing(pageWidth, slideSpacing, effect)
            assertTrue("$effect should overlap, got $spacing", spacing < 0.dp)
        }
    }

    @Test
    fun `page spacing scales linearly with the travel fraction`() {
        val pageWidth = 400.dp
        val slideSpacing = 0.dp
        // 50% -> half a stride between page centers.
        assertEquals(
            -200f,
            PlayerPagerEffectMath.pageSpacing(
                pageWidth,
                slideSpacing,
                PlayerPagerEffect.CROSSFADE
            ).value,
            1e-3f
        )
        // 80% -> 320dp slot.
        assertEquals(
            -80f,
            PlayerPagerEffectMath.pageSpacing(
                pageWidth,
                slideSpacing,
                PlayerPagerEffect.TILT
            ).value,
            1e-3f
        )
    }

    // ------------------------------------------------------------------ fling target

    private fun target(
        absoluteOffset: Float,
        velocity: Float = 0f,
        minFlingVelocity: Float = 1000f,
        pageCount: Int = 10
    ) = PlayerPagerEffectMath.flingTargetPage(absoluteOffset, velocity, minFlingVelocity, pageCount)

    @Test
    fun `slow release settles on the nearest page`() {
        assertEquals(0, target(0.49f))
        assertEquals(1, target(0.5f))
        assertEquals(1, target(0.9f))
        // The reported bug: just past the next page must land on it, not the one after.
        assertEquals(1, target(1.0f))
        assertEquals(1, target(1.1f))
        assertEquals(1, target(1.49f))
        assertEquals(2, target(1.5f))
        assertEquals(2, target(1.9f))
        assertEquals(2, target(2.0f))
        assertEquals(2, target(2.1f))
    }

    @Test
    fun `slow release works for both scroll directions`() {
        // currentPage already flipped, offsetFraction negative: the absolute
        // offset stays continuous, so the nearest page is still correct.
        assertEquals(2, target(1.9f))
        assertEquals(2, target(1.6f))
        assertEquals(2, target(1.5f))
        assertEquals(1, target(1.4f))
        assertEquals(0, target(0.49f))
        assertEquals(0, target(-0.2f))
    }

    @Test
    fun `fast forward fling moves exactly one page ahead`() {
        val fast = 2000f
        assertEquals(1, target(0.1f, fast))
        assertEquals(1, target(0.99f, fast))
        assertEquals(2, target(1.0f, fast))
        assertEquals(2, target(1.1f, fast))
        assertEquals(2, target(1.9f, fast))
    }

    @Test
    fun `fast backward fling moves exactly one page back`() {
        val fast = -2000f
        assertEquals(0, target(0.1f, fast))
        assertEquals(0, target(0.99f, fast))
        assertEquals(0, target(1.0f, fast))
        assertEquals(1, target(1.1f, fast))
        assertEquals(1, target(1.9f, fast))
        assertEquals(1, target(2.0f, fast))
    }

    @Test
    fun `fling target is clamped to the queue`() {
        assertEquals(0, target(-0.3f, velocity = 0f))
        assertEquals(0, target(-0.3f, velocity = -2000f))
        assertEquals(0, target(0.1f, velocity = -2000f))
        assertEquals(9, target(9.4f, pageCount = 10))
        assertEquals(9, target(9.9f, velocity = 2000f, pageCount = 10))
        assertEquals(0, target(1.2f, pageCount = 0))
    }

    @Test
    fun `velocity threshold decides between nearest page and fling`() {
        val min = 1000f
        // Just under the threshold: nearest page.
        assertEquals(1, target(1.1f, velocity = min - 1f, minFlingVelocity = min))
        assertEquals(1, target(1.1f, velocity = -(min - 1f), minFlingVelocity = min))
        // At or above the threshold: one page in the fling direction.
        assertEquals(2, target(1.1f, velocity = min, minFlingVelocity = min))
        assertEquals(1, target(1.1f, velocity = -min, minFlingVelocity = min))
    }

    // ------------------------------------------------------------------- SLIDE identity

    @Test
    fun `slide keeps the stock pager untouched at every offset`() {
        for (offset in offsets) {
            val layer = PlayerPagerEffectMath.layer(PlayerPagerEffect.SLIDE, offset)
            assertEquals(1f, layer.alpha, 1e-6f)
            assertEquals(1f, layer.scale, 1e-6f)
            assertEquals(0f, layer.rotationY, 1e-6f)
            assertEquals(0f, layer.rotationX, 1e-6f)
            assertEquals(0f, PlayerPagerEffectMath.cameraDistance(PlayerPagerEffect.SLIDE, 3f), 1e-6f)
        }
    }

    // --------------------------------------------------------------------- crossfade

    @Test
    fun `every non-slide effect crossfades with abs offset`() {
        for (effect in nonSlideEffects) {
            for (offset in offsets) {
                assertEquals(
                    "alpha of $effect at $offset",
                    1f - kotlin.math.abs(offset),
                    PlayerPagerEffectMath.layer(effect, offset).alpha,
                    1e-6f
                )
            }
        }
    }

    // ------------------------------------------------------------------- DEPTH scale

    @Test
    fun `depth scales pages between its target and 1`() {
        val min = PlayerPagerEffect.DEPTH.spec.targetScale
        assertEquals(min, PlayerPagerEffectMath.layer(PlayerPagerEffect.DEPTH, 1f).scale, 1e-6f)
        assertEquals(1f, PlayerPagerEffectMath.layer(PlayerPagerEffect.DEPTH, 0f).scale, 1e-6f)
        assertEquals(
            0.94f,
            PlayerPagerEffectMath.layer(PlayerPagerEffect.DEPTH, 0.5f).scale,
            1e-6f
        )
    }

    @Test
    fun `only depth scales`() {
        for (effect in PlayerPagerEffect.entries.filter { it != PlayerPagerEffect.DEPTH }) {
            assertEquals(1f, PlayerPagerEffectMath.layer(effect, 0f).scale, 1e-6f)
            assertEquals(1f, PlayerPagerEffectMath.layer(effect, 1f).scale, 1e-6f)
        }
    }

    // -------------------------------------------------------------------- 3D effects

    @Test
    fun `flip reaches 90 degrees toward the centre and mirrors per side`() {
        assertEquals(90f, PlayerPagerEffectMath.layer(PlayerPagerEffect.FLIP, 1f).rotationY, 1e-6f)
        assertEquals(-90f, PlayerPagerEffectMath.layer(PlayerPagerEffect.FLIP, -1f).rotationY, 1e-6f)
        assertEquals(45f, PlayerPagerEffectMath.layer(PlayerPagerEffect.FLIP, 0.5f).rotationY, 1e-6f)
        assertEquals(0f, PlayerPagerEffectMath.layer(PlayerPagerEffect.FLIP, 0f).rotationY, 1e-6f)
    }

    @Test
    fun `tilt yaw is 20 degrees and pitch is 10 degrees at full separation`() {
        assertEquals(20f, PlayerPagerEffectMath.layer(PlayerPagerEffect.TILT, 1f).rotationY, 1e-6f)
        assertEquals(-20f, PlayerPagerEffectMath.layer(PlayerPagerEffect.TILT, -1f).rotationY, 1e-6f)
        assertEquals(10f, PlayerPagerEffectMath.layer(PlayerPagerEffect.TILT, 1f).rotationX, 1e-6f)
        assertEquals(0f, PlayerPagerEffectMath.layer(PlayerPagerEffect.TILT, 0f).rotationX, 1e-6f)
    }

    @Test
    fun `flat effects never rotate`() {
        for (effect in listOf(
            PlayerPagerEffect.SLIDE,
            PlayerPagerEffect.CROSSFADE,
            PlayerPagerEffect.DEPTH
        )) {
            for (offset in offsets) {
                val layer = PlayerPagerEffectMath.layer(effect, offset)
                assertEquals("$effect rotationY at $offset", 0f, layer.rotationY, 1e-6f)
                assertEquals("$effect rotationX at $offset", 0f, layer.rotationX, 1e-6f)
            }
        }
    }

    @Test
    fun `camera distance is set for 3D effects only`() {
        for (effect in listOf(PlayerPagerEffect.FLIP, PlayerPagerEffect.TILT)) {
            assertEquals(
                PlayerPagerEffectMath.CAMERA_DISTANCE_DENSITY_FACTOR * 3f,
                PlayerPagerEffectMath.cameraDistance(effect, 3f),
                1e-6f
            )
        }
        for (effect in listOf(
            PlayerPagerEffect.SLIDE,
            PlayerPagerEffect.CROSSFADE,
            PlayerPagerEffect.DEPTH
        )) {
            assertEquals(0f, PlayerPagerEffectMath.cameraDistance(effect, 3f), 1e-6f)
        }
    }

    // ----------------------------------------------------------------- rest identity

    @Test
    fun `every effect is the identity on the centred page`() {
        for (effect in PlayerPagerEffect.entries) {
            val layer = PlayerPagerEffectMath.layer(effect, 0f)
            assertEquals("$effect alpha", 1f, layer.alpha, 1e-6f)
            assertEquals("$effect scale", 1f, layer.scale, 1e-6f)
            assertEquals("$effect rotationY", 0f, layer.rotationY, 1e-6f)
            assertEquals("$effect rotationX", 0f, layer.rotationX, 1e-6f)
        }
    }
}
