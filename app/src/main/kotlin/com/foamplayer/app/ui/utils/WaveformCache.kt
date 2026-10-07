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
import kotlin.math.abs

/**
 * How full a track's extraction is, exposed as Compose state for progressive
 * drawing.
 */
class WaveformState internal constructor(val bucketCount: Int) {
    /** Raw mean-amplitude buckets, NOT yet scaled — [scale] carries the song's reference. */
    internal val peaks = FloatArray(bucketCount)

    /** Highest bucket index (+1) backed by decoded audio so far; grows as buckets land. */
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
 * Process-wide waveform cache, keyed by file path — the same pattern
 * [AlbumArtPaletteCache] uses for accent colors.
 *
 * Extraction is ULTRA-DOWNSAMPLED and PARALLEL: the timeline is split into
 * [WORKER_COUNT] contiguous REGIONS, one per worker, and each worker seeks ONCE
 * to its region start and streams [WINDOW_FRAMES] PCM frames per bucket straight
 * off the codec — one codec prime and one seek per worker instead of one per
 * bucket (every seek flushes and re-primes the decoder, which dominates the
 * cost). Each bucket's mean amplitude fills as its frames stream by, so the
 * waveform appears within the codec's own priming latency. The seek bar
 * resamples the fixed-resolution result to however many bars its width allows,
 * so rotations never re-trigger an extraction. Results are published
 * bucket-by-bucket ([WaveformState.filled]) into a scope the cache OWNS: a
 * screen change mid-extraction neither cancels it nor duplicates it — per-path
 * dedup plus the LRU bound govern lifetime.
 *
 * [WaveformCache.prefetch] keeps the queue's adjacent tracks' waveforms hot, so
 * the full player's track-change morph has both envelopes ready the moment a
 * swipe starts.
 */
object WaveformCache {
    private const val TAG = "WaveformCache"

    /** Number of sampled buckets — also the waveform's fixed bucket resolution. */
    private const val WINDOW_COUNT = 64

    /**
     * Concurrent extractor+decoder pairs. Worker [w] owns the contiguous region
     * [w * WINDOWS_PER_WORKER, (w+1) * WINDOWS_PER_WORKER). Hardware codecs are a
     * limited resource; 8 keeps contention (and battery cost) sane.
     */
    private const val WORKER_COUNT = 8

    /** PCM frames decoded per bucket: ~93ms at 44.1kHz (~1% of a 3-minute track). */
    private const val WINDOW_FRAMES = 4096L

    /** Buckets each worker samples: WINDOW_COUNT / WORKER_COUNT. */
    private const val WINDOWS_PER_WORKER = WINDOW_COUNT / WORKER_COUNT

    /**
     * Safety bound per worker's decode loop, in iterations (codec stall /
     * pathological input): generous enough for the whole region.
     */
    private const val MAX_LOOP_ITERS = 4096

    /**
     * Bucket percentile used as the per-song loudness reference. A global max
     * pins every loud master's bars at full height; the 95th percentile leaves
     * headroom above it, so only the loudest ~5% of the song reaches (near) max.
     */
    private const val SCALE_PERCENTILE = 0.95f

    /** Floor for the derived scale, as a fraction of the absolute peak. */
    private const val MIN_SCALE_FRACTION = 0.35f

    /** Maximum number of cached waveforms before the least-recently-read entry is evicted. */
    private const val MAX_ENTRIES = 12

    /** Read this directly from composition; it's a snapshot-backed map. */
    val waveforms = mutableStateMapOf<String, WaveformState>()

    // Detached from any composable: an extraction survives screen changes and
    // finishes exactly once per path, whatever the UI does meanwhile.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inFlight = mutableSetOf<String>()
    private val failedPaths = mutableSetOf<String>()
    private val inFlightLock = Mutex()

    // Access-ordered record of what's been read recently, driving eviction
    // (same recency mechanism as AlbumArtPaletteCache).
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

    /**
     * Ensures [path]'s waveform is being extracted, starting it at most once per
     * path even if called concurrently.
     */
    fun ensure(context: Context, path: String?, durationMs: Long) {
        if (path.isNullOrEmpty() || durationMs <= 0L) return
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
            } finally {
                inFlightLock.withLock { inFlight -= path }
            }
        }
    }

    /**
     * Warms the cache for [paths] (the queue's neighbors around the playing
     * track): each missing path starts its extraction in the background so the
     * seek bar's track-change morph has both envelopes on hand when a swipe
     * begins. Cheap no-op for paths already extracted, in-flight, or known
     * failed. Failures and completion are handled exactly like [ensure]; the
     * caller reads results through [waveforms].
     */
    fun prefetch(context: Context, paths: List<Pair<String, Long>>) {
        val appContext = context.applicationContext
        paths.forEach { (path, durationMs) -> ensure(appContext, path, durationMs) }
    }

    /**
     * Publishes one sampled bucket into [state]: writes the value, advances
     * [WaveformState.filled] (out-of-order completion makes this a max, so holes
     * between completed buckets render as the neutral minimum until they land)
     * and refreshes the scale. Snapshot-state writes are thread-safe, and each
     * bucket index has exactly one writer, so "bucket write, then state write"
     * is a proper publication for readers observing the state.
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

    /**
     * Opens [extractor] on [path]: SAF `content://` URIs go through the
     * ContentResolver ([MediaExtractor]'s context overload), absolute paths
     * open directly.
     */
    private fun openExtractor(extractor: MediaExtractor, context: Context, path: String) {
        if (path.startsWith("content://")) {
            extractor.setDataSource(context, path.toUri(), null)
        } else {
            extractor.setDataSource(path)
        }
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
        // Timeline anchor for the region positions: the container's own duration
        // when it carries one, the track tag's duration otherwise.
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
     * contiguous regions; each worker seeks once to its region start and streams
     * [WINDOWS_PER_WORKER * WINDOW_FRAMES] PCM frames, folding every
     * [WINDOW_FRAMES]-frame group's mean absolute amplitude into one bucket,
     * published as it completes. Returns false when the file cannot be opened at
     * all (unsupported container/codec, unreadable data) — callers treat that as
     * terminal, like palette extraction.
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
        } finally {
            runCatching { probe.release() }
        }

        (0 until WORKER_COUNT).map { worker ->
            async(Dispatchers.IO) {
                // Each worker owns its extractor+codec: no cross-worker contention,
                // and ONE seek + ONE codec prime for its whole contiguous region.
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
     * [seekToUs], folding every [WINDOW_FRAMES] frames' mean absolute amplitude
     * into one bucket and handing each completed bucket to [onBucket] (index
     * within the region). Stops early at stream end; a codec that never produces
     * output simply ends the region.
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

        // Closes the current bucket if it has collected all its frames.
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
 * Composes the waveform state for [path], kicking off the (ultra-downsampled,
 * parallel, sub-second) extraction once — into the cache's own scope, see
 * [WaveformCache]. Returns null only for files extraction cannot handle at all.
 */
@Composable
fun rememberWaveform(path: String, durationMs: Long): WaveformState? {
    val context = LocalContext.current
    LaunchedEffect(path, durationMs) {
        WaveformCache.ensure(context, path, durationMs)
    }
    // Snapshot read: recomposes this caller when the state object lands, and the
    // state's own fields propagate draw invalidations from there.
    return WaveformCache.waveforms[path]
}