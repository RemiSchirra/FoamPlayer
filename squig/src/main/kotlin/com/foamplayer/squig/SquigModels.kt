package com.foamplayer.squig

import kotlin.math.ln
import kotlin.math.pow

/** Base of the squig.link index and shared lab URLs. */
const val SQUIG_INDEX_URL = "https://squig.link"

/**
 * A squig.link community database: one reviewer's measurement rig. The shared
 * index (squigsites.json) lists every instance with its URL shape and the
 * form-factor databases it hosts.
 */
data class SquigSite(
    val username: String,
    val name: String,
    val urlType: String,
    val altDomain: String? = null,
    val dbs: List<SquigDb>,
) {
    /** Base URL of this instance's graph tool. */
    val baseUrl: String
        get() = when (urlType) {
            "root" -> SQUIG_INDEX_URL
            "labFolder" -> "$SQUIG_INDEX_URL/lab/$username"
            "altDomain" -> altDomain?.takeIf { it.isNotBlank() } ?: "$SQUIG_INDEX_URL/lab/$username"
            else -> "https://$username.squig.link"
        }

    override fun toString(): String = name.ifBlank { username }
}

/** One form-factor database of a site (IEMs, Headphones, Earbuds, 5128...). */
data class SquigDb(
    val type: String,
    val folder: String,
)

/** A measured device (or a preference target curve) inside a database. */
data class SquigDevice(
    val brand: String,
    val model: String,
    /** Measurement file name relative to the database's data directory. */
    val file: String,
    val isTarget: Boolean = false,
    val reviewScore: String? = null,
) {
    val displayName: String get() = "$brand $model".trim()
}

/** A loaded database: device catalog + preference targets + where curves live. */
data class SquigDatabase(
    val site: SquigSite,
    val db: SquigDb,
    /** Absolute URL of the measurement directory; ends with '/'. */
    val dataUrl: String,
    /** Multi-sample rigs number their channels, e.g. "Aria L1.txt". */
    val numSamples: Int,
    val devices: List<SquigDevice>,
    val targets: List<SquigDevice>,
)

/**
 * A frequency-response curve sampled on an arbitrary frequency axis, in dB.
 * Interpolation happens on the log-frequency axis so the curve behaves like
 * the REW graphs it comes from.
 */
class FrequencyResponseCurve(private val sorted: List<Point>) {

    data class Point(val frequencyHz: Float, val db: Float)

    init {
        require(sorted.zipWithNext().all { (a, b) -> a.frequencyHz <= b.frequencyHz }) {
            "FrequencyResponseCurve points must be sorted by frequency"
        }
    }

    val isEmpty: Boolean get() = sorted.isEmpty()
    val minFrequencyHz: Float get() = sorted.firstOrNull()?.frequencyHz ?: 0f
    val maxFrequencyHz: Float get() = sorted.lastOrNull()?.frequencyHz ?: 0f

    /** Linear-in-log-frequency interpolation, constant outside the measured range. */
    fun interpolate(frequencyHz: Float): Float {
        if (sorted.isEmpty()) return 0f
        if (frequencyHz <= sorted.first().frequencyHz) return sorted.first().db
        if (frequencyHz >= sorted.last().frequencyHz) return sorted.last().db
        var lo = 0
        var hi = sorted.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (sorted[mid].frequencyHz <= frequencyHz) lo = mid else hi = mid
        }
        val a = sorted[lo]
        val b = sorted[hi]
        val span = ln(b.frequencyHz) - ln(a.frequencyHz)
        if (span <= 0f) return a.db
        val t = (ln(frequencyHz) - ln(a.frequencyHz)) / span
        return a.db + (b.db - a.db) * t
    }

    /** Mean level between [minHz] and [maxHz], sampled uniformly on the log axis. */
    fun meanDb(minHz: Float, maxHz: Float): Float {
        if (isEmpty || maxHz <= minHz) return 0f
        var sum = 0.0
        var count = 0
        var f = minHz.toDouble()
        val step = 1.0 / POINTS_PER_OCTAVE
        while (f <= maxHz) {
            sum += interpolate(f.toFloat())
            count++
            f = minHz * 2.0.pow(step * count)
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    /** Fractional-octave moving average; [octaves] is the total smoothing bandwidth. */
    fun smoothed(octaves: Float): FrequencyResponseCurve {
        if (octaves <= 0f || sorted.size < 3) return this
        val half = octaves / 2.0
        val out = ArrayList<Point>(sorted.size)
        for (point in sorted) {
            val lo = point.frequencyHz * 2.0.pow(-half)
            val hi = point.frequencyHz * 2.0.pow(half)
            var sum = 0.0
            var count = 0
            for (other in sorted) {
                if (other.frequencyHz < lo || other.frequencyHz > hi) continue
                sum += other.db
                count++
            }
            out += Point(point.frequencyHz, if (count == 0) point.db else (sum / count).toFloat())
        }
        return FrequencyResponseCurve(out)
    }

    companion object {
        private const val POINTS_PER_OCTAVE = 48

        val EMPTY: FrequencyResponseCurve = FrequencyResponseCurve(emptyList())

        /**
         * Averages curves dB-by-dB over their common frequency range. L/R
         * channel files usually share the measurement axis, but the resample
         * keeps the average correct when they don't.
         */
        fun average(curves: List<FrequencyResponseCurve>, pointsPerOctave: Int = POINTS_PER_OCTAVE): FrequencyResponseCurve {
            val valid = curves.filter { !it.isEmpty }
            if (valid.isEmpty()) return EMPTY
            if (valid.size == 1) return valid[0]
            val minHz = valid.maxOf { it.minFrequencyHz }.coerceAtLeast(1f)
            val maxHz = valid.minOf { it.maxFrequencyHz }
            if (maxHz <= minHz) return valid[0]
            val step = 1.0 / pointsPerOctave
            val points = ArrayList<Point>()
            var index = 0
            var f = minHz.toDouble()
            while (f <= maxHz) {
                points += Point(f.toFloat(), valid.map { it.interpolate(f.toFloat()) }.average().toFloat())
                index++
                f = minHz * 2.0.pow(step * index)
            }
            return FrequencyResponseCurve(points)
        }
    }
}
