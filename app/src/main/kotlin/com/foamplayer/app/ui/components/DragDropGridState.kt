package com.foamplayer.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Distance from the top/bottom of the grid's viewport that triggers autoscroll. */
private val AUTO_SCROLL_EDGE_ZONE = 56.dp

/** Grid scroll per autoscroll tick, in px. */
private const val AUTO_SCROLL_STEP_PX = 32f

/** Autoscroll tick interval, in ms. */
private const val AUTO_SCROLL_INTERVAL_MS = 16L

/** Duration of the settle animation played when the drag is released. */
private const val SETTLE_DURATION_MS = 200

@Composable
fun rememberDragDropGridState(
    gridState: LazyGridState,
    canMove: (fromIndex: Int, toIndex: Int) -> Boolean = { _, _ -> true },
    onMove: (fromIndex: Int, toIndex: Int) -> Unit
): DragDropGridState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // The callbacks are captured once by remember, so they are kept fresh
    // through rememberUpdatedState: a lambda closing over a plain value would
    // freeze at its first-composition value mid-drag.
    val latestCanMove by rememberUpdatedState(canMove)
    val latestOnMove by rememberUpdatedState(onMove)
    return remember(gridState) {
        DragDropGridState(gridState, scope, density, { f, t -> latestCanMove(f, t) }, { f, t -> latestOnMove(f, t) })
    }
}

/**
 * Grid counterpart of the queue's drag-drop list state: drives a
 * draggable-reorderable LazyVerticalGrid with 2D position tracking,
 * swap-on-hover, edge autoscroll and an animated settle on release.
 */
