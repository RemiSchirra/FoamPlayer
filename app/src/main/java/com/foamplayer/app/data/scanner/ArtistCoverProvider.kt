package com.foamplayer.app.data.scanner

import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * One artist's resolved picture, plus how Coil must load it: a picture
 * resolved through Spotify ([ArtistImageScanner]) is a plain cache file, while
 * the library fallback (the artist's first track with embedded art) is an
 * audio file whose artwork has to be extracted — the same distinction the UI
 * components' `imageIsEmbeddedArt` flag models.
 */
data class ArtistCover(
    val imageUrl: String,
    val isEmbeddedArt: Boolean
)

/**
 * Automatic artist covers, independent of the Spotify scan: a scan-resolved
 * picture wins, artists without one fall back to their first track
 * (alphabetical by title, the same source the artist detail page uses) that
 * carries embedded art — so every artist row shows a picture instead of the
 * letter monogram whenever the library has any art for them, with no scan
 * having to run. No entry means neither source produced anything and the
 * caller renders its letter fallback.
 */
@Singleton
class ArtistCoverProvider @Inject constructor(
    private val trackDao: TrackDao,
    private val artistImageScanner: ArtistImageScanner
) {
    /**
     * Artist name -> resolved cover, rebuilt whenever [artists] changes, the
     * track table changes (new fallback sources) or the scan lands new files
     * (its cachedCount), so cards fill in live.
     */
    fun coversFlow(artists: Flow<List<String>>): Flow<Map<String, ArtistCover>> =
        combine(
            artists,
            trackDao.getAllTracks(),
            // Tick when the Spotify scan lands/clears images so the Spotify
            // winner is re-evaluated without polling the store.
            artistImageScanner.state.map { it.cachedCount }.distinctUntilChanged()
        ) { names, tracks, _ -> names to tracks }
            .map { (names, tracks) ->
                // First title-ordered track with embedded art per credited
                // artist: a single library pass, cheaper than a per-artist
                // scan. putIfAbsent keeps the title-alphabetical first track.
                val fallbacks = HashMap<String, String>(names.size)
                for (track in tracks) {
                    if (track.albumArtPath == null) continue
                    for (name in creditedNames(track)) {
                        fallbacks.putIfAbsent(name, track.albumArtPath)
                    }
                }

                val map = HashMap<String, ArtistCover>(names.size)
                for (name in names) {
                    val spotify = artistImageScanner.imageFor(name)
                    if (spotify != null) {
                        map[name] = ArtistCover(spotify, isEmbeddedArt = false)
                    } else {
                        fallbacks[name]?.let { map[name] = ArtistCover(it, isEmbeddedArt = true) }
                    }
                }
                map
            }
            // The per-artist cache lookups (IO hops) and map build run off the
            // collecting context, so screens subscribe without main-thread churn.
            .flowOn(Dispatchers.Default)
}

/**
 * The individual names [track] credits (the app-wide "; " convention), across
 * both the artist and album-artist fields.
 */
private fun creditedNames(track: TrackEntity): List<String> =
    (track.artist.split("; ") + track.albumArtist.orEmpty().split("; "))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
