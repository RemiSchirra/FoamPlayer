package com.foamplayer.app.ui.screens.browse

import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.distant.DistantRepository
import com.foamplayer.app.data.repository.SettingsRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ArtistCover
import com.foamplayer.app.data.scanner.ArtistCoverProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import androidx.core.net.toUri
import com.foamplayer.distant.DistantServer
import com.foamplayer.distant.DistantUris

/** A browsable folder: its storage path, display name and recursive track count. */
data class FolderEntry(
    val path: String,
    val name: String,
    val trackCount: Int,
    val isDistant: Boolean = false,
)

/** Path of a distant server's flat root folder, matching DistantUris' track-path shape minus the id. */
internal fun distantRootPath(server: DistantServer): String =
    "${DistantUris.SCHEME}://${server.type.name.lowercase()}/${server.id}"

/**
 * Human title for a distant server folder: "jellyfin:example.com" — provider
 * plus the domain of the server URL ("http://jellyfin.example.com" →
 * "example.com") instead of the raw generated id.
 */
internal fun distantServerTitle(server: DistantServer): String =
    "${server.type.displayName}:${distantServerDomain(server)}"

/** Registrable-ish domain of [server]'s base URL, e.g. "jellyfin.example.com" → "example.com". */
internal fun distantServerDomain(server: DistantServer): String {
    val host = server.baseUrl.toUri().host ?: return server.name
    // IP addresses (and IPv6 hosts, which contain ':') are shown verbatim —
    if (host.contains(':') || host.all { it.isDigit() || it == '.' }) return host
    val labels = host.split('.')
    // Drop the first label (e.g. the "jellyfin" subdomain) when something is
    // left; a bare "example.com" stays intact.
    return if (labels.size >= 3) labels.drop(1).joinToString(".") else host
}

/** The content of one folder level: subfolders plus the tracks directly inside. */
data class FolderContent(
    val path: String?,
    val folders: List<FolderEntry>,
    val tracks: List<TrackEntity>
)

/**
 * Process-wide memo of each track path's document id: parsing a content URI
 * per track was the dominant cost of every folder listing. A path is immutable
 * for a given track row, so the parse happens once per path per process.
 */
private object TrackDocumentIds {
    private val cache = ConcurrentHashMap<String, String>()

    fun of(path: String): String = cache.computeIfAbsent(path, ::parseDocumentId)

    private fun parseDocumentId(trackPath: String): String = try {
        val uri = trackPath.toUri()
        when (uri.scheme) {
            "content" -> DocumentsContract.getDocumentId(uri)
            "file" -> uri.path ?: trackPath
            else -> trackPath
        }
    } catch (_: Exception) {
        trackPath
    }
}

/**
 * Process-wide cache of built folder listings: every folder screen is a new
 * ViewModel, so without this each click rebuilt its listing from scratch.
 * Entries remember which tracks snapshot they were built from, so a hit is
 * known to be current.
 */
private object FolderListingCache {
    private class Entry(
        val content: FolderContent,
        val sourceTracks: List<TrackEntity>
    )

    private val entries = HashMap<String, Entry>()

    /** Tracks of the most recent build — the warm source for click-time pre-caching. */
    @Volatile
    var latestTracks: List<TrackEntity>? = null
        private set

    @Synchronized
    fun contentFor(path: String?): FolderContent? = entries[path.orEmpty()]?.content

    @Synchronized
    fun sourceTracksFor(path: String?): List<TrackEntity>? = entries[path.orEmpty()]?.sourceTracks

