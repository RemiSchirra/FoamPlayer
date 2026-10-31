package com.foamplayer.app.ui.modals

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.constants.PreferredLyricsProvider
import com.foamplayer.app.ui.components.FoamSwitch
import com.foamplayer.app.ui.screens.queue.rememberDragDropListState
import com.foamplayer.app.ui.screens.settings.SettingsViewModel
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurfaceBlend

/**
 * Bottom sheet for the lyrics providers: each row shows the provider's search
 * priority (top = first), an enable/disable switch, and a drag handle for
 * reordering. Changes commit to [com.foamplayer.app.ui.screens.settings.SettingsViewModel] (and are persisted) as the
 * gestures complete.
 */
@Composable
fun LyricsProvidersModal(
    visible: Boolean,
    onDismiss: () -> Unit,
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
            if (from in displayedOrder.indices && to in displayedOrder.indices) {
                displayedOrder = displayedOrder.toMutableList().apply {
                    add(to, removeAt(from))
                }
            }
        }
    )

    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        // FoamBottomModal hosts its content in a Box, so everything here must
        // live in a single Column to stack vertically.
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(R.string.lyrics_providers),
                style = FoamTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
            BasicText(
                text = stringResource(R.string.drag_to_reorder_desc),
                style = FoamTheme.typography.body
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingStandard))

            // The sheet auto-fits its content, so the list grows with it: sized to
            // its natural height (row ~48dp + 4dp spacer per item), capped at the
            // screen height minus an allowance for the sheet chrome (system bars,
            // grab bar, paddings, header) so the sheet expands up to near
            // full-screen instead of clipping the drag area. A definite height is
            // still required: the sheet's internal scroll hands this Column
            // unbounded constraints, which a LazyColumn can't measure against --
            // and a fixed viewport also keeps the sheet from resizing on every
            // swap mid-drag. Overflow scrolls (with drag autoscroll) as usual.
            val listHeight = minOf(
                (48.dp) * displayedOrder.size,
                (LocalConfiguration.current.screenHeightDp.dp - 200.dp).coerceAtLeast(320.dp)
            )
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.height(listHeight)
            ) {
                itemsIndexed(
                    items = displayedOrder,
                    key = { _, provider -> provider.name }
                ) { index, provider ->
                    val lazyColumnIndex = index
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
                    ) {
                        LyricsProviderItem(
                            provider = provider,
                            isEnabled = isEnabled,
                            onEnabledChange = { viewModel.setLyricsProviderEnabled(provider, it) },
                            isDragging = isDragging,
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
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsProviderItem(
    provider: PreferredLyricsProvider,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier
) {
    // Drag feedback blends surface -> surfaceVariant as a float; the blend and
    // its accent tint are resolved in the draw pass (see [foamSurfaceBlend]),
    // so neither the drag animation nor an animating accent recomposes the row.
    val dragBlend by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        label = "dragBackgroundBlend"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .foamSurfaceBlend(dragBlend)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FoamDimens.spacingMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FoamTheme.icons.current.dragHandle,
                contentDescription = stringResource(R.string.reorder),
                modifier = dragHandleModifier.size(20.dp),
                tint = if (isEnabled) FoamTheme.colors.textSecondary else FoamTheme.colors.textSecondary.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = provider.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                    style = FoamTheme.typography.body.copy(
                        fontWeight = FontWeight.Medium,
                        color = if (isEnabled) FoamTheme.colors.textPrimary else FoamTheme.colors.textSecondary.copy(alpha = 0.6f)
                    )
                )
            }
            FoamSwitch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}
