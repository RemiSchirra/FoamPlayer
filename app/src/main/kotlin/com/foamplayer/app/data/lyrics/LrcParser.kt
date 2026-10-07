package com.foamplayer.app.data.lyrics

/** One line of parsed LRC content: its timestamp and display text. */
data class LrcLine(
    val timestampMs: Long,
    val text: String
)

/** Parses LRC lyrics ([mm:ss.xx] timestamps) into sorted, timestamped lines. */
object LrcParser {
    private val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")

    /** Timestamped lines sorted by time; untagged lines land at 0 ms. */
    fun parse(rawLrc: String?): List<LrcLine> {
        if (rawLrc.isNullOrBlank()) return emptyList()

        val lines = mutableListOf<LrcLine>()
        rawLrc.lines().forEach { line ->
            val match = regex.find(line.trim())
            if (match != null) {
                val (minStr, secStr, msStr, text) = match.destructured
                val minutes = minStr.toLongOrNull() ?: 0L
                val seconds = secStr.toLongOrNull() ?: 0L
                var milliseconds = msStr.toLongOrNull() ?: 0L
                if (msStr.length == 2) milliseconds *= 10

                val totalMs = (minutes * 60 * 1000) + (seconds * 1000) + milliseconds
                lines.add(LrcLine(timestampMs = totalMs, text = text.trim()))
            } else if (line.isNotBlank() && !line.startsWith("[")) {
                lines.add(LrcLine(timestampMs = 0L, text = line.trim()))
            }
        }
        return lines.sortedBy { it.timestampMs }
    }
}
