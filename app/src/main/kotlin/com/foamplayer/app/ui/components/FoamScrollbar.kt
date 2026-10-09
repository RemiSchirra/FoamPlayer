package com.foamplayer.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.foamplayer.app.ui.theme.FoamTheme

/** Width of the touch strip hosting the thumb, overlaid on the container's end edge. */
private val ScrollbarTouchWidth = 24.dp

/** Gap between the strip's end edge and the thumb's outer side. */
private val ScrollbarEdgeInset = 4.dp

/** Thumb opacity at rest; a press/drag swells it to full opacity. */
private const val ScrollbarIdleAlpha = 0.4f

/** Duration of the press/drag thickness + emphasis tween (thickness never snaps). */
private const val ScrollbarPressAnimationMs = 150

/**
 * Visual scale on the thumb's proportional length: the viewport/content share
 * is halved so the thumb stays a compact handle even for content barely long
 * enough to show the bar. The drag mapping is unaffected — travel still spans
 * the whole track, so dragging the thumb end to end covers the whole content.
 */
private const val ScrollbarThumbLengthScale = 0.5f

/**
 * Fast scrollbar for long lazy content: a thumb floating over the container's
 * end edge, colored by [color] — hosts pass their collection's accent (the
 * artwork-derived hero color), which falls back to the app accent upstream.
 * [trackTopPadding]/[trackBottomPadding] inset the thumb's track (and its
 * touch strip) from the screen's top/bottom chrome.
 *
 * It only appears — visually AND as a touch strip — when the content is long
 * enough to be worth fast-scrolling ([minContentToViewportRatio] viewports or
 * more): short collections get no strip at all, so nothing under the end edge
 * is ever intercepted for them.
 *
 * Pressing the strip thickens the thumb (thickness and emphasis both tween —
 * never a snap); dragging it scrolls the list proportionally: one pixel of
 * thumb travel equals the same share of the content, so the thumb tracks the
 * finger exactly while covering the whole collection. The scroll lands as a
 * DIRECT jump to the target item ([LazyListState.requestScrollToItem] /
 * [LazyGridState.requestScrollToItem]) rather than a raw incremental delta —
 * a long throw never composes or measures the items it passes over, and the
 * (many) drag events between two frames collapse into a single remeasure —
 * which is what keeps far scrollbar drags on huge collections jank-free.
 * Plain taps fall through to the rows underneath (the down is never consumed)
 * — only an actual drag inside the strip is captured. While the content isn't
 * long enough nothing is consumed either, so the strip is fully transparent
 * to the list.
 *
 * The thumb's drawn position and the drag scroll both read the lazy state
 * during the draw/gesture phases, so scrolling never recomposes the host.
 *
 * Thumb sizing models the hosts' structure — a leading item at index 0 (the
 * hero header) and a trailing item at the last index (the bottom spacer) —
 * by measuring both individually and averaging ONLY the uniform items between
 * them: folding the tall hero into the average would make the estimated
 * content height (and with it the thumb height) pulse as the hero slides
 * through the viewport, worst on the immersive layouts where it spans half
 * the screen. Both edge items report their FULL main-axis size even while
 * only partially visible (`LazyListItemInfo.size` is the item's size, not its
 * visible part), and the last sampled values are kept, so the estimate is
 * stable from the first frame the rows are measured.
 */
