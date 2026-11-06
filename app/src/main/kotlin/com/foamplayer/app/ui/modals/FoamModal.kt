package com.foamplayer.app.ui.modals

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.os.Build
import android.view.WindowManager
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.LocalFoamBackdropState
import com.foamplayer.app.ui.theme.foamSurface
import kotlin.math.abs

/**
 * A modal sheet docked to the bottom of the screen: inset from the sides, kept
 * clear of the navigation bars, and rounded on ALL four corners so its bottom
 * edge is visible as a docked card just above the bottom of the screen.
 *
 * Sizing / gesture behavior:
 * - Opens sized to fit its [content] exactly (capped just short of the top of
 *   the screen). Content taller than that cap falls back to a normal internal
 *   scroll, so the whole modal is visible without any interaction in the
 *   common case.
 * - Dragging/scrolling DOWN while the content is scrolled to its top
 *   progressively shrinks the sheet -- the bottom edge stays docked while the
 *   top descends -- rather than fighting with the content scroll. Dragging
 *   back up restores it, again only up to its fitted size (never into empty
 *   space).
 * - Releasing mid-drag (with or without a flick) springs to the nearest
 *   resting point: dismissed, or back at its fitted size. The scrim fades with
 *   the sheet's live size, so dragging it down also fades the background.
 *
 * Rendered in a full-screen [Dialog] so it always overlays the whole screen
 * regardless of where it's called from (e.g. from inside a nav bar that isn't
 * itself full-size).
 */
