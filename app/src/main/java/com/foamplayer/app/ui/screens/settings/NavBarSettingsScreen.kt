package com.foamplayer.app.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.constants.NavBarItem
import com.foamplayer.app.constants.NavigationBarMode
import com.foamplayer.app.ui.components.FoamEnumItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.screens.queue.DragDropListState
import com.foamplayer.app.ui.screens.queue.rememberDragDropListState
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme

/** Which of the two bars a drag gesture belongs to. */
private enum class NavBarSection { PRIMARY, SECONDARY }

/*
 * LazyColumn layout (kept in sync with the items built below):
 *   0      – mode enum item
 *   1      – transparency item
 *   2      – "Main bar" section header
 *   3..8   – main bar items (5 screen items + the MORE toggle marker)
 *   9      – "Second bar" section header
 *   10..15 – second bar items (6: the screen items + Stats)
 *   16     – bottom spacer
 */
private const val PRIMARY_SECTION_START = 3
private const val SECONDARY_SECTION_START = 10
private val PRIMARY_SECTION_RANGE = PRIMARY_SECTION_START until SECONDARY_SECTION_START - 1
private val SECONDARY_SECTION_RANGE = SECONDARY_SECTION_START until SECONDARY_SECTION_START + 6

@Composable
fun NavBarSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val lazyListState = rememberLazyListState()

    var displayedPrimaryOrder by remember { mutableStateOf(settings.navBarPrimaryOrder) }
    var displayedSecondaryOrder by remember { mutableStateOf(settings.navBarSecondaryOrder) }
    var draggedSection by remember { mutableStateOf<NavBarSection?>(null) }

    LaunchedEffect(settings.navBarPrimaryOrder) {
        if (draggedSection != NavBarSection.PRIMARY) {
            displayedPrimaryOrder = settings.navBarPrimaryOrder
        }
    }

    LaunchedEffect(settings.navBarSecondaryOrder) {
        if (draggedSection != NavBarSection.SECONDARY) {
            displayedSecondaryOrder = settings.navBarSecondaryOrder
        }
    }

    val dragDropListState = rememberDragDropListState(
        lazyListState = lazyListState,
        canMove = { from, to ->
            (from in PRIMARY_SECTION_RANGE && to in PRIMARY_SECTION_RANGE) ||
                    (from in SECONDARY_SECTION_RANGE && to in SECONDARY_SECTION_RANGE)
        },
        onMove = { from, to ->
            when {
                from in PRIMARY_SECTION_RANGE && to in PRIMARY_SECTION_RANGE -> {
                    val fromIdx = from - PRIMARY_SECTION_START
                    val toIdx = to - PRIMARY_SECTION_START
                    displayedPrimaryOrder = displayedPrimaryOrder.toMutableList().apply {
                        add(toIdx, removeAt(fromIdx))
                    }
                }
                from in SECONDARY_SECTION_RANGE && to in SECONDARY_SECTION_RANGE -> {
                    val fromIdx = from - SECONDARY_SECTION_START
                    val toIdx = to - SECONDARY_SECTION_START
                    displayedSecondaryOrder = displayedSecondaryOrder.toMutableList().apply {
                        add(toIdx, removeAt(fromIdx))
                    }
                }
            }
        }
    )

    fun persistDraggedOrder() {
        when (draggedSection) {
            NavBarSection.PRIMARY -> viewModel.setNavBarPrimaryOrder(displayedPrimaryOrder)
            NavBarSection.SECONDARY -> viewModel.setNavBarSecondaryOrder(displayedSecondaryOrder)
            null -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.navigation_bar),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            item {
                FoamEnumItem(
                    title = stringResource(R.string.navbar_mode),
                    description = stringResource(R.string.navbar_mode_desc),
                    options = NavigationBarMode.entries.toTypedArray(),
                    selectedOption = settings.navigationBarMode,
                    onOptionSelected = { viewModel.setNavigationBarMode(it) },
                    getLabel = { mode ->
                        when (mode) {
                            NavigationBarMode.DOCKED -> stringResource(R.string.navbar_mode_docked)
                            NavigationBarMode.FLOATING -> stringResource(R.string.navbar_mode_floating)
                            NavigationBarMode.ATTACHED -> stringResource(R.string.navbar_mode_attached)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                Column(modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)) {
                    BasicText(
                        text = stringResource(R.string.transparency),
                        style = FoamTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                    ) {
                        FoamSwitchItem(
                            title = stringResource(R.string.transparent_when_player_open),
                            description = stringResource(R.string.transparent_when_player_open_desc),
                            checked = settings.navBarTransparentWhenPlayerOpen,
                            onCheckedChange = { viewModel.setNavBarTransparentWhenPlayerOpen(it) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item(key = "header_primary") {
                NavBarSectionHeader(
                    title = stringResource(R.string.navbar_main_bar),
                    description = stringResource(R.string.navbar_main_bar_desc)
                )
            }

            itemsIndexed(
                items = displayedPrimaryOrder,
                key = { _, item -> "primary_${item.name}" }
            ) { index, item ->
                NavBarDraggableItem(
                    item = item,
                    section = NavBarSection.PRIMARY,
                    lazyColumnIndex = index + PRIMARY_SECTION_START,
                    // The MORE marker has no switch: it is visible exactly while the
                    // second bar is non-empty, so mirror that state here.
                    isEnabled = if (item == NavBarItem.MORE) {
                        settings.secondaryNavBarItems.isNotEmpty()
                    } else {
                        item in settings.navBarPrimaryEnabled
                    },
                    onEnabledChange = { viewModel.setNavBarPrimaryItemEnabled(item, it) },
                    lazyListState = lazyListState,
                    dragDropListState = dragDropListState,
                    onDragStart = { draggedSection = it },
                    onDragEnd = {
                        dragDropListState.onDragEnd()
                        persistDraggedOrder()
                        draggedSection = null
                    },
                    onDragCancel = {
                        dragDropListState.onDragCancel()
                        displayedPrimaryOrder = settings.navBarPrimaryOrder
                        displayedSecondaryOrder = settings.navBarSecondaryOrder
                        draggedSection = null
                    }
                )
            }

            item(key = "header_secondary") {
                NavBarSectionHeader(
                    title = stringResource(R.string.navbar_second_bar),
                    description = stringResource(R.string.navbar_second_bar_desc)
                )
            }

            itemsIndexed(
                items = displayedSecondaryOrder,
                key = { _, item -> "secondary_${item.name}" }
            ) { index, item ->
                NavBarDraggableItem(
                    item = item,
                    section = NavBarSection.SECONDARY,
                    lazyColumnIndex = index + SECONDARY_SECTION_START,
                    isEnabled = item in settings.navBarSecondaryEnabled,
                    onEnabledChange = { viewModel.setNavBarSecondaryItemEnabled(item, it) },
                    lazyListState = lazyListState,
                    dragDropListState = dragDropListState,
                    onDragStart = { draggedSection = it },
                    onDragEnd = {
                        dragDropListState.onDragEnd()
                        persistDraggedOrder()
                        draggedSection = null
                    },
                    onDragCancel = {
                        dragDropListState.onDragCancel()
                        displayedPrimaryOrder = settings.navBarPrimaryOrder
                        displayedSecondaryOrder = settings.navBarSecondaryOrder
                        draggedSection = null
                    }
                )
            }

            item {
                Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            }
        }
    }
}

@Composable
private fun NavBarSectionHeader(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = FoamDimens.paddingMedium)) {
        BasicText(
            text = title,
            style = FoamTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
        BasicText(
            text = description,
            style = FoamTheme.typography.body
        )
    }
    Spacer(modifier = Modifier.height(FoamDimens.spacingStandard))
}

/**
 * One reorderable entry of a nav-bar settings list. Must be called from inside a
 * [LazyColumn] item scope ([LazyItemScope]) so the item can animate while reordering.
 */
@Composable
private fun LazyItemScope.NavBarDraggableItem(
    item: NavBarItem,
    section: NavBarSection,
    lazyColumnIndex: Int,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    lazyListState: LazyListState,
    dragDropListState: DragDropListState,
    onDragStart: (NavBarSection) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val isDragging = dragDropListState.isItemDragging(lazyColumnIndex)
    val currentIndex = rememberUpdatedState(lazyColumnIndex)
    val dragKey = "${section.name}_${item.name}"

    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "navBarItemElevation")

    val dragHandleModifier = Modifier.pointerInput(dragKey) {
        detectDragGestures(
            onDragStart = {
                onDragStart(section)
                dragDropListState.onDragStart(currentIndex.value)
            },
            onDrag = { change, dragAmount ->
                change.consume()
                dragDropListState.onDrag(dragAmount)
            },
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel
        )
    }

    Column(
        modifier = Modifier
            .padding(horizontal = FoamDimens.paddingMedium)
            .let { if (isDragging) it else it.animateItem() }
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (isDragging) {
                    val itemInfo = lazyListState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == currentIndex.value }
                    if (itemInfo != null) {
                        dragDropListState.draggingItemTop - itemInfo.offset
                    } else 0f
                } else {
                    0f
                }
                shadowElevation = elevation.toPx()
            }
    ) {
        NavBarSettingsItem(
            item = item,
            isEnabled = isEnabled,
            onEnabledChange = onEnabledChange,
            isDragging = isDragging,
            dragHandleModifier = dragHandleModifier
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
    }
}

@Composable
private fun NavBarSettingsItem(
    item: NavBarItem,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isDragging: Boolean,
    dragHandleModifier: Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isDragging) FoamTheme.colors.surfaceVariant else FoamTheme.colors.surface,
        label = "navBarItemDragBackground"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .background(backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Rows without a switch (MORE) otherwise collapse to the icon
                // height; keep every row as tall as the switch's touch target.
                .heightIn(min = 48.dp)
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
                Icon(
                    imageVector = when (item) {
                        NavBarItem.HOME -> FoamTheme.icons.current.home
                        NavBarItem.PLAYLISTS -> FoamTheme.icons.current.playlists
                        NavBarItem.SEARCH -> FoamTheme.icons.current.search
                        NavBarItem.STATS -> FoamTheme.icons.current.stats
                        NavBarItem.EQUALIZER -> FoamTheme.icons.current.equalizer
                        NavBarItem.SETTINGS -> FoamTheme.icons.current.settings
                        NavBarItem.MORE -> FoamTheme.icons.current.more
                    },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isEnabled) FoamTheme.accentColor else FoamTheme.colors.textSecondary.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))
            BasicText(
                text = navBarItemLabel(item),
                style = FoamTheme.typography.body.copy(
                    fontWeight = FontWeight.Medium,
                    color = if (isEnabled) FoamTheme.colors.textPrimary else FoamTheme.colors.textSecondary.copy(alpha = 0.6f)
                ),
                modifier = Modifier.weight(1f)
            )
            // The MORE marker has no switch: it appears automatically while the
            // second bar is non-empty and disappears when it is empty.
            if (item != NavBarItem.MORE) {
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
}

@Composable
private fun navBarItemLabel(item: NavBarItem): String = when (item) {
    NavBarItem.HOME -> stringResource(R.string.home)
    NavBarItem.PLAYLISTS -> stringResource(R.string.playlists)
    NavBarItem.SEARCH -> stringResource(R.string.search)
    NavBarItem.STATS -> stringResource(R.string.statistics)
    NavBarItem.EQUALIZER -> stringResource(R.string.equalizer_dsp)
    NavBarItem.SETTINGS -> stringResource(R.string.settings)
    NavBarItem.MORE -> stringResource(R.string.navbar_item_more)
}
