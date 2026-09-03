package com.foamplayer.app.ui.screens.equalizer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.components.FoamSlider
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = "Equalizer & DSP",
                style = FoamTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (state.enabled) FoamTheme.colors.primaryAccent
                        else FoamTheme.colors.surfaceVariant
                    )
                    .clickable { viewModel.toggleEqualizer(!state.enabled) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                BasicText(
                    text = if (state.enabled) "ON" else "OFF",
                    style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.colors.background)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state.enabled) {
            FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                BasicText(
                    text = "⚠️ Notice: Equalizer active. Audio path is operating in high-precision software DSP fallback mode (Bit-perfect bypassed).",
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.bitPerfectInactive)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            state.presets.forEach { preset ->
                val selected = state.selectedPreset == preset
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) FoamTheme.colors.primaryAccent
                            else FoamTheme.colors.surfaceVariant
                        )
                        .clickable { viewModel.selectPreset(preset) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    BasicText(
                        text = preset,
                        style = FoamTheme.typography.caption.copy(
                            color = if (selected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        state.bands.forEachIndexed { index, gainMb ->
            val freqHz = state.bandFrequencies.getOrElse(index) { 1000 }
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BasicText(
                        text = if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz",
                        style = FoamTheme.typography.body
                    )
                    BasicText(
                        text = "${gainMb / 100} dB",
                        style = FoamTheme.typography.monospace
                    )
                }
                FoamSlider(
                    value = (gainMb + 1500) / 3000f,
                    onValueChange = { normVal ->
                        val newGain = (normVal * 3000 - 1500).toInt()
                        viewModel.setBandGain(index, newGain)
                    }
                )
            }
        }
    }
}
