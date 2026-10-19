package com.foamplayer.app.data.scanner

import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.distant.DistantArtworkStore
import com.foamplayer.app.data.distant.DistantRepository
import com.foamplayer.distant.DistantServer
import com.foamplayer.distant.DistantTrack
import com.foamplayer.distant.DistantUris
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the connected distant servers into [TrackEntity] rows. Remote tracks
 * carry a synthetic `foam-distant://` path that the player's data source
 * resolves to a real, authorized stream at playback time. Cover art is
 * downloaded once, here, and stored locally so it is never fetched again.
 */
@Singleton
class RemoteLibraryScanner @Inject constructor(
    private val repository: DistantRepository,
    private val artworkStore: DistantArtworkStore
) {

    /** Renews expired tokens before a scan. */
    suspend fun refreshTokens() = repository.refreshExpiredTokens()

    suspend fun scan(
        servers: List<DistantServer>,
        onProgress: (Float, String) -> Unit
    ): List<TrackEntity> {
        if (servers.isEmpty()) return emptyList()

        val totalFolders = servers.sumOf { it.folders.size }.coerceAtLeast(1)
        val listed = ArrayList<Pair<DistantServer, List<DistantTrack>>>()
        var foldersDone = 0

        servers.forEach { server ->
            server.folders.forEach { folder ->
                val label = "${server.name} · ${folder.name}"
                onProgress(foldersDone.toFloat() / (totalFolders * 2), label)
                val tracks = runCatching { repository.listTracks(server, folder) }.getOrDefault(emptyList())
                listed += server to tracks
                foldersDone++
                onProgress(foldersDone.toFloat() / (totalFolders * 2), label)
            }
        }

        val totalTracks = listed.sumOf { it.second.size }.coerceAtLeast(1)
        val result = ArrayList<TrackEntity>(totalTracks)
        // Album art is shared by many tracks: download each distinct URL once.
        val artworkCache = HashMap<String, String?>()
        var tracksDone = 0

        listed.forEach { (server, tracks) ->
            tracks.forEach { track ->
                onProgress(0.5f + tracksDone.toFloat() / (totalTracks * 2), track.title)
                result += track.toEntity(server, artworkCache)
                tracksDone++
            }
        }
        onProgress(1f, "")
        return result
    }

    private suspend fun DistantTrack.toEntity(
        server: DistantServer,
        artworkCache: HashMap<String, String?>
    ): TrackEntity {
        val albumArtPath = repository.artFor(server, this)?.let { url ->
            if (artworkCache.containsKey(url)) {
                artworkCache[url]
            } else {
                artworkStore.cache(url).also { artworkCache[url] = it }
            }
        }

        return TrackEntity(
            title = title.ifBlank { "Unknown" },
            artist = artist,
            album = album,
            albumArtist = albumArtist.ifBlank { artist },
            genre = genre,
            composer = composer,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber,
            duration = durationMs,
            bitrate = bitrateKbps ?: 0,
            sampleRate = sampleRate ?: 0,
            bitDepth = bitDepth ?: 0,
            channels = channels ?: 2,
            format = format ?: "NETWORK",
            path = DistantUris.build(server.type, server.id, remoteId),
            size = sizeBytes ?: 0L,
            albumArtPath = albumArtPath,
            dateAdded = 0L
        )
    }
}
