package com.foamplayer.app.ui.screens.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.constants.PreferredLyricsProvider
import com.foamplayer.app.ui.screens.queue.rememberDragDropListState
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun LyricsProviderSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val lazyListState = rememberLazyListState()

    var displayedOrder by remember { mutableStateOf(settings.lyricsProviderOrder) }
    var dragOriginIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(settings.lyricsProviderOrder) {
        if (dragOriginIndex == null) {
            displayedOrder = settings.lyricsProviderOrder
        }
    }

    val dragDropListState = rememberDragDropListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            // Adjusted for the 2 header items
            val fromIdx = from - 2
            val toIdx = to - 2
            if (fromIdx in displayedOrder.indices && toIdx in displayedOrder.indices) {
                displayedOrder = displayedOrder.toMutableList().apply {
                    add(toIdx, removeAt(fromIdx))
                }
            }
        }
    )

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
            .padding(horizontal = 16.dp)
    ) {
        item {
            SettingsHeader(title = "Lyrics Providers", onBack = onBack)
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            BasicText(
                text = "Search priority",
                style = FoamTheme.typography.titleMedium
            )
            BasicText(
                text = "Enable and reorder providers to set search priority.",
                style = FoamTheme.typography.body
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        itemsIndexed(
            items = displayedOrder,
            key = { _, provider -> provider.name }
        ) { index, provider ->
            val lazyColumnIndex = index + 2
            val isDragging = dragDropListState.isItemDragging(lazyColumnIndex)
            val currentIndex = rememberUpdatedState(lazyColumnIndex)
            val isEnabled = settings.enabledLyricsProviders.contains(provider)

            val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elevation")

            Column(
                modifier = Modifier
                    .let { if (isDragging) it else it.animateItem() }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) {
                            val itemInfo = lazyListState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.index == lazyColumnIndex }
                            if (itemInfo != null) {
                                dragDropListState.draggingItemTop - itemInfo.offset
                            } else 0f
                        } else {
                            0f
                        }
                        shadowElevation = elevation.toPx()
                    }
                    .background(if (isDragging) FoamTheme.colors.surfaceVariant else FoamTheme.colors.background)
            ) {
                LyricsProviderItem(
                    provider = provider,
                    isEnabled = isEnabled,
                    onEnabledChange = { viewModel.setLyricsProviderEnabled(provider, it) },
                    dragHandleModifier = Modifier.pointerInput(provider.name) {
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
                                dragDropListState.onDragEnd()
                                dragOriginIndex = null
                                viewModel.setLyricsProviderOrder(displayedOrder)
                            },
                            onDragCancel = {
                                dragDropListState.onDragCancel()
                                dragOriginIndex = null
                                displayedOrder = settings.lyricsProviderOrder
                            }
                        )
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LyricsProviderItem(
    provider: PreferredLyricsProvider,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(FoamTheme.colors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FoamTheme.icons.current.dragHandle,
                contentDescription = "Reorder",
                modifier = dragHandleModifier.size(24.dp),
                tint = FoamTheme.colors.textSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = provider.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                    style = FoamTheme.typography.titleMedium
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = FoamTheme.accentColor,
                    checkedTrackColor = FoamTheme.accentColor.copy(alpha = 0.5f),
                    uncheckedThumbColor = FoamTheme.colors.textSecondary.copy(alpha = 1f),
                    uncheckedTrackColor = FoamTheme.colors.textSecondary.copy(alpha = 0.5f)
                )
            )
        }
    }
}