@Composable
fun FoamScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    color: Color = FoamTheme.accentColor,
    thickness: Dp = 8.dp,
    pressedThickness: Dp = 12.dp,
    /** Show the scrollbar only when the content is at least this many viewports tall. */
    minContentToViewportRatio: Float = 2f,
    /** Floor for the thumb length, so huge collections stay graspable. */
    minThumbHeight: Dp = 24.dp,
    /** Clears the thumb's track (and touch strip) of the screen's top chrome. */
    trackTopPadding: Dp = 0.dp,
    /** Clears the thumb's track (and touch strip) of the screen's bottom chrome. */
    trackBottomPadding: Dp = 0.dp,
    /**
     * Extra draw-alpha multiplier, read every frame inside the draw phase.
     * Hosts riding shared-element flights pass their transition alpha so the
     * bar fades with the screen while it is lifted into the transition
     * overlay (a lifted layer escapes the host hierarchy's own fades).
     */
    alpha: () -> Float = { 1f }
) {
    FoamScrollbarCore(
        listLayoutInfo = { state.layoutInfo },
        gridLayoutInfo = null,
        onThumbScrollTo = { itemIndex, offsetInItem ->
            state.requestScrollToItem(itemIndex, offsetInItem)
        },
        color = color,
        thickness = thickness,
        pressedThickness = pressedThickness,
        minContentToViewportRatio = minContentToViewportRatio,
        minThumbHeight = minThumbHeight,
        trackTopPadding = trackTopPadding,
        trackBottomPadding = trackBottomPadding,
        alpha = alpha,
        modifier = modifier
    )
}

/** Grid counterpart of [FoamScrollbar] (see it for the full behavior contract). */
@Composable
fun FoamScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    color: Color = FoamTheme.accentColor,
    thickness: Dp = 8.dp,
    pressedThickness: Dp = 18.dp,
    /** Show the scrollbar only when the content is at least this many viewports tall. */
    minContentToViewportRatio: Float = 2f,
    /** Floor for the thumb length, so huge collections stay graspable. */
    minThumbHeight: Dp = 24.dp,
    /** Clears the thumb's track (and touch strip) of the screen's top chrome. */
    trackTopPadding: Dp = 0.dp,
    /** Clears the thumb's track (and touch strip) of the screen's bottom chrome. */
    trackBottomPadding: Dp = 0.dp,
    /**
     * Extra draw-alpha multiplier, read every frame inside the draw phase.
     * Hosts riding shared-element flights pass their transition alpha so the
     * bar fades with the screen while it is lifted into the transition
     * overlay (a lifted layer escapes the host hierarchy's own fades).
     */
    alpha: () -> Float = { 1f }
) {
    FoamScrollbarCore(
        listLayoutInfo = null,
        gridLayoutInfo = { state.layoutInfo },
        onThumbScrollTo = { itemIndex, offsetInItem ->
            state.requestScrollToItem(itemIndex, offsetInItem)
        },
        color = color,
        thickness = thickness,
        pressedThickness = pressedThickness,
        minContentToViewportRatio = minContentToViewportRatio,
        minThumbHeight = minThumbHeight,
        trackTopPadding = trackTopPadding,
        trackBottomPadding = trackBottomPadding,
        alpha = alpha,
        modifier = modifier
    )
}

/**
 * Geometry snapshot the thumb, its drag mapping and the direct-jump mapping
 * are derived from, computed from whichever lazy layout is currently hosting
 * the content. All px. The structural fields ([headerPx], [itemPx],
 * [rowCountTotal], [trailerTopPx], [totalItemsCount], [columns]) mirror the
 * hosts' uniform list shape (hero header + uniform rows + bottom spacer) and
 * turn a content-space scroll offset into a concrete (item index, offset
 * within item) jump target — see [jumpTarget].
 */
