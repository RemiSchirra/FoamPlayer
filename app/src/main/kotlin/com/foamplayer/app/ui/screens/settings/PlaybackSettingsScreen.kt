package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.ui.components.FoamSliderStateItem
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Playback settings (subscreen of Settings > Audio):
 *  - crossfade at natural track transitions,
 *  - quick fades on play/pause/seek/skip,
 *  - the inter-track silence gap (disables the automatic crossfade while > 0).
 */
@Composable
fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    viewModel: AudioSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    val secondsTemplate = stringResource(R.string.duration_seconds)
    val secondsDecimalTemplate = stringResource(R.string.duration_seconds_decimal)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        SettingsHeader(
            title = stringResource(R.string.playback),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = FoamDimens.paddingMedium)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium),
            contentPadding = PaddingValues(bottom = LocalBottomBarClearance.current)
        ) {
            item {
                Spacer(modifier = Modifier.height(FoamDimens.spacingMedium))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
                        .background(FoamTheme.colors.surface)
                ) {
                    Column {
                        FoamSliderStateItem(
                            title = stringResource(R.string.crossfade),
                            description = stringResource(R.string.crossfade_desc),
                            initialValue = settings.normalFadeSeconds,
                            onValueChangeFinished = { viewModel.setNormalFadeSeconds(it) },
                            steps = 14, // integer seconds 0..15
                            valueRange = 0f..15f,
                            formatValue = { String.format(Locale.getDefault(), secondsTemplate, it.roundToInt()) },
                            minMaxDisplay = formatSeconds(secondsTemplate, 0) to formatSeconds(secondsTemplate, 15)
                        )
                        SectionDivider()
                        FoamSliderStateItem(
                            title = stringResource(R.string.quick_fade),
                            description = stringResource(R.string.quick_fade_desc),
                            initialValue = settings.quickFadeSeconds,
                            onValueChangeFinished = { viewModel.setQuickFadeSeconds(it) },
                            steps = 9, // 0.1 s increments 0..1
                            valueRange = 0f..1f,
                            formatValue = {
                                formatSecondsDecimal(secondsDecimalTemplate, it)
                            },
                            minMaxDisplay = formatSeconds(secondsTemplate, 0) to formatSeconds(secondsTemplate, 1)
                        )
                        SectionDivider()
                        FoamSliderStateItem(
                            title = stringResource(R.string.silence_between_tracks),
                            description = stringResource(R.string.silence_between_tracks_desc),
                            initialValue = settings.silenceBetweenTracksSeconds,
                            onValueChangeFinished = { viewModel.setSilenceBetweenTracksSeconds(it) },
                            steps = 9, // 0.5 s increments 0..5
                            valueRange = 0f..5f,
                            formatValue = {
                                formatSecondsDecimal(secondsDecimalTemplate, it)
                            },
                            minMaxDisplay = formatSeconds(secondsTemplate, 0) to formatSeconds(secondsTemplate, 5)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FoamDimens.paddingExtraLarge))
            }
        }
    }
}

/** "5 s" from the localized [template] ("%1$d s"). */
private fun formatSeconds(template: String, seconds: Int): String =
    String.format(Locale.getDefault(), template, seconds)

/** "0.5 s" from the localized decimal [template] ("%1$.1f s"). */
private fun formatSecondsDecimal(template: String, seconds: Float): String =
    String.format(Locale.getDefault(), template, seconds)

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(FoamTheme.colors.borderOverlay)
    )
}