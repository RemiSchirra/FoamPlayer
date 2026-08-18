package com.foamplayer.app.ui.screens.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.foamplayer.app.data.lyrics.LrcParser
import com.foamplayer.app.ui.theme.FoamTheme

private const val TOP_OFFSET = 0.25f

@Composable
fun LyricsScreen(
    rawLyrics: String?,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    backgroundColor: Color = FoamTheme.colors.background
) {
    val lrcLines = remember(rawLyrics) { LrcParser.parse(rawLyrics) }
    val listState = rememberLazyListState()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val activeIndex = remember(currentPositionMs, lrcLines) {
        lrcLines.indexOfLast { it.timestampMs <= currentPositionMs }
    }

    LaunchedEffect(activeIndex) {
        if (lrcLines.isNotEmpty()) {
            // Offset the active line 25% down the top of the viewport
            val viewportHeight = listState.layoutInfo.viewportSize.height
            val targetOffset = -(viewportHeight * TOP_OFFSET).toInt()
            val idx = if (activeIndex >= 0)activeIndex else 0
            listState.animateScrollToItem(idx, targetOffset)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (lrcLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = "No lyrics found.",
                        style = FoamTheme.typography.body
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewportHeightPx = it.height },
                    contentPadding = PaddingValues(
                        bottom = with(density) { (viewportHeightPx * (1 - TOP_OFFSET)).toInt().toDp() }
                    )
                ) {
                    itemsIndexed(lrcLines) { index, line ->
                        val isActive = index == activeIndex
                        BasicText(
                            text = line.text,
                            style = if (isActive) {
                                FoamTheme.typography.titleLarge.copy(color = FoamTheme.colors.primaryAccent)
                            } else {
                                FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSeek(line.timestampMs) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}