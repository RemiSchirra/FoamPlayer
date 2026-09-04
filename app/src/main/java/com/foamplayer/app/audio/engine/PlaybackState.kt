package com.foamplayer.app.audio.engine

import com.foamplayer.app.audio.bitperfect.AudioOutputInfo
import com.foamplayer.app.data.db.entities.TrackEntity

enum class RepeatMode { NONE, ALL, ONE }

data class PlaybackState(
    val currentTrack: TrackEntity? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<TrackEntity> = emptyList(),
    val queueIndex: Int = -1,
    val isShuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val audioOutputInfo: AudioOutputInfo? = null,
    val currentLyrics: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaybackState) return false
        return currentTrack == other.currentTrack &&
                isPlaying == other.isPlaying &&
                currentPositionMs == other.currentPositionMs &&
                durationMs == other.durationMs &&
                queue === other.queue &&
                queueIndex == other.queueIndex &&
                isShuffle == other.isShuffle &&
                repeatMode == other.repeatMode &&
                audioOutputInfo == other.audioOutputInfo &&
                currentLyrics == other.currentLyrics
    }

    override fun hashCode(): Int {
        var result = currentTrack?.hashCode() ?: 0
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + currentPositionMs.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + System.identityHashCode(queue)
        result = 31 * result + queueIndex
        result = 31 * result + isShuffle.hashCode()
        result = 31 * result + repeatMode.hashCode()
        result = 31 * result + (audioOutputInfo?.hashCode() ?: 0)
        result = 31 * result + (currentLyrics?.hashCode() ?: 0)
        return result
    }
}