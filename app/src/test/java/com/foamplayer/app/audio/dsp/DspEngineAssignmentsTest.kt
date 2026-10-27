package com.foamplayer.app.audio.dsp

import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.settings.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Regression tests for the preset auto-switch primitives on [DspEngine]:
 * the auto path must apply without recording a manual choice, the revert
 * must restore the chain state an assignment displaced (the baseline) —
 * falling back to the last manual pick only when no baseline survives —
 * and a deleted preset must never resolve.
 */
class DspEngineAssignmentsTest {

    private lateinit var engine: DspEngine

    @Before
    fun setUp() {
        val settingsRepository = mock<SettingsRepository> {
            on { settings } doReturn kotlinx.coroutines.flow.MutableStateFlow(AppSettings())
        }
        engine = DspEngine(settingsRepository)
    }

    private fun audioEngineState(): DspState = engine.state.value

    @Test
    fun `applyAssignedPreset applies a built-in without recording it as manual`() = runTest {
        engine.setTrackAssignment(42L, "Bass Boost")

        assertTrue(engine.applyAssignedPreset("Bass Boost"))

        assertTrue(audioEngineState().enabled)
        assertTrue(audioEngineState().graphicEq.enabled)
        // The auto path must not record the applied preset as a manual choice.
        assertNull(engine.assignments.value.lastManualPresetName)
    }

    @Test
    fun `applyAssignedPreset with an unknown preset returns false and keeps state`() = runTest {
        val before = audioEngineState()

        assertFalse(engine.applyAssignedPreset("No Such Preset"))

        assertEquals(before, audioEngineState())
    }

    @Test
    fun `revertFromAssignment applies the preset the user picked by hand`() = runTest {
        engine.applyPreset(enginePreview("Bass Boost"))
        assertEquals("Bass Boost", engine.assignments.value.lastManualPresetName)

        // Simulate an assignment-driven apply of a different sound.
        assertTrue(engine.applyAssignedPreset("Flat"))
        assertNull(engine.assignments.value.lastManualPresetName.takeIf { it == "Flat" })

        // Moving to an unassigned track: revert to the last manual pick.
        assertTrue(engine.revertFromAssignment())
        assertEquals("Bass Boost", engine.assignments.value.lastManualPresetName)
        assertTrue(engine.statePresetMatches("Bass Boost"))
    }

    @Test
    fun `revertFromAssignment restores the displaced default EQ without a manual pick`() = runTest {
        // The user never hand-picked a preset: the chain is on the defaults.
        val defaultState = audioEngineState()
        assertFalse(defaultState.enabled)

        // A playlist-assigned track takes over the sound.
        engine.setTrackAssignment(7L, "Bass Boost")
        assertTrue(engine.applyAssignedPreset("Bass Boost"))
        assertTrue(audioEngineState().enabled)

        // Back to a default (unassigned) track: the displaced default EQ is
        // restored even though no manual preset was ever picked.
        assertTrue(engine.revertFromAssignment())
        assertEquals(
            defaultState.copy(volumePercent = audioEngineState().volumePercent),
            audioEngineState()
        )
    }

    @Test
    fun `revertFromAssignment without a manual pick or baseline is a no-op`() = runTest {
        // No assignment-driven apply ever happened: nothing to revert to.
        assertFalse(engine.revertFromAssignment())
    }

    @Test
    fun `chained assigned tracks keep the first displaced baseline`() = runTest {
        val defaultState = audioEngineState()

        // Two assigned tracks in a row: the baseline must stay the default EQ,
        // not the first assigned preset.
        engine.setTrackAssignment(1L, "Bass Boost")
        assertTrue(engine.applyAssignedPreset("Bass Boost"))
        engine.setTrackAssignment(2L, "Flat")
        assertTrue(engine.applyAssignedPreset("Flat"))

        assertTrue(engine.revertFromAssignment())
        assertEquals(
            defaultState.copy(volumePercent = audioEngineState().volumePercent),
            audioEngineState()
        )
    }

    @Test
    fun `a hand-picked preset supersedes the pre-assignment baseline`() = runTest {
        engine.applyPreset(enginePreview("Rock"))

        // Assignment applies (a baseline is captured, but the manual pick wins).
        engine.setTrackAssignment(7L, "Bass Boost")
        assertTrue(engine.applyAssignedPreset("Bass Boost"))

        // The user hand-picks another preset while assigned: the revert must
        // target the manual pick, not the captured baseline.
        engine.applyPreset(enginePreview("Flat"))

        assertTrue(engine.revertFromAssignment())
        assertTrue(engine.statePresetMatches("Flat"))
    }

    @Test
    fun `deleted preset never resolves and its assignments are dropped`() = runTest {
        engine.saveUserPreset("Night")
        engine.setTrackAssignment(7L, "Night")

        engine.deleteUserPreset("Night")

        assertFalse(engine.applyAssignedPreset("Night"))
        assertNull(engine.assignments.value.trackPresets[7L])
    }

    @Test
    fun `applyAssignedPreset skips the churn when state already matches`() = runTest {
        engine.applyPreset(enginePreview("Flat"))
        val matched = audioEngineState()

        assertTrue(engine.applyAssignedPreset("Flat"))

        assertEquals(matched, audioEngineState())
    }

    @Test
    fun `stale manual pick naming the assigned preset does not block the revert`() = runTest {
        // A hand-pick from an earlier session persists in the assignments and
        // names the same preset an assignment is about to apply.
        engine.applyPreset(enginePreview("Bass Boost"))
        assertEquals("Bass Boost", engine.assignments.value.lastManualPresetName)
        // Reset the chain without a hand-pick (knob-level setters only): the
        // pre-assignment sound the assignment is about to displace.
        engine.setEnabled(false)
        engine.setGraphicEnabled(false)
        engine.setBassDb(0f)
        val preAssignment = audioEngineState()

        engine.setTrackAssignment(7L, "Bass Boost")
        assertTrue(engine.applyAssignedPreset("Bass Boost"))
        assertTrue(audioEngineState().enabled)

        // Moving to an unassigned track must restore the displaced sound,
        // even though the persisted manual pick names the assigned preset.
        assertTrue(engine.revertFromAssignment())
        assertEquals(
            preAssignment.copy(volumePercent = audioEngineState().volumePercent),
            audioEngineState()
        )
        assertEquals("Bass Boost", engine.assignments.value.lastManualPresetName)
    }

    /** Applies a built-in through the manual path so the revert target is set. */
    private fun enginePreview(name: String): DspPreset =
        DspPreset.BUILT_INS.firstOrNull { it.name == name }
            ?: error("Missing built-in preset $name")

    private fun DspEngine.statePresetMatches(name: String): Boolean =
        (DspPreset.BUILT_INS.firstOrNull { it.name == name } ?: userPresets.value
            .firstOrNull { it.name == name })?.matchesState(state.value) == true
}
