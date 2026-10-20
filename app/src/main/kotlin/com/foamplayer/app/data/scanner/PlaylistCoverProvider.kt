package com.foamplayer.app.data.scanner

import com.foamplayer.app.data.db.dao.PlaylistDao
import com.foamplayer.app.data.db.dao.TrackDao
import com.foamplayer.app.data.db.entities.PlaylistEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * One playlist's resolved cover, plus how Coil must load it: a user-chosen
 * cover image is a plain content URI, while the automatic fallback (the first
 * track in the playlist's custom order that carries embedded art) is an audio
 * file whose artwork has to be extracted — the same distinction the UI
 * components' `imageIsEmbeddedArt` flag models.
 */
data class PlaylistCover(
    val imageUrl: String,
    val isEmbeddedArt: Boolean
)

/**
 * Automatic playlist covers, on top of the user's own choice: a custom cover
 * image always wins, playlists without one fall back to the first track in
 * their stored (custom drag & drop) order that carries embedded art — so every
 * playlist row shows a picture instead of the letter monogram whenever any of
 * its tracks has art, with no extra settings. No entry means neither source
 * produced anything and the caller renders its letter fallback.
 */
@Singleton
class PlaylistCoverProvider @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao
) {
    /**
     * Playlist id -> resolved cover, rebuilt whenever the playlist table
     * changes (custom covers set/cleared), the track table changes (new
     * fallback sources) or playlist memberships/order change (the fallback
     * walk follows the custom order), so cards fill in live.
     */
    fun coversFlow(playlists: Flow<List<PlaylistEntity>>): Flow<Map<Long, PlaylistCover>> =
        combine(
            playlists,
            trackDao.getAllTracks(),
            playlistDao.getAllPlaylistTrackOrder()
        ) { playlists, tracks, orderEntries -> Triple(playlists, tracks, orderEntries) }
            .map { (playlists, tracks, orderEntries) ->
                val trackById = tracks.associateBy { it.id }

                // First track with embedded art per playlist, walking each
                // playlist's stored (custom) order: the DAO emits memberships
                // ordered by (playlistId, orderIndex), so putIfAbsent keeps
                // the custom-order first track. Tracks no longer in the
                // library cannot contribute.
                val fallbacks = HashMap<Long, String>(playlists.size)
                for (entry in orderEntries) {
                    if (fallbacks.containsKey(entry.playlistId)) continue
                    val track = trackById[entry.trackId] ?: continue
                    val art = track.albumArtPath ?: continue
                    fallbacks[entry.playlistId] = art
                }

                val map = HashMap<Long, PlaylistCover>(playlists.size)
                for (playlist in playlists) {
                    val custom = playlist.imageUrl?.takeIf { it.isNotBlank() }
                    if (custom != null) {
                        if (custom.contains("://")) continue
                        map[playlist.playlistId] = PlaylistCover(custom, isEmbeddedArt = false)
                    } else {
                        fallbacks[playlist.playlistId]?.let {
                            map[playlist.playlistId] = PlaylistCover(it, isEmbeddedArt = true)
                        }
                    }
                }
                map
            }
            // The map build runs off the collecting context, so screens
            // subscribe without main-thread churn.
            .flowOn(Dispatchers.Default)
}
