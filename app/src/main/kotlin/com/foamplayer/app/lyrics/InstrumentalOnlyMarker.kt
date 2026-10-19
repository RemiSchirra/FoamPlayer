package com.foamplayer.app.lyrics

/**
 * Marker lyrics sources emit for a track that has no vocals, e.g. as the first
 * payload line "[00:00.00] [Instrumental Only]".
 */
const val INSTRUMENTAL_ONLY_MARKER = "[Instrumental Only]"

/** Chinese equivalent sources emit for an instrumental-only track. */
const val INSTRUMENTAL_ONLY_MARKER_ZH = "纯音乐，请欣赏"

private val INSTRUMENTAL_ONLY_MARKERS = listOf(INSTRUMENTAL_ONLY_MARKER, INSTRUMENTAL_ONLY_MARKER_ZH)

/** LRC metadata header line ([ti:...] / [ar:...] / [al:...] / [by:...] / ...). */
private val LRC_METADATA_HEADER_LINE_REGEX = Regex("""^\[[a-z]+:.*]\s*$""", RegexOption.IGNORE_CASE)

private val WHITESPACE_REGEX = "\\s+".toRegex()

private const val NBSP = ' '

private fun String.containsInstrumentalMarker(): Boolean {
    val collapsed = replace(WHITESPACE_REGEX, " ")
    return INSTRUMENTAL_ONLY_MARKERS.any { collapsed.contains(it, ignoreCase = true) }
}

/**
 * True when [lyrics] self-declares the track instrumental-only. For LRC and
 * plain payloads the marker must appear on the first content line (blank lines
 * and LRC metadata headers are skipped); TTML payloads declare it as the first
 * parsed lyric line's text.
 */
fun isInstrumentalOnlyLyrics(lyrics: String): Boolean {
    val normalized = LyricsUtils.normalizeLyricsText(lyrics)
    if (normalized.isEmpty()) return false

    if (LyricsUtils.isTtml(normalized)) {
        val firstEntry = runCatching { LyricsUtils.parseTtml(normalized) }
            .getOrDefault(emptyList())
            .firstOrNull() ?: return false
        return firstEntry.text.containsInstrumentalMarker()
    }

    for (line in normalized.lineSequence()) {
        val trimmed = line.trim { it.isWhitespace() || it == NBSP }
        if (trimmed.isEmpty() || LRC_METADATA_HEADER_LINE_REGEX.matches(trimmed)) continue
        return trimmed.containsInstrumentalMarker()
    }
    return false
}
