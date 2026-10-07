package com.foamplayer.app.data.lyrics

import com.foamplayer.app.lyrics.LyricsEntry
import com.foamplayer.app.lyrics.LyricsUtils

/**
 * Content-side analysis for lyrics payloads: type classification, duration
 * derivation and metadata similarity. All functions are pure / CPU-only.
 */
object LyricsSearchHelper {

    /** Metadata tags that can ride along in an LRC header ([ti:] / [ar:] / [al:]). */
    data class LrcMetadata(val title: String?, val artist: String?, val album: String?)
    private val ENHANCED_LRC_WORD_TIME_REGEX = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{2,3})?>""")

    private val YRC_WORD_TIME_REGEX = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")

    private val LRC_HEADER_TAG_REGEX = Regex("""^\[(ti|ar|al):(.*)]\s*$""", RegexOption.IGNORE_CASE)

    /** How close (ms) a derived result duration must be to the track's to count as a match. */
    const val DURATION_MATCH_TOLERANCE_MS = 2_000L

    fun classify(rawLyrics: String): LyricsResultType {
        val normalized = LyricsUtils.normalizeLyricsText(rawLyrics)
        if (LyricsUtils.isTtml(normalized)) {
            val hasWordTimings = runCatching { LyricsUtils.parseTtml(normalized) }
                .getOrDefault(emptyList())
                .any { entry -> !entry.words.isNullOrEmpty() }
            return if (hasWordTimings) LyricsResultType.WORD_SYNCED else LyricsResultType.SYNCED
        }
        if (LyricsUtils.isLineSyncedLrc(normalized)) {
            // Enhanced LRC (<mm:ss.xx>) and YRC ((start,duration)) carry word-level timings.
            val hasWordTimings = normalized.lineSequence().any { line ->
                ENHANCED_LRC_WORD_TIME_REGEX.containsMatchIn(line) ||
                        YRC_WORD_TIME_REGEX.containsMatchIn(line)
            }
            return if (hasWordTimings) LyricsResultType.WORD_SYNCED else LyricsResultType.SYNCED
        }
        return LyricsResultType.PLAIN
    }

    /**
     * Best-effort duration of the lyrics payload itself, derived from its own
     * timings: the last timestamp of a synced payload, or the last word/line end
     * of a TTML document. Plain text carries no timing information (null).
     */
    fun deriveDurationMs(rawLyrics: String): Long? {
        val normalized = LyricsUtils.normalizeLyricsText(rawLyrics)
        return when {
            LyricsUtils.isTtml(normalized) -> {
                val entries = runCatching { LyricsUtils.parseTtml(normalized) }.getOrDefault(emptyList())
                entries.maxOfOrNull { entry -> entry.lastWordEndTime() }?.takeIf { it > 0L }
            }
            LyricsUtils.isLineSyncedLrc(normalized) -> {
                val entries = runCatching { LyricsUtils.parseLyrics(normalized) }.getOrDefault(emptyList())
                entries.maxOfOrNull { it.time }?.takeIf { it > 0L }
            }
            else -> null
        }
    }

    /** LRC header tags, when the payload carries them. */
    fun extractLrcMetadata(rawLyrics: String): LrcMetadata? {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        rawLyrics.lineSequence().forEach { line ->
            val match = LRC_HEADER_TAG_REGEX.find(line.trim()) ?: return@forEach
            val value = match.groupValues[2].trim().takeIf { it.isNotEmpty() } ?: return@forEach
            when (match.groupValues[1].lowercase()) {
                "ti" -> title = value
                "ar" -> artist = value
                "al" -> album = value
            }
        }
        return if (title == null && artist == null && album == null) null else LrcMetadata(title, artist, album)
    }

    /**
     * 0..1 relevancy of [resultLyrics] against the searched track's metadata.
     * LRC payloads with header tags are compared field-by-field; payloads
     * without any usable metadata score a neutral 0.5 (duration delta decides).
     */
    fun metadataSimilarity(resultLyrics: String, title: String, artist: String, album: String?): Double {
        val metadata = extractLrcMetadata(resultLyrics) ?: return 0.5
        var score = 0.0
        var weight = 0.0
        if (metadata.title != null) {
            score += 0.55 * stringSimilarity(metadata.title, title)
            weight += 0.55
        }
        if (metadata.artist != null) {
            score += 0.30 * stringSimilarity(metadata.artist, artist)
            weight += 0.30
        }
        if (metadata.album != null && !album.isNullOrBlank()) {
            score += 0.15 * stringSimilarity(metadata.album, album)
            weight += 0.15
        }
        return if (weight == 0.0) 0.5 else score / weight
    }

    /** Similarity in 0..1: containment shortcut, else 1 - normalized Levenshtein distance. */
    fun stringSimilarity(a: String, b: String): Double {
        val s1 = a.trim().lowercase()
        val s2 = b.trim().lowercase()
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0
        if (s1.contains(s2) || s2.contains(s1)) return 0.8
        val maxLength = maxOf(s1.length, s2.length)
        return 1.0 - levenshteinDistance(s1, s2).toDouble() / maxLength
    }

    fun durationDeltaLabel(derivedDurationMs: Long?, trackDurationMs: Long): String? {
        val derived = derivedDurationMs ?: return null
        val deltaSeconds = (derived - trackDurationMs) / 1000.0
        if (kotlin.math.abs(derived - trackDurationMs) <= DURATION_MATCH_TOLERANCE_MS) return null
        val formatted = String.format(java.util.Locale.US, "%.1f", deltaSeconds)
        return if (deltaSeconds >= 0) "+${formatted}s" else "${formatted}s"
    }

    fun durationsMatch(derivedDurationMs: Long?, trackDurationMs: Long): Boolean {
        val derived = derivedDurationMs ?: return false
        return kotlin.math.abs(derived - trackDurationMs) <= DURATION_MATCH_TOLERANCE_MS
    }

    private fun LyricsEntry.lastWordEndTime(): Long {
        val words = words ?: return time
        val lastEnd = words.maxOfOrNull { it.endTime } ?: return time
        return (lastEnd * 1000.0).toLong().coerceAtLeast(time)
    }

    private fun levenshteinDistance(str1: String, str2: String): Int {
        val len1 = str1.length
        val len2 = str2.length
        val matrix = Array(len1 + 1) { IntArray(len2 + 1) }
        for (i in 0..len1) matrix[i][0] = i
        for (j in 0..len2) matrix[0][j] = j
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (str1[i - 1] == str2[j - 1]) 0 else 1
                matrix[i][j] = minOf(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1, matrix[i - 1][j - 1] + cost)
            }
        }
        return matrix[len1][len2]
    }
}
