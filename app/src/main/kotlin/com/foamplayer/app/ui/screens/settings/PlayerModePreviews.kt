package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foamplayer.app.R
import com.foamplayer.app.data.settings.PlayerMode
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamBorderOverlay
import com.foamplayer.app.ui.theme.foamSurfaceVariant

/** Width of one player-mode phone thumbnail; fixed so the three previews line up. */
private val PlayerModePreviewWidth = 96.dp

/** Height of one preview thumbnail; the real phone is taller, but this keeps the row compact. */
private val PlayerModePreviewHeight = 136.dp

/** Corner rounding of the preview phone body. */
private val PlayerModePreviewRounding = 12.dp

/**
 * Picker for [PlayerMode], laid out like the collection hero layout picker: a
 * horizontally scrollable row of phone-aspect skeleton previews, each captioned
 * with the mode's name. Tapping writes the mode; the selected one gets an
 * accent ring and an accent caption.
 */
@Composable
internal fun PlayerModePicker(
    selected: PlayerMode,
    onSelect: (PlayerMode) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall),
        contentPadding = PaddingValues(horizontal = FoamDimens.paddingMedium)
    ) {
        items(PlayerMode.entries, key = { it.name }) { mode ->
            val isSelected = mode == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerModePreview(
                    mode = mode,
                    isSelected = isSelected,
                    onClick = { onSelect(mode) }
                )
                Spacer(modifier = Modifier.height(4.dp))
                BasicText(
                    text = playerModeLabel(mode),
                    style = FoamTheme.typography.caption.copy(
                        color = if (isSelected) FoamTheme.accentColor else FoamTheme.colors.textSecondary
                    ),
                    modifier = Modifier.width(PlayerModePreviewWidth)
                )
            }
        }
    }
}

@Composable
private fun playerModeLabel(mode: PlayerMode): String = when (mode) {
    PlayerMode.NORMAL -> stringResource(R.string.player_mode_normal)
    PlayerMode.IMMERSIVE -> stringResource(R.string.player_mode_immersive)
    PlayerMode.VINYL -> stringResource(R.string.player_mode_vinyl)
}

/**
 * One mode preview inside a phone-aspect thumbnail: a skeleton of the real full
 * player (art, title/artist, seek bar, transport), so the three
 * modes can be compared at a glance. Presses show a bounded ripple clipped to
 * the phone's rounded shape.
 */
@Composable
private fun PlayerModePreview(
    mode: PlayerMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val label = playerModeLabel(mode)
    Box(
        modifier = Modifier
            .width(PlayerModePreviewWidth)
            .height(PlayerModePreviewHeight)
            .clip(RoundedCornerShape(PlayerModePreviewRounding))
            .foamSurfaceVariant()
            .selectable(
                selected = isSelected,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics { contentDescription = label }
            .playerModeSelectionRing(
                isSelected = isSelected,
                rounding = PlayerModePreviewRounding
            )
    ) {
        when (mode) {
            PlayerMode.NORMAL -> NormalPlayerPreview()
            PlayerMode.IMMERSIVE -> ImmersivePlayerPreview()
            PlayerMode.VINYL -> VinylPlayerPreview()
        }
    }
}

/**
 * Selection ring drawn on top of the preview content, so it stays visible over
 * the immersive mode's edge-to-edge artwork. The selected item gets the accent
 * color and a thicker stroke; the others keep a hairline.
 */
@Composable
private fun Modifier.playerModeSelectionRing(isSelected: Boolean, rounding: Dp): Modifier {
    val strokeWidth = if (isSelected) 2.dp else 1.dp
    val color = if (isSelected) FoamTheme.accentColor else FoamTheme.colors.borderOverlay
    return drawWithContent {
        drawContent()
        val stroke = strokeWidth.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius((rounding.toPx() - stroke / 2f).coerceAtLeast(0f)),
            style = Stroke(stroke)
        )
    }
}

/** Thin skeleton line. */
@Composable
private fun PreviewLine(
    width: Dp,
    thickness: Dp,
    modifier: Modifier = Modifier,
    color: Color = FoamTheme.colors.textSecondary.copy(alpha = 0.4f)
) {
    Box(
        modifier = modifier
            .width(width)
            .height(thickness)
            .clip(RoundedCornerShape(thickness / 2))
            .background(color)
    )
}

/** Artwork placeholder: the plain border-overlay tone. */
@Composable
private fun PreviewArtFill(modifier: Modifier = Modifier) {
    Box(modifier = modifier.foamBorderOverlay())
}

/** Square album-art placeholder with the player's rounded corners. */
@Composable
private fun PreviewArtBox(size: Dp, rounding: Dp) {
    PreviewArtFill(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(rounding))
    )
}

/** Left-aligned title + artist skeleton, like the full player's track row. */
@Composable
private fun PreviewTitleBlock() {
    Column(modifier = Modifier.fillMaxWidth()) {
        PreviewLine(
            width = 52.dp,
            thickness = 4.dp,
            color = FoamTheme.colors.textPrimary.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        PreviewLine(width = 34.dp, thickness = 3.dp)
    }
}

/** Seek bar skeleton: accent track, with the unplayed portion at 40% opacity. */
@Composable
private fun PreviewSeekBar(progress: Float = 0.62f) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(FoamTheme.accentColor.copy(alpha = 0.4f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(FoamTheme.accentColor)
        )
    }
}

/**
 * Transport skeleton using the app's active icon theme, with the same relative
 * sizes as the real controls: small previous/next around a larger play button.
 */
