package com.foamplayer.app.audio.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The playing session as persisted for playback resumption: the main-queue
 * track ids (the auxiliary pool is redrawn on restore), the cursor, the saved
 * position and the session modes.
 */
data class PersistedPlayback(
    val trackIds: List<Long>,
    /** Per-entry "queue was started from [queueSourceId]" flags, parallel to [trackIds]. */
    val sourceFlags: List<Boolean>,
    val currentIndex: Int,
    val positionMs: Long,
    val isShuffle: Boolean,
    val repeatMode: RepeatMode,
    val queueSourceId: String?
)

/**
 * DataStore-backed snapshot of the playing session. [AudioEngine] writes it
 * (debounced, position refreshed while playing); [PlaybackService] reads it in
 * `onPlaybackResumption` so a media button can restart the service after the
 * system retired it (Media3 drops the foreground state after 10 paused minutes)
 * or the process was killed — and put the queue back exactly where it was.
 */
@Singleton
class QueuePersistence @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    /** Latest snapshot as a flow (null = nothing resumable saved). */
    val snapshot: Flow<PersistedPlayback?> = dataStore.data.map { decode(it[KEY]) }

    /** One-shot read of the latest snapshot; null when nothing was saved (or unreadable). */
    suspend fun current(): PersistedPlayback? = runCatching {
        decode(dataStore.data.first()[KEY])
    }.getOrNull()

    suspend fun save(state: PersistedPlayback) {
        dataStore.edit { preferences -> preferences[KEY] = encode(state) }
    }

    suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(KEY) }
    }

    private fun encode(state: PersistedPlayback): String = buildString {
        append(FORMAT_VERSION).append(FIELD_SEPARATOR)
        append(state.currentIndex).append(FIELD_SEPARATOR)
        append(state.positionMs).append(FIELD_SEPARATOR)
        append(if (state.isShuffle) '1' else '0').append(FIELD_SEPARATOR)
        append(state.repeatMode.name).append(FIELD_SEPARATOR)
        append(state.trackIds.joinToString(ITEM_SEPARATOR))
        append(FIELD_SEPARATOR)
        append(state.sourceFlags.joinToString(ITEM_SEPARATOR) { if (it) "1" else "0" })
        append(FIELD_SEPARATOR)
        // Last field: a source id may contain any character, and the limit-bound
        // split below keeps everything after the last field separator intact.
        append(state.queueSourceId.orEmpty())
    }

    private fun decode(raw: String?): PersistedPlayback? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(FIELD_SEPARATOR, limit = 8)
        if (parts.size != 8 || parts[0] != FORMAT_VERSION.toString()) return null

        val ids = parts[5].split(ITEM_SEPARATOR).mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return null
        val index = parts[1].toIntOrNull() ?: return null
        val position = parts[2].toLongOrNull() ?: return null
        val flags = parts[6].split(ITEM_SEPARATOR).map { it == "1" }
        val repeat = runCatching { RepeatMode.valueOf(parts[4]) }.getOrDefault(RepeatMode.NONE)

        return PersistedPlayback(
            trackIds = ids,
            sourceFlags = flags.takeIf { it.size == ids.size } ?: List(ids.size) { false },
            currentIndex = index,
            positionMs = position,
            isShuffle = parts[3] == "1",
            repeatMode = repeat,
            queueSourceId = parts[7].takeIf { it.isNotEmpty() }
        )
    }

    private companion object {
        private val KEY = stringPreferencesKey("playback_resumption_snapshot")
        private const val FORMAT_VERSION = 1
        private const val FIELD_SEPARATOR = "|"
        private const val ITEM_SEPARATOR = ","
    }
}
