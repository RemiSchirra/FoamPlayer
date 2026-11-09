package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.foamplayer.app.R
import com.foamplayer.app.audio.fidelity.FidelityItem
import com.foamplayer.app.audio.fidelity.FidelityScore
import com.foamplayer.app.audio.fidelity.FidelityStatus
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamBorderOverlay
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Live audio fidelity verdict with an itemized breakdown, shared between the
 * audio settings screen and the player's audio-info modal. Tapping toggles
 * the breakdown; with no playback the card is inert.
 */
@Composable
fun FidelityScoreCard(
    score: FidelityScore?,
    hasTrack: Boolean
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expandable = hasTrack

    FoamCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (expandable) {
            { expanded = !expanded }
        } else null,
        indication = null
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoamDimens.cornerRadiusMedium))
                    .then(
                        if (expandable) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = FoamTheme.colors.textPrimary),
                                onClick = { expanded = !expanded }
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(FoamDimens.paddingMedium)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FidelityRing(score = score)

                    Spacer(modifier = Modifier.width(FoamDimens.paddingMedium))

                    Column(modifier = Modifier.weight(1f)) {
                        FidelityVerdictText(score = score, hasTrack = hasTrack)
                        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))
                        BasicText(
                            text = stringResource(R.string.audio_fidelity_desc),
                            style = FoamTheme.typography.caption
                        )
                    }
                    if (expandable) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = FoamTheme.colors.textSecondary
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded && expandable) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = FoamDimens.paddingMedium)
                        .padding(bottom = FoamDimens.paddingMedium)
                ) {
                    score?.items?.forEachIndexed { index, item ->
                        if (index > 0) FidelitySectionDivider()
                        FidelityItemRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun scoreColor(score: FidelityScore?) = when {
    score == null -> FoamTheme.colors.textSecondary
    score.total >= 85 -> FoamTheme.colors.bitPerfectActive
    score.total >= 55 -> FoamTheme.colors.bitPerfectInactive
    else -> FoamTheme.accentColor
}

/**
 * The verdict headline: slides up + fades in when the verdict improves, slides
 * down when it worsens, and doesn't animate when the text doesn't change.
 * Direction comes from the total (the label is derived from it); measuring /
 * no-playback rank below every verdict, so a track change settles the old
 * verdict down and the incoming one up.
 */
@Composable
private fun FidelityVerdictText(score: FidelityScore?, hasTrack: Boolean) {
    val text = score?.label
        ?: if (hasTrack) "Measuring…" else stringResource(R.string.fidelity_no_playback)

    val rank = score?.total ?: Int.MIN_VALUE
    var lastState by remember { mutableStateOf(text to rank) }
    val direction = if (text != lastState.first) {
        when {
            rank > lastState.second -> 1
            rank < lastState.second -> -1
            else -> 0
        }
    } else null
    SideEffect { lastState = text to rank }

    AnimatedContent(
        targetState = text,
        transitionSpec = {
            when (direction) {
                1 ->
                    (slideInVertically(tween(FoamMotion.Duration.SWIFT)) { it } + fadeIn(tween(FoamMotion.Duration.SWIFT))) togetherWith
                            (slideOutVertically(tween(FoamMotion.Duration.SWIFT)) { -it } + fadeOut(tween(FoamMotion.Duration.SWIFT)))
                -1 ->
                    (slideInVertically(tween(FoamMotion.Duration.SWIFT)) { -it } + fadeIn(tween(FoamMotion.Duration.SWIFT))) togetherWith
                            (slideOutVertically(tween(FoamMotion.Duration.SWIFT)) { it } + fadeOut(tween(FoamMotion.Duration.SWIFT)))
                else -> EnterTransition.None togetherWith ExitTransition.None
            }
        },
        modifier = Modifier.clipToBounds(),
        label = "fidelityVerdict"
    ) { verdict ->
        BasicText(
            text = verdict,
            style = FoamTheme.typography.titleMedium.copy(
                color = scoreColor(score),
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun FidelitySectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .foamBorderOverlay()
    )
}

/** Score ring with the total in the middle. */
@Composable
private fun FidelityRing(score: FidelityScore?) {
    val trackColor = FoamTheme.tintedSurfaceVariant
    val targetFraction = score?.fraction ?: 0f

    // The score streams in as measurement refines, so glide the sweep and
    // color toward the new verdict instead of snapping — same retargeting
    // tween as the volume control's fraction.
    val fraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(
            durationMillis = FoamMotion.Duration.STANDARD,
            easing = FoamMotion.Easings.STANDARD
        ),
        label = "fidelityRingSweep"
    )
    val ringColor by animateColorAsState(
        targetValue = scoreColor(score),
        animationSpec = tween(
            durationMillis = FoamMotion.Duration.STANDARD,
            easing = FoamMotion.Easings.STANDARD
        ),
        label = "fidelityRingColor"
    )

    Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(72.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (fraction > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RollingScoreNumber(
                value = score?.total,
                maxValue = score?.max ?: 100,
                style = FoamTheme.typography.titleMedium.copy(
                    color = ringColor,
                    fontWeight = FontWeight.Bold
                )
            )
            BasicText(
                text = "/ ${score?.max ?: 100}",
                style = FoamTheme.typography.caption.copy(fontSize = 9.sp)
            )
        }
    }
}

@Composable
private fun FidelityItemRow(item: FidelityItem) {
    val statusColor = when (item.status) {
        FidelityStatus.PASS -> FoamTheme.colors.bitPerfectActive
        FidelityStatus.WARN -> FoamTheme.colors.bitPerfectInactive
        FidelityStatus.FAIL -> FoamTheme.accentColor
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FoamDimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = item.label, style = FoamTheme.typography.body)
            BasicText(text = item.detail, style = FoamTheme.typography.caption)
        }
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        BasicText(
            text = "${item.points}/${item.maxPoints}",
            style = FoamTheme.typography.monospace.copy(color = statusColor)
        )
    }
}

/**
 * Odometer-style number: one rolling cell per place value, aligned from the
 * right. Counting up rolls digits up (9 wraps to 0), counting down rolls them
 * down, and places beyond the current length collapse. Null renders a static
 * "—" at the same height.
 */
@Composable
private fun RollingScoreNumber(value: Int?, maxValue: Int, style: TextStyle) {
    val density = LocalDensity.current
    val cellHeight = with(density) {
        (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.25f).toDp()
    }
    if (value == null) {
        Box(modifier = Modifier.height(cellHeight), contentAlignment = Alignment.Center) {
            BasicText(text = "—", style = style)
        }
        return
    }

    // Keyed on the metric-defining style parts only: the animated color must
    // not re-trigger measurement.
    val textMeasurer = rememberTextMeasurer()
    val digitWidth = remember(style.fontSize, style.fontFamily, style.fontWeight) {
        with(density) {
            (0..9).maxOf { textMeasurer.measure(it.toString(), style).size.width }.toDp()
        }
    }

    var previous by remember { mutableIntStateOf(value) }
    val increasing = value >= previous
    SideEffect { previous = value }

    val length = value.toString().length
    Row {
        for (place in maxValue.toString().length - 1 downTo 0) {
            key(place) {
                AnimatedVisibility(
                    visible = place < length,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    RollingDigitCell(
                        digit = if (place < length) value / 10.0.pow(place).toInt() % 10 else null,
                        increasing = increasing,
                        cellWidth = digitWidth,
                        cellHeight = cellHeight,
                        style = style
                    )
                }
            }
        }
    }
}

/**
 * One place value. The position is unbounded and only two digits are drawn
 * (floor and floor + 1), so rolling wraps 9 → 0 in either direction. A null
 * digit (cell collapsing) keeps the last one shown.
 */
@Composable
private fun RollingDigitCell(
    digit: Int?,
    increasing: Boolean,
    cellWidth: Dp,
    cellHeight: Dp,
    style: TextStyle
) {
    val position = remember { Animatable((digit ?: 0).toFloat()) }
    LaunchedEffect(digit) {
        if (digit == null) return@LaunchedEffect
        val current = position.targetValue.roundToInt().mod(10)
        val steps = if (increasing) (digit - current).mod(10) else -(current - digit).mod(10)
        position.animateTo(
            targetValue = position.targetValue + steps,
            animationSpec = tween(
                durationMillis = FoamMotion.Duration.STANDARD,
                easing = FoamMotion.Easings.STANDARD
            )
        )
    }

    val base = floor(position.value).toInt()
    val offset = position.value - base
    Box(
        modifier = Modifier
            .width(cellWidth)
            .height(cellHeight)
            .clipToBounds()
    ) {
        // Unbounded height: otherwise the Box's max height collapses the second digit.
        Column(
            modifier = Modifier
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = -offset * cellHeight.toPx() }
        ) {
            for (n in base..base + 1) {
                Box(
                    modifier = Modifier.size(cellWidth, cellHeight),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(text = n.mod(10).toString(), style = style)
                }
            }
        }
    }
}