package com.foamplayer.app.ui.screens.stats

import com.foamplayer.app.data.db.dao.ArtistPlayRow
import com.foamplayer.app.data.db.dao.TopAlbumStat
import com.foamplayer.app.data.db.dao.TopTrackStat

/** One day in milliseconds (period window arithmetic). */
private const val DayMs = 24L * 60 * 60 * 1000

/** Which ranked list a stats "see all" page shows. */
enum class StatsTopKind { TRACKS, ARTISTS, ALBUMS }

/** Analysis period of the stats screen; [durationMs] is a rolling window, NULL = everything. */
enum class StatsPeriod(val durationMs: Long?) {
    WEEK(DayMs * 7),
    MONTH(DayMs * 30),
    THREE_MONTHS(DayMs * 91),
    SIX_MONTHS(DayMs * 182),
    YEAR(DayMs * 365),
    ALL_TIME(null);

    /** Bucket granularity of the activity chart at this zoom level. */
    val chartBucket: ChartBucket
        get() = when (this) {
            WEEK, MONTH -> ChartBucket.DAY
            THREE_MONTHS, SIX_MONTHS -> ChartBucket.WEEK
            YEAR, ALL_TIME -> ChartBucket.MONTH
        }
}

enum class ChartBucket { DAY, WEEK, MONTH }

/** One artist credited through the "; " multi-artist convention, with its period totals. */
data class ArtistStat(
    val name: String,
    val playCount: Int,
    val totalDurationMs: Long
)

/** One genre credited through the "; " multi-genre convention, with its period play count. */
data class GenreStat(
    val name: String,
    val playCount: Int
)

/** One bar of the activity chart: a calendar day / week / month of the period. */
data class StatsTimeBucket(
    /** Epoch day of the bucket's first day (ordering key). */
    val startEpochDay: Long,
    /** Short label (day-of-week letter, day-of-month or month name). */
    val label: String,
    val playCount: Int,
    val totalDurationMs: Long
)

/** Everything the stats screen renders, all scoped to [period]. */
data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.WEEK,
    val loading: Boolean = true,
    val totalPlays: Int = 0,
    val totalDurationMs: Long = 0,
    val uniqueTracks: Int = 0,
    val uniqueArtists: Int = 0,
    /** Listening time per day over the covered window. */
    val dailyAverageMs: Long = 0,
    /** Hour of day (0..23) with the most listens, or null with no data. */
    val peakHour: Int? = null,
    /** Number of distinct days with at least one listen. */
    val activeDays: Int = 0,
    val timeBuckets: List<StatsTimeBucket> = emptyList(),
    /** Plays per local hour (size 24). */
    val hourHistogram: List<Int> = emptyList(),
    val topTracks: List<TopTrackStat> = emptyList(),
    val topArtists: List<ArtistStat> = emptyList(),
    val topAlbums: List<TopAlbumStat> = emptyList(),
    val topGenres: List<GenreStat> = emptyList()
)

/** Splits one raw credited value on the app-wide "; " convention. */
internal fun splitCredited(raw: String): List<String> =
    raw.split("; ").map(String::trim).filter(String::isNotEmpty)

/**
 * Aggregates the per-raw-artist rows into credited artists (each share of a
 * "; A; B" credit counts the row's plays/duration once), ranked by plays.
 */
internal fun aggregateArtistRows(rows: List<ArtistPlayRow>): List<ArtistStat> {
    val totals = HashMap<String, ArtistStat>()
    for (row in rows) {
        for (name in splitCredited(row.rawArtist)) {
            val previous = totals[name]
            totals[name] = ArtistStat(
                name = name,
                playCount = (previous?.playCount ?: 0) + row.playCount,
                totalDurationMs = (previous?.totalDurationMs ?: 0L) + row.totalDurationMs
            )
        }
    }
    return totals.values.sortedByDescending { it.playCount }
}
