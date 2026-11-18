package com.foamplayer.app.ui.screens.queue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.EaseOutQuint
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamBorderOverlay
import com.foamplayer.app.ui.theme.foamSurface

/** The queue header's toggles that own a mode-picker modal. */
internal enum class QueueModeModalType { REPEAT, SHUFFLE }

/**
 * What the mode picker shows: the toggle that opened it plus the pressed button's
 * center (in the window's root coordinates) so the dialog can scale in from the
 * button.
 */
internal data class QueueModeModalRequest(
    val type: QueueModeModalType,
    val anchorCenter: Offset,
)

/**
 * One selectable row of the mode picker: an icon, its label and whether it's
 * active. [isDimmed] marks an off state (repeat none, shuffle off) whose icon
 * renders semi-transparent, matching the player's header toggles.
 */
internal data class QueueModeOption(
    val icon: ImageVector,
    val label: String,
    val isSelected: Boolean,
    val onSelect: () -> Unit,
    val isDimmed: Boolean = false,
)

/**
 * An optional trailing action of the mode picker, after a hairline separator: a
 * row that is not a mode — e.g. the shuffle modal's "Randomize", which reshuffles
 * the upcoming list once.
 */
internal data class QueueModeAction(
    val icon: ImageVector,
    val label: String,
    val onSelect: () -> Unit,
)

/**
 * Scrim behind the dialog, drawn by us: the platform window dim would hold at full
 * strength while the card's exit plays and only vanish with the dialog itself.
 */
private val QueueModeModalScrim = Color.Black.copy(alpha = 0.5f)

/** The rows' ripple is bounded to this rounded rectangle instead of the card's edges. */
private val QueueModeModalRippleShape = RoundedCornerShape(FoamDimens.buttonRounding)

/**
 * Fixed resting size of the dialog card: the scale's transform origin is derived
 * from it together with the root size, so the very first frame of the very first
 * open already grows out of the pressed button (AnimatedVisibility captures the
 * transform origin when the enter transition starts — it must not depend on
 * anything measured inside the dialog).
 */
private val QueueModeModalWidth = 300.dp
private val QueueModeModalRowHeight = 48.dp

/** The hairline separator's total height, including its vertical padding. */
private val QueueModeModalSeparatorHeight =
    FoamDimens.separatorHeight + FoamDimens.spacingTiny * 2

/**
 * The queue header's mode pickers, on the project's plain-[Dialog] pattern (see
 * the parametric EQ's node dialogs): a small centered dialog listing every mode
 * of the toggle that opened it (icon + label), the active one ringed with an
 * accent check.
 *
 * Unlike those dialogs it opens with an anchored scale+fade. The dialog window is
 * full-screen (edge-to-edge, like the activity), so its coordinate space is the
 * activity root's and the anchor maps 1:1: the scale's transform origin is the
 * button's position inside the resting card's bounds, so the card grows out of
 * the pressed button instead of popping in at its own center. Dismissing shrinks
 * it back into the same button.
 */
