package com.foamplayer.app.ui.screens.queue

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.audio.engine.RepeatMode
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.screens.player.PlayerViewModel
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.TimeUtils

/**
 * The queue subview. In repeat-all mode it lists the whole main queue — played
 * history, playing track and upcoming — with the auxiliary tracks hidden and
 * the playing row highlighted; drag-reorder maps display slots back to
 * absolute queue indices. In every other mode the list is the upcoming tracks
 * (current excluded) with auxiliary rows kept, rendered translucent.
 * Mutations go through the engine by entry id. The header row acts on the
 * whole upcoming list: reshuffle it (auxiliary slots included are swapped for
 * fresh random tracks) or clear it (the auxiliary pool refills itself right
 * away). The repeat and shuffle toggles open their mode pickers on long press:
 * a small modal scaled in from the pressed button listing every mode.
 *
 * Row gestures: tap plays the entry now; swiping right locks at the max
 * travel and on release springs back and plays the track next (auxiliary rows
 * are promoted into the main queue); swiping left flies the row off-screen and
 * removes it. The drag handle reorders with drag-and-drop.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun QueueScreen(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
    backgroundColor: Color = FoamTheme.colors.background
) {
    val state by viewModel.playbackState.collectAsState()
    val lazyListState = rememberLazyListState()

    // Repeat-all lists the whole main queue (auxiliary tracks hidden) instead
    // of just the upcoming tracks; every other mode keeps the upcoming view.
    val repeatAll = state.repeatMode == RepeatMode.ALL

    // Display items of the queue: upcoming tracks (current excluded), or the
    // whole main queue in repeat-all. In repeat-all the auxiliary tracks are
    // hidden; elsewhere they render dimmed.
    val upcomingItems = remember(state.queue, state.queueIndex, state.queueEntryIds, state.auxiliaryFlags, repeatAll) {
        if (repeatAll) {
            state.queue.mapIndexed { abs, track ->
                QueueItem(
                    // Put negative artificial id if the id is invalid
                    entryId = state.queueEntryIds.getOrNull(abs) ?: -(abs + 1).toLong(),
                    absoluteIndex = abs,
                    track = track,
                    isAuxiliary = state.auxiliaryFlags.getOrNull(abs) == true,
                    isFreshFromRefill = false,
                    isPlaying = abs == state.queueIndex
                )
            }.filterNot { it.isAuxiliary }
        } else {
            val offset = (state.queueIndex + 1).coerceAtLeast(0)
            val previousUpcomingSize = state.queue.size - offset
            state.queue.drop(offset).mapIndexed { i, track ->
                val abs = offset + i
                QueueItem(
                    entryId = state.queueEntryIds.getOrNull(abs) ?: -(abs + 1).toLong(),
                    absoluteIndex = abs,
                    track = track,
                    isAuxiliary = state.auxiliaryFlags.getOrNull(abs) == true,
                    isFreshFromRefill = i >= previousUpcomingSize,
                    isPlaying = false
                )
            }
        }
    }

    var displayedItems by remember { mutableStateOf(upcomingItems) }
    // The upcomingItems instance displayedItems was last synced from. Lets the sync below
    // run during composition (same frame as the mode change) without snapping a freshly
    // dropped, not-yet-acknowledged reorder back to the old order.
    var syncedFrom by remember { mutableStateOf(upcomingItems) }
    var dragOriginIndex by remember { mutableStateOf<Int?>(null) }
    var dragOriginEntryId by remember { mutableStateOf<Long?>(null) }
    // The list as it was when the drag started. onMove reorders displayedItems live, so
    // by drop time displayedItems[to] is the DRAGGED row itself (carrying its own
    // original absoluteIndex) and resolving the engine target through it asks the
    // engine to move the entry onto its own slot: a no-op. The pre-drag list's slot
    // `to` is the row that was displaced, whose absolute index is the final index.
    var dragOriginItems by remember { mutableStateOf<List<QueueItem>>(emptyList()) }

    val latestUpcomingItems by rememberUpdatedState(upcomingItems)

    // Synced during composition rather than in a LaunchedEffect: an effect lands one frame
    // after the mode flips, so the rows would render stale (old mode's list) for a frame
    // before the real list arrives, which showed up as flicker and broken item animations.
    // Skipped mid-drag so the live reorder isn't clobbered; it syncs once the drag ends.
    if (syncedFrom !== upcomingItems && dragOriginIndex == null) {
        syncedFrom = upcomingItems
        displayedItems = upcomingItems
    }

    // Leaving repeat-all drops the history and playing rows, which are usually the list's
    // scroll anchor (the list opens on the playing row). With the anchor key gone, the
    // LazyColumn falls back to the old index and jumps. Reset to the head of the upcoming
    // list in the same frame as the change (SideEffect runs before measure, unlike a
    // LaunchedEffect, and requestScrollToItem doesn't animate).
    val prevRepeatAll = remember { mutableStateOf(repeatAll) }
    SideEffect {
        if (prevRepeatAll.value && !repeatAll) {
            lazyListState.requestScrollToItem(0)
        }
        prevRepeatAll.value = repeatAll
    }

    val dragDropListState = rememberDragDropListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            displayedItems = displayedItems.toMutableList().apply {
                add(to, removeAt(from))
            }
        }
    )

    // Repeat-all opens on the playing row rather than the queue's head, so the
    // full-queue list starts where playback is instead of in the played history.
    // Once per repeat-all activation: later queue changes never yank the scroll.
    var scrolledToPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(repeatAll) {
        if (!repeatAll) {
            scrolledToPlaying = false
        } else if (!scrolledToPlaying) {
            scrolledToPlaying = true
            val playingIndex = upcomingItems.indexOfFirst { it.isPlaying }
            if (playingIndex > 0) {
                lazyListState.scrollToItem(index = playingIndex)
            }
        }
    }

    // The header's mode-picker modal: which toggle opened it plus the pressed button's
    // center (in the window's root coordinates) so the modal scales in from the button.
    var modeModalRequest by remember { mutableStateOf<QueueModeModalRequest?>(null) }

    // Options of the open mode picker: repeat (off/all/one) or shuffle (off/on). Built
    // here so the overlay stays presentation-only; a handful of rows, rebuilt per
    // recomposition is fine.
    val modeModalOptions = when (modeModalRequest?.type) {
        QueueModeModalType.REPEAT -> listOf(
            QueueModeOption(
                icon = FoamTheme.icons.current.repeat,
                label = stringResource(R.string.repeat_off),
                isSelected = state.repeatMode == RepeatMode.NONE,
                isDimmed = true,
                onSelect = { viewModel.setRepeatMode(RepeatMode.NONE) }
            ),
            QueueModeOption(
                icon = FoamTheme.icons.current.repeat,
                label = stringResource(R.string.repeat_all),
                isSelected = state.repeatMode == RepeatMode.ALL,
                onSelect = { viewModel.setRepeatMode(RepeatMode.ALL) }
            ),
            QueueModeOption(
                icon = FoamTheme.icons.current.repeatOne,
                label = stringResource(R.string.repeat_one),
                isSelected = state.repeatMode == RepeatMode.ONE,
                onSelect = { viewModel.setRepeatMode(RepeatMode.ONE) }
            )
        )
        QueueModeModalType.SHUFFLE -> listOf(
            QueueModeOption(
                icon = FoamTheme.icons.current.shuffle,
                label = stringResource(R.string.shuffle_off),
                isSelected = !state.isShuffle,
                isDimmed = true,
                onSelect = { viewModel.setShuffle(false) }
            ),
            QueueModeOption(
                icon = FoamTheme.icons.current.shuffle,
                label = stringResource(R.string.shuffle_on),
                isSelected = state.isShuffle,
                onSelect = { viewModel.setShuffle(true) }
            )
        )
        null -> emptyList()
    }

    // The shuffle modal's trailing action (after a separator): reshuffles the
    // upcoming list once. The repeat modal keeps no trailing action.
    val modeModalAction = if (modeModalRequest?.type == QueueModeModalType.SHUFFLE) {
        QueueModeAction(
            icon = Icons.Rounded.Casino,
            label = stringResource(R.string.queue_randomize),
            onSelect = { viewModel.shuffleUpcoming() }
        )
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 16.dp)
    ) {
        QueueHeaderRow(
            tracks = displayedItems,
            repeatMode = state.repeatMode,
            isShuffle = state.isShuffle,
            onToggleShuffle = viewModel::toggleShuffle,
            onToggleRepeat = viewModel::toggleRepeat,
            onOpenModeModal = { type, anchor ->
                modeModalRequest = QueueModeModalRequest(type, anchor)
            },
            onRandomize = viewModel::shuffleUpcoming,
            onClear = viewModel::clearUpcoming,
            modifier = Modifier.fillMaxWidth()
        )

        // The top fade is kept short and its padding minimal so the first row sits close
        // to the header; the bottom keeps the longer fade (no header above it to crowd).
        val topFadeHeight = 8.dp
        val bottomFadeHeight = 24.dp

        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(top = 2.dp, bottom = bottomFadeHeight),
            modifier = Modifier
                .weight(1f)
                .fadingEdges(topEdgeHeight = topFadeHeight, bottomEdgeHeight = bottomFadeHeight)
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
                        // The playing row is already active; re-tapping it would
                        // restart it and reorder the queue, so it does nothing.
                        if (!item.isPlaying) {
                            viewModel.playQueueEntry(item.entryId)
                        }
                    },
                    active = item.isPlaying,
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
                                dragOriginItems = displayedItems
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
                                    // Display slots map to engine upcoming positions, which
                                    // count from the track after the current one; in
                                    // repeat-all, past/current rows land before it and go
                                    // negative.
                                    val targetUpcoming = dragOriginItems.getOrNull(to)
                                        ?.absoluteIndex?.let { abs ->
                                            abs - (state.queueIndex + 1).coerceAtLeast(0)
                                        } ?: to
                                    viewModel.moveQueueEntry(originEntryId, targetUpcoming)
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
                    onRemove = if (!item.isPlaying) {
                        { viewModel.removeQueueEntry(item.entryId) }
                    } else {
                        // Removing the playing entry would pull the current item
                        // out from under the player; the row keeps no swipe
                        // actions at all.
                        null
                    },
                    // Swipe-right puts the track in the next position. The engine
                    // handles upcoming entries (auxiliary ones join the main queue)
                    // and played-history entries (repeat-all's full-queue view)
                    // alike. Only the playing row is withheld, rather than ticking
                    // and doing nothing.
                    onPlayNext = if (item.isPlaying || item.absoluteIndex == state.queueIndex) {
                        null
                    } else {
                        { viewModel.promoteQueueEntry(item.entryId) }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Rendered in its own window, so composition placement doesn't matter. The
        // dialog window is full-screen and edge-to-edge like the activity, so the
        // activity view's size IS the dialog root's size: the modal's resting
        // position (and with it the scale-in origin) is derived from it.
        QueueModeModalOverlay(
            request = modeModalRequest,
            options = modeModalOptions,
            action = modeModalAction,
            rootSize = IntSize(LocalView.current.width, LocalView.current.height),
            onDismiss = { modeModalRequest = null }
        )
    }
}

/**
 * Header row of the queue subview: "Queue" with the main queue's stats under it
 * (track count and total duration on one line, auxiliary entries excluded) on
 * the left; on the right the repeat and shuffle mode toggles — long press opens
 * their mode pickers, reported with the pressed button's center — the randomize
 * (dice) button that reshuffles the upcoming list once, and the clear button.
 */
