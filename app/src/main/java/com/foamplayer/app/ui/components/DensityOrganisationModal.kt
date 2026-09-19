package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.foamplayer.app.constants.CollectionSortMode
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * The modal opened from a media list screen's 3-dots ("more options") button.
 * Its first page holds — when the screen supports sorting — the sort radio list
 * (one row per [CollectionSortMode]) with the "Inverse" checkbox that reverses
 * the chosen order, followed by the "Organisation" row; tapping that slides in
 * the submodal — a radio list of the six [MediaDensity] modes. Selecting an
 * option applies it immediately (the list behind the sheet recomposes live)
 * and keeps the sheet open so the user can flip through options; Back returns
 * to the first page and the scrim dismisses the sheet.
 */
@Composable
fun DensityOrganisationModal(
    visible: Boolean,
    currentDensity: MediaDensity,
    onSelectDensity: (MediaDensity) -> Unit,
    onDismiss: () -> Unit,
    /** Sort modes the screen supports; empty hides the whole sort section. */
    sortOptions: List<CollectionSortMode> = emptyList(),
    currentSort: CollectionSortMode = CollectionSortMode.NAME,
    onSelectSort: (CollectionSortMode) -> Unit = {},
    sortInverted: Boolean = false,
    onToggleSortInverted: (Boolean) -> Unit = {}
) {
    // The density page is a second page of the SAME sheet (one Dialog window).
    // It resets as soon as the sheet starts closing — never on open — so a
    // later presentation always begins on the menu page instead of resuming
    // (and animating away from) the radio submodal.
    var showDensityPage by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible) showDensityPage = false
    }

    // Horizontal gap kept between the outgoing and incoming pages while they
    // slide (same push/pop pattern as the track/collection modals' details).
    val pageGapPx = with(LocalDensity.current) {
        FoamDimens.spacingSmall.roundToPx()
    }

    FoamBottomModal(
        visible = visible,
        onDismissRequest = onDismiss
    ) {
        AnimatedContent(
            targetState = showDensityPage,
            transitionSpec = {
                if (!visible) {
                    // The sheet is already sliding away: swap pages silently.
                    EnterTransition.None togetherWith ExitTransition.None
                } else if (targetState) {
                    slideInHorizontally { it + pageGapPx } togetherWith
                            slideOutHorizontally { -it - pageGapPx }
                } else {
                    slideInHorizontally { -it - pageGapPx } togetherWith
                            slideOutHorizontally { it + pageGapPx }
                }
            },
            label = "DensityOrganisationPage"
        ) { isDensityPage ->
            Column(verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
                if (isDensityPage) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.itemRounding))
                            .background(FoamTheme.colors.surfaceVariant)
                    ) {
                        MediaDensity.entries.forEachIndexed { index, option ->
                            if (index > 0) ModalSectionDivider()
                            DensityRadioRow(
                                label = option.label,
                                selected = option == currentDensity,
                                onClick = { onSelectDensity(option) }
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.itemRounding))
                            .background(FoamTheme.colors.surfaceVariant)
                    ) {
                        ModalBackButton(onBack = { showDensityPage = false })
                    }
                } else {
                    if (sortOptions.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(FoamDimens.itemRounding))
                                .background(FoamTheme.colors.surfaceVariant)
                        ) {
                            SortSectionTitle(label = "Sorting")

                            sortOptions.forEachIndexed { index, option ->
                                ModalSectionDivider()
                                DensityRadioRow(
                                    label = option.label,
                                    selected = option == currentSort,
                                    onClick = { onSelectSort(option) },
                                    // Smaller, non-bold options under the section title.
                                    textStyle = FoamTheme.typography.body
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
                        ModalRowButton(
                            label = "Organisation",
                            onClick = { showDensityPage = true }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One selectable density option: label on the left, radio indicator on the
 * right. [textStyle] lets the sort section render its rows smaller and
 * non-bold under its title while the density page keeps the row-title style.
 */
@Composable
private fun DensityRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    textStyle: TextStyle = FoamTheme.typography.titleMedium
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
            style = textStyle,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))
        DensityRadioButton(selected = selected)
    }
}

/** The sort section's header row ("Sorting") above its radio list. */
@Composable
private fun SortSectionTitle(label: String) {
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
            text = "Inverse",
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
 * control.
 */
@Composable
internal fun OrganisationMenuButton(
    contentDescription: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = contentDescription,
            tint = FoamTheme.colors.textPrimary,
            modifier = Modifier.size(FoamDimens.iconMedium)
        )
    }
}
