package com.foamplayer.app.audio.engine

import androidx.compose.runtime.Immutable
import com.foamplayer.app.audio.bitperfect.AudioOutputInfo
import com.foamplayer.app.data.db.entities.TrackEntity

enum class RepeatMode { NONE, ALL, ONE }

/**
 * Emitted by [com.foamplayer.app.audio.engine.AudioEngine] when a track's
 * forward playback reaches the configured listen threshold. [playedMs] is the
 * accumulated playback time, used to estimate when the track started.
 */
data class ListenedTrackEvent(
    val trackId: Long,
    val playedMs: Long
)

/**
 * Full playback snapshot published by [AudioEngine], replaced wholesale and
 * never mutated. Collections are compared by identity in equals/hashCode so
 * composables skip when nothing visible changed.
 */
@Immutable
data class PlaybackState(
    val currentTrack: TrackEntity? = null,
    val isPlaying: Boolean = false,
    val isPlaybackRequested: Boolean = false,
    // True while the current track is still being fetched (distant stream) or prepared
    // by a Chromecast receiver. Local files never report loading.
    val isLoading: Boolean = false,
    val durationMs: Long = 0L,
    val queue: List<TrackEntity> = emptyList(),
    // Stable per-entry ids allocated by the engine (duplicates possible).
    val queueEntryIds: List<Long> = emptyList(),
    val queueIndex: Int = -1,
    val isShuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val audioOutputInfo: AudioOutputInfo? = null,
    // True for auxiliary queue entries.
    val auxiliaryFlags: List<Boolean> = emptyList(),
    // Collection id the queue was started from ("album:…", "playlist:…"); null for bare lists.
    val queueSourceId: String? = null,
    // True for entries belonging to the queue source's own section.
    val queueSourceFlags: List<Boolean> = emptyList()
) {
    companion object {
        /** Size of the random auxiliary pool appended after the main queue. */
        const val AUXILIARY_QUEUE_LENGTH = 25
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaybackState) return false
        return currentTrack == other.currentTrack &&
                isPlaying == other.isPlaying &&
                isPlaybackRequested == other.isPlaybackRequested &&
                isLoading == other.isLoading &&
                durationMs == other.durationMs &&
                queue === other.queue &&
                queueEntryIds === other.queueEntryIds &&
                queueIndex == other.queueIndex &&
                isShuffle == other.isShuffle &&
                repeatMode == other.repeatMode &&
                audioOutputInfo == other.audioOutputInfo &&
                auxiliaryFlags === other.auxiliaryFlags &&
                queueSourceId == other.queueSourceId &&
                queueSourceFlags === other.queueSourceFlags
    }

    override fun hashCode(): Int {
        var result = currentTrack?.hashCode() ?: 0
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + isPlaybackRequested.hashCode()
        result = 31 * result + isLoading.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + System.identityHashCode(queue)
        result = 31 * result + System.identityHashCode(queueEntryIds)
        result = 31 * result + queueIndex
        result = 31 * result + isShuffle.hashCode()
        result = 31 * result + repeatMode.hashCode()
        result = 31 * result + (audioOutputInfo?.hashCode() ?: 0)
        result = 31 * result + System.identityHashCode(auxiliaryFlags)
        result = 31 * result + (queueSourceId?.hashCode() ?: 0)
        result = 31 * result + System.identityHashCode(queueSourceFlags)
        return result
    }
}
