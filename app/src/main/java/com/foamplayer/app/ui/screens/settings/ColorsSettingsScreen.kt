package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
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
    // Narrow projections: reading only the two settings values this screen
    // renders through derivedStateOf keeps unrelated AppSettings emissions
    // (language, nav-bar edits, marquee mode, ...) from recomposing this
    // screen -- collectAsState of the whole settings object invalidated the
    // entire body on every unrelated settings change.
    val isAutomaticColorEnabled by remember {
        derivedStateOf { viewModel.settings.value.automaticColorEnabled }
    }
    val accentColorInt by remember {
        derivedStateOf { viewModel.settings.value.accentColor }
    }

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
                color = if (isSelected) Color.White else Color.Transparent,
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
                    .background(Color.White)
            )
        }
    }
}