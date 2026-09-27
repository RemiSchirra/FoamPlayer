package com.foamplayer.app.ui.screens.queue

import androidx.compose.foundation.background
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * The "Up Next" screen: upcoming tracks (current excluded) with drag-reorder,
 * swipe-to-remove and tap-to-play. Mutations go through the engine by entry id.
 */
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
            .padding(horizontal = 16.dp)
    ) {
        if (showTitle) {
            Spacer(modifier = Modifier.height(16.dp))
            BasicText(
                text = "Up Next (${displayedItems.size} tracks)",
                style = FoamTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        val fadeHeight = 24.dp

        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(vertical = fadeHeight),
            modifier = Modifier
                .weight(1f)
                .fadingEdges(edgeHeight = fadeHeight)
        ) {
            itemsIndexed(
                items = displayedItems,
                key = { _, item -> item.entryId }
            ) { index, item ->
                // derivedStateOf: only the rows whose dragging state flips invalidate.
                val isDragging by remember(index) {
                    derivedStateOf { dragDropListState.isItemDragging(index) }
                }
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

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Fades the top and bottom [edgeHeight] of this composable's own content to transparent,
 * instead of letting a scrolling container hard-clip it at the viewport edge.
 *
 * Uses an offscreen compositing layer (alpha < 1f forces one) so the gradient can be applied
 * with [BlendMode.DstIn] against the content's actual alpha channel — this fades whatever is
 * being drawn (list items, backgrounds, images, etc.) rather than painting a solid color on
 * top, so it works correctly no matter what sits behind the container.
 */
private fun Modifier.fadingEdges(edgeHeight: Dp): Modifier = this
    .graphicsLayer { alpha = 0.99f }
    .drawWithContent {
        drawContent()
        val edgeHeightPx = edgeHeight.toPx()
        if (edgeHeightPx <= 0f || size.height <= 0f) return@drawWithContent
        val topFraction = (edgeHeightPx / size.height).coerceIn(0f, 0.5f)
        val bottomFraction = (1f - edgeHeightPx / size.height).coerceIn(0.5f, 1f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                topFraction to Color.Black,
                bottomFraction to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }