package com.foamplayer.app.ui.screens.settings

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.constants.HomeSection
import com.foamplayer.app.ui.components.FoamSwitch
import com.foamplayer.app.ui.screens.queue.rememberDragDropListState
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * Settings > UI > Home: the Home page's sections in a drag-to-reorder list,
 * each with a switch to show or hide it (the same pattern as the lyrics
 * sources list). At least one section stays enabled — the sanitized settings
 * restore "Random tracks" if everything is switched off.
 */
@Composable
fun HomeSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.home),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium)
                .padding(bottom = LocalBottomBarClearance.current)
        ) {
            BasicText(text = stringResource(R.string.home_settings_subtitle), style = FoamTheme.typography.body)
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            HomeSectionsList(
                order = settings.homeSectionOrder,
                enabledSections = settings.enabledHomeSections,
                onOrderChange = viewModel::setHomeSectionOrder,
                onEnabledChange = viewModel::setHomeSectionEnabled
            )
        }
    }
}

@Composable
private fun HomeSectionsList(
    order: List<HomeSection>,
    enabledSections: Set<HomeSection>,
    onOrderChange: (List<HomeSection>) -> Unit,
    onEnabledChange: (HomeSection, Boolean) -> Unit
) {
    var displayedOrder by remember { mutableStateOf(order) }
    var dragOriginIndex by remember { mutableStateOf<Int?>(null) }
    var hasDragged by remember { mutableStateOf(false) }

    LaunchedEffect(order) {
        if (dragOriginIndex == null) {
            displayedOrder = order
        }
    }

    val lazyListState = rememberLazyListState()
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

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(
            items = displayedOrder,
            key = { _, section -> section.name }
        ) { index, section ->
            val isDragging = dragDropListState.isItemDragging(index)
            val currentIndex = rememberUpdatedState(index)

            Column(
                modifier = Modifier
                    .let { if (isDragging || !hasDragged) it else it.animateItem() }
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
                    }
            ) {
                HomeSectionItem(
                    section = section,
                    isEnabled = section in enabledSections,
                    onEnabledChange = { onEnabledChange(section, it) },
                    isDragging = isDragging,
                    dragHandleModifier = Modifier.pointerInput(section.name) {
                        detectDragGestures(
                            onDragStart = {
                                hasDragged = true
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
                                onOrderChange(displayedOrder)
                            },
                            onDragCancel = {
                                dragDropListState.onDragCancel()
                                dragOriginIndex = null
                                displayedOrder = order
                            }
                        )
                    }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            }
        }
    }
}

@Composable
private fun HomeSectionItem(
    section: HomeSection,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isDragging) FoamTheme.colors.surfaceVariant else FoamTheme.colors.surface,
        label = "homeSectionDragBackgroundColor"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .background(backgroundColor)
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
            BasicText(
                text = stringResource(section.labelRes),
                style = FoamTheme.typography.body.copy(
                    fontWeight = FontWeight.Medium,
                    color = if (isEnabled) FoamTheme.colors.textPrimary else FoamTheme.colors.textSecondary.copy(alpha = 0.6f)
                ),
                modifier = Modifier.weight(1f)
            )
            FoamSwitch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}
