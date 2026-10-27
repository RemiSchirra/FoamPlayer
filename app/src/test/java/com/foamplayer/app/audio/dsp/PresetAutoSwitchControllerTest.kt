package com.foamplayer.app.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the preset auto-switch transition rules
 * ([PresetAutoSwitchController]): a track change that resolves the same
 * assignment must re-assert it after mid-track drift (the
 * "stays on the old preset" bug), a hand-pick re-emission must never
 * overwrite the user's freshest intent, and the revert must only fire on an
 * actual transition.
 */
class PresetAutoSwitchControllerTest {

    private val controller = PresetAutoSwitchController()

    private fun feed(
        triggerKey: String?,
        resolved: String?,
        reflectedName: String?
    ): PresetAutoSwitchController.Action =
        controller.onResolved(triggerKey, resolved, reflectedName)

    @Test
    fun `startup does nothing when nothing is assigned`() {
        assertEquals(
            PresetAutoSwitchController.Action.NONE,
            feed("42/Album A", null, "Bass Boost")
        )
    }

    @Test
    fun `first assignment resolves and applies`() {
        assertEquals(
            PresetAutoSwitchController.Action.APPLY_ASSIGNED,
            feed("42/Album A", "Bass Boost", "Flat")
        )
    }

    @Test
    fun `already reflected resolution is churn-free`() {
        // First resolution applies.
        assertEquals(
            PresetAutoSwitchController.Action.APPLY_ASSIGNED,
            feed("42/Album A", "Bass Boost", "Flat")
        )
        // Same trigger + same resolution re-emission (e.g. the hand-pick being
        // recorded) is protected: the state is the user's freshest intent.
        assertEquals(
            PresetAutoSwitchController.Action.NONE,
            feed("42/Album A", "Bass Boost", "Flat")
        )
        // Once the apply landed and the state reflects the preset, a
        // re-emission is a no-op: track changes stay churn-free.
        assertEquals(
            PresetAutoSwitchController.Action.NONE,
            feed("42/Album A", "Bass Boost", "Bass Boost")
        )
    }

    @Test
    fun `drifted state is re-asserted on the next track of the same album`() {
        feed("1/Album A", "Bass Boost", "Bass Boost")
        // Mid-track drift: the user picked another preset by hand (and the
        // hand-pick re-emission is a NONE — see the next test).
        feed("1/Album A", "Bass Boost", "Night")
        // Next track of the same album: same resolution, same reflected
        // preset — the old dedup suppressed this and the bug surfaced.
        assertEquals(
            PresetAutoSwitchController.Action.APPLY_ASSIGNED,
            feed("2/Album A", "Bass Boost", "Night")
        )
    }

    @Test
    fun `drift to Custom is re-asserted on the next track`() {
        feed("1/Album A", "Bass Boost", "Bass Boost")
        // A knob tweak dropped the chain to Custom.
        feed("1/Album A", "Bass Boost", null)
        assertEquals(
            PresetAutoSwitchController.Action.APPLY_ASSIGNED,
            feed("2/Album A", "Bass Boost", null)
        )
    }

    @Test
    fun `same trigger and resolution never overwrites a hand-pick`() {
        feed("1/Album A", "Bass Boost", "Bass Boost")
        // The user hand-picks "Night": assignments re-emit with the same
        // resolution and trigger. The chain state (Night) is the user's
        // freshest intent — applying the assignment would fight the user.
        assertEquals(
            PresetAutoSwitchController.Action.NONE,
            feed("1/Album A", "Bass Boost", "Night")
        )
        // A knob tweak on the same trigger likewise stays untouched.
        assertEquals(
            PresetAutoSwitchController.Action.NONE,
            feed("1/Album A", "Bass Boost", null)
        )
    }

    @Test
    fun `same resolution on a device change applies the new sound`() {
        feed("1/Album A|speaker", "Bass Boost", "Bass Boost")
        // Unassigned resolution for the new device kind, new trigger.
        assertEquals(
            PresetAutoSwitchController.Action.REVERT_TO_MANUAL,
            feed("1/Album A|bluetooth", null, "Bass Boost")
        )
    }

    @Test
    fun `unassigned track after an assigned one reverts to manual`() {
        feed("1/Album A", "Bass Boost", "Bass Boost")
        assertEquals(
            PresetAutoSwitchController.Action.REVERT_TO_MANUAL,
            feed("2/Album B", null, "Bass Boost")
        )
    }

    @Test
    fun `revert does not fire when nothing resolved before`() {
        // Startup with no assignment, then another unassigned trigger.
        feed("1/Album A", null, "Flat")
        assertEquals(
            PresetAutoSwitchController.Action.NONE,
            feed("2/Album B", null, "Flat")
        )
    }

    @Test
    fun `null trigger (playback stopped) is a real transition`() {
        feed("1/Album A", "Bass Boost", "Bass Boost")
        // Queue cleared: the trigger changes, nothing resolves.
        assertEquals(
            PresetAutoSwitchController.Action.REVERT_TO_MANUAL,
            feed(null, null, "Bass Boost")
        )
    }
}
