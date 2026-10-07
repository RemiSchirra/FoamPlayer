package com.foamplayer.app.ui.modals

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import android.view.WindowManager
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
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
    content: @Composable () -> Unit
) {
    // Keep the Dialog mounted while the exit animation plays. currentState only
    // catches up with targetState once AnimatedVisibility finishes animating out,
    // so this is what lets the sheet slide/fade away instead of just vanishing.
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = visible

    if (!transitionState.currentState && !transitionState.targetState) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // The platform Dialog window dims the screen on its own by default. Left
        // alone, that shows up as an instant half-dim UNDER our own animated
        // scrim fade -- i.e. the fade appears to happen "twice". Kill the native
        // dim/animation and let our own scrim be the only thing drawing it.
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                window.setWindowAnimations(0)
                // Keep the sheet clear of the soft keyboard: on API 30+ the
                // window reports IME insets we pad with below; on older APIs
                // this resize mode keeps the sheet above the keyboard.
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            // Lift the sheet above the soft keyboard whenever it is visible
            // (the larger of the two wins so the nav bar is never double-counted).
            // Sheets hosting another focusable window on top (a name-input dialog)
            // can opt out: the keyboard belongs to that other window, and padding
            // for it here would shove this sheet up in lock-step with its typing.
            val imeInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                .takeIf { liftAboveKeyboard } ?: 0.dp
            val bottomSpacing = maxOf(bottomInset, imeInset) + bottomEdgeMargin

            val fullPx = with(density) {
                (maxHeight - topInset - topEdgeMargin - bottomSpacing).coerceAtLeast(0.dp).toPx()
            }
            val scrollState = rememberScrollState()

            // Natural height of the whole sheet interior (drag handle + content),
            // measured off the scroll child, which lays out unbounded and so
            // reports the full height even while the sheet clips it. 0 until the
            // first layout pass after (re)opening.
            var contentHeightPx by remember { mutableFloatStateOf(0f) }

            // Size the sheet rests at: exactly its content, capped just short of
            // the top. Starts at full for the first frame or two so nothing is
            // ever shown clipped; the measured fit takes over while the sheet is
            // still rising out of its slide-in.
            val restingCapPx = if (contentHeightPx > 0f) minOf(contentHeightPx, fullPx) else fullPx

            // Non-null while the user is dragging the sheet away from its resting
            // size (drag-down-to-dismiss and drag-back-up); null means "fit the
            // content".
            var draggedCapPx by remember { mutableStateOf<Float?>(null) }

            // A drag-dismiss springs the cap down to 0 (see settle() below); clear
            // the drag override on re-open so the sheet fits its content again.
            LaunchedEffect(visible) {
                if (visible) draggedCapPx = null
            }

            // NOTE: neither the cap nor the scrim alpha may be read in composition.
            // Writing draggedCapPx on every gesture frame used to recompose this whole
            // scope -- scrim, both AnimatedVisibilitys, and content() -- per frame.
            // Both reads are deferred instead: the cap into the layout modifier below,
            // the scrim alpha into a drawBehind. Composition then only re-runs when
            // the resting size actually changes (content measured, keyboard in/out).

            // Live proxies so the remembered connection below always reads the
            // current callback and available height even though it is created
            // only once (fullPx now varies while the soft keyboard opens/closes).
            val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
            val latestFullPx by rememberUpdatedState(fullPx)

            val nestedScrollConnection = remember {
                object : NestedScrollConnection {

                    // Everything here is recomputed from live state on every
                    // gesture event: this object is remembered across
                    // recompositions, so plain locals captured at creation time
                    // (contentHeightPx was still 0 then) would be stale and break
                    // the drag math. contentHeightPx / draggedCapPx are state
                    // delegates, so they read live; fullPx is read through the
                    // rememberUpdatedState proxy above.
                    fun restingPx(): Float =
                        if (contentHeightPx > 0f) minOf(contentHeightPx, latestFullPx) else latestFullPx

                    fun currentCapPx(): Float = draggedCapPx ?: restingPx()

                    suspend fun settle(flingVelocityY: Float) {
                        val resting = restingPx()
                        val anchors = floatArrayOf(0f, resting)
                        val projected = (currentCapPx() - flingVelocityY * 0.15f).coerceIn(0f, resting)
                        val target = anchors.minByOrNull { abs(it - projected) } ?: resting
                        // Always spring the cap the rest of the way, including down
                        // to 0. This keeps the motion on a single continuous,
                        // velocity-matched curve -- switching mid-drag to the
                        // Dialog's fixed-duration exit transition instead is what
                        // caused the visible speed "jump" on release.
                        animate(
                            initialValue = currentCapPx(),
                            targetValue = target,
                            initialVelocity = -flingVelocityY,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) { value, _ -> draggedCapPx = value }
                        if (target <= 0f) {
                            // The cap has already sprung to 0 by this point, so the
                            // sheet is visually gone and the scrim (tied to the cap)
                            // has already faded out. Keep the drag override at 0
                            // while the exit animation plays -- clearing it here
                            // would flash the sheet back to full size -- and the
                            // `visible` effect clears it on the next open. This
                            // call just tears down the Dialog itself.
                            latestOnDismissRequest()
                        } else {
                            // Back at the fitted size: hand control back to
                            // auto-fit so a later content change (e.g. a picker
                            // list growing) applies.
                            draggedCapPx = null
                        }
                    }

                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        val dy = available.y
                        // Dragging up after a shrink: restore toward the fitted
                        // size, but only while the content is still clipped --
                        // never grow the sheet into empty space.
                        if (dy < 0f) {
                            val room = restingPx() - currentCapPx()
                            if (room > 0f) {
                                val consumed = if (-dy < room) dy else -room
                                draggedCapPx = currentCapPx() - consumed
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
                        val dy = available.y
                        // Dragging down and the content had nothing left to give
                        // (already at its top): shrink the sheet instead, bottom
                        // edge staying docked while the top follows the finger.
                        if (dy > 0f) {
                            val cap = currentCapPx()
                            if (cap > 0f) {
                                val consumed = if (dy < cap) dy else cap
                                draggedCapPx = cap - consumed
                                return Offset(0f, consumed)
                            }
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        val restingMidContent =
                            currentCapPx() >= restingPx() - 0.5f && scrollState.value > 0
                        return if (restingMidContent) {
                            Velocity.Zero
                        } else {
                            settle(available.y)
                            Velocity(0f, available.y)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visibleState = transitionState,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Scrim darkness tracks the sheet's live size instead of only the
                        // open/closed boolean, so dragging the sheet down progressively
                        // fades the background. Read in the draw phase: no recomposition
                        // per drag frame.
                        .drawBehind {
                            val cap = draggedCapPx ?: restingCapPx
                            val alpha = (cap / restingCapPx.coerceAtLeast(1f)).coerceIn(0f, 1f) * 0.55f
                            drawRect(Color.Black.copy(alpha = alpha))
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissRequest
                        )
                )
            }

            AnimatedVisibility(
                visibleState = transitionState,
                enter = slideInVertically(tween(280)) { fullHeight -> fullHeight },
                exit = slideOutVertically(tween(220)) { fullHeight -> fullHeight },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalMargin)
                        // Float above the navigation bars so the sheet's rounded
                        // bottom edge stays visible at the bottom of the screen.
                        .padding(bottom = bottomSpacing)
                        // Live height cap (replaces heightIn(max = cap)): draggedCapPx is
                        // read inside the measure block, so drag frames re-measure only
                        // this chain instead of recomposing the dialog's contents.
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
                        .background(FoamTheme.colors.surface)
                        // Swallow taps here so they don't fall through to the scrim below.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .nestedScroll(nestedScrollConnection)
                        // With a bounded max height this only kicks in once content
                        // actually overflows what capPx currently allows.
                        .verticalScroll(scrollState)
                ) {
                    // Measured to derive the sheet's fitted size. The scroll child
                    // lays out unbounded, so this reports the full natural height
                    // even while the sheet is clipped smaller.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { contentHeightPx = it.height.toFloat() }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = FoamDimens.spacingSmall, bottom = FoamDimens.spacingTiny),
                            contentAlignment = Alignment.Center
                        ) {
                            // Single horizontal grab bar, drawn directly so it stays
                            // independent of the icon style setting.
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
            }
        }
    }
}
