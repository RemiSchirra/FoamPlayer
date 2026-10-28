package com.foamplayer.squig

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin AutoEQ: fits an equalization to the difference between a
 * measured frequency response and a target curve.
 *
 * The difference is centered over 40 Hz–10 kHz (overall level belongs to the
 * volume control, not the EQ), lightly smoothed, then fitted with a greedy
 * sequence of peaking filters: each step places a filter on the residual's
 * largest deviation, estimates its Q from the half-gain bandwidth, subtracts
 * the modeled filter response, and repeats. The pre-amp is the negative of the
 * fitted curve's peak so the applied boost can never clip the output.
 */
object AutoEq {

    const val GRID_MIN_HZ = 20f
    const val GRID_MAX_HZ = 20_000f
    const val FIT_MAX_HZ = 16_000f
    const val CENTER_MIN_HZ = 40f
    const val CENTER_MAX_HZ = 10_000f

    /** Correction grid resolution. */
    const val GRID_POINTS_PER_OCTAVE = 24

    const val DEFAULT_MAX_FILTERS = 10

    /** Light default smoothing; squig.link curves are already 1/12-octave smoothed. */
    const val DEFAULT_SMOOTHING_OCTAVES = 1f / 6f

    /** Fitting stops once the largest residual deviation is below this (dB). */
    const val CONVERGENCE_DB = 0.5f

    const val MAX_FILTER_GAIN_DB = 12f
    const val MIN_FILTER_Q = 0.3f
    const val MAX_FILTER_Q = 8f
    const val MAX_PREAMP_DB = 15f

    /** One fitted peaking filter. */
    data class ParametricFilter(
        val frequencyHz: Float,
        val gainDb: Float,
        val q: Float,
    )

    data class Result(
        /** Centered difference curve (source → target); the ideal correction. */
        val correctionCurve: FrequencyResponseCurve,
        /** Greedy parametric fit of [correctionCurve]. */
        val filters: List<ParametricFilter>,
        /** Negative peak of the fitted curve, so boosts stay in headroom. */
        val preampDb: Float,
        /** RMS of the unfitted residual over the fit range, in dB. */
        val residualRmsDb: Float,
    )

    fun compute(
        source: FrequencyResponseCurve,
        target: FrequencyResponseCurve,
        maxFilters: Int = DEFAULT_MAX_FILTERS,
        smoothingOctaves: Float = DEFAULT_SMOOTHING_OCTAVES,
    ): Result {
        val grid = logGrid(GRID_MIN_HZ, GRID_MAX_HZ, GRID_POINTS_PER_OCTAVE)
        val fitUpperIndex = grid.indexOfLast { it <= FIT_MAX_HZ }

        // Difference of the two responses on the shared log grid.
        var residual = FloatArray(grid.size) { index ->
            target.interpolate(grid[index]) - source.interpolate(grid[index])
        }
        // Center over the fit band: overall level is loudness, not EQ.
        val centerMean = bandMean(grid, residual, CENTER_MIN_HZ, CENTER_MAX_HZ)
        for (i in residual.indices) residual[i] -= centerMean
        // Smooth coupler resonances away: artifacts, not EQ material.
        if (smoothingOctaves > 0f) residual = smooth(grid, residual, smoothingOctaves)

        val correction = FrequencyResponseCurve(
            grid.mapIndexed { index, hz -> FrequencyResponseCurve.Point(hz, residual[index]) }
        )

        // Greedy peaking fit.
        val filters = ArrayList<ParametricFilter>(maxFilters.coerceAtLeast(0))
        val fitted = FloatArray(grid.size)
        repeat(maxFilters.coerceAtLeast(0)) {
            var peakIndex = -1
            var peakAbs = 0f
            for (i in 0..fitUpperIndex) {
                val deviation = abs(residual[i])
                if (deviation > peakAbs) {
                    peakAbs = deviation
                    peakIndex = i
                }
            }
            if (peakIndex < 0 || peakAbs < CONVERGENCE_DB) return@repeat

            val frequency = grid[peakIndex]
            val gain = residual[peakIndex].coerceIn(-MAX_FILTER_GAIN_DB, MAX_FILTER_GAIN_DB)
            val q = estimateQ(grid, residual, peakIndex, gain)

            val response = peakingResponseDb(grid, frequency, gain, q)
            for (i in grid.indices) {
                residual[i] -= response[i]
                fitted[i] += response[i]
            }
            filters += ParametricFilter(frequency, gain, q)
        }

        var fittedPeak = 0f
        var squaredSum = 0.0
        var rmsCount = 0
        for (i in grid.indices) {
            if (fitted[i] > fittedPeak) fittedPeak = fitted[i]
            if (i <= fitUpperIndex) {
                squaredSum += residual[i].toDouble() * residual[i]
                rmsCount++
            }
        }
        val preamp = (-fittedPeak).coerceIn(-MAX_PREAMP_DB, 0f)
        val residualRms = if (rmsCount == 0) 0f else sqrt(squaredSum / rmsCount).toFloat()

        return Result(
            correctionCurve = correction,
            filters = filters,
            preampDb = preamp,
            residualRmsDb = residualRms,
        )
    }

