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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.constants.LyricsSource
import com.foamplayer.app.ui.components.FoamNavigationButton
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.modals.LyricsProvidersModal
import com.foamplayer.app.ui.screens.queue.rememberDragDropListState
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun LyricsSettingsScreen(
    onBack: () -> Unit,
    onNavigateToLyricsManager: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var showProvidersModal by remember { mutableStateOf(false) }
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.lyrics),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FoamDimens.paddingMedium)
                .padding(bottom = LocalBottomBarClearance.current)
        ) {
            BasicText(text = stringResource(R.string.lyrics_sources), style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            LyricsSourcesList(
                order = settings.lyricsSourceOrder,
                enabledSources = settings.enabledLyricsSources,
                onOrderChange = viewModel::setLyricsSourceOrder,
                onEnabledChange = viewModel::setLyricsSourceEnabled
            )

            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))

            BasicText(text = stringResource(R.string.lyrics_providers), style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                    .background(FoamTheme.colors.surface)
            ) {
                FoamNavigationButton(
                    title = stringResource(R.string.lyrics_providers_title),
                    description = stringResource(R.string.lyrics_providers_desc),
                    onClick = { showProvidersModal = true }
                )
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))

            BasicText(text = stringResource(R.string.display), style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                    .background(FoamTheme.colors.surface)
            ) {
                FoamSwitchItem(
                    title = stringResource(R.string.blur_inactive_lines),
                    description = null,
                    checked = settings.lyricsLineBlur,
                    onCheckedChange = viewModel::setLyricsLineBlur
                )
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))

            BasicText(text = stringResource(R.string.manager), style = FoamTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                    .background(FoamTheme.colors.surface)
            ) {
                FoamNavigationButton(
                    title = stringResource(R.string.lyrics_manager),
                    description = stringResource(R.string.lyrics_manager_desc),
                    onClick = onNavigateToLyricsManager
                )
            }

            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
        }
    }

    LyricsProvidersModal(
        visible = showProvidersModal,
        onDismiss = { showProvidersModal = false }
    )
}

@Composable
private fun LyricsSourcesList(
    order: List<LyricsSource>,
    enabledSources: Set<LyricsSource>,
    onOrderChange: (List<LyricsSource>) -> Unit,
    onEnabledChange: (LyricsSource, Boolean) -> Unit
) {
    var displayedOrder by remember { mutableStateOf(order) }
    var dragOriginIndex by remember { mutableStateOf<Int?>(null) }

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
        modifier = Modifier.height(56.dp * displayedOrder.size)
    ) {
        itemsIndexed(
            items = displayedOrder,
            key = { _, source -> source.name }
        ) { index, source ->
            val isDragging = dragDropListState.isItemDragging(index)
            val currentIndex = rememberUpdatedState(index)

            Column(
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
                    }
            ) {
                LyricsSourceItem(
                    source = source,
                    isEnabled = source in enabledSources,
                    onEnabledChange = { onEnabledChange(source, it) },
                    isDragging = isDragging,
                    dragHandleModifier = Modifier.pointerInput(source.name) {
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
private fun LyricsSourceItem(
    source: LyricsSource,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isDragging) FoamTheme.colors.surfaceVariant else FoamTheme.colors.surface,
        label = "dragBackgroundColor"
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
                text = source.label,
                style = FoamTheme.typography.body.copy(
                    fontWeight = FontWeight.Medium,
                    color = if (isEnabled) FoamTheme.colors.textPrimary else FoamTheme.colors.textSecondary.copy(alpha = 0.6f)
                ),
                modifier = Modifier.weight(1f)
            )
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