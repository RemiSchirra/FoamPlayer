package com.foamplayer.app.ui.utils

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 */
object AlbumArtPaletteCache {
    /** Read this directly from composition; it's a snapshot-backed map. */
    val colors = mutableStateMapOf<String, Color>()

    private val inFlight = mutableSetOf<String>()
    private val inFlightLock = Mutex()

    /**
     * Ensures [path]'s color is present in [colors], kicking off extraction
     * at most once per path even if called concurrently from multiple
     * screens.
     */
    fun ensure(scope: CoroutineScope, context: Context, path: String?) {
        if (path == null || colors.containsKey(path)) return
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
                }
            } finally {
                inFlightLock.withLock { inFlight -= path }
            }
        }
    }
}