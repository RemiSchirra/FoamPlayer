package com.foamplayer.app.audio.dsp

/**
 * The stateful decision of the preset auto-switcher, extracted so the
 * transition rules are unit-testable without a live engine.
 *
 * The observer feeds every resolution into [onResolved] — what triggered it
 * (`triggerKey`: the playing track and the routing device), the preset the
 * assignment resolves to (`resolved`, or null when nothing is assigned) and
 * the preset the chain state currently reflects (`reflectedName`) — and acts
 * on the returned [Action].
 *
 * The rules, in order:
 *  1. The state already reflects the resolved preset: do nothing. This makes
 *     track changes that resolve a still-active assignment churn-free.
 *  2. Same trigger and same resolution: the emission was caused by the
 *     assignments themselves changing — a hand-picked preset being recorded
 *     or a modal toggle. The current chain state is the user's freshest
 *     intent, so manual EQ edits while an assignment is active are never
 *     overwritten.
 *  3. A new trigger or resolution with a preset: apply it. This is what
 *     re-asserts the assignment after mid-track drift (a manual pick or a
 *     knob tweak that dropped to Custom) once the next track starts.
 *  4. Nothing resolved on an actual transition: leave the assigned sound —
 *     DspEngine falls back to the last manual pick, or to the chain state
 *     captured before the assignment took over. Never at startup, so the
 *     state restored from persistence is not overwritten.
 */
class PresetAutoSwitchController {

    /** What the observer should do with a resolution. */
    enum class Action { APPLY_ASSIGNED, REVERT_TO_MANUAL, NONE }

    private var lastTriggerKey: String? = null
    private var lastResolved: String? = null

    fun onResolved(triggerKey: String?, resolved: String?, reflectedName: String?): Action {
        val previousResolved = lastResolved
        val triggerChanged = triggerKey != lastTriggerKey
        lastTriggerKey = triggerKey
        lastResolved = resolved
        return when {
            (resolved != null && reflectedName == resolved) -> Action.NONE
            (resolved == previousResolved && !triggerChanged) -> Action.NONE
            resolved != null -> Action.APPLY_ASSIGNED
            previousResolved != null -> Action.REVERT_TO_MANUAL
            else -> Action.NONE
        }
    }
}
