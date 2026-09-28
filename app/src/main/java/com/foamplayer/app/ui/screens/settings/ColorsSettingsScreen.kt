package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.settings.ThemeMode
import com.foamplayer.app.ui.components.FoamBottomModal
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * The accent palette, constant for the lifetime of the process. Hoisted out of
 * [ColorsSettingsScreen] so screen recompositions (toggle flips, parent
 * recompositions) never rebuild the palette or re-run the row chunking -- the
 * previous inline `listOf(...)` + `chunked(4)` allocated a fresh 16-element
 * list plus 16 boxed [Color]s and a 4-element chunk list on every pass.
 */
private val AccentPalette: List<List<Color>> = listOf(
    listOf(
        Color(0xFFFF5252), // Default Red
        Color(0xFFFF4081), // Pink
        Color(0xFFE040FB), // Purple
        Color(0xFF7C4DFF)  // Deep Purple
    ),
    listOf(
        Color(0xFF536DFE), // Indigo
        Color(0xFF448AFF), // Blue
        Color(0xFF40C4FF), // Light Blue
        Color(0xFF18FFFF)  // Cyan
    ),
    listOf(
        Color(0xFF64FFDA), // Teal
        Color(0xFF69F0AE), // Green
        Color(0xFFB2FF59), // Light Green
        Color(0xFFEEFF41)  // Lime
    ),
    listOf(
        Color(0xFFFFFF00), // Yellow
        Color(0xFFFFD740), // Amber
        Color(0xFFFFAB40), // Orange
        Color(0xFFFF6E40)  // Deep Orange
    )
)

@Composable
fun ColorsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    // Whole-settings projection: AppSettings is @Immutable, so recomposition only
    // happens when a value this screen actually renders changes. (A previous
    // narrow-projection attempt read the StateFlow's .value inside derivedStateOf,
    // which never invalidates -- StateFlow is not snapshot state -- freezing the
    // screen: the switch stopped reacting and the dropdown label never updated.)
    val settings by viewModel.settings.collectAsState()
    val isAutomaticColorEnabled = settings.automaticColorEnabled
    val accentColorInt = settings.accentColor
    val themeMode = settings.themeMode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.colors),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            item {
                BasicText(text = stringResource(R.string.theme), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                ThemeSelector(
                    currentTheme = themeMode,
                    onThemeSelected = { viewModel.setThemeMode(it) }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                BasicText(text = stringResource(R.string.system_colors), style = FoamTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    FoamSwitchItem(
                        title = stringResource(R.string.automatic_color),
                        description = "Extract accent color from album art",
                        checked = isAutomaticColorEnabled,
                        onCheckedChange = viewModel::setAutomaticColorEnabled
                    )
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            if (!isAutomaticColorEnabled) {
                item {
                    BasicText(text = stringResource(R.string.accent_palette), style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .background(FoamTheme.colors.surface)
                            .padding(FoamDimens.paddingMedium)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingStandard)) {
                            AccentPalette.forEach { rowColors ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    rowColors.forEach { color ->
                                        val isSelected = color.toArgb() == accentColorInt
                                        Swatch(
                                            color = color,
                                            isSelected = isSelected,
                                            onSelect = { viewModel.setAccentColor(color.toArgb()) }
                                        )
                                    }
                                    // Fill empty slots if row is not full
                                    if (rowColors.size < 4) {
                                        repeat(4 - rowColors.size) {
                                            Spacer(modifier = Modifier.size(64.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
                }
            }
        }
    }
}

/**
 * Dropdown selector for the app's theme mode (dark / light / follow system).
 */
@Composable
private fun ThemeSelector(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit
) {
    var showModal by remember { mutableStateOf(false) }
    val currentLabel = stringResource(
        when (currentTheme) {
            ThemeMode.FOLLOW_SYSTEM -> R.string.theme_follow_system
            ThemeMode.DARK -> R.string.theme_dark
            ThemeMode.LIGHT -> R.string.theme_light
        }
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = { showModal = true }
            )
            .padding(FoamDimens.paddingMedium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(text = currentLabel, style = FoamTheme.typography.body)
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = FoamTheme.colors.textSecondary
            )
        }
    }

    FoamBottomModal(
        visible = showModal,
        onDismissRequest = { showModal = false }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(R.string.theme),
                style = FoamTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            ThemeMode.entries.forEach { mode ->
                val isSelected = mode == currentTheme
                val label = stringResource(
                    when (mode) {
                        ThemeMode.FOLLOW_SYSTEM -> R.string.theme_follow_system
                        ThemeMode.DARK -> R.string.theme_dark
                        ThemeMode.LIGHT -> R.string.theme_light
                    }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        // Highlight for the current selection, drawn under the ripple.
                        .background(
                            if (isSelected) FoamTheme.colors.textPrimary.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = FoamTheme.colors.textPrimary),
                            onClick = {
                                onThemeSelected(mode)
                                showModal = false
                            }
                        )
                        .padding(vertical = FoamDimens.paddingMedium),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = label,
                        style = FoamTheme.typography.body.copy(
                            color = FoamTheme.colors.textPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }
        }
    }
}

/**
 * One selectable palette swatch, its own composable scope: a tap only
 * recomposes the two swatches whose selection state actually flips (this one
 * and the previously selected one), not the surrounding card / LazyColumn
 * item or the remaining 14 swatches.
 */
@Composable
private fun Swatch(
    color: Color,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(color)
            .border(
                width = if ( isSelected) 3.dp else 0.dp,
                color = if (isSelected) FoamTheme.colors.textPrimary else Color.Transparent,
                shape = RoundedCornerShape(FoamDimens.buttonRounding)
            )
            .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(FoamDimens.paddingSmall)
                    .clip(CircleShape)
                    .background(FoamTheme.colors.textPrimary)
            )
        }
    }
}