    @Synchronized
    fun put(path: String?, content: FolderContent, sourceTracks: List<TrackEntity>) {
        entries[path.orEmpty()] = Entry(content, sourceTracks)
        latestTracks = sourceTracks
    }
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val settingsRepository: SettingsRepository,
    private val distantRepository: DistantRepository,
    artistCoverProvider: ArtistCoverProvider
) : ViewModel() {

    val albums: StateFlow<List<AlbumSearchResult>> = trackRepository.getAllAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTracks: StateFlow<List<TrackEntity>> = trackRepository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists: StateFlow<List<String>> = trackRepository.getAllArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val composers: StateFlow<List<String>> = trackRepository.getAllComposers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Resolved pictures per artist name; rebuilt live as the scan finds new files. */
    val artistImages: StateFlow<Map<String, ArtistCover>> =
        artistCoverProvider.coversFlow(artists)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val genres: StateFlow<List<String>> = trackRepository.getAllGenres()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val years: StateFlow<List<Int>> = trackRepository.getAllYears()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _genreTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val genreTracks: StateFlow<List<TrackEntity>> = _genreTracks.asStateFlow()

    private val _yearTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val yearTracks: StateFlow<List<TrackEntity>> = _yearTracks.asStateFlow()

    private val _composerTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val composerTracks: StateFlow<List<TrackEntity>> = _composerTracks.asStateFlow()

    private val _folderContent = MutableStateFlow<FolderContent?>(null)
    val folderContent: StateFlow<FolderContent?> = _folderContent.asStateFlow()

    private var genreTracksJob: Job? = null
    private var yearTracksJob: Job? = null
    private var composerTracksJob: Job? = null
    private var folderContentJob: Job? = null

    fun loadGenreTracks(genre: String) {
        genreTracksJob?.cancel()
        genreTracksJob = viewModelScope.launch {
            // Seed from the process-wide snapshot so revisits render instantly;
            // the live query then corrects/updates the list. The snapshot
            // filter+sort is an O(n) full-library pass — keep it off the main
            // dispatcher.
            if (_genreTracks.value.isEmpty()) {
                _genreTracks.value = withContext(Dispatchers.Default) {
                    trackRepository.allTracksSnapshot().filter { track ->
                        track.genre?.split("; ")?.map { it.trim() }?.contains(genre) == true
                    }.sortedWith(compareBy { it.title })
                }
            }
            trackRepository.getTracksByGenre(genre).collect { _genreTracks.value = it }
        }
    }

    fun loadYearTracks(year: Int) {
        yearTracksJob?.cancel()
        yearTracksJob = viewModelScope.launch {
            if (_yearTracks.value.isEmpty()) {
                _yearTracks.value = withContext(Dispatchers.Default) {
                    trackRepository.allTracksSnapshot()
                        .filter { it.year == year }
                        .sortedWith(
                            compareBy(
                                { it.album },
                                { it.discNumber },
                                { it.trackNumber },
                                { it.title }
                            )
                        )
                }
            }
            trackRepository.getTracksByYear(year).collect { _yearTracks.value = it }
        }
    }

    fun loadComposerTracks(composer: String) {
        composerTracksJob?.cancel()
        composerTracksJob = viewModelScope.launch {
            if (_composerTracks.value.isEmpty()) {
                _composerTracks.value = withContext(Dispatchers.Default) {
                    trackRepository.allTracksSnapshot().filter { track ->
                        track.composer?.split("; ")?.map { it.trim() }?.contains(composer) == true
                    }.sortedWith(compareBy { it.title })
                }
            }
            trackRepository.getTracksByComposer(composer).collect { _composerTracks.value = it }
        }
    }

    /**
     * Builds [folderPath]'s listing synchronously from the last warm snapshot
     * so the destination screen has content on its very first frame. Called
     * from the parent screen's row click, right before navigating.
     */
    fun precacheFolder(folderPath: String) {
        val tracks = FolderListingCache.latestTracks ?: return
        FolderListingCache.put(
            folderPath,
            buildFolderContent(folderPath, tracks, emptySet(), emptyList()),
            tracks
        )
    }

    fun loadFolderContent(path: String?) {
        folderContentJob?.cancel()
        // Frame-1 fast path: publish any previously built listing for this
        // exact folder before the first frame.
        FolderListingCache.contentFor(path)?.let { _folderContent.value = it }
        folderContentJob = viewModelScope.launch {
            // Subfolder listings group by document-id prefix and never consult
            // the library roots, so only the root screen pays the DataStore read.
            val settings = if (path == null) settingsRepository.settings.first() else null
            val libraryFolders = settings?.libraryFolders ?: emptySet()
            val distantServers = settings?.distantServers ?: emptyList()
            val tracks = trackRepository.allTracksSnapshot()
            if (FolderListingCache.sourceTracksFor(path) !== tracks) {
                val built = withContext(Dispatchers.Default) {
                    buildFolderContent(path, tracks, libraryFolders, distantServers)
                }
                FolderListingCache.put(path, built, tracks)
                _folderContent.value = built
            }
            // Keep observing the live stream; its first emission is the same
            // list instance as the snapshot just used, so skip it.
            trackRepository.getAllTracks().collect { liveTracks ->
                if (liveTracks === tracks) return@collect
                val rebuilt = withContext(Dispatchers.Default) {
                    buildFolderContent(path, liveTracks, libraryFolders, distantServers)
                }
                FolderListingCache.put(path, rebuilt, liveTracks)
                _folderContent.value = rebuilt
            }
        }
    }

    // Long-press collection actions: resolve the tracks behind a browsable
    // collection at action time, mirroring the DAO query the collection is
    // browsed through, so queued lists match what the user sees.

    /** One album's tracks in disc/track order. */
    suspend fun tracksForAlbum(album: String): List<TrackEntity> =
        trackRepository.getTracksByAlbum(album).first()

    /** Tracks naming [artist] as artist or album artist, in album order. */
    suspend fun tracksForArtist(artist: String): List<TrackEntity> = withContext(Dispatchers.Default) {
        trackRepository.getAllTracks().first()
            .filter { it.artist == artist || it.albumArtist == artist }
            .sortedWith(compareBy({ it.album }, { it.discNumber }, { it.trackNumber }, { it.title }))
    }

    /** Tracks crediting [composer] among their "; "-separated composers. */
    suspend fun tracksForComposer(composer: String): List<TrackEntity> =
        trackRepository.getTracksByComposer(composer).first()

    /** Tracks tagging [genre] among their "; "-separated genres. */
    suspend fun tracksForGenre(genre: String): List<TrackEntity> =
        trackRepository.getTracksByGenre(genre).first()

    /** One year's tracks in album order. */
    suspend fun tracksForYear(year: Int): List<TrackEntity> =
        trackRepository.getTracksByYear(year).first()

    /** Tracks stored under [folderPath] (document-id prefix match, same rule as [buildFolderContent]). */
    suspend fun tracksForFolder(folderPath: String): List<TrackEntity> = withContext(Dispatchers.Default) {
        trackRepository.getAllTracks().first()
            .filter { TrackDocumentIds.of(it.path).startsWith("$folderPath/") }
    }

    /**
     * Title for one folder path: distant server roots render as
     * "Jellyfin:example.com" (see [distantServerTitle]); local folders render
     * their leaf name.
     */
    fun folderTitle(path: String): String {
        if (DistantUris.isDistant(path)) {
            val serverId = path
                .removePrefix("${DistantUris.SCHEME}://")
                .substringAfter('/', missingDelimiterValue = "")
                .substringBefore('/')
            val server = distantRepository.server(serverId)
            if (server != null) return distantServerTitle(server)
            // Server unknown (cache not warm / just disconnected): show the
            // provider segment rather than the raw server id.
            return path.removePrefix("${DistantUris.SCHEME}://").substringBefore('/')
        }
        return path.substringAfterLast('/').ifEmpty { path.substringAfterLast(':') }
    }

    private fun buildFolderContent(
        path: String?,
        tracks: List<TrackEntity>,
        libraryFolders: Set<String>,
        distantServers: List<DistantServer>
    ): FolderContent {
        if (path == null) {
            val localRoots = libraryFolders.mapNotNull { treeDocumentId(it) }
            val distantRoots = distantServers.associate { server ->
                distantRootPath(server) to distantServerTitle(server)
            }
            val roots = localRoots.ifEmpty {
                tracks.map { parentDocumentIdOf(it.path) }
                    .filter { it.isNotBlank() && !DistantUris.isDistant(it) }
                    .distinct()
            } + distantRoots.keys
            val entries = roots
                .map { root ->
                    FolderEntry(
                        path = root,
                        name = distantRoots[root] ?: folderDisplayName(root),
                        trackCount = tracks.count { TrackDocumentIds.of(it.path).startsWith("$root/") },
                        isDistant = DistantUris.isDistant(root)
                    )
                }
                .filter { it.trackCount > 0 }
                .sortedBy { it.name.lowercase() }
            return FolderContent(path = null, folders = entries, tracks = emptyList())
        }

        val prefix = "$path/"
        val directTracks = mutableListOf<TrackEntity>()
        val subFolderCounts = mutableMapOf<String, Int>()

        tracks.forEach { track ->
            val docId = TrackDocumentIds.of(track.path)
            if (!docId.startsWith(prefix)) return@forEach
            val remainder = docId.removePrefix(prefix)
            val slashIndex = remainder.indexOf('/')
            if (slashIndex == -1) {
                directTracks.add(track)
            } else {
                val subFolder = remainder.take(slashIndex)
                subFolderCounts[subFolder] = (subFolderCounts[subFolder] ?: 0) + 1
            }
        }

        val subFolders = subFolderCounts
            .map { (name, count) -> FolderEntry(path = "$prefix$name", name = name, trackCount = count) }
            .sortedBy { it.name.lowercase() }

        return FolderContent(path = path, folders = subFolders, tracks = directTracks)
    }

    private fun parentDocumentIdOf(trackPath: String): String =
        TrackDocumentIds.of(trackPath).substringBeforeLast('/', missingDelimiterValue = "")

    private fun treeDocumentId(folderUri: String): String? {
        return try {
            val uri = folderUri.toUri()
            if (uri.scheme == "content") DocumentsContract.getTreeDocumentId(uri) else uri.path
        } catch (_: Exception) {
            null
        }
    }

    private fun folderDisplayName(path: String): String =
        path.substringAfterLast('/').ifEmpty { path.substringAfterLast(':') }
}