private class ScrollbarGeometry(
    /** Content-space scroll position of the viewport's top edge. */
    val scrollOffsetPx: Float,
    /** Total scrollable distance: content height (paddings included) minus container height. */
    val scrollExtentPx: Float,
    /** Height of the lazy container itself (viewport minus its content paddings). */
    val viewportPx: Float,
    /** Full height of the leading edge item (the hero header, index 0). */
    val headerPx: Float,
    /** Height of one uniform item (row) between the edge items. */
    val itemPx: Float,
    /** Items per grid row; 1 for lists (a grid scrolls by rows, not by cells). */
    val columns: Int,
    /** Number of uniform items between the leading and trailing edge items. */
    val rowCountTotal: Int,
    /** Content-space top of the trailing edge item (the bottom spacer). */
    val trailerTopPx: Float,
    /** Total item count, for targeting the trailing edge item. */
    val totalItemsCount: Int
) {
    /**
     * Maps a content-space scroll offset to the (item index, offset within
     * that item) pair a direct requestScrollToItem jump needs: offsets inside
     * the leading edge item target index 0, offsets past the last uniform row
     * target the trailing edge item, everything in between targets the row
     * under the offset with the within-row remainder as the item offset. For
     * grids the index is the target row's FIRST item (a grid can only scroll
     * by whole rows — every cell of a row shares its offset.y).
     */
    fun jumpTarget(targetPx: Float): Pair<Int, Int> {
        val offset = targetPx.coerceIn(0f, scrollExtentPx)
        if (offset >= trailerTopPx) {
            return totalItemsCount - 1 to (offset - trailerTopPx).roundToInt()
        }
        if (offset < headerPx) return 0 to offset.roundToInt()
        val rowFloat = (offset - headerPx) / itemPx
        val row = rowFloat.toInt().coerceIn(0, rowCountTotal - 1)
        return 1 + row * columns to ((rowFloat - row) * itemPx).roundToInt()
    }
}

/**
 * Remembered per-host measurements feeding the thumb geometry: the hero
 * header's and bottom spacer's full heights plus the uniform item height.
 * Plain fields (no snapshot state) — they are sampled during the draw/gesture
 * phases from the current layout info and only ever converge to stable
 * constants, so no reader needs invalidation. `headerPx` stays 0 until the
 * header item is first composed: screens opened already scrolled past it
 * estimate slightly conservatively until a scroll near the top samples it
 * once.
 */
private class ScrollbarSamples {
    /** Full height of the leading item (the hero header, index 0). */
    var headerPx = 0f

    /** Full height of the trailing item (the bottom spacer, last index). */
    var trailerPx = 0f

    /** Average height of the uniform items between the edge items. */
    var itemPx = 0f
}