@Composable
fun FoamBottomModal(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = FoamDimens.cornerRadiusLarge,
    topEdgeMargin: Dp = FoamDimens.paddingLarge,
    horizontalMargin: Dp = FoamDimens.paddingMedium,
    bottomEdgeMargin: Dp = FoamDimens.spacingMedium,
    liftAboveKeyboard: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = visible

    if (!transitionState.currentState && !transitionState.targetState) return

    val backdrop = LocalFoamBackdropState.current
    DisposableEffect(visible) {
        if (visible) backdrop.acquire()
        onDispose { if (visible) backdrop.release() }
    }

    // System-bar insets are read OUTSIDE the Dialog, in the activity window:
    // the dialog window fits only the IME (see the fitInsetsTypes SideEffect
    // below), so its statusBars/navigationBars insets report zero inside and
    // bottom-docked sheets slide under the system nav buttons. The activity
    // window's insets are the same values the app's own nav bar pads with, so
    // capturing them here and passing them down keeps the sheet clear.
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarBottomPadding =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                window.setWindowAnimations(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val imeType = android.view.WindowInsets.Type.ime()
                    val imeSide = android.view.WindowInsets.Side.BOTTOM
                    val attrs = window.attributes
                    if (attrs.fitInsetsTypes != imeType || attrs.fitInsetsSides != imeSide) {
                        attrs.setFitInsetsTypes(imeType)
                        attrs.setFitInsetsSides(imeSide)
                        window.attributes = attrs
                    }
                }
                @Suppress("DEPRECATION")
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val imeInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                .takeIf { liftAboveKeyboard } ?: 0.dp
            val bottomSpacing = maxOf(navigationBarBottomPadding, imeInset) + bottomEdgeMargin

            val fullPx = with(density) {
                (maxHeight - statusBarTopPadding - topEdgeMargin - bottomSpacing).coerceAtLeast(0.dp).toPx()
            }
            val scrollState = rememberScrollState()

            var contentHeightPx by remember { mutableFloatStateOf(0f) }
            var footerHeightPx by remember { mutableFloatStateOf(0f) }

            val restingCapPx = if (contentHeightPx > 0f) {
                minOf(contentHeightPx + footerHeightPx, fullPx)
            } else {
                fullPx
            }

            var draggedCapPx by remember { mutableStateOf<Float?>(null) }

            var listMovedThisGesture by remember { mutableStateOf(false) }

            LaunchedEffect(visible) {
                if (visible) {
                    draggedCapPx = null
                    listMovedThisGesture = false
                    backdrop.dragPresence = null
                }
            }

            val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
            val latestFullPx by rememberUpdatedState(fullPx)

            val nestedScrollConnection = remember {
                object : NestedScrollConnection {

                    fun restingPx(): Float =
                        if (contentHeightPx > 0f) {
                            minOf(contentHeightPx + footerHeightPx, latestFullPx)
                        } else {
                            latestFullPx
                        }

                    fun currentCapPx(): Float = draggedCapPx ?: restingPx()

                    fun presence(capPx: Float): Float =
                        capPx / restingPx().coerceAtLeast(1f)

                    suspend fun settle(flingVelocityY: Float) {
                        val resting = restingPx()
                        val anchors = floatArrayOf(0f, resting)
                        val projected = (currentCapPx() - flingVelocityY * 0.15f).coerceIn(0f, resting)
                        val target = anchors.minByOrNull { abs(it - projected) } ?: resting
                        animate(
                            initialValue = currentCapPx(),
                            targetValue = target,
                            initialVelocity = -flingVelocityY,
                            animationSpec = FoamMotion.Springs.SHEET_SETTLE
                        ) { value, _ ->
                            draggedCapPx = value
                            backdrop.dragPresence = presence(value)
                        }
                        if (target <= 0f) {
                            latestOnDismissRequest()
                        } else {
                            draggedCapPx = null
                            backdrop.dragPresence = null
                        }
                    }

                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source != NestedScrollSource.UserInput) return Offset.Zero
                        val dy = available.y
                        if (dy < 0f) {
                            val room = restingPx() - currentCapPx()
                            if (room > 0f) {
                                val consumed = if (-dy < room) dy else -room
                                val cap = currentCapPx() - consumed
                                draggedCapPx = cap
                                backdrop.dragPresence = presence(cap)
                                return Offset(0f, consumed)
                            }
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        if (source != NestedScrollSource.UserInput) return Offset.Zero
                        if (consumed.y != 0f) {
                            listMovedThisGesture = true
                            return Offset.Zero
                        }

                        val dy = available.y
                        if (dy > 0f && !listMovedThisGesture) {
                            val cap = currentCapPx()
                            if (cap > 0f) {
                                val amount = if (dy < cap) dy else cap
                                val next = cap - amount
                                draggedCapPx = next
                                backdrop.dragPresence = presence(next)
                                return Offset(0f, amount)
                            }
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        val listMoved = listMovedThisGesture
                        listMovedThisGesture = false

                        val atRest = currentCapPx() >= restingPx() - 0.5f
                        val listCannotScroll = scrollState.maxValue == 0
                        return when {
                            listMoved -> Velocity.Zero
                            !atRest -> {
                                settle(available.y)
                                Velocity(0f, available.y)
                            }
                            scrollState.value > 0f -> Velocity.Zero
                            !listCannotScroll && available.y < 0f -> Velocity.Zero
                            else -> {
                                settle(available.y)
                                Velocity(0f, available.y)
                            }
                        }
                    }

                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        val listCannotScroll =
                            currentCapPx() >= restingPx() - 0.5f && scrollState.maxValue == 0
                        if (listCannotScroll && available.y > 0f) {
                            settle(available.y)
                        }
                        return Velocity.Zero
                    }
                }
            }

            AnimatedVisibility(
                visibleState = transitionState,
                enter = fadeIn(FoamMotion.Spec.sheetFadeIn),
                exit = fadeOut(FoamMotion.Spec.sheetFadeOut),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissRequest
                        )
                )
            }

            AnimatedVisibility(
                visibleState = transitionState,
                enter = slideInVertically(tween(FoamMotion.Duration.SMOOTH)) { fullHeight -> fullHeight },
                exit = slideOutVertically(tween(FoamMotion.Duration.STANDARD)) { fullHeight -> fullHeight },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalMargin)
                        .padding(bottom = bottomSpacing)
                        .layout { measurable, constraints ->
                            val capPx = (draggedCapPx ?: restingCapPx).toInt().coerceAtLeast(0)
                            val cappedConstraints = constraints.copy(
                                minHeight = 0,
                                maxHeight = minOf(constraints.maxHeight, capPx)
                            )
                            val placeable = measurable.measure(cappedConstraints)
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                        .clip(RoundedCornerShape(cornerRadius))
                        .foamSurface()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .nestedScroll(nestedScrollConnection)
                ) {
                    Column(
                        modifier = Modifier
                            .then(if (footer != null) Modifier.weight(1f, fill = false) else Modifier)
                            .verticalScroll(scrollState)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { contentHeightPx = it.height.toFloat() }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = FoamDimens.spacingSmall,
                                        bottom = FoamDimens.spacingTiny
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(32.dp)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(FoamTheme.colors.textSecondary)
                                )
                            }

                            Box(
                                modifier = Modifier.padding(
                                    horizontal = FoamDimens.paddingMedium,
                                    vertical = FoamDimens.paddingSmall
                                )
                            ) {
                                content()
                            }
                        }
                    }

                    if (footer != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { footerHeightPx = it.height.toFloat() }
                        ) {
                            footer()
                        }
                    }
                }
            }
        }
    }
}
