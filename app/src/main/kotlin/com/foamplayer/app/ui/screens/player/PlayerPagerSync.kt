package com.foamplayer.app.ui.screens.player

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
    latestQueueIndex: State<Int>,
    latestQueueSize: State<Int>,
    latestHeroModel: State<LoopingPagerModel>,
    seekBarProgress: SeekBarProgressState,
    envelopeMorphProgress: () -> Float,
    lastBackwardSkipMs: LongArray,
    markSkipIntent: (Boolean) -> Unit,
    backdropRenumberHold: () -> Boolean,
    isMainPagerVisible: () -> Boolean,
    hopOffGhost: suspend () -> Unit
) {
    if (isProgressiveEnvelope) {
        val isPagerDragged by pagerState.interactionSource.collectIsDraggedAsState()
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

    LaunchedEffect(pagerState) {
        var wrapRequestedReal = -1
        var wrapRequestedAtMs = 0L
        snapshotFlow { pagerState.settledPage }
            .collect { settledPage ->
                // A settled page read mid-renumbering is a stale number, not a user landing.
                if (backdropRenumberHold()) return@collect
                val model = latestHeroModel.value
                if (model.isGhostPage(settledPage)) {
                    if (isMainPagerVisible() &&
                        model.ghostTwinPage(settledPage) != latestQueueIndex.value
                    ) {
                        wrapRequestedReal = model.realIndexOf(settledPage)
                        wrapRequestedAtMs = System.currentTimeMillis()
                        seekBarProgress.onTrackChangeApplied()
                        markSkipIntent(!model.isTrailingGhost(settledPage))
                        viewModel.skipToIndex(
                            wrapRequestedReal,
                            forward = model.isTrailingGhost(settledPage)
                        )
                    }
                    return@collect
                }
                val alreadyRequested = wrapRequestedReal >= 0 &&
                        System.currentTimeMillis() - wrapRequestedAtMs < 2_000L &&
                        model.realIndexOf(settledPage) == wrapRequestedReal
                wrapRequestedReal = -1
                if (alreadyRequested) return@collect

                if (isMainPagerVisible() &&
                    settledPage in 0 until latestQueueSize.value &&
                    settledPage != latestQueueIndex.value
                ) {
                    seekBarProgress.onTrackChangeApplied()
                    viewModel.skipToIndex(
                        model.realIndexOf(settledPage),
                        forward = settledPage > latestQueueIndex.value
                    )
                } else if (settledPage == latestQueueIndex.value) {
                    seekBarProgress.revertTrackChangeEnvelope()
                }
            }
    }

    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) viewModel.prepareQueueShuffle()
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
