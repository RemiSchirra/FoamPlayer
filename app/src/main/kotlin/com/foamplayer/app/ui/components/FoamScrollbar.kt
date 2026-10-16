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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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

/** Duration of the press/drag thickness + emphasis tween. */
private const val ScrollbarPressAnimationMs = 150

/**
 * Visual scale on the thumb's proportional length: the viewport/content share
 * is halved so the thumb stays compact. Drag mapping is unaffected — travel
 * still spans the whole track.
 */
private const val ScrollbarThumbLengthScale = 0.5f

/** Diameter of the section-letter bubble drawn beside the thumb while dragging. */
private val ScrollbarBubbleSize = 60.dp

/** Horizontal gap between the bubble's squared corner and the thumb's leading edge. */
private val ScrollbarBubbleGap = 6.dp

/**
 * The uppercased first non-blank character of [text] — the section letter a
 * fast-scroll bubble shows for an alphabetically sorted row. Null for blank
 * text, which hides the bubble.
 */
internal fun scrollSectionLetter(text: String?): String? =
    text?.firstOrNull { !it.isWhitespace() }?.uppercaseChar()?.toString()

/**
 * Fast scrollbar for long lazy content: a thumb floating over the container's
 * end edge, colored by [color]. Appears — visually AND as a touch strip — only
 * when the content spans at least [minContentToViewportRatio] viewports, so
 * short collections never intercept touches under the end edge.
 *
 * Pressing thickens the thumb (never a snap); dragging scrolls the list
 * proportionally. The scroll lands as a DIRECT jump to the target item
 * ([LazyListState.requestScrollToItem]) rather than incremental deltas: a long
 * throw never composes the items it passes over, which keeps huge collections
 * jank-free. Plain taps fall through to the rows underneath — only an actual
 * drag inside the strip is captured.
 *
 * Thumb sizing models the hosts' structure — a leading header item and a
 * trailing spacer — by measuring both individually and averaging ONLY the
 * uniform items between them: folding the tall hero into the average would
 * make the thumb height pulse as the hero slides through the viewport.
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
     * Optional section-letter source for alphabetically sorted lists: maps the
     * item index under the thumb to the label shown in a bubble to the thumb's
     * left while dragging. Sized to a single glyph; return null (or pass null)
     * for indices with no letter (e.g. the header row) to hide the bubble.
     */
    labelForIndex: ((itemIndex: Int) -> String?)? = null,
    /**
     * Extra draw-alpha multiplier, read every frame inside the draw phase.
     * Hosts riding shared-element flights pass their transition alpha so the
     * bar fades with the screen while lifted into the transition overlay.
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
        labelForIndex = labelForIndex,
        alpha = alpha,
        modifier = modifier
    )
}

/** Grid counterpart of [FoamScrollbar] (see it for the behavior contract). */
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
     * Optional section-letter source for alphabetically sorted lists: maps the
     * item index under the thumb to the label shown in a bubble to the thumb's
     * left while dragging. Sized to a single glyph; return null (or pass null)
     * for indices with no letter (e.g. the header row) to hide the bubble.
     */
    labelForIndex: ((itemIndex: Int) -> String?)? = null,
    /**
     * Extra draw-alpha multiplier, read every frame inside the draw phase.
     * Hosts riding shared-element flights pass their transition alpha so the
     * bar fades with the screen while lifted into the transition overlay.
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
        labelForIndex = labelForIndex,
        alpha = alpha,
        modifier = modifier
    )
}

/**
 * Geometry snapshot the thumb, its drag mapping and the direct-jump mapping
 * derive from. All px. Mirrors the hosts' uniform list shape (hero header +
 * uniform rows + bottom spacer).
 */
