package com.foamplayer.app.ui.utils

import android.content.Context
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide cache of album-art accent colors, keyed by art path.
 *
 * MiniPlayer and FullPlayerScreen both need the accent color for whichever
 * tracks are near-visible in their pagers. They used to each keep a private
 * `mutableStateMapOf` and independently launch [extractPaletteColor] for the
 * same paths. Because the mini player's pager position tracks the same
 * `queueIndex` the full player's pager drives, every swipe in the full
 * player also re-triggered the mini player's own extraction effect for the
 * *same* three images -- two concurrent bitmap decodes competing with Coil's
 * own decode of the visible pager frame, right when the user is swiping.
 *
 * Routing both screens through this single cache means the color for a
 * given path is decoded at most once, no matter how many pagers ask for it.
 *
 * The cache is bounded by an LRU cap so a long session across a large library
 * doesn't grow the map without limit. Entries are touched by [ensure] each
 * time a screen asks for them, so the tracks actually on screen stay hot
 * while distant ones fall out. ([interpolatedColor] deliberately does not
 * touch: it runs inside a `snapshotFlow` read, where mutating the recency
 * map would write snapshot state mid-read and churn on every frame.)
 */
object AlbumArtPaletteCache {
    /** Maximum number of cached colors before the least-recently-read entry is evicted. */
    private const val MAX_ENTRIES = 64

    /** Read this directly from composition; it's a snapshot-backed map. */
    val colors = mutableStateMapOf<String, Color>()

    private val inFlight = mutableSetOf<String>()
    private val inFlightLock = Mutex()

    // Access-ordered record of what's been read recently, driving eviction. Only the
    // paths matter; the value is a placeholder. Backed by a plain LinkedHashMap with
    // accessOrder=true so `touch` moves an entry to the end (most recently used).
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

    /** Marks [path] as recently used, bumping it toward the front of the eviction order. */
    private fun touch(path: String) {
        recency.remove(path)
        recency[path] = Unit
    }

    /**
     * Ensures [path]'s color is present in [colors], kicking off extraction
     * at most once per path even if called concurrently from multiple
     * screens.
     */
    fun ensure(scope: CoroutineScope, context: Context, path: String?) {
        if (path == null) return
        if (colors.containsKey(path)) {
            touch(path)
            return
        }
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
                val color = extractPaletteColor(context, path)
                if (color != null) {
                    colors[path] = color
                    touch(path)
                }
            } finally {
                // The launching scope (a LaunchedEffect) is cancelled the moment its
                // screen/page is swapped or left. `withLock` is cancellable, so on a
                // cancelled scope it would throw before running `inFlight -= path`,
                // permanently leaking `path` in `inFlight` and blocking every future
                // fetch of that path's color. NonCancellable guarantees the marker is
                // always cleared.
                withContext(NonCancellable) {
                    inFlightLock.withLock { inFlight -= path }
                }
            }
        }
    }

    /**
     * Interpolated accent color for a pager sitting between two pages, meant to be
     * called from inside a `snapshotFlow { ... }` so it re-evaluates every frame a
     * pager is mid-drag -- MiniPlayer and FullPlayerScreen both want that continuous
     * per-frame update while swiping, not a throttled/settled version.
     *
     * This used to be two copies of the same index/lerp math, one per screen, each
     * reading its own [PagerState] directly. Centralizing it here doesn't change how
     * often it fires (each caller still drives it from its own pager, every frame,
     * exactly as before) -- it just means there's one implementation to keep correct
     * instead of two that can quietly drift apart.
     *
     * @param currentPage the pager's [PagerState.currentPage]
     * @param currentPageOffsetFraction the pager's [PagerState.currentPageOffsetFraction]
     * @param pageCount number of pages/tracks the pager can be on
     * @param pathForPage resolves a page index to its album-art path; callers that
     *   need to remap a page mid-transition (e.g. MiniPlayer's distant-jump override)
     *   can do so here instead of this function needing to know about that concept
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

        val colorA = pathForPage(indexA)?.let { colors[it] } ?: return null
        val colorB = pathForPage(indexB)?.let { colors[it] }

        return lerp(colorA, colorB ?: colorA, fraction)
    }
}