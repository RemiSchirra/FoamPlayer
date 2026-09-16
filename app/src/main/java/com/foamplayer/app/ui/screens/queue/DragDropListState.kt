package com.foamplayer.app.ui.screens.queue

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Distance from the top/bottom of the list's viewport that triggers autoscroll. */
private val AUTO_SCROLL_EDGE_ZONE = 56.dp

/** How far the list scrolls per autoscroll tick, in px. */
private const val AUTO_SCROLL_STEP_PX = 32f

/** How often an autoscroll tick fires, in ms. */
private const val AUTO_SCROLL_INTERVAL_MS = 16L

/** Duration of the settle animation played when the drag is released, in ms. */
private const val SETTLE_DURATION_MS = 200

@Composable
fun rememberDragDropListState(
    lazyListState: LazyListState,
    canMove: (fromIndex: Int, toIndex: Int) -> Boolean = { _, _ -> true },
    onMove: (fromIndex: Int, toIndex: Int) -> Unit
): DragDropListState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(lazyListState) {
        DragDropListState(lazyListState, scope, density, canMove, onMove)
    }
}

/**
 * Drives a draggable-reorderable LazyColumn:
 *  - Tracks the item currently being dragged and its visual (clamped) offset.
 *  - Clamps ("locks") the dragged item to the top/bottom edge of the list's viewport.
 *  - Swaps the dragged item with whichever item it's currently hovering over, via [onMove].
 *  - Autoscrolls the list when the drag is locked at an edge but more content exists
 *    beyond that edge.
 *  - Settles the dragged item into its final slot with a short animation on release,
 *    instead of snapping there instantly.
 */
