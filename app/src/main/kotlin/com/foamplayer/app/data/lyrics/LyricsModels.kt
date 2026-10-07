package com.foamplayer.app.data.lyrics

import com.foamplayer.app.constants.LyricsSource

/** Content-derived kind of a lyrics payload: plain text, line-synced (LRC) or word-synced (TTML). */
enum class LyricsResultType {
    PLAIN,
    SYNCED,
    WORD_SYNCED
}

/** Lyrics resolved through the source-order pipeline, with the origin source. */
data class ResolvedLyrics(
    val content: String,
    val source: LyricsSource,
    /** Provider name for online lyrics, null for local sources. */
    val providerName: String?
)

/** Which lyrics sources currently provide something for a track. */
data class LyricsSourceStatus(
    val hasCached: Boolean,
    val hasFile: Boolean,
    val hasEmbedded: Boolean
) {
    val hasAny: Boolean get() = hasCached || hasFile || hasEmbedded
}

/** One candidate returned by the concurrent multi-provider lyrics search. */
data class LyricsSearchResult(
    val content: String,
    val providerName: String,
    val type: LyricsResultType,
    /** Duration derived from the content's timings (last timestamp / TTML end), null when unknown. */
    val derivedDurationMs: Long?,
    /** 0..1 metadata similarity against the searched track (0.5 neutral when no metadata available). */
    val similarityScore: Double,
    /** Position of the producing provider in the user's configured provider order. */
    val providerOrder: Int
)

/**
 * Outcome of removing every lyrics source from a track (online cache, sidecar
 * file, embedded tag). [embeddedStillPresent] is set when the track still reads
 * embedded lyrics afterwards (e.g. a container whose tag cannot be rewritten).
 */
data class LyricsDeleteResult(
    val cachedDeleted: Boolean,
    val fileDeleted: Boolean,
    val embeddedRemoved: Boolean,
    val embeddedStillPresent: Boolean
) {
    val deletedSomething: Boolean get() = cachedDeleted || fileDeleted || embeddedRemoved
}
