package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.settings.FoamIconStyle
import com.foamplayer.app.ui.components.FoamCard
import com.foamplayer.app.ui.iconpacks.FoamIconPack
import com.foamplayer.app.ui.iconpacks.FoamIconPackRegistry
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamIconRole
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.rememberFoamIcons

/** Alpha of fallback icons (roles the selected pack does not draw) in the pack grids. */
private const val FallbackIconAlpha = 0.45f

/**
 * Icon settings (Settings > User interface > Icons): the icon style chips and
 * one section per bundled icon pack, each previewing every semantic role in
 * the currently selected style.
 */
@Composable
fun IconSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val iconStyle = settings.iconStyle
    val iconPackId = settings.iconPackId

    SettingsPage(
        title = stringResource(R.string.icon_pack),
        onBack = onBack
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(
                top = SettingsHeaderZoneHeight,
                bottom = LocalBottomBarClearance.current
            )
        ) {
            item {
                IconStyleChips(
                    selectedStyle = iconStyle,
                    onStyleSelected = { viewModel.setIconStyle(it) }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            items(FoamIconPackRegistry.all.size) { index ->
                val pack = FoamIconPackRegistry.all[index]
                IconPackSection(
                    pack = pack,
                    style = iconStyle,
                    isSelected = pack.id == iconPackId,
                    onSelect = { viewModel.setIconPack(pack.id) }
                )
                if (index < FoamIconPackRegistry.all.lastIndex) {
                    Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
                }
            }
        }
    }
}

/** The icon style chips (Solid / Outline / Flat), previewing each style's play icon. */
@Composable
private fun IconStyleChips(
    selectedStyle: FoamIconStyle,
    onStyleSelected: (FoamIconStyle) -> Unit
) {
    Column {
        BasicText(text = stringResource(R.string.icon_style), style = FoamTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
        ) {
            FoamIconStyle.entries.forEach { style ->
                val isSelected = style == selectedStyle
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(
                            if (isSelected) FoamTheme.accentColor
                            else FoamTheme.colors.surface
                        )
                        .clickable { onStyleSelected(style) }
                        .padding(vertical = FoamDimens.paddingMedium),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = FoamTheme.icons.setFor(style).play,
                            contentDescription = null,
                            modifier = Modifier.size(FoamDimens.iconSmall),
                            tint = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
                        BasicText(
                            text = style.label,
                            style = FoamTheme.typography.body.copy(
                                color = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }
    }
}

/** One pack's picker card and its full icon grid, rendered in [style]. */
@Composable
private fun IconPackSection(
    pack: FoamIconPack,
    style: FoamIconStyle,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Column {
        FoamCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(FoamDimens.paddingMedium),
            onClick = onSelect
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PackPreviewIcon(pack = pack, modifier = Modifier.size(FoamDimens.iconMedium))
                    Spacer(modifier = Modifier.width(FoamDimens.spacingMedium))
                    BasicText(
                        text = pack.label,
                        style = FoamTheme.typography.titleMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = FoamTheme.accentColor,
                        modifier = Modifier.size(FoamDimens.iconSmall)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
        FoamCard(modifier = Modifier.fillMaxWidth()) {
            IconRoleGrid(pack = pack, style = style)
        }
    }
}

/** The picker's leading icon: the pack's preview drawable or the default play icon. */
@Composable
private fun PackPreviewIcon(pack: FoamIconPack, modifier: Modifier = Modifier) {
    val preview: Painter? = pack.previewRes?.let { painterResource(it) }
    if (preview != null) {
        Icon(
            painter = preview,
            contentDescription = null,
            tint = FoamTheme.colors.textPrimary,
            modifier = modifier
        )
    } else {
        Icon(
            imageVector = FoamTheme.icons.current.play,
            contentDescription = null,
            tint = FoamTheme.colors.textPrimary,
            modifier = modifier
        )
    }
}

/**
 * Every [FoamIconRole] of [pack] resolved in [style], chunked into rows of 6
 * (Row + weight cells). Partial packs keep the default icon for the roles
 * they do not draw, so the grid always fills all 27 roles; those fallback
 * roles render semi-transparent to show what the pack actually covers.
 */
@Composable
private fun IconRoleGrid(pack: FoamIconPack, style: FoamIconStyle) {
    val icons = rememberFoamIcons(pack, style).current
    val rows = FoamIconRole.entries.chunked(6)
    Column(modifier = Modifier.fillMaxWidth().padding(FoamDimens.paddingMedium)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingTiny)
            ) {
                row.forEach { role ->
                    IconRoleCell(
                        label = role.label,
                        icon = icons[role],
                        // A role renders as a fallback when the pack does not
                        // draw it -- except in the default pack, whose icons
                        // are the defaults themselves, never fallbacks.
                        isFallback = (pack.resFor(style, role) == null) &&
                            (pack.id != FoamIconPackRegistry.DEFAULT_ID),
                        modifier = Modifier.weight(1f)
                    )
                }
                // Pad the last row so the cells keep their 1/6 width.
                repeat(6 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
        }
    }
}

/** One grid cell: the role's icon over a tiny role-name caption. */
@Composable
private fun IconRoleCell(
    label: String,
    icon: ImageVector,
    isFallback: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isFallback) FoamTheme.colors.textPrimary.copy(alpha = FallbackIconAlpha)
            else FoamTheme.colors.textPrimary,
            modifier = Modifier.size(FoamDimens.iconMedium)
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
