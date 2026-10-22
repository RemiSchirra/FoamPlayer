package com.foamplayer.app.ui.screens.lyrics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps the highlighted lyric line near its focus anchor without fighting the user's finger.
 *
 * Extracted from `LyricsEnhanced`: the geometry is independent of the composable's state and the
 * line lookup is pure (and unit-tested).
 */

internal suspend fun LazyListState.scrollLyricIntoFocus(
    index: Int,
    animateToNearbyItem: Boolean,
    force: Boolean,
    alignByItemCenter: Boolean,
) {
    val itemCount = layoutInfo.totalItemsCount
    if (itemCount == 0) return

    val targetIndex = index.coerceIn(0, itemCount - 1)
    var itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == targetIndex }
    if (itemInfo == null) {
        val distance = abs(targetIndex - firstVisibleItemIndex)
        if (animateToNearbyItem && distance <= LYRIC_FOCUS_ANIMATED_DISTANCE) {
            animateScrollToItem(targetIndex)
        } else {
            scrollToItem(targetIndex)
        }
        withFrameNanos { }
        itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == targetIndex }
    }

    itemInfo ?: return

    val viewportStart = layoutInfo.viewportStartOffset
    val viewportEnd = layoutInfo.viewportEndOffset
    val viewportHeight = viewportEnd - viewportStart
    if (viewportHeight <= 0) return

    val itemFocusPoint =
        if (alignByItemCenter) {
            itemInfo.offset + itemInfo.size / 2
        } else {
            itemInfo.offset
        }
    val topGuard = viewportStart + (viewportHeight * LYRIC_FOCUS_TOP_GUARD_RATIO).roundToInt()
    val bottomGuard = viewportEnd - (viewportHeight * LYRIC_FOCUS_BOTTOM_GUARD_RATIO).roundToInt()
    if (!force && itemFocusPoint in topGuard..bottomGuard) return

    val anchorRatio =
        if (alignByItemCenter) {
            LYRIC_FOCUS_ANCHOR_RATIO
        } else {
            LYRIC_LINE_SYNC_TOP_ANCHOR_RATIO
        }
    val targetFocusPoint = viewportStart + (viewportHeight * anchorRatio).roundToInt()
    val scrollDelta = itemFocusPoint - targetFocusPoint
    if (abs(scrollDelta) > LYRIC_FOCUS_MIN_SCROLL_PX) {
        animateScrollBy(
            value = scrollDelta.toFloat(),
            animationSpec =
                tween(
                    durationMillis = LYRIC_FOCUS_SCROLL_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )
    }
}

/**
 * Playback time to focus for [time]: the start of the line that started last, clamped to that
 * line's end so a line that already finished does not keep pulling the list forward.
 */
internal fun SyncedLyrics.positionForStableLineFocus(time: Int): Int {
    if (lines.isEmpty()) return time
    val index = findLastStartedLineIndex(time)
    if (index < 0) return time

    val line = lines[index]
    if (time < line.end) return time

    return (line.end - 1).coerceAtLeast(line.start)
}

/** Index of the last line whose `start` is at or before [time], or -1 before the first line. */
internal fun SyncedLyrics.findLastStartedLineIndex(time: Int): Int {
    var low = 0
    var high = lines.lastIndex
    var result = -1

    while (low <= high) {
        val mid = low + (high - low) / 2
        if (lines[mid].start <= time) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }

    return result
}

private const val LYRIC_FOCUS_ANCHOR_RATIO = 0.42f
private const val LYRIC_LINE_SYNC_TOP_ANCHOR_RATIO = 0.35f
private const val LYRIC_FOCUS_TOP_GUARD_RATIO = 0.18f
private const val LYRIC_FOCUS_BOTTOM_GUARD_RATIO = 0.24f
private const val LYRIC_FOCUS_MIN_SCROLL_PX = 6
private const val LYRIC_FOCUS_ANIMATED_DISTANCE = 12
private const val LYRIC_FOCUS_SCROLL_DURATION_MS = 520
