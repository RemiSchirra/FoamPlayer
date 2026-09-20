package com.foamplayer.app.audio.fidelity

import androidx.compose.runtime.Immutable

/** Outcome of one fidelity criterion. */
enum class FidelityStatus {
    /** The best achievable behavior on this path. */
    PASS,

    /** Acceptable but not lossless-exact (e.g. 32-bit float DSP engaged). */
    WARN,

    /** Something is actively degrading the signal (SRC, quantization...). */
    FAIL
}

/** One scored criterion of the audio fidelity report. */
@Immutable
data class FidelityItem(
    val label: String,
    val status: FidelityStatus,
    val detail: String,
    val points: Int,
    val maxPoints: Int
)

/**
 * The audio fidelity score: a 0..100 verdict over the whole playback path (source,
 * resampling, bit depth, DSP, volume path, output device). Computed per track + per
 * output device + per DSP state, and displayed with its itemized breakdown.
 */
@Immutable
data class FidelityScore(
    val total: Int,
    val max: Int,
    val label: String,
    val items: List<FidelityItem>
) {
    val fraction: Float get() = if (max > 0) total.toFloat() / max else 0f

    companion object {
        const val MAX_SCORE = 100

        fun labelFor(total: Int): String = when {
            total >= 95 -> "Bit-Perfect"
            total >= 85 -> "Reference"
            total >= 70 -> "Excellent"
            total >= 55 -> "High fidelity"
            total >= 40 -> "Standard"
            else -> "Degraded"
        }
    }
}
