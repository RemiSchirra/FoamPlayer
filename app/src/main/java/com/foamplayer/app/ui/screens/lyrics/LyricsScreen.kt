package com.foamplayer.app.ui.screens.lyrics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.foamplayer.app.lyrics.LYRICS_NOT_FOUND
import com.foamplayer.app.ui.theme.FoamTheme

private enum class LyricsViewState { LOADING, INSTRUMENTAL, NO_LYRICS, CONTENT }

@Composable
fun LyricsScreen(
    viewModel: LyricsViewModel = hiltViewModel(),
    backgroundColor: Color = FoamTheme.colors.background,
    // Vertical collapse-drag for the states that render no scrollable (loading,
    // instrumental, "Lyrics not found"): nested-scroll overscroll from the lyrics
    // list is what powers swipe-back-to-full-player, and these states have no list
    // to produce it, so they need the drag wired directly.
    dragModifier: Modifier = Modifier
) {
    val rawLyrics by viewModel.lyrics.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isInstrumental by viewModel.isInstrumental.collectAsState()
    val lyricsLineBlur by viewModel.lyricsLineBlur.collectAsState()

    // Handled here (not inside LyricsEnhanced) so EVERY path that ends without
    // lyrics — provider miss (null), the LYRICS_NOT_FOUND sentinel, or a blank
    // result — shows the instrumental shortcut.
    val showNoLyrics = rawLyrics == null || rawLyrics == LYRICS_NOT_FOUND || rawLyrics?.isBlank() == true

    val viewState = when {
        isLoading && !isInstrumental -> LyricsViewState.LOADING
        isInstrumental -> LyricsViewState.INSTRUMENTAL
        showNoLyrics -> LyricsViewState.NO_LYRICS
        else -> LyricsViewState.CONTENT
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        AnimatedContent(
            targetState = viewState,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (targetState == LyricsViewState.INSTRUMENTAL) {
                    // Marking instrumental: the texts shrink away while the
                    // music note pops in.
                    (scaleIn(
                        initialScale = 0.3f,
                        animationSpec = tween()
                    ) + fadeIn(animationSpec = tween(150))) togetherWith
                            (scaleOut(
                                targetScale = 0.7f,
                                animationSpec = tween(250, easing = FastOutSlowInEasing)
                            ) + fadeOut(animationSpec = tween(200)))
                } else {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                }
            },
            label = "LyricsViewState"
        ) { state ->
            // Full-size centering container per state: every child (spinner,
            // icon, texts) is centered on screen regardless of its own size.
            // The collapse drag only attaches to non-scrollable states -- CONTENT
            // keeps relying on nested-scroll overscroll from the lyrics list.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (state != LyricsViewState.CONTENT) dragModifier else Modifier),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    LyricsViewState.LOADING -> {
                        CircularProgressIndicator(color = FoamTheme.accentColor)
                    }

                    LyricsViewState.INSTRUMENTAL -> {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = "Instrumental",
                            tint = FoamTheme.colors.textPrimary.copy(alpha = 0.6f),
                            modifier = Modifier.size(72.dp)
                        )
                    }

                    LyricsViewState.NO_LYRICS -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Lyrics not found",
                                style = FoamTheme.typography.titleMedium,
                                color = FoamTheme.colors.textPrimary.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Mark song as instrumental?",
                                style = FoamTheme.typography.body,
                                color = FoamTheme.accentColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.clickable(onClick = viewModel::markCurrentTrackInstrumental)
                            )
                        }
                    }

                    LyricsViewState.CONTENT -> {
                        LyricsEnhanced(
                            sliderPositionProvider = { null },
                            lyricsSyncOffset = 0,
                            currentPositionMsProvider = { viewModel.currentPositionMs.value },
                            isPlaying = isPlaying,
                            onSeek = viewModel::seekTo,
                            lyricsLineBlurOverride = lyricsLineBlur,
                            lyrics = rawLyrics,
                            dragModifier = dragModifier,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}