@Composable
internal fun QueueModeModalOverlay(
    request: QueueModeModalRequest?,
    options: List<QueueModeOption>,
    action: QueueModeAction?,
    rootSize: IntSize,
    onDismiss: () -> Unit
) {
    // The request/options/action shown while animating out: the state nulls
    // immediately on dismiss, the dialog must keep rendering the same content and
    // shrink back toward the same button until it's fully gone.
    val shownRequest = remember { mutableStateOf(request) }
    val shownOptions = remember { mutableStateOf(options) }
    val shownAction = remember { mutableStateOf(action) }
    if (request != null) {
        shownRequest.value = request
        shownOptions.value = options
        shownAction.value = action
    }
    val shown = shownRequest.value ?: return

    // Initial false ON PURPOSE: an AnimatedVisibility first-composed with its target
    // already visible skips the enter animation entirely (initial state == target
    // state), which is why the picker previously popped in without scaling. Starting
    // the state at false forces the enter transition to run on the dialog's first
    // composition, and keeps the dialog composed while the exit plays out (the same
    // choreography FoamBottomModal uses).
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = request != null
    if (!transitionState.currentState && !transitionState.targetState) return

    // The trailing action row and the separator's padded height both count toward
    // the resting card's size, so the scale-in pivot stays exact.
    val totalRows = shownOptions.value.size + (if (shownAction.value != null) 1 else 0)
    val transformOrigin = modeModalTransformOrigin(
        anchorCenter = shown.anchorCenter,
        rootSize = rootSize,
        rowCount = totalRows,
        separatorHeight = if (shownAction.value != null) {
            QueueModeModalSeparatorHeight
        } else {
            0.dp
        }
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // Full-screen window: its coordinate space is the activity root's, so the
            // anchor (button center in the activity root) maps 1:1 and the card
            // centers on the actual screen. The platform's outside-tap dismissal is
            // dead on a full-screen window, so the host Box below dismisses on tap.
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // The platform window dim holds at full strength while the card's exit plays
        // and only vanishes with the dialog itself — zeroed out and replaced by our
        // own scrim below, which fades in and out together with the card.
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                window.setWindowAnimations(0)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            // Scrim: shares the card's transition state, so the darkening fades in
            // and out together with the card instead of outliving its exit.
            AnimatedVisibility(
                visibleState = transitionState,
                enter = fadeIn(tween(FoamMotion.Duration.STANDARD)),
                // Front-loaded: drops fast at the start, in step with the card.
                exit = fadeOut(tween(FoamMotion.Duration.SWIFT, easing = FoamMotion.Easings.EXIT)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize().background(QueueModeModalScrim))
            }

            AnimatedVisibility(
                visibleState = transitionState,
                enter = scaleIn(
                    initialScale = 0f,
                    transformOrigin = transformOrigin,
                    animationSpec = tween(
                        FoamMotion.Duration.STANDARD,
                        easing = FoamMotion.Easings.DECELERATE
                    )
                ) + fadeIn(tween(FoamMotion.Duration.STANDARD)),
                exit = scaleOut(
                    targetScale = 0f,
                    transformOrigin = transformOrigin,
                    animationSpec = tween(
                        FoamMotion.Duration.SWIFT,
                        easing = FoamMotion.Easings.EXIT
                    )
                ) + fadeOut(tween(FoamMotion.Duration.SWIFT, easing = EaseOutQuint))
            ) {
                QueueModeModalCard(
                    options = shownOptions.value,
                    action = shownAction.value,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

/**
 * The dialog card, styled after the parametric EQ's node dialogs: flat surface,
 * item rounding, one icon+label row per mode, the active one ringed with an
 * accent check (the icon and label stay text-primary). Each row's ripple is
 * clipped to a rounded rectangle inset from the card's edges, so it reads as a
 * button instead of bleeding across the whole card. Tapping a row applies it
 * (a no-op for the current mode) and closes.
 */
@Composable
private fun QueueModeModalCard(
    options: List<QueueModeOption>,
    action: QueueModeAction?,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(QueueModeModalWidth)
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .foamSurface()
            .padding(vertical = FoamDimens.spacingSmall)
    ) {
        options.forEach { option ->
            QueueModeModalRow(
                icon = option.icon,
                label = option.label,
                isSelected = option.isSelected,
                isDimmed = option.isDimmed,
                onClick = {
                    option.onSelect()
                    onDismiss()
                }
            )
        }

        action?.let { action ->
            // Hairline separator between the mode list and the trailing action,
            // padded vertically so the rows' ripples don't touch it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = FoamDimens.spacingMedium,
                        vertical = FoamDimens.spacingSmall
                    )
                    .height(FoamDimens.separatorHeight)
                    .foamBorderOverlay()
            )
            QueueModeModalRow(
                icon = action.icon,
                label = action.label,
                isSelected = false,
                isDimmed = false,
                onClick = {
                    action.onSelect()
                    onDismiss()
                }
            )
        }
    }
}

/**
 * One row of the mode picker card: icon + label, the active mode ringed with an
 * accent check; [isDimmed] renders the icon semi-transparent (an off state).
 * The ripple is clipped to a rounded rectangle inset from the card's edges.
 */
@Composable
private fun QueueModeModalRow(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    isDimmed: Boolean,
    onClick: () -> Unit
) {
    // The outer padding insets the ripple from the card's edges; the clip
    // bounds it to a rounded rectangle.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(QueueModeModalRowHeight)
            .padding(horizontal = FoamDimens.spacingSmall)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(QueueModeModalRippleShape)
                .clickable(
                    interactionSource = remember(label) { MutableInteractionSource() },
                    indication = ripple(color = FoamTheme.colors.textPrimary),
                    onClick = onClick
                )
                .padding(horizontal = FoamDimens.spacingSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Semi-transparent when the row is an off state (repeat none,
            // shuffle off), the same dimming the player's header uses.
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier
                    .size(FoamDimens.iconMedium)
                    .graphicsLayer { alpha = if (isDimmed) 0.4f else 1f }
            )
            BasicText(
                text = label,
                style = FoamTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Normal,
                    color = FoamTheme.colors.textPrimary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = FoamDimens.spacingMedium)
                    .weight(1f)
            )
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = FoamTheme.accentColor,
                    modifier = Modifier
                        .padding(start = FoamDimens.spacingSmall)
                        .size(FoamDimens.iconSmall)
                )
            }
        }
    }
}

/**
 * The scale's transform origin: the anchor (button center, in the window's root
 * coordinates) expressed as fractions of the resting card's bounds. The dialog
 * window is full-screen, so the card rests centered in the root: its top-left is
 * (rootSize - cardSize) / 2. The fractions run outside 0..1 whenever the button
 * sits outside the card — the usual case — which places the pivot at the button
 * and the card grows out of it.
 */
@Composable
private fun modeModalTransformOrigin(
    anchorCenter: Offset,
    rootSize: IntSize,
    rowCount: Int,
    separatorHeight: Dp
): TransformOrigin {
    val density = LocalDensity.current
    val cardWidth = with(density) { QueueModeModalWidth.toPx() }
    val cardHeight = with(density) {
        (
            QueueModeModalRowHeight * rowCount +
                separatorHeight +
                FoamDimens.spacingSmall * 2
            ).toPx()
    }
    if (rootSize.width <= 0 || rootSize.height <= 0) {
        return TransformOrigin(0.5f, 0.5f)
    }
    val cardTopLeft = Offset(
        x = (rootSize.width - cardWidth) / 2f,
        y = (rootSize.height - cardHeight) / 2f
    )
    return TransformOrigin(
        pivotFractionX = (anchorCenter.x - cardTopLeft.x) / cardWidth,
        pivotFractionY = (anchorCenter.y - cardTopLeft.y) / cardHeight
    )
}
