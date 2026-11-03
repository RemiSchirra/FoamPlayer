package com.foamplayer.app.data.scanner

import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.repository.ArtistImageStore
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.spotify.SpotifyArtistImageResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live progress of the artist image scan shown in Settings > Library. */
data class ArtistImageScanState(
    /** A scan is currently resolving artists. */
    val running: Boolean = false,
    /** Artists of the library that have an image (cached or freshly resolved). */
    val found: Int = 0,
    /** Total number of distinct artists in the library. */
    val total: Int = 0,
    /** Artists whose image file is currently in the app cache directory. */
    val cachedCount: Int = 0
)

/**
 * Resolves a picture for every distinct artist of the library through the
 * Spotify artist image provider, downloading each image into the app cache
 * ([ArtistImageStore]). The library's artist strings follow the "; "
 * multi-artist convention, so the scan works on the distinct individual names
 * ("A; B" stores A's picture under A and B's under B).
 *
 * Already-resolved artists are skipped entirely, so re-running the scan only
 * resolves the gaps. Every stored picture enters the store's strict LRU
 * queue, so the "artists to cache" setting bounds the cache however entries
 * were added.
 */
@Singleton
class ArtistImageScanner @Inject constructor(
    private val trackDao: TrackDao,
    private val artistImageResolver: SpotifyArtistImageResolver,
    private val artistImageStore: ArtistImageStore,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(ArtistImageScanState())
    val state: StateFlow<ArtistImageScanState> = _state.asStateFlow()

    init {
        // Seed found/total/cached from the library + persisted store (no network).
        scope.launch { refreshCounts() }
    }

    /** Recomputes found/total/cached without resolving anything. */
    suspend fun refreshCounts() {
        val artists = libraryArtists()
        var found = 0
        for (artist in artists) {
            if (artistImageStore.hasEntry(ArtistImageStore.key(artist))) found++
        }
        if (scanJob?.isActive != true) {
            _state.value = ArtistImageScanState(
                running = false,
                found = found,
                total = artists.size,
                cachedCount = artistImageStore.cachedFileCount()
            )
        }
    }

    /**
     * Artist name -> cached image file URI, rebuilt whenever [artists] changes
     * or the store's content does (new images, evictions), so cards fill in
     * live. A display string ("A; B") falls back to its first individual
     * artist.
     */
    fun imagesFlow(artists: Flow<List<String>>): Flow<Map<String, String>> =
        combine(
            artists,
            artistImageStore.revisions
        ) { names, _ -> names }
            .map { names ->
                val map = HashMap<String, String>(names.size)
                for (name in names) {
                    imageFor(name)?.let { map[name] = it }
                }
                map
            }
            // The per-artist cache lookups (IO hops) and map build run off the
            // collecting context, so screens subscribe without main-thread churn.
            .flowOn(Dispatchers.Default)

    /**
     * Image for an artist page visit. When artist image resolution is on and
     * the "artists to cache" limit allows it, resolves the picture on demand
     * through Spotify, stores it in the visited-LRU cache and refreshes its
     * recency; when the feature is off (or the limit is 0), only the plain
     * cached read applies. A multi-artist display ("A; B") resolves its first
     * individual artist, matching [imageFor]'s fallback.
     */
    suspend fun imageOnVisit(display: String): String? {
        val settings = settingsRepository.settings.first()
        val limit = if (settings.artistImageResolveEnabled) settings.artistImageCacheLimit else 0
        var stored = false
        if (limit > 0) {
            val name = display.split("; ")
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
            if (name != null) {
                val key = ArtistImageStore.key(name)
                if (artistImageStore.hasEntry(key)) {
                    // Already cached: only the LRU recency moves.
                    artistImageStore.touch(key)
                } else {
                    val url = artistImageResolver.resolveArtistImage(name)
                    if (url != null) {
                        artistImageStore.rememberVisited(key, url, limit)
                        stored = true
                    }
                }
            }
        }
        // Keep the Settings cache count in sync with the on-demand queue:
        // the scan state otherwise only refreshes on scan activity.
        if (stored) refreshCounts()
        return imageFor(display)
    }

    /** Cached image for a (possibly multi-artist) display string, if any. */
    suspend fun imageFor(display: String): String? {
        artistImageStore.imagePath(ArtistImageStore.key(display))?.let { return it }
        val first = display.split("; ")
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        if (first.equals(display.trim(), ignoreCase = true)) return null
        return artistImageStore.imagePath(ArtistImageStore.key(first))
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch { scan() }
    }

    /** Cancels the running scan mid-artist; the counts settle from the store. */
    fun stopScan() {
        val job = scanJob ?: return
        job.cancel()
        scanJob = null
        scope.launch { refreshCounts() }
    }

    /** Deletes every cached artist image file and the stored URL mapping. */
    fun clearCache() {
        scope.launch {
            artistImageStore.clearAll()
            refreshCounts()
        }
    }

    /** Applies a new cache limit: trims the LRU queue and refreshes the counts. */
    fun enforceCacheLimit(limit: Int) {
        scope.launch {
            artistImageStore.evictToLimit(limit)
            refreshCounts()
        }
    }

    private suspend fun scan() {
        val artists = libraryArtists()
        if (artists.isEmpty()) {
            _state.value = ArtistImageScanState()
            return
        }
        try {
            var found = artists.count { artistImageStore.hasEntry(ArtistImageStore.key(it)) }
            _state.value = ArtistImageScanState(running = true, found = found, total = artists.size)

            val pending = artists.filter { !artistImageStore.hasEntry(ArtistImageStore.key(it)) }
            for (artist in pending) {
                val limit = settingsRepository.settings.first().artistImageCacheLimit
                if (limit <= 0) break
                val key = ArtistImageStore.key(artist)
                if (artistImageStore.hasEntry(key)) continue
                val url = artistImageResolver.resolveArtistImage(artist)
                if (url != null) {
                    artistImageStore.remember(key, url, limit)
                    found++
                }
                _state.value = ArtistImageScanState(
                    running = true,
                    found = found,
                    total = artists.size,
                    cachedCount = artistImageStore.cachedFileCount()
                )
                delay(REQUEST_SPACING_MS)
            }
        } finally {
            // Terminal state on completion AND when the scan is stopped
            // mid-artist: NonCancellable so the cancellation path still
            // publishes settled counts instead of leaving a stale "running".
            withContext(NonCancellable) {
                val artistsLeft = libraryArtists()
                _state.value = ArtistImageScanState(
                    running = false,
                    found = artistsLeft.count { artistImageStore.hasEntry(ArtistImageStore.key(it)) },
                    total = artistsLeft.size,
                    cachedCount = artistImageStore.cachedFileCount()
                )
            }
        }
    }

    /** Every distinct individual artist of the library (splits multi-artist tags). */
    private suspend fun libraryArtists(): List<String> =
        trackDao.getAllArtistNames().first()
            .flatMap { it.split("; ") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private companion object {
        /** Pause between Spotify lookups so the scan stays polite. */
        const val REQUEST_SPACING_MS = 250L
    }
}
