package com.foamplayer.app.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.ui.components.CollectionTopBarIcon
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * An extra action row shown at the top of [DensityOrganisationModal], above
 * the sort and organisation groups (e.g. the playlist export and repair
 * entries); hidden when the hosting screen passes none.
 */
data class OrganisationExtraAction(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit
)

/**
 * The modal opened from a media list screen's 3-dots ("more options") button:
 * one page of titled groups. When the screen supports sorting, the "Sorting"
 * group holds one radio row per [CollectionSortMode] plus the "Inverse"
 * checkbox that reverses the chosen order; the "Organisation" group holds the
 * six [MediaDensity] radio rows. Selecting an option applies it immediately
 * (the list behind the sheet recomposes live) and keeps the sheet open so the
 * user can flip through options; the scrim dismisses the sheet.
 */
@Composable
fun DensityOrganisationModal(
    visible: Boolean,
    currentDensity: MediaDensity,
    onSelectDensity: (MediaDensity) -> Unit,
    onDismiss: () -> Unit,
    /** Sort modes the screen supports; empty hides the whole sort group. */
    sortOptions: List<CollectionSortMode> = emptyList(),
    currentSort: CollectionSortMode = CollectionSortMode.NAME,
    onSelectSort: (CollectionSortMode) -> Unit = {},
    sortInverted: Boolean = false,
    onToggleSortInverted: (Boolean) -> Unit = {},
    /** Optional action row above the groups (e.g. playlist export). */
    extraAction: OrganisationExtraAction? = null,
    /** Further action rows rendered after [extraAction] in the same card. */
    extraActions: List<OrganisationExtraAction> = emptyList()
) {
    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            val actions = buildList {
                extraAction?.let { add(it) }
                addAll(extraActions)
            }
            if (actions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.itemRounding))
                        .background(FoamTheme.colors.surfaceVariant)
                ) {
                    actions.forEachIndexed { index, action ->
                        if (index > 0) ModalSectionDivider()
                        ModalActionRow(
                            label = action.label,
                            icon = action.icon,
                            onClick = action.onClick
                        )
                    }
                }
            }

            if (sortOptions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.itemRounding))
                        .background(FoamTheme.colors.surfaceVariant)
                ) {
                    ModalGroupTitle(label = stringResource(R.string.sorting))

                    sortOptions.forEachIndexed { index, option ->
                        ModalSectionDivider()
                        DensityRadioRow(
                            label = option.label,
                            selected = option == currentSort,
                            onClick = { onSelectSort(option) }
                        )
                    }

                    ModalSectionDivider()

                    SortInverseCheckboxRow(
                        checked = sortInverted,
                        onToggle = { onToggleSortInverted(!sortInverted) }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.itemRounding))
                    .background(FoamTheme.colors.surfaceVariant)
            ) {
                ModalGroupTitle(label = stringResource(R.string.organisation))

                MediaDensity.entries.forEachIndexed { index, option ->
                    ModalSectionDivider()
                    DensityRadioRow(
                        label = option.label,
                        selected = option == currentDensity,
                        onClick = { onSelectDensity(option) }
                    )
                }
            }
        }
    }
}

/**
 * One selectable option of a group: label on the left, radio indicator on the
 * right. Rendered in the smaller, non-bold body style so the group titles
 * (row-title style) read as headers above their options.
 */
@Composable
private fun DensityRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.body,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        DensityRadioButton(selected = selected)
    }
}

/**
 * The extra-action card's row: a tappable button-like entry (export, repair…)
 * with an icon ahead of the label (the backup export icon by default). The
 * label uses the row-title style so it stands out from the radio rows below.
 */
@Composable
private fun ModalActionRow(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon ?: Icons.Outlined.FileUpload,
            contentDescription = null,
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(FoamDimens.iconMedium)
        )
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        BasicText(
            text = label,
            style = FoamTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

/** A group's header row ("Sorting" / "Organisation") above its radio list. */
@Composable
private fun ModalGroupTitle(label: String) {
    BasicText(
        text = label,
        style = FoamTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingSmall)
    )
}

/**
 * The sort section's "Inverse" toggle: label on the left, checkbox on the
 * right — mirrors [DensityRadioRow]'s layout with a checkbox instead of a
 * radio. Reverses the currently selected sort's order (a random shuffle too).
 */
@Composable
private fun SortInverseCheckboxRow(
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.paddingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = stringResource(R.string.inverse),
            style = FoamTheme.typography.body,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            // 20dp = the checkbox's own drawn size; drops its 48dp built-in
            // touch padding so the row height matches the radio rows (the whole
            // row is the toggle target).
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = FoamTheme.accentColor,
                uncheckedColor = FoamTheme.colors.textSecondary
            )
        )
    }
}

/** Minimal custom radio: ring that gains an accent-filled dot when selected. */
@Composable
private fun DensityRadioButton(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .border(
                width = 2.dp,
                color = if (selected) FoamTheme.accentColor else FoamTheme.colors.textSecondary,
                shape = CircleShape
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(FoamTheme.accentColor)
            )
        }
    }
}

/**
 * The 3-dots icon button that opens [DensityOrganisationModal]; rendered in a
 * media list screen's top bar. Kept here so every screen shows the identical
 * control: the glyph sits in the floating top-bar's translucent circle
 * ([CollectionTopBarIcon]) with its own press ripple.
 */
@Composable
internal fun OrganisationMenuButton(
    contentDescription: String,
    onClick: () -> Unit
) {
    CollectionTopBarIcon(
        imageVector = Icons.Rounded.MoreVert,
        contentDescription = contentDescription,
        onClick = onClick
    )
}