    /**
     * Q from the half-gain bandwidth around [peakIndex]: a peaking filter's
     * half-gain points f1/f2 give Q = sqrt(2^bw) / (2^bw − 1) with bw =
     * log2(f2/f1). A 1-octave bandwidth lands at the familiar Q = 1.41.
     */
    private fun estimateQ(grid: FloatArray, residual: FloatArray, peakIndex: Int, gain: Float): Float {
        val half = gain / 2f
        val boost = gain > 0f
        fun beyondHalf(value: Float) = if (boost) value >= half else value <= half

        var lo = peakIndex
        while (lo > 0 && beyondHalf(residual[lo])) lo--
        var hi = peakIndex
        while (hi < residual.lastIndex && beyondHalf(residual[hi])) hi++

        val bandwidthOctaves = log2(grid[hi].toDouble() / grid[lo].toDouble())
        if (bandwidthOctaves <= 0.01f) return 1.4f
        val q = sqrt(2.0.pow(bandwidthOctaves)) / (2.0.pow(bandwidthOctaves) - 1.0)
        return q.toFloat().coerceIn(MIN_FILTER_Q, MAX_FILTER_Q)
    }

    /**
     * Peaking-EQ magnitude response across [grid], in dB (RBJ cookbook at a
     * fixed 48 kHz design rate; the response is sample-rate independent).
     */
    internal fun peakingResponseDb(grid: FloatArray, f0Hz: Float, gainDb: Float, q: Float): FloatArray {
        val out = FloatArray(grid.size)
        if (gainDb == 0f) return out
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0Hz / SAMPLE_RATE_HZ
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val b0 = 1.0 + alpha * A
        val b1 = -2.0 * cosW0
        val b2 = 1.0 - alpha * A
        val a0 = 1.0 + alpha / A
        val a1 = b1
        val a2 = 1.0 - alpha / A
        for (i in grid.indices) {
            val w = 2.0 * PI * grid[i] / SAMPLE_RATE_HZ
            val cw = cos(w)
            val c2w = cos(2.0 * w)
            val numerator = b0 * b0 + b1 * b1 + b2 * b2 +
                    2.0 * (b0 * b1 + b1 * b2) * cw + 2.0 * b0 * b2 * c2w
            val denominator = a0 * a0 + a1 * a1 + a2 * a2 +
                    2.0 * (a0 * a1 + a1 * a2) * cw + 2.0 * a0 * a2 * c2w
            out[i] = (10.0 * ln(numerator / denominator) / ln(10.0)).toFloat()
        }
        return out
    }

    /** Log-spaced grid across [minHz, maxHz] at [pointsPerOctave]. */
    internal fun logGrid(minHz: Float, maxHz: Float, pointsPerOctave: Int): FloatArray {
        val count = (log2(maxHz / minHz) * pointsPerOctave).roundToInt() + 1
        val step = 1.0 / pointsPerOctave
        return FloatArray(count) { index -> (minHz * 2.0.pow(step * index)).toFloat() }
    }

    private fun bandMean(grid: FloatArray, values: FloatArray, minHz: Float, maxHz: Float): Float {
        var sum = 0.0
        var count = 0
        for (i in grid.indices) {
            if (grid[i] < minHz || grid[i] > maxHz) continue
            sum += values[i]
            count++
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    /** Fractional-octave moving average in the log domain. */
    private fun smooth(grid: FloatArray, values: FloatArray, octaves: Float): FloatArray {
        val half = octaves / 2.0
        val out = FloatArray(values.size)
        for (i in grid.indices) {
            val lo = grid[i] * 2.0.pow(-half)
            val hi = grid[i] * 2.0.pow(half)
            var sum = 0.0
            var count = 0
            for (j in grid.indices) {
                if (grid[j] < lo || grid[j] > hi) continue
                sum += values[j]
                count++
            }
            out[i] = if (count == 0) values[i] else (sum / count).toFloat()
        }
        return out
    }

    private const val SAMPLE_RATE_HZ = 48_000.0
}
