package com.foamplayer.app.ui.screens.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.foamplayer.app.R
import com.foamplayer.app.lyrics.LYRICS_NOT_FOUND
import com.foamplayer.app.lyrics.LyricsEntry
import com.foamplayer.app.lyrics.LyricsRomanizationPreferences
import com.foamplayer.app.lyrics.LyricsUtils.isLineSyncedLrc
import com.foamplayer.app.lyrics.LyricsUtils.isTtml
import com.foamplayer.app.lyrics.LyricsUtils.parseLyrics
import com.foamplayer.app.lyrics.LyricsUtils.parseTtml
import com.foamplayer.app.lyrics.LyricsUtils.providedRomanizedTextForEntry
import com.foamplayer.app.lyrics.LyricsUtils.providedRomanizedWordsForEntry
import com.foamplayer.app.lyrics.LyricsUtils.providedTranslationTextForEntry
import com.foamplayer.app.lyrics.LyricsUtils.romanizeLyricsLine
import com.foamplayer.app.lyrics.LyricsUtils.romanizeLyricsWordWithLineContext
import com.foamplayer.app.lyrics.LyricsUtils.shouldRomanizeLyricsLine
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.withContext
import java.text.BreakIterator
import kotlin.coroutines.cancellation.CancellationException

private const val LRC_LEAD_MS = 300L
private const val TTML_LEAD_MS = 0L
private const val LYRIC_VISUAL_TUNING_OFFSET_MS = 150L
private const val MANUAL_SCROLL_TIMEOUT_MS = 3000L
private const val MANUAL_SCROLL_DEBOUNCE_MS = 50L
private const val LYRIC_FOCUS_POLL_INTERVAL_MS = 120L
// Poll cadence for the position loop while settled-paused — the only state where
// the position can change without frames being produced (notification/external
// seeks, and the next play press).
private const val LYRICS_PAUSED_POLL_INTERVAL_MS = 50L

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun LyricsEnhanced(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    currentPositionMsProvider: () -> Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    textColorOverride: Color? = null,
    lyricsLineBlurOverride: Boolean? = null,
    wordSyncDisabled: Boolean = false,
    isReadyToParse: Boolean = true,
    lazyListState: LazyListState? = null,
    dragModifier: Modifier = Modifier,
    lyrics: String?
) {
    val context = LocalContext.current
    val animationsDisabled = false

    val lyricsClick = true
    val lyricsTextSize = 26f
    val lyricsLineSpacing = 1.3f
    val lyricsLineBlurPreference = false
    val romanizeChinese = true
    val romanizeHindi = true
    val romanizeJapanese = true
    val romanizeKorean = true
    val romanizeOtherLanguages = true
    val showTranslations = true

    val romanizationPreferences =
        remember(
            romanizeJapanese,
            romanizeKorean,
            romanizeChinese,
            romanizeHindi,
            romanizeOtherLanguages,
        ) {
            LyricsRomanizationPreferences(
                romanizeJapanese = romanizeJapanese,
                romanizeKorean = romanizeKorean,
                romanizeChinese = romanizeChinese,
                romanizeHindi = romanizeHindi,
                romanizeOther = romanizeOtherLanguages,
            )
        }

    val textColor = textColorOverride ?: FoamTheme.colors.textPrimary
    val lyricsLineBlur = lyricsLineBlurOverride ?: lyricsLineBlurPreference

    val isSynced = remember(lyrics) {
        lyrics != null && (isLineSyncedLrc(lyrics) || isPotentiallyTtml(lyrics))
    }
    val isTtmlFormat = remember(lyrics) { lyrics != null && isPotentiallyTtml(lyrics) }

    var lyricsEntries by remember(lyrics) { mutableStateOf<List<LyricsEntry>>(emptyList()) }
    var syncedLyrics by remember(lyrics) { mutableStateOf(SyncedLyrics(emptyList())) }
    var isPreparingLyrics by remember(lyrics) { mutableStateOf(lyrics != null && lyrics != LYRICS_NOT_FOUND) }

    LaunchedEffect(lyrics, wordSyncDisabled) {
        if (lyrics == null || lyrics == LYRICS_NOT_FOUND) {
            lyricsEntries = emptyList()
            syncedLyrics = SyncedLyrics(emptyList())
            isPreparingLyrics = false
            return@LaunchedEffect
        }
        val (entries, synced) = withContext(Dispatchers.Default) {
            val ttml = isTtml(lyrics)
            val builtEntries = when {
                ttml -> parseTtml(lyrics)
                isLineSyncedLrc(lyrics) -> parseLyrics(lyrics)
                else -> lyrics
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { line -> LyricsEntry(time = -1L, text = line.trim()) }
            }
            // "Disable word sync": strip the per-word timings after parsing so every
            // word-synced line falls through to the plain line-synced branch of
            // buildSyncedLyrics instead of rendering as karaoke.
            val normalizedEntries =
                if (wordSyncDisabled && builtEntries.any { it.words != null }) {
                    builtEntries.map { entry ->
                        if (entry.words != null) entry.copy(words = null) else entry
                    }
                } else {
                    builtEntries
                }
            val builtSynced = buildSyncedLyrics(normalizedEntries, emptyMap())
            normalizedEntries to builtSynced
        }
        lyricsEntries = entries
        syncedLyrics = synced
        isPreparingLyrics = false
    }

    LaunchedEffect(lyricsEntries, romanizationPreferences, isReadyToParse) {
        if (!isReadyToParse) return@LaunchedEffect
        if (!romanizationPreferences.isEnabled) return@LaunchedEffect

        val toRomanize =
            lyricsEntries.mapIndexedNotNull { index, entry ->
                val hasProviderRomanization =
                    providedRomanizedTextForEntry(entry, romanizationPreferences) != null
                if (hasProviderRomanization || shouldRomanizeLyricsLine(entry.text, romanizationPreferences)) {
                    index to entry
                } else {
                    null
                }
            }
        if (toRomanize.isEmpty()) return@LaunchedEffect

        val jobs =
            toRomanize.map { (index, entry) ->
                async(Dispatchers.Default) {
                    val romanized: List<String?> =
                        try {
                            if (entry.words != null) {
                                val mainWordCount = entry.words.count { !it.isBackground }
                                providedRomanizedWordsForEntry(entry, mainWordCount, romanizationPreferences)
                                    ?: entry.words.filter { !it.isBackground }.map { word ->
                                        romanizeLyricsWordWithLineContext(word.text, entry.text, romanizationPreferences)
                                    }
                            } else {
                                listOf(
                                    providedRomanizedTextForEntry(entry, romanizationPreferences)
                                        ?: romanizeLyricsLine(entry.text, romanizationPreferences),
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (entry.words != null) {
                                List(entry.words.count { !it.isBackground }) { null }
                            } else {
                                listOf(null)
                            }
                        }
                    index to romanized
                }
            }
        // Assembly (awaitAll + buildSyncedLyrics, which re-scans every line through
        // BreakIterator for non-TTML formats) is real CPU work: keep it off the
        // Main-immediate dispatcher, which is what runs composition right as the
        // lyrics screen settles.
        val newSynced = withContext(Dispatchers.Default) {
            val tempMap = mutableMapOf<Int, List<String?>>()
            jobs.awaitAll().forEach { (index, romanized) ->
                tempMap[index] = romanized
            }
            buildSyncedLyrics(lyricsEntries, tempMap)
        }
        syncedLyrics = newSynced
    }

    val leadMs = if (isTtmlFormat) TTML_LEAD_MS else LRC_LEAD_MS

    val latestSliderPositionProvider = rememberUpdatedState(sliderPositionProvider)
    val latestLyricsSyncOffset = rememberUpdatedState(lyricsSyncOffset)
    val latestLeadMs = rememberUpdatedState(leadMs)
    val latestCurrentPositionMs = rememberUpdatedState(currentPositionMsProvider)
    val latestIsPlaying = rememberUpdatedState(isPlaying)
    val playbackPositionMs =
        remember {
            mutableLongStateOf(currentPositionMsProvider().coerceAtLeast(0L))
        }
    var isManualScrolling by remember { mutableStateOf(false) }
    val defaultListState = rememberLazyListState()
    val listState = lazyListState ?: defaultListState

    LaunchedEffect(animationsDisabled, isReadyToParse, isSynced) {
        if (!isReadyToParse || !isSynced) {
            // Plain (unsynced) lyrics don't scroll with playback, so there's no need to
            // keep a per-frame animation loop running and burning CPU/battery.
            playbackPositionMs.longValue = latestCurrentPositionMs.value().coerceAtLeast(0L)
            return@LaunchedEffect
        }
        var wasSliderActive = false
        var lastRawPositionMs = -1L
        while (isActive) {
            val sliderPosition = latestSliderPositionProvider.value()
            val isSliderActive = sliderPosition != null
            if (isSliderActive && !wasSliderActive) {
                isManualScrolling = false
            }
            wasSliderActive = isSliderActive

            val rawPosition = (sliderPosition ?: latestCurrentPositionMs.value()).coerceAtLeast(0L)
            if (playbackPositionMs.longValue != rawPosition) {
                playbackPositionMs.longValue = rawPosition
            }

            val clockAdvancing = rawPosition != lastRawPositionMs
            lastRawPositionMs = rawPosition
            if (latestIsPlaying.value || clockAdvancing || isSliderActive) {
                withFrameNanos { }
            } else {
                delay(LYRICS_PAUSED_POLL_INTERVAL_MS)
            }
        }
    }

    val playbackSyncPosition: () -> Int =
        remember {
            {
                (
                        playbackPositionMs.longValue +
                                latestLyricsSyncOffset.value.toLong() +
                                latestLeadMs.value +
                                LYRIC_VISUAL_TUNING_OFFSET_MS
                        ).coerceIn(0L, Int.MAX_VALUE.toLong())
                    .toInt()
            }
        }
    val lineFocusPosition: () -> Int =
        remember(syncedLyrics) {
            {
                syncedLyrics.positionForStableLineFocus(playbackSyncPosition())
            }
        }

    val manualScrollResetScope = rememberCoroutineScope()
    val nestedScrollConnection =
        remember {
            var lastUserScrollEventMs = 0L
            var resetJob: Job? = null

            fun scheduleManualScrollReset() {
                resetJob?.cancel()
                resetJob =
                    manualScrollResetScope.launch {
                        delay(MANUAL_SCROLL_TIMEOUT_MS)
                        isManualScrolling = false
                    }
            }

            object : NestedScrollConnection {
                private fun markManualScroll() {
                    val now = System.currentTimeMillis()
                    if (now - lastUserScrollEventMs >= MANUAL_SCROLL_DEBOUNCE_MS) {
                        isManualScrolling = true
                        lastUserScrollEventMs = now
                        scheduleManualScrollReset()
                    }
                }

                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) {
                        markManualScroll()
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    if (isManualScrolling) {
                        scheduleManualScrollReset()
                    }
                    return Velocity.Zero
                }
            }
        }

    LaunchedEffect(syncedLyrics, isSynced, isReadyToParse) {
        if (!isReadyToParse || !isSynced || syncedLyrics.lines.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.viewportEndOffset > listState.layoutInfo.viewportStartOffset
        }.first { it }

        var forceNextScroll = true
        var lastIndex: Int? = null
        while (isActive) {
            val index =
                if (isManualScrolling) {
                    null
                } else {
                    syncedLyrics
                        .getCurrentFirstHighlightLineIndexByTime(lineFocusPosition())
                        .takeIf { candidate -> candidate in syncedLyrics.lines.indices }
                }

            if (index != lastIndex) {
                lastIndex = index
                if (index == null) {
                    forceNextScroll = true
                } else {
                    listState.scrollLyricIntoFocus(
                        index = index,
                        animateToNearbyItem = !forceNextScroll,
                        force = forceNextScroll,
                        alignByItemCenter = isTtmlFormat,
                    )
                    forceNextScroll = false
                }
            }
            delay(LYRIC_FOCUS_POLL_INTERVAL_MS)
        }
    }

    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val typography = FoamTheme.typography
    val normalTextStyle = remember(typography, lyricsTextSize, lyricsLineSpacing) {
        typography.titleMedium.copy(
            fontSize = lyricsTextSize.sp,
            lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = typography.titleMedium.fontFamily,
        )
    }
    val accompanimentTextStyle = remember(typography, lyricsTextSize, lyricsLineSpacing) {
        typography.titleLarge.copy(
            fontSize = (lyricsTextSize * 0.82f).sp,
            lineHeight = (lyricsTextSize * 0.82f * lyricsLineSpacing).sp,
            fontFamily = typography.titleLarge.fontFamily,
        )
    }
    val phoneticTextStyle = remember(typography, lyricsTextSize, lyricsLineSpacing) {
        typography.body.copy(
            fontSize = (lyricsTextSize * 0.55f).sp,
            lineHeight = (lyricsTextSize * 0.55f * lyricsLineSpacing).sp,
            fontWeight = FontWeight.Normal,
        )
    }
    val plainLyrics =
        remember(lyricsEntries, isSynced, showTranslations) {
            PlainLyrics(
                items =
                    if (isSynced) {
                        emptyList()
                    } else {
                        lyricsEntries.mapIndexedNotNull { index, entry ->
                            val text = entry.text.trim()
                            if (text.isBlank()) {
                                null
                            } else {
                                val translation = if (showTranslations) providedTranslationTextForEntry(entry) else null
                                PlainLyricLine(
                                    itemId = "plain:$index",
                                    text = text,
                                    translation = translation,
                                )
                            }
                        }
                    },
            )
        }

    val noLyricsText = stringResource(R.string.lyrics_not_found)

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier =
            modifier
                .fillMaxSize()
                .padding(bottom = 12.dp),
    ) {
        when {
            isPreparingLyrics -> {
                Box(
                    modifier = Modifier.fillMaxSize().then(dragModifier),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = FoamTheme.accentColor)
                }
            }

            lyrics == LYRICS_NOT_FOUND -> {
                Box(
                    modifier = Modifier.fillMaxSize().then(dragModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = noLyricsText,
                        style = FoamTheme.typography.titleMedium,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            isSynced && syncedLyrics.lines.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().then(dragModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = noLyricsText,
                        style = FoamTheme.typography.titleMedium,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            !isSynced && plainLyrics.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().then(dragModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = noLyricsText,
                        style = FoamTheme.typography.titleMedium,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            !isSynced -> {
                LyricPressFeedback {
                    PlainLyricsView(
                        lines = plainLyrics,
                        listState = listState,
                        textColor = textColor,
                        textStyle = normalTextStyle,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                    )
                }
            }

            else -> {
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(nestedScrollConnection),
                ) {
                    val lyricsViewportOffset = remember(maxHeight) { maxHeight * 0.32f }

                    LyricPressFeedback {
                        KaraokeLyricsView(
                            listState = listState,
                            lyrics = syncedLyrics,
                            currentPosition = playbackSyncPosition,
                            onLineClicked = { line ->
                                if (lyricsClick && isSynced && line.start > 0) {
                                    onSeek(line.start.toLong())
                                }
                            },
                            // Long-press no longer enters selection mode.
                            onLinePressed = { },
                            textColor = textColor,
                            normalLineTextStyle = normalTextStyle,
                            accompanimentLineTextStyle = accompanimentTextStyle,
                            phoneticTextStyle = phoneticTextStyle,
                            blendMode = BlendMode.SrcOver,
                            useBlurEffect = lyricsLineBlur && isReadyToParse,
                            showTranslation = showTranslations,
                            showPhonetic = romanizationPreferences.isEnabled,
                            offset = lyricsViewportOffset,
                            keepAliveZone = 72.dp,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }

}

