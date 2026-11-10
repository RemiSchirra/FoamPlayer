package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.iconpacks.FoamIconPack
import com.foamplayer.app.ui.iconpacks.FoamIconPackRegistry
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamIconRole
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.rememberFoamIcons
import com.foamplayer.app.ui.theme.rememberPackRoleIcon

/** Alpha of fallback icons (roles the selected pack does not draw) in the pack grids. */
private const val FallbackIconAlpha = 0.45f

/**
 * Icon settings (Settings > User interface > Icons): on top, the navbar variant
 * controls — a Dynamic navbar icons switch and the active/inactive variant pickers
 * it reveals — then one section per bundled icon pack — name, variant pills when
 * the pack draws several styles, and the full role grid previewing the pack's
 * effective variant.
 */
@Composable
fun IconSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val iconPackId = settings.iconPackId
    // Transitions stay off until the user first toggles the switch, so the
    // section never animates on entry (the settings convention).
    var animateChanges by remember { mutableStateOf(false) }

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
                FoamCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        FoamSwitchItem(
                            title = stringResource(R.string.dynamic_nav_icons),
                            checked = settings.dynamicNavIcons,
                            onCheckedChange = {
                                animateChanges = true
                                viewModel.setDynamicNavIcons(it)
                            }
                        )
                        // The revealed pickers ride the settings expand/shrink
                        // + fade; the spacer lives inside so its height animates
                        // too (see BackgroundSettingsScreen).
                        SettingsExpandVisibility(
                            visible = settings.dynamicNavIcons,
                            animate = animateChanges
                        ) {
                            Column(
                                modifier = Modifier.padding(
                                    start = FoamDimens.spacingStandard,
                                    end = FoamDimens.spacingStandard,
                                    bottom = FoamDimens.spacingStandard
                                )
                            ) {
                                // Only the variants the selected pack actually
                                // draws -- the navbar renders its icons.
                                val availableVariants =
                                    FoamIconPackRegistry.byId(settings.iconPackId).variants
                                NavbarVariantPills(
                                    title = stringResource(R.string.nav_icon_active_variant),
                                    variants = availableVariants,
                                    selectedVariant = settings.navIconActiveVariant,
                                    onVariantSelected = viewModel::setNavIconActiveVariant
                                )
                                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                                NavbarVariantPills(
                                    title = stringResource(R.string.nav_icon_inactive_variant),
                                    variants = availableVariants,
                                    selectedVariant = settings.navIconInactiveVariant,
                                    onVariantSelected = viewModel::setNavIconInactiveVariant
                                )
                            }
                        }
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }
            items(FoamIconPackRegistry.all.size) { index ->
                val pack = FoamIconPackRegistry.all[index]
                IconPackSection(
                    pack = pack,
                    // Each pack previews and selects its own stored variant.
                    preferredStyle = settings.iconStyleFor(pack.id),
                    isSelected = pack.id == iconPackId,
                    onSelect = { viewModel.setIconPack(pack.id) },
                    onVariantSelected = { style ->
                        viewModel.setIconStyleFor(pack.id, style)
                        viewModel.setIconPack(pack.id)
                    }
                )
                if (index < FoamIconPackRegistry.all.lastIndex) {
                    Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
                }
            }
        }
    }
}

/**
 * One pack's card: name row, variant pills (only when the pack draws more than
 * one style) and the full icon grid, all on one [FoamCard]. Tapping the card
 * selects the pack; tapping a pill selects the pack and its variant.
 */
@Composable
private fun IconPackSection(
    pack: FoamIconPack,
    preferredStyle: FoamIconStyle,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onVariantSelected: (FoamIconStyle) -> Unit
) {
    val effective = pack.effectiveVariant(preferredStyle)
    FoamCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(FoamDimens.paddingMedium),
        onClick = onSelect
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PackPreviewIcon(
                        pack = pack,
                        preferredStyle = preferredStyle,
                        modifier = Modifier.size(FoamDimens.iconMedium)
                    )
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
            if (pack.variants.size > 1) {
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingTiny)
                ) {
                    pack.variants.forEach { variant ->
                        PackVariantPill(
                            variant = variant,
                            // The pill previews the card's own pack, not the
                            // app-wide selected one.
                            icon = rememberPackRoleIcon(pack, variant, FoamIconRole.PLAY),
                            isSelected = variant == effective,
                            onSelected = { onVariantSelected(variant) }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            IconRoleGrid(pack = pack, style = effective)
        }
    }
}

/**
 * One navbar variant picker: [title] over a row of [variants] pills, mirroring
 * the pack sections' pill row. Each pill previews the resolved icon set for
 * the variant (the play role), not any raw pack's.
 */
@Composable
private fun NavbarVariantPills(
    title: String,
    variants: List<FoamIconStyle>,
    selectedVariant: FoamIconStyle,
    onVariantSelected: (FoamIconStyle) -> Unit
) {
    Column {
        BasicText(
            text = title,
            style = FoamTheme.typography.body
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingTiny)
        ) {
            variants.forEach { variant ->
                PackVariantPill(
                    variant = variant,
                    icon = FoamTheme.icons.setFor(variant).play,
                    isSelected = variant == selectedVariant,
                    onSelected = { onVariantSelected(variant) }
                )
            }
        }
    }
}

/** One variant pill: the pack's own sample play icon for [variant] over its label. */
@Composable
private fun PackVariantPill(
    variant: FoamIconStyle,
    icon: ImageVector,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(
                if (isSelected) FoamTheme.accentColor
                else FoamTheme.colors.surface
            )
            .clickable(
                interactionSource = interactions,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onSelected
            )
            .padding(horizontal = FoamDimens.spacingSmall, vertical = FoamDimens.spacingTiny)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(FoamDimens.iconSmall),
                tint = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingTiny))
            BasicText(
                text = variant.label,
                style = FoamTheme.typography.caption.copy(
                    color = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            )
        }
    }
}

/**
 * The picker's leading icon: the pack's preview drawable, or the pack's own
 * play icon in its effective variant (not the app-wide selected pack's).
 */
@Composable
private fun PackPreviewIcon(
    pack: FoamIconPack,
    preferredStyle: FoamIconStyle,
    modifier: Modifier = Modifier
) {
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
            imageVector = rememberPackRoleIcon(
                pack, pack.effectiveVariant(preferredStyle), FoamIconRole.PLAY
            ),
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
    Column(modifier = Modifier.fillMaxWidth()) {
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
