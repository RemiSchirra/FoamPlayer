package com.foamplayer.app.lastfm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.foamplayer.lastfm.LastFmScrobble
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** JSON shape of one queued scrobble in the DataStore. */
@Serializable
private data class PendingScrobbleDto(
    val artist: String,
    val title: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationSeconds: Int? = null,
    val trackNumber: Int? = null,
    val timestampSeconds: Long
)

private fun LastFmScrobble.toDto() = PendingScrobbleDto(
    artist = track.artist,
    title = track.title,
    album = track.album,
    albumArtist = track.albumArtist,
    durationSeconds = track.durationSeconds,
    trackNumber = track.trackNumber,
    timestampSeconds = timestampSeconds
)

private fun PendingScrobbleDto.toScrobble() = LastFmScrobble(
    track = com.foamplayer.lastfm.LastFmTrack(
        artist = artist,
        title = title,
        album = album,
        albumArtist = albumArtist,
        durationSeconds = durationSeconds,
        trackNumber = trackNumber
    ),
    timestampSeconds = timestampSeconds
)

/**
 * Persists scrobbles captured while the device was offline so they can be
 * submitted later, once connectivity returns.
 *
 * The queue lives in the app DataStore as a JSON list, so pending scrobbles
 * survive process death. Entries are kept in chronological order (oldest
 * first) and deduplicated on submit. The queue is capped at
 * [MAX_PENDING_SCROBBLES], dropping the oldest entries: Last.fm ignores
 * scrobbles older than roughly two weeks anyway.
 */
@Singleton
class LastFmScrobbleQueue @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Pending scrobbles in chronological order (oldest first). */
    val pending: Flow<List<LastFmScrobble>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[KEY_PENDING].toScrobbles() }

    suspend fun enqueue(scrobble: LastFmScrobble) = enqueueAll(listOf(scrobble))

    /** Appends scrobbles, deduplicated and capped at [MAX_PENDING_SCROBBLES]. */
    suspend fun enqueueAll(scrobbles: List<LastFmScrobble>) {
        if (scrobbles.isEmpty()) return
        dataStore.edit { preferences ->
            val existing = preferences[KEY_PENDING].toScrobbles()
            val updated = (existing + scrobbles)
                .distinctBy { it.deduplicationKey }
                .sortedBy { it.timestampSeconds }
                .takeLast(MAX_PENDING_SCROBBLES)
            preferences[KEY_PENDING] = updated.encode()
        }
    }

    /** Removes the given scrobbles, e.g. after a batch was accepted by Last.fm. */
    suspend fun removeAll(scrobbles: List<LastFmScrobble>) {
        if (scrobbles.isEmpty()) return
        dataStore.edit { preferences ->
            val existing = preferences[KEY_PENDING].toScrobbles()
            val removed = scrobbles.map { it.deduplicationKey }.toSet()
            val updated = existing.filter { it.deduplicationKey !in removed }
            preferences[KEY_PENDING] = updated.encode()
        }
    }

    private fun List<LastFmScrobble>.encode(): String =
        json.encodeToString(
            ListSerializer(PendingScrobbleDto.serializer()),
            map { it.toDto() }
        )

    private fun String?.toScrobbles(): List<LastFmScrobble> {
        if (isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(
                ListSerializer(PendingScrobbleDto.serializer()),
                this
            ).map { it.toScrobble() }
        }.getOrDefault(emptyList())
    }

    private val LastFmScrobble.deduplicationKey: String
        get() = "${track.artist}:${track.title}:${track.album.orEmpty()}:$timestampSeconds"

    companion object {
        private val KEY_PENDING = stringPreferencesKey("lastfm_pending_scrobbles")

        /** Upper bound for the offline backlog, oldest entries dropped beyond it. */
        const val MAX_PENDING_SCROBBLES = 1_000
    }
}
