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
)