class DragDropGridState(
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
    private val density: Density,
    private val canMove: (Int, Int) -> Boolean,
    private val onMove: (Int, Int) -> Unit
) {
    private data class ItemSnapshot(val index: Int, val offset: IntOffset, val size: IntOffset)

    /** Index of the item currently being dragged, or null. */
    var draggedIndex by mutableStateOf<Int?>(null)
        private set

    /** Clamped visual offset (px) applied to the dragged item via graphicsLayer. */
    var draggingItemOffset by mutableStateOf(IntOffset.Zero)
        private set

    private var draggedDistance = Offset.Zero
    private var anchor: ItemSnapshot? = null
    private var autoScrollJob: Job? = null
    private var autoScrollDirection = 0f
    private var settleJob: Job? = null

    private val edgeZonePx: Float by lazy { with(density) { AUTO_SCROLL_EDGE_ZONE.toPx() } }

    fun isItemDragging(index: Int): Boolean = index == draggedIndex

    fun onDragStart(index: Int) {
        settleJob?.cancel()
        settleJob = null

        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        draggedIndex = index
        draggedDistance = Offset.Zero
        draggingItemOffset = info.offset
        anchor = ItemSnapshot(info.index, info.offset, IntOffset(info.size.width, info.size.height))
    }

    fun onDrag(dragAmount: Offset) {
        draggedDistance += dragAmount
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
        draggingItemOffset = IntOffset.Zero
        draggedDistance = Offset.Zero
        anchor = null
    }

    private fun settleToRestingPosition() {
        stopAutoScroll()

        val index = draggedIndex ?: return
        val layoutInfo = gridState.layoutInfo
        val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

        val resting = targetInfo?.offset?.let { IntOffset(it.x, it.y) } ?: run {
            val size = anchor?.size ?: IntOffset.Zero
            val maxX = (layoutInfo.viewportSize.width - size.x).coerceAtLeast(0)
            val maxY = (layoutInfo.viewportEndOffset - size.y).coerceAtLeast(layoutInfo.viewportStartOffset)
            IntOffset(
                draggingItemOffset.x.coerceIn(0, maxX),
                draggingItemOffset.y.coerceIn(layoutInfo.viewportStartOffset, maxY)
            )
        }

        if (abs(resting.x - draggingItemOffset.x) < 0.5f &&
            abs(resting.y - draggingItemOffset.y) < 0.5f
        ) {
            reset()
            return
        }

        settleJob?.cancel()
        val animation = Animatable(draggingItemOffset, IntOffset.VectorConverter)
        settleJob = scope.launch {
            animation.animateTo(
                resting,
                tween(durationMillis = SETTLE_DURATION_MS, easing = FastOutSlowInEasing)
            ) {
                draggingItemOffset = value
            }
            reset()
        }
    }

    /** Recomputes clamped position, swap target and autoscroll state. */
    private fun evaluate() {
        val current = anchor ?: return
        val layoutInfo = gridState.layoutInfo
        val viewportStart = layoutInfo.viewportStartOffset
        val viewportEnd = layoutInfo.viewportEndOffset

        val raw = IntOffset(
            (current.offset.x + draggedDistance.x).roundToInt(),
            (current.offset.y + draggedDistance.y).roundToInt()
        )
        val maxX = (layoutInfo.viewportSize.width - current.size.x).coerceAtLeast(0)
        val maxY = (viewportEnd - current.size.y).coerceAtLeast(viewportStart)

        // Lock the dragged item to the grid's own bounds, not the finger's.
        val clamped = IntOffset(
            raw.x.coerceIn(0, maxX),
            raw.y.coerceIn(viewportStart, maxY)
        )
        draggingItemOffset = clamped

        // Swap with whichever visible item the dragged center now overlaps.
        val draggedCenter = IntOffset(clamped.x + current.size.x / 2, clamped.y + current.size.y / 2)
        val target = layoutInfo.visibleItemsInfo.firstOrNull { info ->
            draggedCenter.x >= info.offset.x && draggedCenter.x <= info.offset.x + info.size.width &&
                draggedCenter.y >= info.offset.y && draggedCenter.y <= info.offset.y + info.size.height
        }
        val fromIndex = draggedIndex
        if (target != null && fromIndex != null && target.index != fromIndex && canMove(fromIndex, target.index)) {
            // Record the scroll position before the grid mutates.
            val firstIndex = gridState.firstVisibleItemIndex
            val firstOffset = gridState.firstVisibleItemScrollOffset

            onMove(fromIndex, target.index)
            draggedIndex = target.index
            // Re-anchor so the offset math stays correct against the new slot.
            val newOffset = IntOffset(target.offset.x, target.offset.y)
            anchor = ItemSnapshot(target.index, newOffset, IntOffset(target.size.width, target.size.height))

            // Preserve the out-of-bounds finger position so the item doesn't
            // detach from the finger after a swap.
            draggedDistance = Offset(
                raw.x - newOffset.x.toFloat(),
                raw.y - newOffset.y.toFloat()
            )

            // If we swapped the top visible item, force the grid to keep the
            // current index/offset rather than jumping to follow the key.
            if (firstIndex == fromIndex || firstIndex == target.index) {
                gridState.requestScrollToItem(firstIndex, firstOffset)
            }
        }

        updateAutoScroll(raw.y.toFloat(), (raw.y + current.size.y).toFloat(), viewportStart, viewportEnd)
    }

    private fun updateAutoScroll(rawTop: Float, rawBottom: Float, viewportStart: Int, viewportEnd: Int) {
        val layoutInfo = gridState.layoutInfo

        val atListStart = gridState.firstVisibleItemIndex == 0 &&
                gridState.firstVisibleItemScrollOffset == 0
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
        val atListEnd = lastVisible != null &&
                lastVisible.index == layoutInfo.totalItemsCount - 1 &&
                (lastVisible.offset.y + lastVisible.size.height) <= viewportEnd

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
                val consumed = gridState.scrollBy(step)

                // Offset the drag distance by the scroll amount so the item's
                // raw position stays constant relative to the screen.
                draggedDistance += Offset(0f, consumed)

                // Keep the anchor's frame of reference in sync with the scroll,
                // so the item stays visually locked at the edge.
                anchor?.let { anchor = it.copy(offset = it.offset.copy(y = it.offset.y - consumed.roundToInt())) }

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