@Composable
private fun QueueHeaderRow(
    tracks: List<QueueItem>,
    repeatMode: RepeatMode,
    isShuffle: Boolean,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onOpenModeModal: (QueueModeModalType, Offset) -> Unit,
    onRandomize: () -> Unit, // Unused but can be used to randomize the queue
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val counted = tracks.filterNot { it.isAuxiliary }
    val trackCount = counted.size
    val durationMs = counted.sumOf { it.track.duration }
    val durationText = remember(durationMs) { TimeUtils.formatDurationLong(durationMs) }

    Row(
        modifier = modifier.padding(top = FoamDimens.spacingTiny, bottom = FoamDimens.spacingTiny),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = stringResource(R.string.queue),
                style = FoamTheme.typography.titleMedium
            )
            BasicText(
                text = stringResource(R.string.track_count_duration, trackCount, durationText),
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                maxLines = 1
            )
        }

        QueueHeaderButton(
            icon = when (repeatMode) {
                RepeatMode.ONE -> FoamTheme.icons.current.repeatOne
                else -> FoamTheme.icons.current.repeat
            },
            contentDescription = stringResource(R.string.action_repeat),
            isDimmed = repeatMode == RepeatMode.NONE,
            onClick = onToggleRepeat,
            onLongClick = { anchor -> onOpenModeModal(QueueModeModalType.REPEAT, anchor) }
        )
        QueueHeaderButton(
            icon = FoamTheme.icons.current.shuffle,
            contentDescription = "Shuffle",
            isDimmed = !isShuffle,
            onClick = onToggleShuffle,
            onLongClick = { anchor -> onOpenModeModal(QueueModeModalType.SHUFFLE, anchor) }
        )
        QueueHeaderButton(
            icon = FoamTheme.icons.current.close,
            contentDescription = stringResource(R.string.queue_clear),
            onClick = onClear
        )
    }
}

