package com.foamplayer.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foamplayer.app.data.db.dao.AlbumRecommendation
import com.foamplayer.app.data.db.dao.PlaylistRecommendation
import com.foamplayer.app.data.db.entities.PlaylistEntity
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.repository.PlaylistRepository
import com.foamplayer.app.data.repository.TrackRepository
import com.foamplayer.app.data.scanner.ArtistCover
import com.foamplayer.app.data.scanner.ArtistCoverProvider
import com.foamplayer.app.data.scanner.PlaylistCover
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** An artist plus the total plays of its songs (Home "Favorite artists" row). */
data class FavoriteArtist(
    val name: String,
    val totalPlayCount: Int
)

/**
 * Data for the Home screen's five rows and their "See all" pages. Every flow
 * holds the full sorted list (DAO-capped at 50); the rows render the first
 * five entries and the see-all pages all fifty, so both ends of a "See all"
 * always agree on the ordering.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val playlistRepository: PlaylistRepository,
    artistCoverProvider: ArtistCoverProvider
) : ViewModel() {

    /** All playlists, for resolving a recommendation into its real entity (long-press modal). */
    val playlists: StateFlow<List<PlaylistEntity>> =
        playlistRepository.getAllPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Playlists ranked by the aggregate play count + likes of the songs they contain. */
    val recommendedPlaylists: StateFlow<List<PlaylistRecommendation>> =
        playlistRepository.getRecommendedPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Resolved covers per playlist id (custom image first, else the first
     * track in custom order carrying embedded art, else absent = letter
     * fallback), mirroring how the artist cards resolve their pictures.
     */
    val playlistCovers: StateFlow<Map<Long, PlaylistCover>> =
        playlistRepository.getPlaylistCovers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Albums ranked by the aggregate play count + likes of their tracks. */
    val recommendedAlbums: StateFlow<List<AlbumRecommendation>> =
        trackRepository.getRecommendedAlbums()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Tracks with at least one listen, newest listen first. */
    val recentTracks: StateFlow<List<TrackEntity>> =
        trackRepository.getRecentlyPlayed()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Long-standing favorites: tracks with listening history whose last listen
     * is older than [OldFavoritesCutoffMs] (plus never-played-but-liked ones),
     * ranked by aggregate play count + likes — surfacing long-standing
     * favorites rather than recent hits.
     */
    val oldFavoriteTracks: StateFlow<List<TrackEntity>> =
        trackRepository.getOldFavorites(System.currentTimeMillis() - OldFavoritesCutoffMs)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Artists ranked by the total plays of their songs. Artist names split on
     * the app-wide "; " convention (the same rule as the repository's artist
     * listings) with each credited share of a play counted once; artists
     * without any play are not "favorites".
     */
    val favoriteArtists: StateFlow<List<FavoriteArtist>> =
        trackRepository.getAllTracks()
            .map { tracks ->
                val totals = HashMap<String, Int>()
                for (track in tracks) {
                    track.artist.split("; ")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .forEach { artist -> totals.merge(artist, track.playCount, Int::plus) }
                }
                totals.entries.asSequence()
                    .filter { it.value > 0 }
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Int>> { it.value }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key }
                    )
                    .map { FavoriteArtist(it.key, it.value) }
                    .toList()
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Resolved pictures per artist name: a cached Spotify image when the scan
     * found one, else the artist's first track with embedded art (both are
     * automatic — no scan has to run for the fallback), for the
     * favorite-artist cards and their see-all page.
     */
    val artistImages: StateFlow<Map<String, ArtistCover>> =
        artistCoverProvider.coversFlow(favoriteArtists.map { artists -> artists.map { it.name } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Long-press collection actions for the see-all pages, mirroring the browse
    // screens: resolve the tracks behind a collection at action time.

    /** One album's tracks in disc/track order (mirrors TrackDao.getTracksByAlbum). */
    suspend fun tracksForAlbum(album: String): List<TrackEntity> =
        trackRepository.getTracksByAlbum(album).first()

    /** Tracks naming [artist] as artist or album artist, in album order (mirrors the browse screen's matching). */
    suspend fun tracksForArtist(artist: String): List<TrackEntity> = withContext(Dispatchers.Default) {
        trackRepository.getAllTracks().first()
            .filter { it.artist == artist || it.albumArtist == artist }
            .sortedWith(compareBy({ it.album }, { it.discNumber }, { it.trackNumber }, { it.title }))
    }

    companion object {
        /** Listens older than this count as "long-standing" for the Old favorites row (14 days). */
        private const val OldFavoritesCutoffMs = 14L * 24 * 60 * 60 * 1000
    }
}
