package com.foamplayer.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.R
import com.foamplayer.app.audio.dsp.EqBandLayout
import com.foamplayer.app.ui.components.FoamEnumItem
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamBackground

/**
 * Equalizer settings (subscreen of Settings > Audio): the ISO band layout of
 * the graphic EQ, chosen as a chip enum. The count applies to the Equalizer
 * screen's fader strip and resamples the curve so the audible response
 * survives the layout change.
 */
@Composable
fun EqualizerSettingsScreen(
    onBack: () -> Unit,
    viewModel: AudioSettingsViewModel = hiltViewModel()
) {
    val dspState by viewModel.dspState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .foamBackground()
    ) {
        SettingsHeader(
            title = stringResource(R.string.equalizer_dsp),
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
                FoamEnumItem(
                    title = stringResource(R.string.eq_band_layout),
                    description = stringResource(R.string.eq_band_layout_desc),
                    options = EqBandLayout.entries.toTypedArray(),
                    selectedOption = EqBandLayout.nearest(dspState.graphicEq.count),
                    onOptionSelected = { viewModel.setEqBandLayout(it) },
                    getLabel = { layout -> layout.bands.toString() }
                )
                Spacer(modifier = Modifier.height(FoamDimens.paddingExtraLarge))
            }
        }
    }
}
