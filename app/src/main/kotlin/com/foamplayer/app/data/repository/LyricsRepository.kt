package com.foamplayer.app.data.repository

import android.content.Context
import android.util.LruCache
import androidx.core.net.toUri
import com.foamplayer.app.constants.LyricsSource
import com.foamplayer.app.data.db.dao.CachedLyricsDao
import com.foamplayer.app.data.db.entities.CachedLyricsEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.lyrics.AudioLyricsTagWriter
import com.foamplayer.app.data.lyrics.LyricsDeleteResult
import com.foamplayer.app.data.lyrics.LyricsFileWriter
import com.foamplayer.app.data.lyrics.LyricsSearchHelper
import com.foamplayer.app.data.lyrics.LyricsSearchResult
import com.foamplayer.app.data.lyrics.LyricsSourceStatus
import com.foamplayer.app.data.lyrics.LyricsStatusScanner
import com.foamplayer.app.data.lyrics.ResolvedLyrics
import com.foamplayer.app.data.settings.AppSettings
import com.foamplayer.app.lyrics.LyricsProvider
import com.foamplayer.app.lyrics.LyricsUtils
import com.foamplayer.app.lyrics.lyricsProvider
import com.foamplayer.app.lyrics.providers.EmbeddedLyricsProvider
import com.foamplayer.app.lyrics.providers.LocalLyricsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lyrics data layer: resolves lyrics through the user's configured source order
 * (online cache → sidecar file → embedded tag), manages the persisted online
 * cache, and provides search/status/write/delete operations over all sources.
 */