/**
 * Small circular icon button with a bounded ripple; [isDimmed] marks a toggle that is off.
 * [onLongClick] (the header's mode pickers) fires with the button's center in the
 * window's root coordinates so the modal can scale in from the pressed button.
 */
@Composable
private fun QueueHeaderButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDimmed: Boolean = false,
    onLongClick: ((Offset) -> Unit)? = null
) {
    val buttonInteractions = remember { MutableInteractionSource() }
    val hapticFeedback = LocalHapticFeedback.current
    // Center of the button in the window's root coordinates; read when the long press
    // fires (the mode picker's scale-in origin).
    var buttonCenter by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .onGloballyPositioned { buttonCenter = it.boundsInRoot().center }
            .combinedClickable(
                interactionSource = buttonInteractions,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick,
                onLongClick = onLongClick?.let { handler ->
                    {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        handler(buttonCenter)
                    }
                }
            )
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(24.dp).graphicsLayer { alpha = if (isDimmed) 0.4f else 1f }
        )
    }
}

/**
 * Fades the top [topEdgeHeight] and bottom [bottomEdgeHeight] of this composable's own content
 * to transparent, instead of letting a scrolling container hard-clip it at the viewport edge.
 *
 * Uses an offscreen compositing layer (alpha < 1f forces one) so the gradient can be applied
 * with [BlendMode.DstIn] against the content's actual alpha channel — this fades whatever is
 * being drawn (list items, backgrounds, images, etc.) rather than painting a solid color on
 * top, so it works correctly no matter what sits behind the container.
 */
private fun Modifier.fadingEdges(topEdgeHeight: Dp, bottomEdgeHeight: Dp): Modifier = this
    .graphicsLayer { alpha = 0.99f }
    .drawWithContent {
        drawContent()
        val topPx = topEdgeHeight.toPx()
        val bottomPx = bottomEdgeHeight.toPx()
        if (size.height <= 0f || (topPx <= 0f && bottomPx <= 0f)) return@drawWithContent
        val topFraction = (topPx / size.height).coerceIn(0f, 0.5f)
        val bottomFraction = (1f - bottomPx / size.height).coerceIn(0.5f, 1f)
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