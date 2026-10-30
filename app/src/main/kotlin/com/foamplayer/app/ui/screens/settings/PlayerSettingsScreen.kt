package com.foamplayer.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.data.settings.MarqueeMode
import com.foamplayer.app.data.settings.SeekBarStyle
import com.foamplayer.app.ui.components.FoamEnumItem
import com.foamplayer.app.ui.components.FoamSliderStateItem
import com.foamplayer.app.ui.components.FoamSwitchItem
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.LocalMiniPlayerRoundingPreview
import com.foamplayer.app.ui.theme.foamBackground
import com.foamplayer.app.ui.theme.foamBorderOverlay
import com.foamplayer.app.ui.theme.foamSurface
import kotlin.math.roundToInt

@Composable
fun PlayerSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val isImmersiveEnabled = settings.immersiveModeEnabled
    val isAnimateSeekBarEnabled = settings.animateSeekBarEnabled
    val seekBarStyle = settings.seekBarStyle
    val isAutoOpenPlayerEnabled = settings.autoOpenPlayerEnabled
    val isAnimatedCoverEnabled = settings.animatedCoverEnabled
    val isAnimatedCoverAllowMobileData = settings.animatedCoverAllowMobileData
    val marqueeMode = settings.marqueeMode

    // Animations only play after the user toggles a switch, never on screen open
    // (even if the settings flow emits its stored value after the first frame).
    var animateChanges by remember { mutableStateOf(false) }
    val enterTransition = if (animateChanges) expandVertically() + fadeIn() else EnterTransition.None
    val exitTransition = if (animateChanges) shrinkVertically() + fadeOut() else ExitTransition.None

    // Slider min/max labels ("Square"/"Circle") resolve once per composition.
    val roundingMin = stringResource(R.string.square)
    val roundingMax = stringResource(R.string.circle)

    val roundingPreview = LocalMiniPlayerRoundingPreview.current
    DisposableEffect(Unit) {
        onDispose { roundingPreview.clear() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .foamBackground()
    ) {
        SettingsHeader(
            title = stringResource(R.string.player),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
                ) {
                    BasicText(text = stringResource(R.string.behaviour), style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .foamSurface()
                    ) {
                        FoamSwitchItem(
                            title = stringResource(R.string.auto_open_player),
                            description = stringResource(R.string.auto_open_player_desc),
                            checked = isAutoOpenPlayerEnabled,
                            onCheckedChange = { viewModel.setAutoOpenPlayerEnabled(it) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
                ) {
                    BasicText(text = stringResource(R.string.visuals), style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .foamSurface()
                    ) {
                        Column {
                            FoamSwitchItem(
                                title = stringResource(R.string.immersive_mode),
                                description = stringResource(R.string.immersive_mode_desc),
                                checked = isImmersiveEnabled,
                                onCheckedChange = {
                                    animateChanges = true
                                    viewModel.setImmersiveModeEnabled(it)
                                }
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .foamBorderOverlay()
                            )
                            FoamSwitchItem(
                                title = stringResource(R.string.animate_seek_bar),
                                description = stringResource(R.string.animate_seek_bar_desc),
                                checked = isAnimateSeekBarEnabled,
                                onCheckedChange = { viewModel.setAnimateSeekBarEnabled(it) }
                            )
                            AnimatedVisibility(
                                visible = isImmersiveEnabled,
                                enter = enterTransition,
                                exit = exitTransition
                            ) {
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(FoamDimens.separatorHeight)
                                            .foamBorderOverlay()
                                    )
                                    FoamSwitchItem(
                                        title = stringResource(R.string.animated_cover),
                                        description = stringResource(R.string.animated_cover_desc),
                                        checked = isAnimatedCoverEnabled,
                                        onCheckedChange = {
                                            animateChanges = true
                                            viewModel.setAnimatedCoverEnabled(it)
                                        }
                                    )
                                    AnimatedVisibility(
                                        visible = isAnimatedCoverEnabled,
                                        enter = enterTransition,
                                        exit = exitTransition
                                    ) {
                                        Column {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(FoamDimens.separatorHeight)
                                                    .foamBorderOverlay()
                                            )
                                            FoamSwitchItem(
                                                title = stringResource(R.string.animated_cover_mobile_data),
                                                description = stringResource(R.string.animated_cover_mobile_data_desc),
                                                checked = isAnimatedCoverAllowMobileData,
                                                onCheckedChange = { viewModel.setAnimatedCoverAllowMobileData(it) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                FoamEnumItem(
                    title = stringResource(R.string.seek_bar_style),
                    description = stringResource(R.string.seek_bar_style_desc),
                    options = SeekBarStyle.entries.toTypedArray(),
                    selectedOption = seekBarStyle,
                    onOptionSelected = { viewModel.setSeekBarStyle(it) },
                    getLabel = { style ->
                        when (style) {
                            SeekBarStyle.NORMAL -> stringResource(R.string.seek_bar_style_normal)
                            SeekBarStyle.WAVEFORM -> stringResource(R.string.seek_bar_style_waveform)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                FoamEnumItem(
                    title = stringResource(R.string.marquee_title),
                    description = stringResource(R.string.marquee_desc),
                    options = MarqueeMode.entries.toTypedArray(),
                    selectedOption = marqueeMode,
                    onOptionSelected = { viewModel.setMarqueeMode(it) },
                    getLabel = { it.label }
                )
                Spacer(modifier = Modifier.height(FoamDimens.spacingLarge))
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
                ) {
                    BasicText(text = stringResource(R.string.interface_rounding), style = FoamTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                            .foamSurface()
                    ) {
                        Column {
                            FoamSliderStateItem(
                                title = stringResource(R.string.mini_player),
                                initialValue = settings.miniPlayerRounding.toFloat(),
                                onValueChangeFinished = {
                                    viewModel.setMiniPlayerRounding(it.roundToInt())
                                },
                                onValueChange = { roundingPreview.miniPlayerRounding = it.roundToInt() },
                                steps = 9,
                                valueRange = 0f..100f,
                                formatValue = { "${it.roundToInt()}%" },
                                minMaxDisplay = roundingMin to roundingMax
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FoamDimens.separatorHeight)
                                    .foamBorderOverlay()
                            )
                            FoamSliderStateItem(
                                title = stringResource(R.string.full_player),
                                initialValue = settings.fullPlayerRounding.toFloat(),
                                onValueChangeFinished = {
                                    viewModel.setFullPlayerRounding(it.roundToInt())
                                },
                                steps = 9,
                                valueRange = 0f..100f,
                                formatValue = { "${it.roundToInt()}%" },
                                minMaxDisplay = roundingMin to roundingMax
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.spacingExtraLarge))
            }
        }
    }
}