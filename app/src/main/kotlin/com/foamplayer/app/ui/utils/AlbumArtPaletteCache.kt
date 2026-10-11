package com.foamplayer.app.ui.utils

import android.content.Context
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide cache of album-art accent colors keyed by art path. MiniPlayer
 * and FullPlayerScreen both need colors for their pagers' visible tracks; a
 * single cache guarantees each path is decoded at most once no matter how many
 * pagers ask for it.
 *
 * Bounded by an LRU cap; entries are touched by [ensure] so on-screen tracks
 * stay hot while distant ones fall out. ([interpolatedColor] deliberately does
 * not touch: it runs inside a `snapshotFlow`, where mutating the recency map
 * would write snapshot state mid-read on every frame.)
 */
object AlbumArtPaletteCache {
    /** Maximum cached colors before the least-recently-read entry is evicted. */
    private const val MAX_ENTRIES = 64

    /** Snapshot-backed map; read directly from composition. */
    val colors = mutableStateMapOf<String, Color>()

    private val inFlight = mutableSetOf<String>()
    private val inFlightLock = Mutex()

    /**
     * Paths whose extraction produced no usable swatch. Extraction failures are
     * effectively permanent (missing or unreadable art), so they are treated as
     * terminal instead of re-launching a decode on every [ensure] call.
     */
    private val failedPaths = mutableSetOf<String>()

    // Access-ordered record driving eviction; values are placeholders.
    private val recency = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean {
            return if (size > MAX_ENTRIES) {
                eldest?.let { colors.remove(it.key) }
                true
            } else {
                false
            }
        }
    }

    /** Marks [path] as recently used, moving it to the front of the eviction order. */
    private fun touch(path: String) {
        recency.remove(path)
        recency[path] = Unit
    }

    /**
     * Ensures [path]'s color is present in [colors], starting extraction at
     * most once per path even when called concurrently. [isEmbeddedArt] is
     * false for plain image URIs (playlist covers).
     */
    fun ensure(scope: CoroutineScope, context: Context, path: String?, isEmbeddedArt: Boolean = true) {
        if (path == null) return
        if (colors.containsKey(path)) {
            touch(path)
            return
        }
        if (path in failedPaths) return
        scope.launch {
            val shouldFetch = inFlightLock.withLock {
                if (path in inFlight) {
                    false
                } else {
                    inFlight += path
                    true
                }
            }
            if (!shouldFetch) return@launch
            try {
                val color = extractPaletteColor(context, path, isEmbeddedArt)
                if (color != null) {
                    colors[path] = color
                    touch(path)
                } else {
                    failedPaths += path
                }
            } finally {
                // The launching scope may be cancelled mid-`withLock`, which would
                // throw before clearing `inFlight` and block every future fetch of
                // this path. NonCancellable guarantees the marker is cleared.
                withContext(NonCancellable) {
                    inFlightLock.withLock { inFlight -= path }
                }
            }
        }
    }

    /**
     * Interpolated accent color for a pager sitting between two pages, meant to
     * be called from inside a `snapshotFlow { ... }` for continuous per-frame
     * updates while swiping.
     *
     * @param currentPage the pager's [PagerState.currentPage]
     * @param currentPageOffsetFraction the pager's [PagerState.currentPageOffsetFraction]
     * @param pageCount number of pages/tracks the pager can be on
     * @param pathForPage resolves a page index to its album-art path; callers
     *   that need to remap a page mid-transition can do so here
     */
    fun interpolatedColor(
        currentPage: Int,
        currentPageOffsetFraction: Float,
        pageCount: Int,
        pathForPage: (Int) -> String?
    ): Color? {
        if (pageCount <= 0) return null

        val absoluteOffset = currentPage + currentPageOffsetFraction
        val indexA = absoluteOffset.toInt().coerceIn(0, pageCount - 1)
        val indexB = (indexA + 1).coerceAtMost(pageCount - 1)
        val fraction = (absoluteOffset - indexA).coerceIn(0f, 1f)

        var colorA: Color? = null
        var colorB: Color? = null
        Snapshot.withoutReadObservation {
            colorA = pathForPage(indexA)?.let { colors[it] }
            colorB = pathForPage(indexB)?.let { colors[it] }
        }

        val resolvedA = colorA ?: return null
        return lerp(resolvedA, colorB ?: resolvedA, fraction)
    }
}