@Composable
private fun FoamScrollbarCore(
    listLayoutInfo: (() -> LazyListLayoutInfo)?,
    gridLayoutInfo: (() -> LazyGridLayoutInfo)?,
    onThumbScrollTo: (itemIndex: Int, offsetInItem: Int) -> Unit,
    color: Color,
    thickness: Dp,
    pressedThickness: Dp,
    minContentToViewportRatio: Float,
    minThumbHeight: Dp,
    trackTopPadding: Dp,
    trackBottomPadding: Dp,
    alpha: () -> Float,
    modifier: Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    // Fresh lambda reads for the gesture/draw phases: the wrappers recreate
    // these on every recomposition (they close over live state), while the
    // pointerInput below never restarts.
    val currentListLayoutInfo by rememberUpdatedState(listLayoutInfo)
    val currentGridLayoutInfo by rememberUpdatedState(gridLayoutInfo)
    val currentOnThumbScrollTo by rememberUpdatedState(onThumbScrollTo)
    // Stable across recompositions: sampled in place by the geometry lambda.
    val samples = remember { ScrollbarSamples() }

    val currentGeometry by rememberUpdatedState {
        val list = currentListLayoutInfo?.invoke()
        if (list != null) {
            list.toScrollbarGeometry(minContentToViewportRatio, samples)
        } else {
            currentGridLayoutInfo?.invoke()
                ?.toScrollbarGeometry(minContentToViewportRatio, samples)
        }
    }

    // Press/drag feedback: thickness and emphasis animate on the same curve so
    // grabbing the thumb reads as one continuous swell, and relax on release.
    val animatedThickness by animateDpAsState(
        targetValue = if (pressed) pressedThickness else thickness,
        animationSpec = tween(durationMillis = ScrollbarPressAnimationMs),
        label = "scrollbarThickness"
    )
    val animatedAlpha by animateFloatAsState(
        targetValue = if (pressed) 1f else ScrollbarIdleAlpha,
        animationSpec = tween(durationMillis = ScrollbarPressAnimationMs),
        label = "scrollbarEmphasis"
    )

    Box(
        modifier = modifier
            // The insets shrink both the drawn track and the touch strip: the
            // thumb never reaches under the screen's top/bottom chrome.
            .padding(top = trackTopPadding, bottom = trackBottomPadding)
            .width(ScrollbarTouchWidth)
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    try {
                        drag(down.id) { change ->
                            // Not long enough to scroll: stay fully transparent
                            // to the list (don't consume, don't hijack).
                            val geo = currentGeometry() ?: return@drag
                            val dy = change.positionChange().y
                            if (dy == 0f) return@drag
                            // Consuming from the first moved change keeps the
                            // list's own scroll (and row clicks) out of the way.
                            change.consume()
                            val track = size.height.toFloat()
                            val thumbHeight = geo.thumbHeightPx(track, minThumbHeight.toPx())
                            val travel = (track - thumbHeight).coerceAtLeast(1f)
                            val targetOffset =
                                ((geo.scrollOffsetPx / geo.scrollExtentPx * travel + dy)
                                    .coerceIn(0f, travel)) / travel * geo.scrollExtentPx
                            val (itemIndex, offsetInItem) = geo.jumpTarget(targetOffset)
                            currentOnThumbScrollTo(itemIndex, offsetInItem)
                        }
                    } finally {
                        pressed = false
                    }
                }
            }
            .drawBehind {
                val geo = currentGeometry() ?: return@drawBehind
                if (size.height <= 0f) return@drawBehind
                val thumbHeight = geo.thumbHeightPx(size.height, minThumbHeight.toPx())
                val travel = (size.height - thumbHeight).coerceAtLeast(1f)
                val thumbTop = (geo.scrollOffsetPx / geo.scrollExtentPx).coerceIn(0f, 1f) * travel
                val thumbWidth = animatedThickness.toPx()
                drawRoundRect(
                    color = color.copy(alpha = color.alpha * animatedAlpha * alpha()),
                    topLeft = Offset(size.width - ScrollbarEdgeInset.toPx() - thumbWidth, thumbTop),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(thumbWidth / 2f)
                )
            }
    )
}

/**
 * Thumb length for this geometry: the container's share of the content,
 * visually scaled down ([ScrollbarThumbLengthScale]) and floored.
 */
private fun ScrollbarGeometry.thumbHeightPx(trackPx: Float, minThumbPx: Float): Float =
    (trackPx * (viewportPx / (viewportPx + scrollExtentPx)) * ScrollbarThumbLengthScale)
        .coerceAtLeast(minThumbPx)

/**
 * Scrollbar geometry for a lazy LIST whose leading item is a hero header and
 * whose trailing item is a bottom spacer: both are measured individually
 * ([ScrollbarSamples.headerPx]/[ScrollbarSamples.trailerPx], their FULL
 * heights even while partially visible) and excluded from the uniform-item
 * average, so the estimate (and the thumb height with it) never pulses while
 * either edge item slides through the viewport. Returns null while nothing is
 * composed, the uniform items aren't measured yet, or the content isn't long
 * enough to scroll.
 *
 * The viewport spans the container PLUS its content paddings
 * (viewportStartOffset == -beforeContentPadding, viewportEndOffset == container
 * height - afterContentPadding), so both paddings are folded back in: the
 * scrollable extent is the full padded content minus the bare container height.
 */
