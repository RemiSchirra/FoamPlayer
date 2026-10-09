package com.foamplayer.app.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filter shapes available to the parametric EQ. Names follow the RBJ audio
 * EQ cookbook so the UI labels and the coefficient math stay in sync.
 */
enum class FilterType(val label: String, val gainAdjustable: Boolean) {
    PEAKING("Peak", true),
    LOW_SHELF("Low shelf", true),
    HIGH_SHELF("High shelf", true),
    LOW_PASS("Low pass", false),
    HIGH_PASS("High pass", false)
}

/**
 * Time-invariant biquad coefficients in transposed direct form II layout.
 * Pure data: [EqGraph] applies them to samples, [EqMath] evaluates the
 * magnitude response for the UI.
 */
data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double
) {
    /**
     * Magnitude of H(e^{jw}) in dB at normalized angular frequency [w] in
     * 0..pi. Closed form, no state, safe to call from the UI thread.
     */
    fun magnitudeDb(w: Double): Double {
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)

        val numMag = sqrt(numRe * numRe + numIm * numIm)
        val denMag = sqrt(denRe * denRe + denIm * denIm)
        if (denMag == 0.0 || numMag == 0.0) return -120.0

        val linear = numMag / denMag
        return if (linear <= 1e-6) -120.0 else 20.0 * log10(linear)
    }

    companion object {
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Per-channel runtime state of one biquad (transposed direct form II) with
 * its single-sample processing step. Instantiated per (filter, channel) pair
 * by [EqGraph]; coefficients are swapped in place when settings change.
 */
class BiquadState(private var c: BiquadCoefficients) {
    private var z1 = 0.0
    private var z2 = 0.0
    private var identity = c == BiquadCoefficients.IDENTITY

    /**
     * Swaps the coefficients while KEEPING the filter memory (z1/z2), so a
     * settings change morphs the response without a discontinuity in the
     * output. Audio thread only.
     */
    fun setCoefficients(coefficients: BiquadCoefficients) {
        c = coefficients
        identity = coefficients == BiquadCoefficients.IDENTITY
    }

    fun process(x: Float): Float {
        // Fast path: an identity section with empty memory is a bit-exact no-op.
        // (Keeping 0 dB sections in the chain keeps the layout stable; this makes
        // them free. With leftover memory the full path runs and flushes it.)
        if (identity && z1 == 0.0 && z2 == 0.0) return x
        val y = c.b0 * x + z1
        z1 = c.b1 * x - c.a1 * y + z2
        z2 = c.b2 * x - c.a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }
}

/**
 * 64-bit RBJ audio-EQ-cookbook biquad designer. All frequency-domain math in
 * double precision; an invalid sample rate, a frequency outside (0, fs/2), a
 * non-positive Q or a zero gain yields [BiquadCoefficients.IDENTITY] instead
 * of NaN coefficients, so a bad slider value can never poison the audio path.
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

    /** Low shelf: raises/lowers everything below [frequencyHz] by [gainDb]. */
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

    /** High shelf: raises/lowers everything above [frequencyHz] by [gainDb]. */
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

    /**
     * First-order low pass with the -3 dB point at [frequencyHz], via the
     * bilinear transform of H(s) = 1 / (1 + s/wc): the exact discrete
     * equivalent, 0 dB at DC and -3 dB at the cutoff.
     */
    fun firstOrderLowPass(sampleRate: Double, frequencyHz: Double): BiquadCoefficients {
        if (!valid(sampleRate, frequencyHz)) return BiquadCoefficients.IDENTITY
        val w0 = 2.0 * PI * frequencyHz / sampleRate
        val tanHalf = kotlin.math.tan(w0 / 2.0)
        val denom = 1.0 + tanHalf

        val b0 = tanHalf / denom
        val b1 = tanHalf / denom
        val b2 = 0.0
        val a1 = (tanHalf - 1.0) / denom
        val a2 = 0.0
        return BiquadCoefficients(b0, b1, b2, a1, a2)
    }

    /**
     * First-order high pass with the -3 dB point at [frequencyHz], the mirror
     * of [firstOrderLowPass]: 0 dB at Nyquist, blocks DC.
     */
    fun firstOrderHighPass(sampleRate: Double, frequencyHz: Double): BiquadCoefficients {
        if (!valid(sampleRate, frequencyHz)) return BiquadCoefficients.IDENTITY
        val w0 = 2.0 * PI * frequencyHz / sampleRate
        val tanHalf = kotlin.math.tan(w0 / 2.0)
        val denom = 1.0 + tanHalf

        val b0 = 1.0 / denom
        val b1 = -1.0 / denom
        val b2 = 0.0
        val a1 = (tanHalf - 1.0) / denom
        val a2 = 0.0
        return BiquadCoefficients(b0, b1, b2, a1, a2)
    }

    private fun valid(sampleRate: Double, frequencyHz: Double): Boolean =
        sampleRate > 0.0 && frequencyHz > 0.0 && frequencyHz < sampleRate / 2.0

    private fun normalize(
        b0: Double, b1: Double, b2: Double,
        a0: Double, a1: Double, a2: Double
    ): BiquadCoefficients =
        BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}