@Composable
private fun PreviewTransportRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = FoamTheme.colors.textPrimary
        Icon(
            imageVector = FoamTheme.icons.current.previous,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp)
        )
        Icon(
            imageVector = FoamTheme.icons.current.play,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
        Icon(
            imageVector = FoamTheme.icons.current.next,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp)
        )
    }
}

/**
 * Everything below the art, shared by all three modes: title block, seek bar and
 * transport. The weighted spacers give image -> text and text -> seek bar the
 * same rhythm in every mode.
 */
@Composable
private fun PreviewPlayerChrome(artSlot: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 7.dp, vertical = 8.dp)
    ) {
        artSlot()
        Spacer(modifier = Modifier.weight(1f))
        PreviewTitleBlock()
        Spacer(modifier = Modifier.weight(1f))
        PreviewSeekBar()
        Spacer(modifier = Modifier.height(6.dp))
        PreviewTransportRow()
    }
}

/** Classic mode: rounded square art, then the shared controls. */
@Composable
private fun NormalPlayerPreview() {
    Box(modifier = Modifier.fillMaxSize().background(FoamTheme.colors.surfaceVariant)) {
        PreviewPlayerChrome {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PreviewArtBox(size = 58.dp, rounding = 9.dp)
            }
        }
    }
}

/**
 * Vinyl mode: same layout as classic, but the art is a record (grooves, art label
 * and spindle) with the tonearm resting over its grooves.
 */
@Composable
private fun VinylPlayerPreview() {
    Box(modifier = Modifier.fillMaxSize().background(FoamTheme.colors.surfaceVariant)) {
        PreviewPlayerChrome {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PreviewVinylRecord(modifier = Modifier.size(58.dp))
            }
        }
    }
}

/**
 * The record: dark disc with faint grooves and a rim, an art label with a
 * spindle hole, and the tonearm at the same pivot/arm geometry as
 * [com.foamplayer.app.ui.screens.player.TonearmOverlay] (pivot ring, silver arm,
 * dark cartridge at the stylus end).
 */
@Composable
private fun PreviewVinylRecord(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        PreviewVinylDisc(modifier = Modifier.fillMaxSize())
        // Label: the exact same placeholder tone as the classic/immersive art,
        // drawn as a real box (not on the canvas) so it isn't darkened by the disc.
        Box(
            modifier = Modifier
                .fillMaxSize(0.4f)
                .clip(CircleShape)
                .background(FoamTheme.colors.surfaceVariant)
                .foamBorderOverlay()
        )
        Box(
            modifier = Modifier
                .size(2.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
        )
    }
}

/** Disc, grooves, rim and tonearm of the record; the art label is layered on top. */
@Composable
private fun PreviewVinylDisc(modifier: Modifier = Modifier) {
    val discColor = Color(0xFF1C1C1C)
    val grooveColor = Color.White.copy(alpha = 0.07f)
    val rimColor = Color.White.copy(alpha = 0.16f)
    val armColor = FoamTheme.colors.textSecondary.copy(alpha = 0.9f)
    val cartridgeColor = Color(0xFF2B2E38)

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        val labelRadius = radius * 0.4f

        drawCircle(color = discColor, radius = radius, center = center)
        val grooveCount = 8
        for (i in 1..grooveCount) {
            val fraction = i / (grooveCount + 1f)
            drawCircle(
                color = grooveColor,
                radius = labelRadius + (radius - labelRadius) * fraction,
                center = center,
                style = Stroke(width = 0.5.dp.toPx())
            )
        }
        drawCircle(
            color = rimColor,
            radius = radius - 0.5.dp.toPx(),
            center = center,
            style = Stroke(width = 0.75.dp.toPx())
        )

        // Tonearm: pivot in the top-right wedge, stylus over the grooves.
        // Same 0.95/0.12 pivot and 0.56 arm length as the real overlay.
        val pivot = Offset(size.width * 0.95f, size.height * 0.12f)
        val angle = Math.toRadians(-15.7).toFloat()
        val sinA = kotlin.math.sin(angle)
        val cosA = kotlin.math.cos(angle)
        val armLength = size.minDimension * 0.56f
        val stylus = Offset(pivot.x + armLength * sinA, pivot.y + armLength * cosA)
        val counterweight = Offset(
            pivot.x - size.minDimension * 0.06f * sinA,
            pivot.y - size.minDimension * 0.06f * cosA
        )
        val cartridgeStart = Offset(
            stylus.x - size.minDimension * 0.07f * sinA,
            stylus.y - size.minDimension * 0.07f * cosA
        )

        drawLine(
            color = armColor,
            start = counterweight,
            end = stylus,
            strokeWidth = 1.3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = cartridgeColor,
            start = cartridgeStart,
            end = stylus,
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = armColor, radius = 2.2.dp.toPx(), center = pivot)
        drawCircle(color = cartridgeColor, radius = 0.8.dp.toPx(), center = pivot)
    }
}

/**
 * Immersive mode: sharp artwork bleeding edge to edge, fading into the surface
 * behind the controls, with the same title block, seek bar and transport as the
 * other modes. No separate art box is drawn.
 */
@Composable
private fun ImmersivePlayerPreview() {
    Box(modifier = Modifier.fillMaxSize()) {
        PreviewArtFill(modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        0.75f to FoamTheme.colors.surfaceVariant.copy(alpha = 0.55f),
                        1f to FoamTheme.colors.surfaceVariant
                    )
                )
        )
        // The artwork is the whole card in this mode, so the art slot is an
        // empty spacer of the same height as the other modes' art. That keeps
        // the title block, seek bar and transport at identical positions.
        PreviewPlayerChrome { Spacer(modifier = Modifier.height(58.dp)) }
    }
}