package com.foamplayer.app.ui.screens.queue

import androidx.compose.foundation.background
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
import com.foamplayer.app.ui.screens.library.TrackListItem
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun QueueScreen(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
    showTitle: Boolean = true,
    backgroundColor: Color = FoamTheme.colors.background
) {
    val state by viewModel.playbackState.collectAsState()
    val lazyListState = rememberLazyListState()

    // Locally-reordered view of the queue. While a drag is in progress this is the
    // source of truth for the list (so items swap immediately as you drag), and it
    // resyncs from the ViewModel whenever nothing is being dragged.
    var displayedQueue by remember { mutableStateOf(state.queue) }
    var dragOriginIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.queue) {
        if (dragOriginIndex == null) {
            displayedQueue = state.queue
        }
    }

    val dragDropListState = rememberDragDropListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            displayedQueue = displayedQueue.toMutableList().apply {
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
                text = "Playback Queue (${displayedQueue.size} tracks)",
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = displayedQueue,
                key = { _, track -> track.id }
            ) { index, track ->
                val isDragging = dragDropListState.isItemDragging(index)
                val currentIndex = rememberUpdatedState(index)

                TrackListItem(
                    track = track,
                    onClick = { viewModel.playQueue(displayedQueue, index) },
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
                    dragHandleModifier = Modifier.pointerInput(track.id) {
                        detectDragGestures(
                            onDragStart = {
                                dragOriginIndex = currentIndex.value
                                dragDropListState.onDragStart(currentIndex.value)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragDropListState.onDrag(dragAmount)
                            },
                            onDragEnd = {
                                val from = dragOriginIndex
                                val to = dragDropListState.draggedIndex
                                dragDropListState.onDragEnd()
                                dragOriginIndex = null
                                if (from != null && to != null && from != to) {
                                    viewModel.moveQueueItem(from, to)
                                }
                            },
                            onDragCancel = {
                                dragDropListState.onDragCancel()
                                dragOriginIndex = null
                                displayedQueue = state.queue
                            }
                        )
                    },
                    onRemove = { viewModel.removeQueueItem(currentIndex.value) }
                )
            }
        }
    }
}