private class ScrollbarGeometry(
    /** Content-space scroll position of the viewport's top edge. */
    val scrollOffsetPx: Float,
    /** Total scrollable distance: content height minus container height. */
    val scrollExtentPx: Float,
    /** Height of the lazy container itself (viewport minus content paddings). */
    val viewportPx: Float,
    /** Full height of the leading edge item (the hero header). */
    val headerPx: Float,
    /** Height of one uniform item between the edge items. */
    val itemPx: Float,
    /** Items per grid row; 1 for lists. */
    val columns: Int,
    /** Number of uniform items between the edge items. */
    val rowCountTotal: Int,
    /** Content-space top of the trailing edge item (the bottom spacer). */
    val trailerTopPx: Float,
    /** Total item count, for targeting the trailing edge item. */
    val totalItemsCount: Int
) {
    /**
     * Maps a content-space scroll offset to the (item index, offset within
     * item) pair a direct jump needs. For grids the index is the target row's
     * FIRST item (a grid only scrolls by whole rows).
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
 * Remembered per-host measurements feeding the thumb geometry. Plain fields —
 * sampled during the draw/gesture phases and only converge to stable
 * constants, so no reader needs invalidation.
 */
private class ScrollbarSamples {
    /** Full height of the leading item (the hero header). */
    var headerPx = 0f

    /** Full height of the trailing item (the bottom spacer). */
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
    labelForIndex: ((itemIndex: Int) -> String?)?,
    alpha: () -> Float,
    modifier: Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var bubbleLabel by remember { mutableStateOf<String?>(null) }
    // Fresh lambda reads for the gesture/draw phases: the wrappers recreate
    // these on every recomposition while the pointerInput never restarts.
    val currentListLayoutInfo by rememberUpdatedState(listLayoutInfo)
    val currentGridLayoutInfo by rememberUpdatedState(gridLayoutInfo)
    val currentOnThumbScrollTo by rememberUpdatedState(onThumbScrollTo)
    val currentLabelForIndex by rememberUpdatedState(labelForIndex)
    val bubbleTextStyle = FoamTheme.typography.titleLarge
    val textMeasurer = rememberTextMeasurer()
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
    val bubbleAlpha by animateFloatAsState(
        targetValue = if (pressed && activeLabel != null) 1f else 0f,
        animationSpec = tween(durationMillis = ScrollbarPressAnimationMs),
        label = "scrollbarBubbleEmphasis"
    )

    Box(
        modifier = modifier
            .padding(top = trackTopPadding, bottom = trackBottomPadding)
            .width(ScrollbarTouchWidth)
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    // Seed the letter from the item currently at the viewport's
                    // top, so the bubble reads the section on a plain press too.
                    val seed = currentGeometry()?.let { geo ->
                        currentLabelForIndex?.invoke(geo.jumpTarget(geo.scrollOffsetPx).first)
                    }
                    activeLabel = seed
                    if (seed != null) bubbleLabel = seed
                    try {
                        drag(down.id) { change ->
                            // Not long enough to scroll: stay transparent to the list.
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
                            val label = currentLabelForIndex?.invoke(itemIndex)
                            activeLabel = label
                            if (label != null) bubbleLabel = label
                            currentOnThumbScrollTo(itemIndex, offsetInItem)
                        }
                    } finally {
                        pressed = false
                        activeLabel = null
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
                val thumbLeft = size.width - ScrollbarEdgeInset.toPx() - thumbWidth
                drawRoundRect(
                    color = color.copy(alpha = color.alpha * animatedAlpha * alpha()),
                    topLeft = Offset(thumbLeft, thumbTop),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(thumbWidth / 2f)
                )

                // Section-letter bubble: a circle whose bottom-right corner is
                // square and pinned to the thumb's vertical center, so the notch
                // points at the thumb's leading edge. Drawn from the retained
                // label so it survives the fade-out as [bubbleAlpha] drops.
                val label = bubbleLabel
                if (label != null && bubbleAlpha > 0f) {
                    val opacity = bubbleAlpha * alpha()
                    val bubblePx = ScrollbarBubbleSize.toPx()
                    val bubbleRight = thumbLeft - ScrollbarBubbleGap.toPx()
                    val bubbleBottom = thumbTop + thumbHeight / 2f
                    val rect = Rect(
                        left = bubbleRight - bubblePx,
                        top = bubbleBottom - bubblePx,
                        right = bubbleRight,
                        bottom = bubbleBottom
                    )
                    val radius = CornerRadius(bubblePx / 2f)
                    val bubble = Path().apply {
                        addRoundRect(
                            RoundRect(
                                rect = rect,
                                topLeft = radius,
                                topRight = radius,
                                bottomRight = CornerRadius.Zero,
                                bottomLeft = radius
                            )
                        )
                    }
                    drawPath(
                        path = bubble,
                        color = color.copy(alpha = color.alpha * opacity)
                    )
                    val contentColor =
                        if (color.luminance() > 0.5f) Color.Black else Color.White
                    val layout = textMeasurer.measure(
                        text = label,
                        style = bubbleTextStyle.copy(
                            color = contentColor.copy(alpha = opacity)
                        ),
                        maxLines = 1
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            x = rect.center.x - layout.size.width / 2f,
                            y = rect.center.y - layout.size.height / 2f
                        )
                    )
                }
            }
    )
}

/** Thumb length for this geometry: the container's share of the content, scaled and floored. */
private fun ScrollbarGeometry.thumbHeightPx(trackPx: Float, minThumbPx: Float): Float =
    (trackPx * (viewportPx / (viewportPx + scrollExtentPx)) * ScrollbarThumbLengthScale)
        .coerceAtLeast(minThumbPx)

/**
 * Scrollbar geometry for a lazy LIST whose leading item is a hero header and
 * whose trailing item is a bottom spacer: both are measured individually and
 * excluded from the uniform-item average, so the estimate never pulses while
 * either edge item slides through the viewport. Returns null while nothing is
 * composed, items aren't measured yet, or the content isn't long enough.
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
        when (item.index) {
            0 -> samples.headerPx = item.size.toFloat()
            totalItemsCount - 1 -> samples.trailerPx = item.size.toFloat()
            else -> {
                rowSum += item.size
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
    // Exact scroll offset from the first visible item's real position; nothing
    // in the visible range is estimated.
    val firstContentTop = if (first.index == 0) {
        0f
    } else {
        samples.headerPx + (first.index - 1) * itemPx
    }
    val scrollOffset = firstContentTop - first.offset
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
        when (item.index) {
            0 -> samples.headerPx = item.size.height.toFloat()
            totalItemsCount - 1 -> samples.trailerPx = item.size.height.toFloat()
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
    val scrollOffset = firstContentTop - first.offset.y
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
