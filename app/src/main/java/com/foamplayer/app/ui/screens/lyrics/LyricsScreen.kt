package com.foamplayer.app.ui.screens.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.ui.theme.FoamTheme

@Composable
fun LyricsScreen(
    viewModel: LyricsViewModel = hiltViewModel(),
    backgroundColor: Color = FoamTheme.colors.background
) {
    val rawLyrics by viewModel.lyrics.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = FoamTheme.accentColor
            )
        } else {
            LyricsEnhanced(
                sliderPositionProvider = { null },
                lyricsSyncOffset = 0,
                currentPositionMsProvider = { viewModel.currentPositionMs.value },
                isPlaying = isPlaying,
                onSeek = viewModel::seekTo,
                lyrics = rawLyrics,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}