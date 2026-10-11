package com.foamplayer.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.foamplayer.app.ui.theme.FoamTheme

// Hoisted: this list was rebuilt on every recomposition of the score popup.
private val ScoreOptions = listOf(
    2 to Icons.Filled.AutoAwesome,
    1 to Icons.Filled.Favorite,
    -1 to Icons.Filled.HeartBroken
)

/** Icon/tint pair for the track score heart. Always textPrimary, never the accent. */
@Composable
fun trackScoreVisual(score: Int): Pair<ImageVector, Color> = when (score) {
    2 -> Icons.Filled.AutoAwesome to FoamTheme.colors.textPrimary
    1 -> Icons.Filled.Favorite to FoamTheme.colors.textPrimary
    -1 -> Icons.Filled.HeartBroken to FoamTheme.colors.textPrimary
    else -> Icons.Filled.FavoriteBorder to FoamTheme.colors.textSecondary
}

/**
 * The track score heart: tap sets like (1), or clears back to none (0) when
 * the track already carries a score; long press opens an expanding menu of
 * the three rating options — super like (top), like (middle, landing where
 * the resting icon sits) and dislike (bottom). Haptic feedback on open/pick.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackScoreHeart(
    score: Int,
    onSetScore: (Int) -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
    touchTarget: Dp = 40.dp
) {
    var menuVisible by remember { mutableStateOf(false) } // popup in composition
    var menuExpanded by remember { mutableStateOf(false) } // target state
    val haptics = LocalHapticFeedback.current

    // Spring pop whenever the score changes (skipped on first composition).
    val popScale = remember { Animatable(1f) }
    var lastScore by remember { mutableStateOf(score) }
    LaunchedEffect(score) {
        if (score != lastScore) {
            lastScore = score
            popScale.snapTo(0.55f)
            popScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    // Drives the menu's scale/alpha; stays mounted until the exit settles so
    // the dismissal is animated too.
    val menuProgress = remember { Animatable(0f) }
    LaunchedEffect(menuExpanded) {
        if (menuExpanded) {
            menuProgress.snapTo(0f)
            menuProgress.animateTo(
                1f,
                spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
            )
        } else {
            menuProgress.animateTo(0f, tween(durationMillis = 130, easing = FastOutSlowInEasing))
            menuVisible = false
        }
    }

    fun closeMenu() {
        menuExpanded = false
    }

    Box(
        modifier = modifier
            .size(touchTarget)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuVisible = true
                    menuExpanded = true
                },
                onClick = { onSetScore(if (score != 0) 0 else 1) }
            ),
        contentAlignment = Alignment.Center
    ) {
        val (icon, tint) = trackScoreVisual(score)
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { scaleX = popScale.value; scaleY = popScale.value }
        )

        if (menuVisible) {
            Popup(
                alignment = Alignment.Center,
                onDismissRequest = ::closeMenu,
                properties = PopupProperties(focusable = true)
            ) {
                // One elevated capsule holding all three options; scales/fades
                // around the heart. The outer padding gives the shadow room.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .graphicsLayer {
                            val progress = menuProgress.value
                            scaleX = progress
                            scaleY = progress
                            alpha = progress
                        }
                        .padding(14.dp)
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(28.dp),
                            ambientColor = Color.Black,
                            spotColor = Color.Black
                        )
                        .clip(RoundedCornerShape(28.dp))
                        .background(FoamTheme.colors.surfaceVariant)
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                ) {
                    ScoreOptions.forEach { (option, optionIcon) ->
                            val selected = score == option
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onSetScore(option)
                                            closeMenu()
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = optionIcon,
                                    contentDescription = null,
                                    tint = if (selected) FoamTheme.accentColor else FoamTheme.colors.textPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                }
            }
        }
    }
}
