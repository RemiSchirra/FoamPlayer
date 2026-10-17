package com.foamplayer.app.ui.utils

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.abs

/**
 * Extraction state for one track, exposed as Compose state for progressive
 * drawing.
 */
class WaveformState internal constructor(val bucketCount: Int) {
    /** Raw mean-amplitude buckets, not yet scaled — [scale] carries the song's reference. */
    internal val peaks = FloatArray(bucketCount)

    /** Highest bucket index (+1) backed by decoded audio so far. */
    val filled: IntState get() = _filled

    /** Per-song loudness reference (95th percentile of the buckets decoded so far). */
    val scale: FloatState get() = _scale

    /** True once every region has been sampled and the scale is final. */
    val done: Boolean get() = _done.value

    internal val _filled: MutableIntState = mutableIntStateOf(0)
    internal val _scale: MutableFloatState = mutableFloatStateOf(0f)
    internal val _done: MutableState<Boolean> = mutableStateOf(false)
}

/**
 * Process-wide waveform cache keyed by file path, mirroring
 * [AlbumArtPaletteCache] for accent colors.
 *
 * Extraction is heavily downsampled and parallel: the timeline is split into
 * [WaveformCache.WORKER_COUNT] contiguous regions, each worker seeks once to
 * its region start and streams [WaveformCache.WINDOW_FRAMES] PCM frames per
 * bucket — one codec prime and one seek per worker instead of one per bucket.
 * Buckets publish as they complete ([WaveformState.filled]) into a scope the
 * cache owns, so a screen change mid-extraction neither cancels nor duplicates
 * it; per-path dedup plus an LRU bound govern lifetime. Results are read at
 * fixed resolution and resampled by the seek bar to its bar count.
 *
 * [WaveformCache.prefetch] keeps the queue's adjacent tracks hot so the full
 * player's track-change morph has both envelopes ready when a swipe starts.
 */
object WaveformCache {
    private const val TAG = "WaveformCache"

    /**
     * Track path schemes extraction cannot open (MediaExtractor only reads local
     * files and content URIs). Distant/server tracks stream through the player's
     * own data source and have no readable media file — they must never reach a
     * MediaExtractor (setDataSource on these schemes crashes the process).
     */
    private val UNSUPPORTED_SCHEMES = Collections.unmodifiableSet(
        setOf("http", "https", "rtsp", "udp", "udp6", "tcp", "foam-distant")
    )

    /** Sampled buckets — also the waveform's fixed bucket resolution. */
    private const val WINDOW_COUNT = 64

    /**
     * The definitive envelope of a track extraction can never handle (streams):
     * all-flat at the standard bucket resolution. Published for such paths so
     * track-change morphs ANIMATE to (or from) flat — the bars collapse with the
     * swipe like between any two extracted tracks — instead of the previous
     * shape holding through the swipe and snapping flat when the track flips.
     * Shared read-only instance: nothing ever writes to it after creation.
     */
    private val FLAT_ENVELOPE: WaveformState = WaveformState(WINDOW_COUNT).apply {
        _done.value = true
    }

    /**
     * Concurrent extractor+decoder pairs. Worker w owns the contiguous region
     * [w * WINDOWS_PER_WORKER, (w+1) * WINDOWS_PER_WORKER); 8 keeps hardware-codec
     * contention (and battery cost) sane.
     */
    private const val WORKER_COUNT = 8

    /** PCM frames decoded per bucket: ~93ms at 44.1kHz (~1% of a 3-minute track). */
    private const val WINDOW_FRAMES = 4096L

    private const val WINDOWS_PER_WORKER = WINDOW_COUNT / WORKER_COUNT

    /** Safety bound per worker's decode loop (codec stall / pathological input). */
    private const val MAX_LOOP_ITERS = 4096

    /**
     * Bucket percentile used as the per-song loudness reference. A global max
     * pins loud masters at full height; the 95th percentile leaves headroom so
     * only the loudest ~5% of a song reaches (near) max.
     */
    private const val SCALE_PERCENTILE = 0.95f

    /** Floor for the derived scale, as a fraction of the absolute peak. */
    private const val MIN_SCALE_FRACTION = 0.35f

    /** Maximum cached waveforms before LRU eviction. */
    private const val MAX_ENTRIES = 12

    /** Snapshot-backed map; read directly from composition. */
    val waveforms = mutableStateMapOf<String, WaveformState>()

    // Detached from any composable: an extraction survives screen changes and
    // finishes exactly once per path.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inFlight = mutableSetOf<String>()
    private val failedPaths: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val inFlightLock = Mutex()

