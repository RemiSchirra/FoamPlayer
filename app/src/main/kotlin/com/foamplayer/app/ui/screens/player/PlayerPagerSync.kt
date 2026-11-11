package com.foamplayer.app.ui.screens.player

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.foamplayer.app.data.settings.PlayerPagerEffect
import com.foamplayer.app.ui.components.LoopingPagerModel
import com.foamplayer.app.ui.components.SeekBarProgressState
import kotlin.math.abs

/** Pager/envelope synchronisation effects of [FullPlayerScreen]. */
@Composable
internal fun PlayerPagerSyncEffects(
    pagerState: PagerState,
    viewModel: PlayerViewModel,
    isProgressiveEnvelope: Boolean,
    queueIndex: Int,
    currentEntryId: Long?,
    pagerEffect: PlayerPagerEffect,
    latestQueueIndex: State<Int>,
    latestQueueSize: State<Int>,
    latestHeroModel: State<LoopingPagerModel>,
    seekBarProgress: SeekBarProgressState,
    envelopeMorphProgress: () -> Float,
    lastBackwardSkipMs: LongArray,
    markSkipIntent: (Boolean) -> Unit,
    backdropRenumberHold: () -> Boolean,
    isMainPagerVisible: () -> Boolean,
    programmaticScrollPage: MutableState<Int?>,
    pagerFlingTarget: MutableState<Int?>,
    hopOffGhost: suspend () -> Unit
) {
    val isPagerDragged by pagerState.interactionSource.collectIsDraggedAsState()
    val pendingSkip = remember { PendingSkip() }

    if (isProgressiveEnvelope) {
        LaunchedEffect(pagerState) {
            snapshotFlow {
                Triple(isPagerDragged, pagerState.targetPage, pagerState.currentPageOffsetFraction)
            }.collect { (dragging, targetPage, offsetFraction) ->
                val playingPage = latestQueueIndex.value
                if (dragging) {
                    val dragNeighbor = when {
                        offsetFraction > 0f -> pagerState.currentPage + 1
                        offsetFraction < 0f -> pagerState.currentPage - 1
                        else -> null
                    }
                    val pinned =
                        if (targetPage != playingPage && targetPage in 0 until latestQueueSize.value) {
                            targetPage
                        } else {
                            dragNeighbor
                        }
                    if (pinned != null && pinned != playingPage && pinned in 0 until latestQueueSize.value) {
                        val absolute = pagerState.currentPage + offsetFraction
                        val fromPage =
                            if (abs(absolute - pinned) <= abs(absolute - playingPage)) {
                                seekBarProgress.envelopeTargetPage
                            } else {
                                null
                            }
                        seekBarProgress.onSwipeEnvelopeTarget(pinned, fromPage)
                    }
                } else if (targetPage == playingPage) {
                    seekBarProgress.settleSwipeEnvelopeBack(envelopeMorphProgress())
                }
            }
        }
    }

    var previousQueueIndex by remember { mutableStateOf(queueIndex) }
    var previousEnvelopeEntryId by remember { mutableStateOf(currentEntryId) }
    LaunchedEffect(queueIndex, currentEntryId) {
        if (currentEntryId != previousEnvelopeEntryId) {
            val landedPage = latestHeroModel.value.wrapTransitPage(
                fromPage = previousQueueIndex,
                toPage = queueIndex,
                backward = System.currentTimeMillis() - lastBackwardSkipMs[0] < SKIP_INTENT_WINDOW_MS
            ) ?: queueIndex
            seekBarProgress.onPlayingIndexChanged(landedPage, previousQueueIndex)
        }
        previousQueueIndex = queueIndex
        previousEnvelopeEntryId = currentEntryId
    }

    fun requestSkip(model: LoopingPagerModel, page: Int) {
        val real = model.realIndexOf(page)
        val forward = if (model.isGhostPage(page)) {
            markSkipIntent(!model.isTrailingGhost(page))
            model.isTrailingGhost(page)
        } else {
            page > latestQueueIndex.value
        }
        pendingSkip.record(real)
        seekBarProgress.onTrackChangeApplied()
        viewModel.skipToIndex(real, forward = forward)
    }

    // Commit on release: the track must not wait for the pager's settle animation.
    LaunchedEffect(pagerState, pagerEffect) {
        snapshotFlow {
            Triple(isPagerDragged, pagerState.targetPage, pagerFlingTarget.value)
        }
            .collect { (dragging, stockTargetPage, effectTargetPage) ->
                if (dragging ||
                    !isMainPagerVisible() ||
                    backdropRenumberHold() ||
                    programmaticScrollPage.value != null
                ) return@collect
                // The shortened-stride pager publishes the exact page it settles on
                // (see PlayerPagerEffectFlingBehavior); wait for it rather than trusting
                // the stock target, whose positional threshold assumes a full page.
                val targetPage = if (pagerEffect == PlayerPagerEffect.SLIDE) {
                    stockTargetPage
                } else {
                    effectTargetPage ?: return@collect
                }
                if (targetPage !in 0 until latestQueueSize.value) return@collect
                // Consume the published target so a later renumbering cannot replay it.
                if (pagerEffect != PlayerPagerEffect.SLIDE) pagerFlingTarget.value = null
                val model = latestHeroModel.value
                val isNewTrack = if (model.isGhostPage(targetPage)) {
                    model.ghostTwinPage(targetPage) != latestQueueIndex.value
                } else {
                    targetPage != latestQueueIndex.value
                }
                if (isNewTrack && !pendingSkip.matches(model.realIndexOf(targetPage))) {
                    requestSkip(model, targetPage)
                }
            }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { settledPage ->
                // The fling is over; its published target is stale from here on.
                pagerFlingTarget.value = null
                if (programmaticScrollPage.value != null) {
                    programmaticScrollPage.value = null
                    if (settledPage == latestQueueIndex.value) {
                        seekBarProgress.revertTrackChangeEnvelope()
                    }
                    return@collect
                }
                // A settled page read mid-renumbering is a stale number, not a user landing.
                if (backdropRenumberHold()) return@collect
                val model = latestHeroModel.value
                if (model.isGhostPage(settledPage)) {
                    if (isMainPagerVisible() &&
                        model.ghostTwinPage(settledPage) != latestQueueIndex.value &&
                        !pendingSkip.matches(model.realIndexOf(settledPage))
                    ) {
                        requestSkip(model, settledPage)
                    }
                    return@collect
                }
                val alreadyRequested = pendingSkip.matches(model.realIndexOf(settledPage))
                pendingSkip.clear()
                if (alreadyRequested) return@collect

                if (isMainPagerVisible() &&
                    settledPage in 0 until latestQueueSize.value &&
                    settledPage != latestQueueIndex.value
                ) {
                    requestSkip(model, settledPage)
                } else if (settledPage == latestQueueIndex.value) {
                    seekBarProgress.revertTrackChangeEnvelope()
                }
            }
    }

    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    programmaticScrollPage.value = null
                    pagerFlingTarget.value = null
                    viewModel.prepareQueueShuffle()
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    programmaticScrollPage.value = null
                }
            }
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow {
            !backdropRenumberHold() &&
                    !pagerState.isScrollInProgress &&
                    !latestHeroModel.value.isGhostPage(pagerState.currentPage) &&
                    pagerState.currentPage == latestQueueIndex.value
        }.collect { rested -> if (rested) viewModel.onHeroPagerRested() }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow {
            val model = latestHeroModel.value
            if (!backdropRenumberHold() && !pagerState.isScrollInProgress &&
                model.isGhostPage(pagerState.currentPage)
            ) {
                pagerState.currentPage
            } else {
                -1
            }
        }.collect { ghostPage ->
            if (ghostPage >= 0) hopOffGhost()
        }
    }
}

/** Last skip requested by the pager, so the settle handler does not request it twice. */
private class PendingSkip {
    private var real = -1
    private var atMs = 0L

    fun record(realIndex: Int) {
        real = realIndex
        atMs = System.currentTimeMillis()
    }

    fun matches(realIndex: Int) =
        real >= 0 && realIndex == real && System.currentTimeMillis() - atMs < 2_000L

    fun clear() {
        real = -1
    }
}