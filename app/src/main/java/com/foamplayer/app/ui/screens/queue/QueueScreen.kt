package com.foamplayer.app.ui.screens.queue

import androidx.compose.foundation.background
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.ui.screens.library.TrackListItem
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * A track paired with its absolute position in the *real* underlying queue
 * (state.queue), captured at the same time as the rest of the displayed list.
 *
 * We deliberately never recompute this index later from a separately-read
 * offset (e.g. `index + state.queueIndex + 1` at click time) -- that combines
 * a position from one snapshot of the list with an offset from a possibly
 * newer snapshot (displayedQueue can lag state by a frame via the
 * LaunchedEffect below, or during a drag). Bundling the absolute index with
 * the track up front means every consumer (click, remove, reorder) always
 * uses an index that came from the exact same snapshot as what's on screen,
 * so it can never drift out of sync and resolve to the wrong track.
 */
private data class QueueItem(val absoluteIndex: Int, val track: TrackEntity)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun QueueScreen(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
    showTitle: Boolean = true,
    backgroundColor: Color = FoamTheme.colors.background
) {
    val state by viewModel.playbackState.collectAsState()
    val lazyListState = rememberLazyListState()

    // Display the queue starting at the next track, and hide previous songs.
    // Each item carries its real absolute index in state.queue, computed once
    // here alongside the slice itself.
    val upcomingItems = remember(state.queue, state.queueIndex) {
        val offset = (state.queueIndex + 1).coerceAtLeast(0)
        state.queue.drop(offset).mapIndexed { i, track -> QueueItem(offset + i, track) }
    }

    var displayedItems by remember { mutableStateOf(upcomingItems) }
    var dragOriginIndex by remember { mutableStateOf<Int?>(null) }
    var dragOriginAbsoluteIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(upcomingItems) {
        if (dragOriginIndex == null) {
            displayedItems = upcomingItems
        }
    }

    val dragDropListState = rememberDragDropListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            displayedItems = displayedItems.toMutableList().apply {
                add(to, removeAt(from))
            }
        }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        if (showTitle) {
            BasicText(
                text = "Up Next (${displayedItems.size} tracks)",
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = displayedItems,
                key = { _, item -> item.track.id }
            ) { index, item ->
                val isDragging = dragDropListState.isItemDragging(index)
                val currentIndex = rememberUpdatedState(index)

                TrackListItem(
                    track = item.track,
                    onClick = {
                        // Move the clicked track to be immediately next, then take a
                        // single +1 step to it, rather than skipping straight to its
                        // (possibly distant) absolute index. A big jump forward drags
                        // the main player's pager across every page in between via its
                        // own sync effect, which can race with this and resolve to the
                        // wrong settled page. Moving-then-skipping-by-one sidesteps that
                        // entirely and matches what tapping a queued track should do:
                        // play it next, immediately.
                        val nextIndex = state.queueIndex + 1
                        if (item.absoluteIndex != nextIndex) {
                            viewModel.moveQueueItem(item.absoluteIndex, nextIndex)
                        }
                        viewModel.skipToIndex(nextIndex)
                    },
                    active = false, // Current track is not in this list
                    modifier = Modifier
                        .let { if (isDragging) it else it.animateItem() }
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) {
                                val itemInfo = lazyListState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == index }
                                if (itemInfo != null) {
                                    dragDropListState.draggingItemTop - itemInfo.offset
                                } else 0f
                            } else {
                                0f
                            }
                        },
                    showDragHandle = true,
                    dragHandleModifier = Modifier.pointerInput(item.track.id) {
                        detectDragGestures(
                            onDragStart = {
                                dragOriginIndex = currentIndex.value
                                dragOriginAbsoluteIndex = displayedItems.getOrNull(currentIndex.value)?.absoluteIndex
                                dragDropListState.onDragStart(currentIndex.value)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragDropListState.onDrag(dragAmount)
                            },
                            onDragEnd = {
                                val from = dragOriginIndex
                                val to = dragDropListState.draggedIndex
                                val originAbsoluteIndex = dragOriginAbsoluteIndex
                                dragDropListState.onDragEnd()
                                dragOriginIndex = null
                                dragOriginAbsoluteIndex = null
                                if (from != null && to != null && from != to && originAbsoluteIndex != null) {
                                    viewModel.moveQueueItem(originAbsoluteIndex, originAbsoluteIndex + (to - from))
                                }
                            },
                            onDragCancel = {
                                dragDropListState.onDragCancel()
                                dragOriginIndex = null
                                dragOriginAbsoluteIndex = null
                                displayedItems = upcomingItems
                            }
                        )
                    },
                    onRemove = { viewModel.removeQueueItem(item.absoluteIndex) }
                )
            }
        }
    }
}