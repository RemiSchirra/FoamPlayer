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
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.theme.FoamTheme

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

    // Display the queue starting at the next track, and hide previous songs
    val upcomingItems = remember(state.queue, state.queueIndex, state.queueEntryIds, state.auxiliaryFlags) {
        val offset = (state.queueIndex + 1).coerceAtLeast(0)
        val previousUpcomingSize = state.queue.size - offset
        state.queue.drop(offset).mapIndexed { i, track ->
            val abs = offset + i
            QueueItem(
                // Put negative artificial id if the id is invalid
                entryId = state.queueEntryIds.getOrNull(abs) ?: -(abs + 1).toLong(),
                absoluteIndex = abs,
                track = track,
                isAuxiliary = state.auxiliaryFlags.getOrNull(abs) == true,
                isFreshFromRefill = i >= previousUpcomingSize
            )
        }
    }

    var displayedItems by remember { mutableStateOf(upcomingItems) }
    var dragOriginIndex by remember { mutableStateOf<Int?>(null) }
    var dragOriginEntryId by remember { mutableStateOf<Long?>(null) }

    // Latest upcoming list for the drag callbacks: pointerInput is keyed on the item
    // id, so a drag cancelled after a queue change (aux refill) would otherwise
    // restore a stale snapshot captured when the gesture handler was created.
    val latestUpcomingItems by rememberUpdatedState(upcomingItems)

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
                key = { _, item -> item.entryId }
            ) { index, item ->
                val isDragging = dragDropListState.isItemDragging(index)
                val currentIndex = rememberUpdatedState(index)

                TrackListItem(
                    track = item.track,
                    onClick = {
                        viewModel.playQueueEntry(item.entryId)
                    },
                    active = false, // Current track is not in this list
                    auxiliary = item.isAuxiliary,
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
                    dragHandleModifier = Modifier.pointerInput(item.entryId) {
                        detectDragGestures(
                            onDragStart = {
                                dragOriginIndex = currentIndex.value
                                dragOriginEntryId = displayedItems.getOrNull(currentIndex.value)?.entryId
                                dragDropListState.onDragStart(currentIndex.value)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragDropListState.onDrag(dragAmount)
                            },
                            onDragEnd = {
                                val from = dragOriginIndex
                                val to = dragDropListState.draggedIndex
                                val originEntryId = dragOriginEntryId
                                dragDropListState.onDragEnd()
                                dragOriginIndex = null
                                dragOriginEntryId = null
                                if (from != null && to != null && from != to && originEntryId != null) {
                                    viewModel.moveQueueEntry(originEntryId, to)
                                }
                            },
                            onDragCancel = {
                                dragDropListState.onDragCancel()
                                dragOriginIndex = null
                                dragOriginEntryId = null
                                displayedItems = latestUpcomingItems
                            }
                        )
                    },
                    onRemove = { viewModel.removeQueueEntry(item.entryId) }
                )
            }
        }
    }
}