    // Access-ordered record driving eviction (same mechanism as AlbumArtPaletteCache).
    private val recency = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean {
            return if (size > MAX_ENTRIES) {
                eldest?.let { waveforms.remove(it.key) }
                true
            } else {
                false
            }
        }
    }

    private fun touch(path: String) {
        recency.remove(path)
        recency[path] = Unit
    }

    /** Starts [path]'s extraction at most once per path, even when called concurrently. */
    fun ensure(context: Context, path: String?, durationMs: Long) {
        if (path.isNullOrEmpty() || durationMs <= 0L) return
        // Streams (distant/server tracks, plain URLs) have no readable media
        // file: publish the shared flat envelope without ever constructing an
        // extractor — MediaExtractor.setDataSource on these schemes crashes
        // the process.
        if (path.toUri().scheme?.lowercase() in UNSUPPORTED_SCHEMES) {
            failedPaths += path
            waveforms[path] = FLAT_ENVELOPE
            touch(path)
            return
        }
        if (waveforms.containsKey(path)) {
            touch(path)
            return
        }
        if (path in failedPaths) return
        scope.launch {
            val shouldFetch = inFlightLock.withLock {
                if (path in inFlight) false else {
                    inFlight += path
                    true
                }
            }
            if (!shouldFetch) return@launch
            try {
                val state = WaveformState(WINDOW_COUNT)
                val ok = extractWaveform(context.applicationContext, path, durationMs, state)
                if (ok) {
                    waveforms[path] = state
                    touch(path)
                } else {
                    failedPaths += path
                }
            } catch (_: Exception) {
                // Extraction must never take the app down (the full player kicks
                // these off for every queue neighbor): treat as terminal failure.
                failedPaths += path
            } finally {
                inFlightLock.withLock { inFlight -= path }
            }
        }
    }

    /** Warms the cache for [paths] (e.g. the queue's neighbors around the playing track). */
    fun prefetch(context: Context, paths: List<Pair<String, Long>>) {
        val appContext = context.applicationContext
        paths.forEach { (path, durationMs) -> ensure(appContext, path, durationMs) }
    }

    /**
     * Publishes one sampled bucket into [state]: writes the value, advances
     * [WaveformState.filled] (a max — out-of-order completion leaves holes that
     * render as the neutral minimum until they land) and refreshes the scale.
     * Each bucket index has exactly one writer, so the bucket write followed by
     * the state write is a proper publication.
     */
    private fun publishBucket(state: WaveformState, bucket: Int, meanAmplitude: Float) {
        synchronized(state) {
            state.peaks[bucket] = meanAmplitude
            if (bucket + 1 > state._filled.intValue) state._filled.intValue = bucket + 1
            val filled = state._filled.intValue
            val sorted = state.peaks.copyOf(filled).filter { it > 0f }.toFloatArray()
            if (sorted.isEmpty()) return
            sorted.sort()
            val percentile = sorted[((sorted.size - 1) * SCALE_PERCENTILE).toInt()]
            val absoluteMax = sorted[sorted.size - 1]
            var scale = maxOf(percentile, absoluteMax * MIN_SCALE_FRACTION)
            if (scale <= 0f) scale = 1f
            state._scale.floatValue = scale
        }
    }

    /** Opens [extractor] on [path]: `content://` URIs via ContentResolver, paths directly. */
    private fun openExtractor(extractor: MediaExtractor, context: Context, path: String) {
        if (path.startsWith("content://")) {
            extractor.setDataSource(context, path.toUri(), null)
        } else {
            extractor.setDataSource(path)
        }
    }

    /** True when [throwable] is the native-side rejection of an unopenable data source. */
    private fun isUnopenableSource(throwable: Throwable): Boolean {
        val message = throwable.message ?: return false
        return message.contains("Failed to open file", ignoreCase = true) ||
                message.contains("No such file or directory", ignoreCase = true) ||
                message.contains("setDataSource failed", ignoreCase = true)
    }

    /** The file's first audio track format + its timeline duration in µs, or null. */
    private fun probeTrack(
        extractor: MediaExtractor,
        fallbackDurationMs: Long
    ): Pair<MediaFormat, Long>? {
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                format = trackFormat
                extractor.selectTrack(i)
                break
            }
        }
        format ?: return null
        // Timeline anchor: the container's duration when present, the track tag's otherwise.
        val containerDuration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION, 0L)
        } else {
            0L
        }.takeIf { it > 0L } ?: (fallbackDurationMs * 1000L)
        if (containerDuration <= 0L) return null
        return format to containerDuration
    }

    /**
     * Samples [path]'s audio track: the timeline is split into [WORKER_COUNT]
     * contiguous regions; each worker streams its region and folds every
     * [WINDOW_FRAMES]-frame group's mean amplitude into one bucket, published
     * as it completes. Returns false when the file cannot be opened at all —
     * treated as terminal, like palette extraction.
     */
    private suspend fun extractWaveform(
        context: Context,
        path: String,
        durationMs: Long,
        state: WaveformState
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        // One probe supplies the shared track format + timeline duration; the
        // workers re-open the file (a cheap fd open) for their own instances.
        // Everything here can die natively on an unreadable path (the platform
        // logs "Failed to open file" and, on some builds, aborts the process);
        // the scheme gate in [ensure] keeps the known-dead inputs away, and the
        // catch bounds any remaining native-side failure to a failed extraction.
        val probe = MediaExtractor()
        val trackFormat: MediaFormat
        val durationUs: Long
        try {
            openExtractor(probe, appContext, path)
            val track = probeTrack(probe, durationMs)
            if (track == null) {
                Log.w(TAG, "No audio track: $path")
                return@withContext false
            }
            trackFormat = track.first
            durationUs = track.second
        } catch (error: Exception) {
            if (!isUnopenableSource(error)) {
                Log.w(TAG, "Extraction failed: $path", error)
            }
            return@withContext false
        } finally {
            runCatching { probe.release() }
        }

        (0 until WORKER_COUNT).map { worker ->
            async(Dispatchers.IO) {
                // Each worker owns its extractor+codec: no cross-worker contention,
                // one seek + one codec prime for its whole region.
                val extractor = MediaExtractor()
                var codec: MediaCodec? = null
                try {
                    currentCoroutineContext().ensureActive()
                    openExtractor(extractor, appContext, path)
                    val track = probeTrack(extractor, durationMs) ?: return@async
                    val mime = track.first.getString(MediaFormat.KEY_MIME) ?: return@async
                    codec = MediaCodec.createDecoderByType(mime)
                    codec.configure(track.first, null, null, 0)
                    codec.start()

                    val firstBucket = worker * WINDOWS_PER_WORKER
                    sampleRegion(
                        extractor = extractor,
                        codec = codec,
                        seekToUs = durationUs * firstBucket / WINDOW_COUNT,
                        regionBuckets = WINDOWS_PER_WORKER,
                        onBucket = { indexInRegion, meanAmplitude ->
                            publishBucket(state, firstBucket + indexInRegion, meanAmplitude)
                        }
                    )
                } catch (error: Exception) {
                    if (!isUnopenableSource(error)) {
                        Log.w(TAG, "Worker extraction failed: $path", error)
                    }
                } finally {
                    runCatching {
                        codec?.stop()
                        codec?.release()
                        extractor.release()
                    }
                }
            }
        }.awaitAll()

        state._done.value = true
        // A track with no decodable audio anywhere: treat like palette failure.
        state._filled.intValue > 0
    }

    /**
     * Streams up to [regionBuckets] * [WINDOW_FRAMES] PCM frames starting at
     * [seekToUs], folding every [WINDOW_FRAMES] frames into one bucket handed
     * to [onBucket] (index within the region). Stops early at stream end.
     */
    private fun sampleRegion(
        extractor: MediaExtractor,
        codec: MediaCodec,
        seekToUs: Long,
        regionBuckets: Int,
        onBucket: (Int, Float) -> Unit
    ) {
        extractor.seekTo(seekToUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        var channels = 2
        var sawFormatChange = false
        var bucketIndex = 0
        var bucketFrameCount = 0L
        var bucketAmplitudeSum = 0.0
        var iters = 0
        val info = MediaCodec.BufferInfo()

        fun maybeCloseBucket() {
            if (bucketFrameCount >= WINDOW_FRAMES) {
                onBucket(bucketIndex, (bucketAmplitudeSum / bucketFrameCount).toFloat())
                bucketIndex++
                bucketFrameCount = 0L
                bucketAmplitudeSum = 0.0
            }
        }

        while (bucketIndex < regionBuckets) {
            if (++iters > MAX_LOOP_ITERS) break

            val inIndex = codec.dequeueInputBuffer(10_000L)
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex) ?: break
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) break // Stream ended.
                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                extractor.advance()
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000L)
            when {
                outIndex >= 0 -> {
                    val outBuffer = codec.getOutputBuffer(outIndex) ?: break
                    if (sawFormatChange) {
                        channels = codec.outputFormat
                            .getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    }
                    val encoding = codec.outputFormat.getInteger(
                        MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> {
                            val floats = outBuffer.asFloatBuffer()
                            val frames = floats.remaining() / channels
                            for (frame in 0 until frames) {
                                var amplitude = 0f
                                for (c in 0 until channels) {
                                    amplitude += abs(floats.get(frame * channels + c))
                                }
                                bucketAmplitudeSum += amplitude / channels
                            }
                            bucketFrameCount += frames
                        }
                        else -> {
                            // 16-bit PCM (and legacy encodings): signed shorts.
                            val shorts = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / channels
                            for (frame in 0 until frames) {
                                var amplitude = 0
                                for (c in 0 until channels) {
                                    amplitude += abs(shorts.get(frame * channels + c).toInt())
                                }
                                bucketAmplitudeSum += amplitude / channels / 32768.0
                            }
                            bucketFrameCount += frames
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    maybeCloseBucket()
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sawFormatChange = true
                // INFO_TRY_AGAIN_LATER: spin (bounded by the iteration guard).
            }
        }
    }
}

/**
 * Composes the waveform state for [path], kicking off the extraction once into
 * the cache's own scope. Returns the shared flat envelope for streams
 * (extraction can never handle them) and null while a real track's extraction
 * is still in flight.
 */
@Composable
fun rememberWaveform(path: String, durationMs: Long): WaveformState? {
    val context = LocalContext.current
    LaunchedEffect(path, durationMs) {
        WaveformCache.ensure(context, path, durationMs)
    }
    return WaveformCache.waveforms[path]
}