@Singleton
class LyricsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val localLyricsProvider: LocalLyricsProvider,
    private val embeddedLyricsProvider: EmbeddedLyricsProvider,
    private val cachedLyricsDao: CachedLyricsDao,
    private val lyricsFileWriter: LyricsFileWriter,
    private val statusScanner: LyricsStatusScanner
) {
    /** Session memo of searchable sidecar + embedded text, keyed by size. LRU-bounded. */
    private val searchTextCache = LruCache<Long, Pair<Long, String?>>(SEARCH_TEXT_CACHE_ENTRIES)

    suspend fun getLyrics(track: TrackEntity): String? = resolveLyrics(track)?.content

    /** Resolves lyrics through the configured source order, tagging the winning source. */
    suspend fun resolveLyrics(track: TrackEntity): ResolvedLyrics? = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        for (source in settings.lyricsSourceOrder) {
            if (source !in settings.enabledLyricsSources) continue
            val resolved = when (source) {
                LyricsSource.ONLINE -> resolveOnline(settings, track)
                LyricsSource.LYRICS_FILE -> resolveFromFile(track)
                LyricsSource.EMBEDDED -> resolveFromEmbedded(track)
            }
            if (resolved != null) return@withContext resolved
        }

        null
    }

    /**
     * Same as [resolveLyrics] but strictly local: ONLINE is served from the
     * persisted cache only. Used by the lyrics manager's read-only details modal.
     */
    suspend fun resolveLocalLyrics(track: TrackEntity): ResolvedLyrics? = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()

        for (source in settings.lyricsSourceOrder) {
            if (source !in settings.enabledLyricsSources) continue
            val resolved = when (source) {
                LyricsSource.ONLINE ->
                    cachedLyricsDao.getByTrackId(track.id)
                        ?.takeIf { it.lyrics.isNotBlank() }
                        ?.let { ResolvedLyrics(it.lyrics, LyricsSource.ONLINE, it.provider) }
                LyricsSource.LYRICS_FILE -> resolveFromFile(track)
                LyricsSource.EMBEDDED -> resolveFromEmbedded(track)
            }
            if (resolved != null) return@withContext resolved
        }

        null
    }

    private fun resolveFromFile(track: TrackEntity): ResolvedLyrics? =
        localLyricsProvider.getLyrics(track.path)
            ?.let { ResolvedLyrics(it, LyricsSource.LYRICS_FILE, null) }

    private fun resolveFromEmbedded(track: TrackEntity): ResolvedLyrics? =
        embeddedLyricsProvider.getLyrics(track.path.toUri())
            ?.let { ResolvedLyrics(it, LyricsSource.EMBEDDED, null) }

    /** Serves from the persisted cache, else queries providers in configured order. */
    private suspend fun resolveOnline(settings: AppSettings, track: TrackEntity): ResolvedLyrics? {
        cachedLyricsDao.getByTrackId(track.id)
            ?.takeIf { it.lyrics.isNotBlank() }
            ?.let { cached ->
                return ResolvedLyrics(cached.lyrics, LyricsSource.ONLINE, cached.provider)
            }

        val providers = settings.lyricsProviderOrder
            .filter { it in settings.enabledLyricsProviders }
            .mapNotNull { it.lyricsProvider }

        for (provider in providers) {
            if (!provider.isEnabled(context)) continue
            val lyrics = fetchFrom(provider, track)
            if (!lyrics.isNullOrBlank()) {
                runCatching {
                    cachedLyricsDao.upsert(
                        CachedLyricsEntity(
                            trackId = track.id,
                            lyrics = lyrics,
                            provider = provider.name,
                            fetchedAtMs = System.currentTimeMillis()
                        )
                    )
                }
                return ResolvedLyrics(lyrics, LyricsSource.ONLINE, provider.name)
            }
        }
        return null
    }

    /** Track ids that have online-fetched lyrics persisted in the cache. */
    fun observeCachedTrackIds(): Flow<List<Long>> = cachedLyricsDao.observeCachedTrackIds()

    /** Per-track availability of every source; all checks hit IO. */
    suspend fun getSourceStatus(track: TrackEntity): LyricsSourceStatus = withContext(Dispatchers.IO) {
        val hasCached = cachedLyricsDao.getByTrackId(track.id)?.lyrics?.isNotBlank() == true
        val hasFile = localLyricsProvider.getLyrics(track.path) != null
        val hasEmbedded = embeddedLyricsProvider.getLyrics(track.path.toUri()) != null
        LyricsSourceStatus(hasCached = hasCached, hasFile = hasFile, hasEmbedded = hasEmbedded)
    }

    /** Batched per-track status scan for the lyrics manager (see [LyricsStatusScanner]). */
    suspend fun scanSourceStatuses(
        tracks: List<TrackEntity>,
        onStatus: (Long, LyricsSourceStatus) -> Unit
    ) = statusScanner.scan(tracks, onStatus)

    /**
     * Streams every track of [tracks] whose local lyrics contain [query].
     * Never touches the network; tracks are inspected in parallel (bounded) and
     * matches stream as found. Cancelling the collector stops the scan.
     */
    fun searchLyricsText(query: String, tracks: List<TrackEntity>): Flow<TrackEntity> = channelFlow {
        val needle = query.trim()
        if (needle.isEmpty() || tracks.isEmpty()) return@channelFlow

        val cachedLyrics = runCatching { cachedLyricsDao.getAll() }
            .getOrDefault(emptyList())
            .associate { it.trackId to it.lyrics }

        val limiter = Semaphore(LYRICS_SEARCH_CONCURRENCY)
        coroutineScope {
            for (track in tracks) {
                launch {
                    limiter.withPermit {
                        val text = searchableLyricsText(track, cachedLyrics[track.id])
                        if (text != null && text.contains(needle, ignoreCase = true)) {
                            send(track)
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Joins the display text of every local lyrics source of [track]. */
    private suspend fun searchableLyricsText(track: TrackEntity, cached: String?): String? {
        val cachedText = cached
            ?.takeIf { it.isNotBlank() }
            ?.let { LyricsUtils.displayLyricsText(it) }
        val localText = localSearchableText(track)
        return when {
            cachedText == null -> localText
            localText == null -> cachedText
            else -> "$cachedText\n$localText"
        }
    }

    /** Display text of the sidecar file + embedded tag, memoized per session. */
    private suspend fun localSearchableText(track: TrackEntity): String? {
        searchTextCache.get(track.id)
            ?.takeIf { it.first == track.size }
            ?.let { return it.second }

        val text = listOfNotNull(
            runCatching { localLyricsProvider.getLyrics(track.path) }.getOrNull(),
            runCatching { embeddedLyricsProvider.getLyrics(track.path.toUri()) }.getOrNull()
        ).joinToString("\n") { LyricsUtils.displayLyricsText(it) }
            .ifBlank { null }

        searchTextCache.put(track.id, track.size to text)
        return text
    }

    /** Drops the memoized sidecar/embedded search text of [trackId]. */
    private fun invalidateSearchText(trackId: Long) {
        searchTextCache.remove(trackId)
    }

    /**
     * Queries every enabled provider concurrently and ranks results: metadata
     * similarity, then duration delta, then provider order. A provider that
     * errors or times out is skipped without failing the search.
     */
    suspend fun searchAllProviders(track: TrackEntity): List<LyricsSearchResult> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        val providers = settings.lyricsProviderOrder
            .filter { it in settings.enabledLyricsProviders }
            .mapNotNull { it.lyricsProvider }

        val perProvider: List<List<LyricsSearchResult>> = coroutineScope {
            providers.mapIndexed { providerIndex, provider ->
                async {
                    runCatching {
                        withTimeoutOrNull(PROVIDER_SEARCH_TIMEOUT_MS) {
                            searchProvider(provider, track, providerIndex)
                        }
                    }.getOrNull().orEmpty()
                }
            }
        }.awaitAll()

        perProvider.flatten()
            .distinctBy { LyricsUtils.normalizeLyricsText(it.content) }
            .sortedWith(
                compareByDescending<LyricsSearchResult> { it.similarityScore }
                    .thenBy { result -> durationDeltaRank(result, track) }
                    .thenBy { it.providerOrder }
            )
    }

    /** Saves [content] as a .lrc/.ttml sidecar next to the audio file. */
    suspend fun saveLyricsFile(track: TrackEntity, content: String, wordSynced: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            lyricsFileWriter.writeSidecar(track.path, content, wordSynced)
                .onSuccess { invalidateSearchText(track.id) }
        }

    /** Writes [content] into the audio file's embedded lyrics tag. */
    suspend fun embedLyrics(track: TrackEntity, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        AudioLyricsTagWriter.embed(context, track.path, content)
            .onSuccess {
                statusScanner.invalidate(track.id)
                invalidateSearchText(track.id)
            }
    }

    /** Removes every lyrics source from a track (cache, sidecars, embedded tag). */
    suspend fun deleteLyrics(track: TrackEntity): LyricsDeleteResult = withContext(Dispatchers.IO) {
        // Force the next status scan to re-read the audio file.
        statusScanner.invalidate(track.id)
        invalidateSearchText(track.id)

        val hadCached = runCatching { cachedLyricsDao.getByTrackId(track.id) != null }.getOrDefault(false)
        if (hadCached) {
            runCatching { cachedLyricsDao.deleteByTrackId(track.id) }
        }

        val fileDeleted = lyricsFileWriter.deleteSidecars(track.path).getOrDefault(0) > 0

        val embeddedRemoved = runCatching {
            AudioLyricsTagWriter.removeEmbeddedLyrics(context, track.path)
        }.getOrNull()?.getOrDefault(false) ?: false

        val embeddedStillPresent = embeddedLyricsProvider.getLyrics(track.path.toUri()) != null

        LyricsDeleteResult(
            cachedDeleted = hadCached,
            fileDeleted = fileDeleted,
            embeddedRemoved = embeddedRemoved,
            embeddedStillPresent = embeddedStillPresent
        )
    }

    private suspend fun fetchFrom(provider: LyricsProvider, track: TrackEntity): String? =
        try {
            provider.getLyrics(
                id = track.id.toString(),
                title = track.title,
                artist = track.artist,
                album = track.album,
                duration = (track.duration / 1000).toInt()
            ).getOrNull()?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun searchProvider(
        provider: LyricsProvider,
        track: TrackEntity,
        providerOrder: Int
    ): List<LyricsSearchResult> {
        if (!provider.isEnabled(context)) return emptyList()

        val contents = mutableListOf<String>()
        provider.getAllLyrics(
            id = track.id.toString(),
            title = track.title,
            artist = track.artist,
            album = track.album,
            duration = (track.duration / 1000).toInt()
        ) { lyrics -> contents.add(lyrics) }

        return contents.filter { LyricsUtils.hasMeaningfulLyricsContent(it) }
            .map { content ->
                LyricsSearchResult(
                    content = content,
                    providerName = provider.name,
                    type = LyricsSearchHelper.classify(content),
                    derivedDurationMs = LyricsSearchHelper.deriveDurationMs(content),
                    similarityScore = LyricsSearchHelper.metadataSimilarity(
                        resultLyrics = content,
                        title = track.title,
                        artist = track.artist,
                        album = track.album
                    ),
                    providerOrder = providerOrder
                )
            }
    }

    private fun durationDeltaRank(result: LyricsSearchResult, track: TrackEntity): Long {
        val derived = result.derivedDurationMs ?: return Long.MAX_VALUE
        return kotlin.math.abs(derived - track.duration)
    }

    private companion object {
        /** Per-provider cap so one slow provider cannot stall the whole search. */
        const val PROVIDER_SEARCH_TIMEOUT_MS = 20_000L

        /** Parallel track inspections during the local lyrics text search. */
        const val LYRICS_SEARCH_CONCURRENCY = 16

        /** Max memoized sidecar/embedded search texts kept in memory. */
        const val SEARCH_TEXT_CACHE_ENTRIES = 256
    }
}