private fun LazyListLayoutInfo.toScrollbarGeometry(
    minRatio: Float,
    samples: ScrollbarSamples
): ScrollbarGeometry? {
    val visible = visibleItemsInfo
    if (visible.isEmpty() || totalItemsCount == 0) return null
    val first = visible.first()
    val container = (viewportEndOffset - viewportStartOffset +
            afterContentPadding - beforeContentPadding).toFloat()
    if (container <= 0f) return null

    // Sample the edge items and sum the uniform rows in one pass. Item index
    // 0 takes precedence over the last index (they coincide for a 1-item list).
    var rowSum = 0
    var rowCount = 0
    for (item in visible) {
        when {
            item.index == 0 -> samples.headerPx = item.size.toFloat()
            item.index == totalItemsCount - 1 -> samples.trailerPx = item.size.toFloat()
            else -> {
                rowSum += item.size
                rowCount++
            }
        }
    }
    if (rowCount > 0) samples.itemPx = rowSum.toFloat() / rowCount

    val itemPx = samples.itemPx
    if (itemPx <= 0f) return null
    // Hero header + bottom spacer excluded from the row count.
    val rowCountTotal = (totalItemsCount - 2).coerceAtLeast(0)
    val content = beforeContentPadding + samples.headerPx +
            itemPx * rowCountTotal + samples.trailerPx + afterContentPadding
    if (content < container * minRatio) return null
    val extent = content - container
    if (extent <= 0f) return null
    // Exact scroll offset from the first visible item's real position: its
    // content-space top (header at 0, row i at header + (i-1) rows) minus its
    // measured offset from the container top. Nothing in the visible range is
    // estimated, so the thumb never twitches while the hero slides through.
    val firstContentTop = if (first.index == 0) {
        0f
    } else {
        samples.headerPx + (first.index - 1) * itemPx
    }
    val scrollOffset = firstContentTop - (first.offset - viewportStartOffset)
    return ScrollbarGeometry(
        scrollOffsetPx = scrollOffset,
        scrollExtentPx = extent,
        viewportPx = container,
        headerPx = samples.headerPx,
        itemPx = itemPx,
        columns = 1,
        rowCountTotal = rowCountTotal,
        trailerTopPx = samples.headerPx + rowCountTotal * itemPx,
        totalItemsCount = totalItemsCount
    )
}

/** Grid counterpart of [LazyListLayoutInfo.toScrollbarGeometry] (cells on the y axis). */
private fun LazyGridLayoutInfo.toScrollbarGeometry(
    minRatio: Float,
    samples: ScrollbarSamples
): ScrollbarGeometry? {
    val visible = visibleItemsInfo
    if (visible.isEmpty() || totalItemsCount == 0) return null
    val first = visible.first()
    val container = (viewportEndOffset - viewportStartOffset +
            afterContentPadding - beforeContentPadding).toFloat()
    if (container <= 0f) return null

    var rowSum = 0
    var rowCount = 0
    var columns = 1
    var firstDataRowY = Float.NaN
    for (item in visible) {
        when {
            item.index == 0 -> samples.headerPx = item.size.height.toFloat()
            item.index == totalItemsCount - 1 -> samples.trailerPx = item.size.height.toFloat()
            else -> {
                if (firstDataRowY.isNaN()) {
                    firstDataRowY = item.offset.y.toFloat()
                } else if (item.offset.y.toFloat() == firstDataRowY) {
                    columns++
                }
                rowSum += item.size.height
                rowCount++
            }
        }
    }
    if (rowCount > 0) samples.itemPx = rowSum.toFloat() / rowCount

    val itemPx = samples.itemPx
    if (itemPx <= 0f) return null
    val rowCountTotal = (totalItemsCount - 2).coerceAtLeast(0)
    val content = beforeContentPadding + samples.headerPx +
            itemPx * rowCountTotal + samples.trailerPx + afterContentPadding
    if (content < container * minRatio) return null
    val extent = content - container
    if (extent <= 0f) return null
    val firstContentTop = if (first.index == 0) {
        0f
    } else {
        samples.headerPx + (first.index - 1) * itemPx
    }
    val scrollOffset = firstContentTop - (first.offset.y - viewportStartOffset)
    return ScrollbarGeometry(
        scrollOffsetPx = scrollOffset,
        scrollExtentPx = extent,
        viewportPx = container,
        headerPx = samples.headerPx,
        itemPx = itemPx,
        columns = columns,
        rowCountTotal = rowCountTotal,
        trailerTopPx = samples.headerPx + rowCountTotal * itemPx,
        totalItemsCount = totalItemsCount
    )
}
