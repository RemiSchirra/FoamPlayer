package com.foamplayer.app.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.ArtistPlayRow
import com.foamplayer.app.data.db.dao.DailyPlayBucket
import com.foamplayer.app.data.db.dao.GenrePlayRow
import com.foamplayer.app.data.db.dao.HourBucket
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ArtistCover
import com.foamplayer.app.data.scanner.ArtistCoverProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    artistCoverProvider: ArtistCoverProvider
) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.WEEK)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    val state: StateFlow<StatsUiState> = _period
        .flatMapLatest { period -> buildStats(period) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    /**
     * Resolved pictures per artist name: a cached Spotify image when the scan
     * found one, else the artist's first track with embedded art (both are
     * automatic — no scan has to run for the fallback), for the top-artist rows.
     */
    val artistImages: StateFlow<Map<String, ArtistCover>> =
        artistCoverProvider.coversFlow(trackRepository.getAllArtists())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setPeriod(period: StatsPeriod) {
        _period.value = period
    }

    // Long-press collection actions for the ranked cards, mirroring the browse
    // screens: resolve the tracks behind a collection at action time.

    /** One album's tracks in disc/track order (mirrors TrackDao.getTracksByAlbum). */
    suspend fun tracksForAlbum(album: String): List<TrackEntity> =
        trackRepository.getTracksByAlbum(album).first()

    /** Tracks naming [artist] as artist or album artist, in album order. */
    suspend fun tracksForArtist(artist: String): List<TrackEntity> = withContext(Dispatchers.Default) {
        trackRepository.getAllTracks().first()
            .filter { it.artist == artist || it.albumArtist == artist }
            .sortedWith(compareBy({ it.album }, { it.discNumber }, { it.trackNumber }, { it.title }))
    }

    /**
     * One Room flow per aggregate, combined into the screen state. The whole
     * chain re-runs per period switch ([flatMapLatest]) and whenever a listen
     * or track row invalidates the queries.
     */
    private fun buildStats(period: StatsPeriod): Flow<StatsUiState> {
        val since = period.durationMs?.let { System.currentTimeMillis() - it }
        val tzOffsetMs = ZoneId.systemDefault().rules
            .getOffset(Instant.now()).totalSeconds * 1000L

        val core = combine(
            trackRepository.observeListenOverview(since),
            trackRepository.observeListenedArtists(since),
            trackRepository.observeDailyListenBuckets(since, tzOffsetMs),
            trackRepository.observeListenHourHistogram(since, tzOffsetMs)
        ) { overview, distinctArtistStrings, dailyBuckets, hourBuckets ->
            CoreStats(overview, distinctArtistStrings, dailyBuckets, hourBuckets)
        }

        val tops = combine(
            trackRepository.observeTopListenedTracks(since),
            trackRepository.observeListenedArtistRows(since),
            trackRepository.observeTopListenedAlbums(since, TopListLimit),
            trackRepository.observeListenedGenreRows(since)
        ) { topTracks, artistRows, topAlbums, genreRows ->
            TopStats(topTracks, artistRows, topAlbums, genreRows)
        }

        return combine(core, tops) { coreStats, topStats ->
            val hourHistogram = hourHistogramOf(coreStats.hourBuckets)
            val timeBuckets = buildTimeBuckets(coreStats.dailyBuckets, period)
            val windowDays = daysInWindow(coreStats.dailyBuckets, period)

            StatsUiState(
                period = period,
                loading = false,
                totalPlays = coreStats.overview.totalPlays,
                totalDurationMs = coreStats.overview.totalDurationMs,
                uniqueTracks = coreStats.overview.uniqueTracks,
                uniqueArtists = coreStats.distinctArtistStrings
                    .flatMap(::splitCredited)
                    .distinct()
                    .size,
                dailyAverageMs = if (hourHistogram.sum() > 0 && windowDays > 0) {
                    coreStats.overview.totalDurationMs / windowDays
                } else 0L,
                peakHour = peakHourOf(coreStats.hourBuckets),
                timeBuckets = timeBuckets,
                hourHistogram = hourHistogram,
                topTracks = topStats.topTracks,
                topArtists = aggregateArtistRows(topStats.artistRows),
                topAlbums = topStats.topAlbums,
                topGenres = aggregateGenres(topStats.genreRows)
            )
        }.onStart { emit(StatsUiState(period = period, loading = true)) }
            // Aggregations (artist/genre crediting, time buckets) run per
            // emission; keep them off the collecting (main) thread.
            .flowOn(Dispatchers.Default)
    }

    /** Overview counters + time-distribution source rows of one period. */
    private data class CoreStats(
        val overview: com.foamplayer.app.data.db.dao.ListenOverview,
        val distinctArtistStrings: List<String>,
        val dailyBuckets: List<DailyPlayBucket>,
        val hourBuckets: List<HourBucket>
    )

    /** Ranked-list source rows of one period. */
    private data class TopStats(
        val topTracks: List<com.foamplayer.app.data.db.dao.TopTrackStat>,
        val artistRows: List<ArtistPlayRow>,
        val topAlbums: List<com.foamplayer.app.data.db.dao.TopAlbumStat>,
        val genreRows: List<GenrePlayRow>
    )

    private fun hourHistogramOf(buckets: List<HourBucket>): List<Int> =
        List(24) { hour -> buckets.find { it.hourOfDay == hour }?.playCount ?: 0 }

    private fun peakHourOf(buckets: List<HourBucket>): Int? =
        buckets.maxByOrNull { it.playCount }?.takeIf { it.playCount > 0 }?.hourOfDay

    /** Aggregates the per-raw-genre rows into credited genres, ranked by plays. */
    private fun aggregateGenres(rows: List<GenrePlayRow>): List<GenreStat> {
        val totals = HashMap<String, Int>()
        for (row in rows) {
            for (name in splitCredited(row.rawGenre)) {
                totals[name] = (totals[name] ?: 0) + row.playCount
            }
        }
        return totals.entries
            .sortedByDescending { it.value }
            .take(GenreListLimit)
            .map { GenreStat(it.key, it.value) }
    }

    /**
     * Builds the activity chart's buckets: every calendar day/week/month of the
     * period window (zero-filled between the first listen and today), with its
     * play count and listening time summed from the daily rows.
     */
    private fun buildTimeBuckets(
        daily: List<DailyPlayBucket>,
        period: StatsPeriod
    ): List<StatsTimeBucket> {
        if (daily.isEmpty()) return emptyList()

        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        val today = Instant.now().atZone(zone).toLocalDate()
        val playsByDay = daily.associateBy { it.dayIndex }
        val firstDataDay = daily.minOf { it.dayIndex }

        // The window the chart covers: the period's first local day (or the
        // first listen for all-time) through today, inclusive.
        val windowStart = period.durationMs
            ?.let { window ->
                Instant.ofEpochMilli(System.currentTimeMillis() - window)
                    .atZone(zone).toLocalDate().toEpochDay()
            }
            ?.coerceAtLeast(firstDataDay)
            ?: firstDataDay
        val endDay = today.toEpochDay()

        val dayLabels = { date: LocalDate ->
            when (period.chartBucket) {
                ChartBucket.DAY -> date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
                ChartBucket.WEEK -> date.dayOfMonth.toString()
                ChartBucket.MONTH -> date.month.getDisplayName(TextStyle.SHORT, locale)
            }
        }

        return when (period.chartBucket) {
            ChartBucket.DAY -> (windowStart..endDay).map { day ->
                val row = playsByDay[day]
                StatsTimeBucket(
                    startEpochDay = day,
                    label = dayLabels(LocalDate.ofEpochDay(day)),
                    playCount = row?.playCount ?: 0,
                    totalDurationMs = row?.totalDurationMs ?: 0L
                )
            }
            ChartBucket.WEEK -> {
                val firstMonday = LocalDate.ofEpochDay(windowStart)
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                generateSequence(firstMonday) { it.plusWeeks(1) }
                    .takeWhile { !it.isAfter(today) }
                    .map { weekStart ->
                        val days = weekStart.toEpochDay()..minOf(
                            weekStart.plusWeeks(1).toEpochDay() - 1,
                            endDay
                        )
                        StatsTimeBucket(
                            startEpochDay = weekStart.toEpochDay(),
                            label = dayLabels(weekStart),
                            playCount = days.sumOf { playsByDay[it]?.playCount ?: 0 },
                            totalDurationMs = days.sumOf { playsByDay[it]?.totalDurationMs ?: 0L }
                        )
                    }
                    .toList()
            }
            ChartBucket.MONTH -> {
                val firstMonth = LocalDate.ofEpochDay(windowStart).withDayOfMonth(1)
                generateSequence(firstMonth) { it.plusMonths(1) }
                    .takeWhile { !it.isAfter(today) }
                    .map { monthStart ->
                        val days = monthStart.toEpochDay()..minOf(
                            monthStart.withDayOfMonth(monthStart.lengthOfMonth()).toEpochDay(),
                            endDay
                        )
                        StatsTimeBucket(
                            startEpochDay = monthStart.toEpochDay(),
                            label = dayLabels(monthStart),
                            playCount = days.sumOf { playsByDay[it]?.playCount ?: 0 },
                            totalDurationMs = days.sumOf { playsByDay[it]?.totalDurationMs ?: 0L }
                        )
                    }
                    .toList()
            }
        }
    }

    private fun daysInWindow(daily: List<DailyPlayBucket>, period: StatsPeriod): Int {
        if (daily.isEmpty()) return 0
        val zone = ZoneId.systemDefault()
        val today = Instant.now().atZone(zone).toLocalDate().toEpochDay()
        val firstDataDay = daily.minOf { it.dayIndex }
        val windowStart = period.durationMs
            ?.let { Instant.ofEpochMilli(System.currentTimeMillis() - it).atZone(zone).toLocalDate().toEpochDay() }
            ?.coerceAtLeast(firstDataDay)
            ?: firstDataDay
        return (today - windowStart + 1).toInt().coerceAtLeast(1)
    }

    private companion object {
        /**
         * Size of the ranked album query (the cards display the first few, with
         * a "see all" page behind them). Top tracks are uncapped.
         */
        const val TopListLimit = 50

        /** Number of genre bars the overview shows (genres have no see-all page). */
        const val GenreListLimit = 10
    }
}