class DragDropListState(
    private val lazyListState: LazyListState,
    private val scope: CoroutineScope,
    private val density: Density,
    private val canMove: (Int, Int) -> Boolean,
    private val onMove: (Int, Int) -> Unit
) {
    private data class ItemSnapshot(val index: Int, val offset: Int, val size: Int)

    /** Index of the item currently being dragged, or null if nothing is dragging. */
    var draggedIndex by mutableStateOf<Int?>(null)
        private set

    /** Clamped visual Y offset (px) to apply to the dragged item via graphicsLayer. */
    var draggingItemTop by mutableStateOf(0f)
        private set

    private var draggedDistance = 0f
    private var anchor: ItemSnapshot? = null
    private var autoScrollJob: Job? = null
    private var autoScrollDirection = 0f
    private var settleJob: Job? = null

    private val edgeZonePx: Float by lazy { with(density) { AUTO_SCROLL_EDGE_ZONE.toPx() } }

    fun isItemDragging(index: Int): Boolean = index == draggedIndex

    fun onDragStart(index: Int) {
        settleJob?.cancel()
        settleJob = null

        val info = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        draggedIndex = index
        draggedDistance = 0f
        draggingItemTop = info.offset.toFloat()
        anchor = ItemSnapshot(info.index, info.offset, info.size)
    }

    fun onDrag(dragAmount: Offset) {
        draggedDistance += dragAmount.y
        evaluate()
    }

    fun onDragEnd() {
        settleToRestingPosition()
    }

    fun onDragCancel() {
        settleToRestingPosition()
    }

    private fun reset() {
        stopAutoScroll()
        draggedIndex = null
        draggingItemTop = 0f
        draggedDistance = 0f
        anchor = null
    }

    private fun settleToRestingPosition() {
        stopAutoScroll()

        val index = draggedIndex ?: return
        val layoutInfo = lazyListState.layoutInfo
        val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

        val restingTop = targetInfo?.offset?.toFloat() ?: run {
            val size = anchor?.size ?: 0
            val maxTop = (layoutInfo.viewportEndOffset - size).toFloat()
                .coerceAtLeast(layoutInfo.viewportStartOffset.toFloat())
            draggingItemTop.coerceIn(layoutInfo.viewportStartOffset.toFloat(), maxTop)
        }

        val startTop = draggingItemTop

        if (abs(restingTop - startTop) < 0.5f) {
            reset()
            return
        }

        settleJob?.cancel()
        val animation = Animatable(startTop)
        settleJob = scope.launch {
            animation.animateTo(
                restingTop,
                tween(durationMillis = SETTLE_DURATION_MS, easing = FastOutSlowInEasing)
            ) {
                draggingItemTop = value
            }
            reset()
        }
    }

    /** Recomputes clamped position, swap target, and autoscroll state from current inputs. */
    private fun evaluate() {
        val current = anchor ?: return
        val layoutInfo = lazyListState.layoutInfo
        val viewportStart = layoutInfo.viewportStartOffset
        val viewportEnd = layoutInfo.viewportEndOffset

        val rawTop = current.offset + draggedDistance
        val maxTop = (viewportEnd - current.size).toFloat().coerceAtLeast(viewportStart.toFloat())

        // Lock the dragged item's visible position within the list's own bounds,
        // not the raw finger position.
        val clampedTop = rawTop.coerceIn(viewportStart.toFloat(), maxTop)
        draggingItemTop = clampedTop

        // Swap with whichever visible item the dragged item's center now overlaps.
        val draggedCenter = clampedTop + current.size / 2f
        val target = layoutInfo.visibleItemsInfo.firstOrNull { info ->
            draggedCenter >= info.offset && draggedCenter <= info.offset + info.size
        }
        val fromIndex = draggedIndex
        if (target != null && fromIndex != null && target.index != fromIndex && canMove(fromIndex, target.index)) {
            // 1. Record the current scroll position before the list mutates
            val firstIndex = lazyListState.firstVisibleItemIndex
            val firstOffset = lazyListState.firstVisibleItemScrollOffset

            onMove(fromIndex, target.index)
            draggedIndex = target.index
            // Re-anchor so the offset math stays correct against the item's new slot.
            anchor = ItemSnapshot(target.index, target.offset, target.size)

            // FIX 1: Use rawTop instead of clampedTop to preserve the out-of-bounds
            // finger position. This stops the dragged item from detaching from your finger.
            draggedDistance = rawTop - target.offset

            // FIX 2: If we swapped the top visible item, force LazyColumn to maintain
            // the current index/offset rather than jumping to follow the item's key.
            if (firstIndex == fromIndex || firstIndex == target.index) {
                lazyListState.requestScrollToItem(firstIndex, firstOffset)
            }
        }

        // Materializing LazyListLayoutInfo copies the visible-items snapshot, so pass
        // the one we already read in evaluate() instead of re-reading per autoscroll tick.
        updateAutoScroll(layoutInfo, rawTop, rawTop + current.size, viewportStart, viewportEnd)
    }

    private fun updateAutoScroll(
        layoutInfo: LazyListLayoutInfo,
        rawTop: Float,
        rawBottom: Float,
        viewportStart: Int,
        viewportEnd: Int
    ) {
        val atListStart = lazyListState.firstVisibleItemIndex == 0 &&
                lazyListState.firstVisibleItemScrollOffset == 0
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
        val atListEnd = lastVisible != null &&
                lastVisible.index == layoutInfo.totalItemsCount - 1 &&
                (lastVisible.offset + lastVisible.size) <= viewportEnd

        val nearTop = (rawTop - viewportStart) < edgeZonePx
        val nearBottom = (viewportEnd - rawBottom) < edgeZonePx

        when {
            nearTop && !atListStart -> startAutoScroll(-AUTO_SCROLL_STEP_PX)
            nearBottom && !atListEnd -> startAutoScroll(AUTO_SCROLL_STEP_PX)
            else -> stopAutoScroll()
        }
    }

    private fun startAutoScroll(step: Float) {
        if (autoScrollJob?.isActive == true && autoScrollDirection == step) return
        autoScrollJob?.cancel()
        autoScrollDirection = step
        autoScrollJob = scope.launch {
            while (isActive) {
                val consumed = lazyListState.scrollBy(step)

                // FIX: Offset the drag distance by the scroll amount so the item's
                // rawTop remains constant relative to the screen.
                draggedDistance += consumed

                // Keep the anchor's frame of reference in sync with what just scrolled,
                // so the item stays visually locked at the edge instead of drifting.
                anchor?.let { anchor = it.copy(offset = it.offset - consumed.toInt()) }

                evaluate()
                delay(AUTO_SCROLL_INTERVAL_MS)
            }
        }
    }

    private fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
        autoScrollDirection = 0f
    }
}