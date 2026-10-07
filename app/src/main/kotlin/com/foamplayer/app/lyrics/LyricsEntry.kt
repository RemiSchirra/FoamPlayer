package com.foamplayer.app.lyrics

import kotlinx.coroutines.flow.MutableStateFlow

/** One word of a word-synced lyrics line, with its timing window. */
data class WordTimestamp(
    val text: String,
    val startTime: Double,
    val endTime: Double,
    val isBackground: Boolean = false,
)

/**
 * One lyrics line: line timestamp, display text and optional word timings.
 * The provider-romanized fields are pre-computed romanizations; the
 * [romanizedTextFlow] holds the lazily computed value for UI observation.
 */
data class LyricsEntry(
    val time: Long,
    val text: String,
    val words: List<WordTimestamp>? = null,
    val agent: String? = null,
    val isInstrumental: Boolean = false,
    val durationMs: Long = 0L,
    val providerRomanizedText: String? = null,
    val providerRomanizedWords: List<String>? = null,
    val providerRomanizedLanguage: String? = null,
    val providerTranslationText: String? = null,
    val romanizedTextFlow: MutableStateFlow<String?> = MutableStateFlow(null),
) : Comparable<LyricsEntry> {
    override fun compareTo(other: LyricsEntry): Int = (time - other.time).toInt()

    companion object {
        /** Placeholder shown before the first timestamped line. */
        val HEAD_LYRICS_ENTRY = LyricsEntry(0L, "")
    }
}