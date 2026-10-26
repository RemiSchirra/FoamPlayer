package com.foamplayer.app.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.foamplayer.app.R
import com.foamplayer.app.audio.engine.RepeatMode
import com.foamplayer.app.ui.components.PreviousAction
import com.foamplayer.app.ui.components.SeekBarProgressState
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * The control row under the player content: the lyrics/queue selector plus the mini transport
 * cluster, both riding the subview transition (`subControlsProgress`) and the navbar's extra-row
 * growth (`navbarExpandHeight`).
 *
 * Split out of `FullPlayerScreen` so the screen body stays a layout; everything here is driven by
 * the progress providers it is handed, so no player state is read during composition here.
 */
@Composable
internal fun PlayerBottomControls(
    viewModel: PlayerViewModel,
    subViewMode: SubViewMode,
    onSelectSubView: (SubViewMode) -> Unit,
    repeatMode: RepeatMode,
    isShuffle: Boolean,
    onPlayOnClick: () -> Unit,
    uiAlpha: () -> Float,
    subControlsProgress: () -> Float,
    navbarExpandHeight: () -> Float,
    isPlayingProvider: () -> Boolean,
    isLoadingProvider: () -> Boolean,
    seekBarProgress: SeekBarProgressState,
    settleSeekBarAnimated: Boolean,
    isWaveformSeek: Boolean,
    queueIndex: Int,
    queueSize: Int,
    envelopeMorphProgress: () -> Float,
    markSkipIntent: (Boolean) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        val latestNavbarExpandHeight = rememberUpdatedState(navbarExpandHeight)
        val latestSubControlsProgress = rememberUpdatedState(subControlsProgress)

        val selectorRowModifier = remember(subControlsProgress, uiAlpha) {
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PlayerDimens.screenPadding,
                    end = PlayerDimens.screenPadding,
                    top = PlayerDimens.screenPadding * 2 - PlayerDimens.selectorRowBottomPadding,
                    bottom = PlayerDimens.selectorRowBottomPadding
                )
                .graphicsLayer {
                    alpha = uiAlpha()
                    val progress = latestSubControlsProgress.value()
                    translationY = PlayerDimens.selectorRowNavbarDrop.toPx() * (1f - progress) -
                            PlayerDimens.selectorRowRise.toPx() * progress -
                            latestNavbarExpandHeight.value()
                }
        }

        SubViewSelectorRow(
            subViewMode = subViewMode,
            repeatMode = repeatMode,
            isShuffle = isShuffle,
            progress = subControlsProgress,
            onSelect = onSelectSubView,
            onShuffleClick = { viewModel.toggleShuffle() },
            onPlayOnClick = onPlayOnClick,
            onRepeatClick = { viewModel.toggleRepeat() },
            modifier = selectorRowModifier
        )

        // Compose the transport cluster only while it is (becoming) visible. At rest it
        // is alpha 0 but still sits on top of the shuffle / radio / Play on / repeat buttons
        // (same Box, composed later, ~162dp wide centered), and its disabled
        // clickables still take part in hit-testing, swallowing taps meant for them.
        val showTransportCluster by remember(subControlsProgress) {
            derivedStateOf { subControlsProgress() > PlayerDimens.visibilityEpsilon }
        }

        val transportClusterModifier = remember(subControlsProgress) {
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = PlayerDimens.selectorRowBottomPadding)
                .height(PlayerDimens.miniPlayButtonSize)
                .graphicsLayer {
                    translationY = -(PlayerDimens.playButtonRise + PlayerDimens.transportBottomInset).toPx() *
                            latestSubControlsProgress.value() -
                            latestNavbarExpandHeight.value()
                }
        }

        if (showTransportCluster) {
            Row(
                modifier = transportClusterModifier,
                horizontalArrangement = Arrangement.spacedBy(PlayerDimens.transportSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniTransportButton(
                    icon = FoamTheme.icons.current.previous,
                    contentDescription = stringResource(R.string.previous),
                    progress = subControlsProgress,
                    onClick = {
                        when (
                            seekBarProgress.onPreviousPressed(
                                animated = settleSeekBarAnimated,
                                positionMs = viewModel.positionMs.value,
                                morphTargetPage = if (isWaveformSeek) {
                                    (queueIndex - 1).takeIf { it >= 0 }
                                } else {
                                    null
                                },
                                fromBlend = envelopeMorphProgress(),
                                fromPageIndex = queueIndex
                            )
                        ) {
                            PreviousAction.RESTART_CURRENT -> viewModel.seekTo(0L)
                            PreviousAction.SKIP_TO_PREVIOUS -> {
                                markSkipIntent(true)
                                viewModel.previous()
                            }
                        }
                    }
                )

                MiniPlayPauseButton(
                    isPlaying = isPlayingProvider,
                    isLoading = isLoadingProvider,
                    progress = subControlsProgress,
                    onClick = { if (isPlayingProvider()) viewModel.pause() else viewModel.play() }
                )

                MiniTransportButton(
                    icon = FoamTheme.icons.current.next,
                    contentDescription = stringResource(R.string.next),
                    progress = subControlsProgress,
                    onClick = {
                        seekBarProgress.onNextPressed(
                            animated = settleSeekBarAnimated,
                            morphTargetPage = if (isWaveformSeek) {
                                (queueIndex + 1).takeIf { it < queueSize }
                            } else {
                                null
                            },
                            fromBlend = envelopeMorphProgress(),
                            fromPageIndex = queueIndex
                        )
                        markSkipIntent(false)
                        viewModel.next()
                    }
                )
            }
        }
    }
}
