package com.foamplayer.app.audio.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.foamplayer.app.audio.bitperfect.BitPerfectDetector
import com.foamplayer.app.audio.equalizer.EqualizerManager
import com.foamplayer.app.data.db.entities.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bitPerfectDetector: BitPerfectDetector,
    private val equalizerManager: EqualizerManager
) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private var progressJob: Job? = null

    init {
        setupPlayerListeners()
        startProgressTracker()
    }

    private fun setupPlayerListeners() {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = player.currentMediaItemIndex
                val currentQueue = _playbackState.value.queue
                if (index in currentQueue.indices) {
                    val track = currentQueue[index]
                    val outputInfo = bitPerfectDetector.evaluateOutput(
                        sourceSampleRate = track.sampleRate,
                        sourceBitDepth = track.bitDepth,
                        isEqualizerActive = equalizerManager.state.value.enabled
                    )
                    _playbackState.value = _playbackState.value.copy(
                        currentTrack = track,
                        queueIndex = index,
                        durationMs = track.duration,
                        currentPositionMs = 0L,
                        audioOutputInfo = outputInfo,
                        currentLyrics = track.lyrics
                    )
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizerManager.attachToAudioSession(audioSessionId)
            }
        })
    }

    fun setQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        _playbackState.value = _playbackState.value.copy(
            queue = tracks,
            queueIndex = startIndex
        )
        val mediaItems = tracks.map { MediaItem.fromUri(Uri.parse(it.path)) }
        player.setMediaItems(mediaItems, startIndex, 0L)
        player.prepare()
        player.play()
    }

    fun play() = player.play()
    fun pause() = player.pause()

    fun next() {
        if (player.hasNextMediaItem()) {
            seekTo(0)
            player.seekToNextMediaItem()
        }
    }

    fun previous() {
        if (player.hasPreviousMediaItem()) {
            // Skip to previous media only if the cursor is in the first 5 seconds of the track
            // TODO : This should be an app parameter
            if (player.contentPosition <= 5000L) {
                player.seekToPreviousMediaItem()
            }
            seekTo(0)
        }
    }

    fun skipToIndex(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0L)
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentQueue = _playbackState.value.queue
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) return

        // Reflect the move in the actual player so the currently loaded/playing item is untouched
        player.moveMediaItem(fromIndex, toIndex)

        val newQueue = currentQueue.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }

        // Keep queueIndex pointing at whichever track was actually playing, even though its
        // position in the list may have shifted as a side effect of the move.
        val oldPlayingIndex = _playbackState.value.queueIndex
        val newPlayingIndex = when {
            oldPlayingIndex == fromIndex -> toIndex
            fromIndex < oldPlayingIndex && toIndex >= oldPlayingIndex -> oldPlayingIndex - 1
            fromIndex > oldPlayingIndex && toIndex <= oldPlayingIndex -> oldPlayingIndex + 1
            else -> oldPlayingIndex
        }

        _playbackState.value = _playbackState.value.copy(
            queue = newQueue,
            queueIndex = newPlayingIndex
        )
    }

    fun removeQueueItem(index: Int) {
        val currentQueue = _playbackState.value.queue
        if (index !in currentQueue.indices) return

        player.removeMediaItem(index)

        val newQueue = currentQueue.toMutableList().apply {
            removeAt(index)
        }

        val oldPlayingIndex = _playbackState.value.queueIndex
        val newPlayingIndex = when {
            index < oldPlayingIndex -> oldPlayingIndex - 1
            index == oldPlayingIndex -> if (newQueue.isEmpty()) -1 else index.coerceAtMost(newQueue.size - 1)
            else -> oldPlayingIndex
        }

        _playbackState.value = _playbackState.value.copy(
            queue = newQueue,
            queueIndex = newPlayingIndex,
            currentTrack = if (newPlayingIndex != -1) newQueue.getOrNull(newPlayingIndex) else null
        )
    }

    fun toggleShuffle() {
        val newShuffle = !_playbackState.value.isShuffle
        player.shuffleModeEnabled = newShuffle
        _playbackState.value = _playbackState.value.copy(isShuffle = newShuffle)
    }

    fun toggleRepeat() {
        val nextMode = when (_playbackState.value.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        player.repeatMode = when (nextMode) {
            RepeatMode.NONE -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        _playbackState.value = _playbackState.value.copy(repeatMode = nextMode)
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                if (player.isPlaying) {
                    _playbackState.value = _playbackState.value.copy(
                        currentPositionMs = player.currentPosition
                    )
                }
                delay(200)
            }
        }
    }
}