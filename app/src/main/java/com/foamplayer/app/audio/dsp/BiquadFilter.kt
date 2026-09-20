package com.foamplayer.app.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filter shape of a single parametric/EQ band. Follows the RBJ audio-EQ-cookbook
 * section names so coefficient math and UI labels stay in sync.
 */
enum class FilterType(val label: String) {
    PEAKING("Peak"),
    LOW_SHELF("Low shelf"),
    HIGH_SHELF("High shelf"),
    LOW_PASS("Low pass"),
    HIGH_PASS("High pass")
}

/**
 * Time-invariant biquad coefficients (direct form I normalized by a0). Pure data:
 * building them from [DspState] is [DspMath]'s job, applying them to samples is the
 * audio processor's job, and evaluating their magnitude response is the UI's job.
 */
data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double
) {
    /** Magnitude of H(e^{jw}) in dB at normalized angular frequency [w] (0..pi). */
    fun magnitudeDb(w: Double): Double {
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)

        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin(2.0 * w))
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin(2.0 * w))

        val numMag = sqrt(numRe * numRe + numIm * numIm)
        val denMag = sqrt(denRe * denRe + denIm * denIm)
        if (denMag == 0.0 || numMag == 0.0) return -120.0

        val linear = numMag / denMag
        return if (linear <= 1e-6) -120.0 else 20.0 * kotlin.math.log10(linear)
    }

    companion object {
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Mutable per-channel state of one biquad (transposed direct form II), plus the
 * single-sample processing step. One instance lives per (filter, channel) pair in
 * the audio processor; the DSP graph itself is rebuilt wholesale when settings change.
 */
class BiquadState(private val coefficients: BiquadCoefficients) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Float): Float {
        val y = coefficients.b0 * x + z1
        z1 = coefficients.b1 * x - coefficients.a1 * y + z2
        z2 = coefficients.b2 * x - coefficients.a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }
}

/**
 * RBJ-cookbook coefficient builders. All frequency-domain arguments are double
 * precision; the sample-rate (fs) must be > 0 and the corner frequency strictly
 * inside (0, fs/2) or the identity filter is returned instead of NaN coefficients.
 */
object BiquadDesigner {

    /** Peaking EQ: [gainDb] boost/cut at [frequencyHz] with quality [q]. */
    fun peaking(sampleRate: Double, frequencyHz: Double, gainDb: Double, q: Double): BiquadCoefficients {
        if (!valid(sampleRate, frequencyHz) || q <= 0.0 || gainDb == 0.0) return BiquadCoefficients.IDENTITY
        val a = 10.0.pow(gainDb / 40.0) // amplitude^0.5
        val w0 = 2.0 * PI * frequencyHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW0
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha / a
        return normalize(b0, b1, b2, a0, a1, a2)
    }

    /** Low shelf: boosts/cuts everything below [frequencyHz] by [gainDb]. */
    fun lowShelf(sampleRate: Double, frequencyHz: Double, gainDb: Double, slope: Double = 0.9): BiquadCoefficients {
        if (!valid(sampleRate, frequencyHz) || gainDb == 0.0) return BiquadCoefficients.IDENTITY
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * frequencyHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
        val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
        val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
        val a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
        return normalize(b0, b1, b2, a0, a1, a2)
    }

    /** High shelf: boosts/cuts everything above [frequencyHz] by [gainDb]. */
    fun highShelf(sampleRate: Double, frequencyHz: Double, gainDb: Double, slope: Double = 0.9): BiquadCoefficients {
        if (!valid(sampleRate, frequencyHz) || gainDb == 0.0) return BiquadCoefficients.IDENTITY
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * frequencyHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
        val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
        val b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
        val a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
        val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
        val a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
        return normalize(b0, b1, b2, a0, a1, a2)
    }

    /** Second-order low pass at [frequencyHz] with quality [q]. */
    fun lowPass(sampleRate: Double, frequencyHz: Double, q: Double): BiquadCoefficients {
        if (!valid(sampleRate, frequencyHz) || q <= 0.0) return BiquadCoefficients.IDENTITY
        val w0 = 2.0 * PI * frequencyHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val b0 = (1.0 - cosW0) / 2.0
        val b1 = 1.0 - cosW0
        val b2 = (1.0 - cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        return normalize(b0, b1, b2, a0, a1, a2)
    }

    /** Second-order high pass at [frequencyHz] with quality [q]. */
    fun highPass(sampleRate: Double, frequencyHz: Double, q: Double): BiquadCoefficients {
        if (!valid(sampleRate, frequencyHz) || q <= 0.0) return BiquadCoefficients.IDENTITY
        val w0 = 2.0 * PI * frequencyHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = (1.0 + cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        return normalize(b0, b1, b2, a0, a1, a2)
    }

    private fun valid(sampleRate: Double, frequencyHz: Double): Boolean =
        sampleRate > 0.0 && frequencyHz > 0.0 && frequencyHz < sampleRate / 2.0

    private fun normalize(
        b0: Double, b1: Double, b2: Double,
        a0: Double, a1: Double, a2: Double
    ): BiquadCoefficients =
        BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}
