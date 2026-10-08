package com.foamplayer.app.ui.screens.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
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
import com.foamplayer.app.lyrics.WordTimestamp
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.withContext
import java.text.BreakIterator
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

private const val LRC_LEAD_MS = 300L
private const val TTML_LEAD_MS = 0L
private const val LYRIC_VISUAL_TUNING_OFFSET_MS = 150L
private const val MANUAL_SCROLL_TIMEOUT_MS = 3000L
private const val MANUAL_SCROLL_DEBOUNCE_MS = 50L
private const val LYRIC_FOCUS_ANCHOR_RATIO = 0.42f
private const val LYRIC_LINE_SYNC_TOP_ANCHOR_RATIO = 0.35f
private const val LYRIC_FOCUS_TOP_GUARD_RATIO = 0.18f
private const val LYRIC_FOCUS_BOTTOM_GUARD_RATIO = 0.24f
private const val LYRIC_FOCUS_MIN_SCROLL_PX = 6
private const val LYRIC_FOCUS_ANIMATED_DISTANCE = 12
private const val LYRIC_FOCUS_SCROLL_DURATION_MS = 520
private const val LYRIC_FOCUS_POLL_INTERVAL_MS = 120L
// Poll cadence for the position loop while settled-paused — the only state where
// the position can change without frames being produced (notification/external
// seeks, and the next play press).
private const val LYRICS_PAUSED_POLL_INTERVAL_MS = 50L
private const val MIN_KARAOKE_SYLLABLE_DURATION_MS = 1

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
    isReadyToParse: Boolean = true,
    lazyListState: LazyListState? = null,
    // Vertical collapse-drag, applied only to the non-scrollable placeholder states
    // (spinner / "Lyrics not found"): those branches render no scrollable, so no
    // nested-scroll overscroll can ever bubble up to the player's connection and the
    // swipe-back needs a direct drag handler. The scrollable branches are left alone.
    dragModifier: Modifier = Modifier,
    lyrics: String?
) {
    val context = LocalContext.current
    val animationsDisabled = false

    val lyricsClick = true
    val lyricsTextSize = 26f
    val lyricsLineSpacing = 1.3f
    // Word-synced (karaoke) rendering is inherently heavier than plain lyrics: it drives a
    // 60fps position feed for per-syllable highlighting and renders extra composables per
    // line (translation, phonetic, accompaniment). On top of that, a GPU blur effect on the
    // non-active lines is one of the most expensive things Compose can draw and gets
    // recomposed as the highlighted line changes during playback. The user's choice
    // (Settings > Lyrics > Display, persisted via LyricsLineBlurKey) arrives through
    // lyricsLineBlurOverride; this false is only the fallback for callers that don't wire
    // the setting, so word-synced lyrics never pay for blur by default.
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

    // isTtml() normalizes the entire payload (several whole-string regex passes)
    // before checking its markers; on a large TTML document that stalls the main
    // thread right at track change. The '<' gate is conservative: text whose first
    // '<' is not near the start cannot normalize into a TTML document, so it skips
    // the full check. Non-XML lyrics (the overwhelmingly common case) now pay a
    // single indexOf instead of full-document regex normalization.
    val isSynced = remember(lyrics) {
        lyrics != null && (isLineSyncedLrc(lyrics) || isPotentiallyTtml(lyrics))
    }
    val isTtmlFormat = remember(lyrics) { lyrics != null && isPotentiallyTtml(lyrics) }

    var lyricsEntries by remember(lyrics) { mutableStateOf<List<LyricsEntry>>(emptyList()) }
    var syncedLyrics by remember(lyrics) { mutableStateOf(SyncedLyrics(emptyList())) }
    var isPreparingLyrics by remember(lyrics) { mutableStateOf(lyrics != null && lyrics != LYRICS_NOT_FOUND) }

    LaunchedEffect(lyrics) {
        if (lyrics == null || lyrics == LYRICS_NOT_FOUND) {
            lyricsEntries = emptyList()
            syncedLyrics = SyncedLyrics(emptyList())
            isPreparingLyrics = false
            return@LaunchedEffect
        }
        val (entries, synced) = withContext(Dispatchers.Default) {
            // Detect the format once; isTtml()/isLineSyncedLrc() are whole-payload
            // passes and used to run twice more below for the same lyrics string.
            val ttml = isTtml(lyrics)
            val builtEntries = when {
                ttml -> parseTtml(lyrics)
                isLineSyncedLrc(lyrics) -> parseLyrics(lyrics)
                else -> lyrics
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { line -> LyricsEntry(time = -1L, text = line.trim()) }
            }
            val builtSynced = buildSyncedLyrics(builtEntries, emptyMap())
            builtEntries to builtSynced
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
                // Romanization does real CPU work (script conversion / transliteration).
                // Without an explicit dispatcher, `async` here would run on the same
                // Main-immediate dispatcher as the composition, blocking the UI thread
                // and causing the lag/jank felt when the lyrics screen first opens.
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

            // Relay the engine clock 1:1. ALL smoothing lives in AudioEngine's
            // progress tracker (dead-reckoning over the quantized player clock,
            // projection through crossfade priming, monotonic settled-pause
            // freeze); a second projection here just adds slew latency to seeks
            // and a backward step at the pause freeze (the fill had run a frame
            // ahead of the frozen player clock — publishing the raw sample
            // verbatim snapped it back and flapped the focus line).
            val rawPosition = (sliderPosition ?: latestCurrentPositionMs.value()).coerceAtLeast(0L)
            if (playbackPositionMs.longValue != rawPosition) {
                playbackPositionMs.longValue = rawPosition
            }

            // Per-frame while the clock moves (playback, fade ramps — isPlaying
            // flips false at the START of a pause fade while the clock is still
            // advancing); cheap poll while settled-paused. Value semantics are
            // identical in both branches, so the cadence flip can't jump.
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

    // The manual-scroll-timeout logic previously lived in a `LaunchedEffect(isManualScrolling,
    // lastManualScrollTime)` declared directly in this composable's body. Reading those two
    // states as LaunchedEffect keys at the top level of LyricsEnhanced (rather than inside a
    // lambda/child composable) invalidates this ENTIRE composable's recompose scope on every
    // change — and markManualScroll() below fires up to ~20x/sec while actively scrolling
    // (debounced to 50ms), so that was forcing a full re-execution of LyricsEnhanced's body,
    // recreating every unremembered lambda passed to KaraokeLyricsView, up to 20 times a
    // second during scroll/auto-follow. PlainLyricsView never touches nestedScrollConnection,
    // which is why plain lyrics never paid this cost. Managing the timeout as a plain
    // coroutine job (cancelled/relaunched from the callback itself) avoids reading this state
    // anywhere in composition, so toggling it no longer recomposes anything above the scopes
    // that already read it inside non-composable coroutine bodies.
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

        // Line-level scroll-follow doesn't need frame-perfect precision the way the
        // karaoke syllable highlight does. Polling on a fixed cadence instead of via
        // snapshotFlow (which would re-run this lookup on every position tick, i.e.
        // up to ~60x/sec during playback) avoids hammering
        // getCurrentFirstHighlightLineIndexByTime far more often than the UI could
        // ever need it.
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

    // remember'd: these are rebuilt on every recomposition of the screen otherwise,
    // and they feed KaraokeLyricsView whose params then never compare equal. The
    // typography is read outside the remember lambdas (CompositionLocal reads are
    // composable calls, not allowed in a remember calculation).
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

@Immutable
private data class PlainLyrics(
    val items: List<PlainLyricLine>,
)

// FoamTheme never installs an Indication, so pressed lyric lines fall back to
// foundation's DefaultDebugIndication — a flat 30% black rectangle overlay — and the
// accompanist library's combinedClickable plays a LongPress buzz for any held press.
// Both lyric branches run under this provider so a press only ripples, never dims or
// vibrates; ripple(color) matches the tint used by every other pressable in the app.
@Composable
private fun LyricPressFeedback(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIndication provides ripple(color = FoamTheme.colors.textPrimary),
        LocalHapticFeedback provides SilentHapticFeedback,
        content = content,
    )
}

private object SilentHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

@Immutable
private data class PlainLyricLine(
    val itemId: String,
    val text: String,
    val translation: String? = null,
)

@Composable
private fun PlainLyricsView(
    lines: PlainLyrics,
    listState: LazyListState,
    textColor: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val contentPadding =
        remember {
            PaddingValues(
                top = 120.dp,
                bottom = 96.dp,
            )
        }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            items = lines.items,
            key = { line -> line.itemId },
            contentType = { "plain_lyric_line" },
        ) { line ->
            PlainLyricLineItem(
                line = line,
                textColor = textColor,
                textStyle = textStyle,
            )
        }
    }
}

@Composable
private fun PlainLyricLineItem(
    line: PlainLyricLine,
    textColor: Color,
    textStyle: TextStyle,
) {
    // Pressing (tap or long-press) only plays the default ripple; selection was removed.
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .clickable { }
        .padding(vertical = 8.dp)

    if (line.translation != null) {
        Column(modifier = modifier) {
            Text(
                text = line.text,
                style = textStyle,
                color = textColor,
            )
            Text(
                text = line.translation,
                style = FoamTheme.typography.titleMedium.copy(
                    fontSize = (textStyle.fontSize.value * 0.55f).sp,
                    lineHeight = (textStyle.fontSize.value * 0.75f).sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = textColor.copy(alpha = 0.76f),
                modifier = Modifier.padding(top = (textStyle.fontSize.value * 0.3f).dp),
            )
        }
    } else {
        Text(
            text = line.text,
            style = textStyle,
            color = textColor,
            modifier = modifier,
        )
    }
}

private suspend fun LazyListState.scrollLyricIntoFocus(
    index: Int,
    animateToNearbyItem: Boolean,
    force: Boolean,
    alignByItemCenter: Boolean,
) {
    val itemCount = layoutInfo.totalItemsCount
    if (itemCount == 0) return

    val targetIndex = index.coerceIn(0, itemCount - 1)
    var itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == targetIndex }
    if (itemInfo == null) {
        val distance = abs(targetIndex - firstVisibleItemIndex)
        if (animateToNearbyItem && distance <= LYRIC_FOCUS_ANIMATED_DISTANCE) {
            animateScrollToItem(targetIndex)
        } else {
            scrollToItem(targetIndex)
        }
        withFrameNanos { }
        itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == targetIndex }
    }

    itemInfo ?: return

    val viewportStart = layoutInfo.viewportStartOffset
    val viewportEnd = layoutInfo.viewportEndOffset
    val viewportHeight = viewportEnd - viewportStart
    if (viewportHeight <= 0) return

    val itemFocusPoint =
        if (alignByItemCenter) {
            itemInfo.offset + itemInfo.size / 2
        } else {
            itemInfo.offset
        }
    val topGuard = viewportStart + (viewportHeight * LYRIC_FOCUS_TOP_GUARD_RATIO).roundToInt()
    val bottomGuard = viewportEnd - (viewportHeight * LYRIC_FOCUS_BOTTOM_GUARD_RATIO).roundToInt()
    if (!force && itemFocusPoint in topGuard..bottomGuard) return

    val anchorRatio =
        if (alignByItemCenter) {
            LYRIC_FOCUS_ANCHOR_RATIO
        } else {
            LYRIC_LINE_SYNC_TOP_ANCHOR_RATIO
        }
    val targetFocusPoint = viewportStart + (viewportHeight * anchorRatio).roundToInt()
    val scrollDelta = itemFocusPoint - targetFocusPoint
    if (abs(scrollDelta) > LYRIC_FOCUS_MIN_SCROLL_PX) {
        animateScrollBy(
            value = scrollDelta.toFloat(),
            animationSpec =
                tween(
                    durationMillis = LYRIC_FOCUS_SCROLL_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )
    }
}

private fun SyncedLyrics.positionForStableLineFocus(time: Int): Int {
    if (lines.isEmpty()) return time
    val index = findLastStartedLineIndex(time)
    if (index < 0) return time

    val line = lines[index]
    if (time < line.end) return time

    return (line.end - 1).coerceAtLeast(line.start)
}

private fun SyncedLyrics.findLastStartedLineIndex(time: Int): Int {
    var low = 0
    var high = lines.lastIndex
    var result = -1

    while (low <= high) {
        val mid = low + (high - low) / 2
        if (lines[mid].start <= time) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }

    return result
}

private fun List<WordTimestamp>.toKaraokeSyllables(phonetics: List<String?>): List<KaraokeSyllable> =
    mapIndexed { index, word ->
        val start = word.startTime.toMilliseconds()
        val nextStart = getOrNull(index + 1)?.startTime?.toMilliseconds()
        val rawEnd = word.endTime.toMilliseconds()
        val end =
            nextStart
                ?.let { minOf(rawEnd, it) }
                ?: rawEnd

        KaraokeSyllable(
            content = word.text,
            start = start,
            end = end.coerceAtLeast(start + MIN_KARAOKE_SYLLABLE_DURATION_MS),
            phonetic = phonetics.getOrNull(index),
        )
    }

private fun Double.toMilliseconds(): Int = (this * 1000.0).roundToInt().coerceAtLeast(0)

private fun buildSyncedLyrics(
    entries: List<LyricsEntry>,
    romanizationMap: Map<Int, List<String?>>,
): SyncedLyrics {
    if (entries.isEmpty()) return SyncedLyrics(emptyList())
    val lines = mutableListOf<ISyncedLine>()

    entries.forEachIndexed { index, entry ->
        if (entry.time < 0L) return@forEachIndexed
        if (entry.isInstrumental) return@forEachIndexed
        if (entry.text.isBlank() && entry.words.isNullOrEmpty()) return@forEachIndexed

        if (entry.words != null) {
            val translation = providedTranslationTextForEntry(entry)
            val mainWords = entry.words!!.filter { !it.isBackground }
            val bgWords = entry.words!!.filter { it.isBackground }
            val alignment =
                when (entry.agent?.lowercase()) {
                    "v2" -> KaraokeAlignment.End
                    else -> KaraokeAlignment.Start
                }

            val wordsForMain = if (mainWords.isNotEmpty()) mainWords else entry.words!!
            val wordPhonetics = romanizationMap[index] ?: emptyList()
            val mainSyllables = wordsForMain.toKaraokeSyllables(wordPhonetics)

            val lineStart = mainSyllables.first().start
            val lineEnd = mainSyllables.last().end
            if (lineEnd <= lineStart) return@forEachIndexed

            val accompanimentLines =
                if (mainWords.isNotEmpty() && bgWords.isNotEmpty()) {
                    val bgSyllables = bgWords.toKaraokeSyllables(emptyList())
                    val bgStart = bgSyllables.first().start
                    val bgEnd = bgSyllables.last().end
                    if (bgEnd > bgStart) {
                        listOf(
                            KaraokeLine.AccompanimentKaraokeLine(
                                syllables = bgSyllables,
                                translation = null,
                                alignment = alignment,
                                start = bgStart,
                                end = bgEnd,
                                phonetic = null,
                            ),
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }

            lines.add(
                KaraokeLine.MainKaraokeLine(
                    syllables = mainSyllables,
                    translation = translation,
                    alignment = alignment,
                    start = lineStart,
                    end = lineEnd,
                    phonetic = null,
                    accompanimentLines = accompanimentLines,
                ),
            )
        } else {
            val nextEntry = entries.getOrNull(index + 1)
            val lineEnd =
                if (nextEntry != null && nextEntry.time > entry.time) {
                    val gap = nextEntry.time - entry.time
                    if (gap > 3000L) {
                        minOf((nextEntry.time - 1L).toInt(), (entry.time + 4000L).toInt())
                            .coerceAtLeast(entry.time.toInt() + 1)
                    } else {
                        (nextEntry.time - 1L).coerceAtLeast(entry.time + 1L).toInt()
                    }
                } else {
                    (entry.time + 4000L).toInt()
                }
            lines.add(
                buildLineSyncedLrcLine(
                    entry = entry,
                    romanizedText = romanizationMap[index]?.firstOrNull(),
                    start = entry.time.toInt(),
                    end = lineEnd,
                ),
            )
        }
    }

    return SyncedLyrics(lines = lines)
}

private fun buildLineSyncedLrcLine(
    entry: LyricsEntry,
    romanizedText: String?,
    start: Int,
    end: Int,
): ISyncedLine {
    val translation = providedTranslationTextForEntry(entry)
    val normalizedRomanizedText = romanizedText?.trim()?.takeIf { it.isNotEmpty() }

    if (normalizedRomanizedText == null) {
        return SyncedLine(
            content = entry.text,
            translation = translation,
            start = start,
            end = end,
        )
    }

    val syllables =
        buildWrappingKaraokeSyllables(
            content = entry.text,
            romanizedText = normalizedRomanizedText,
            start = start,
            end = end,
        )

    return KaraokeLine.MainKaraokeLine(
        syllables = syllables,
        translation = translation,
        alignment = KaraokeAlignment.Start,
        start = start,
        end = end,
    )
}

internal fun String.toLyricsWrappingUnits(): List<String> {
    if (isEmpty()) return emptyList()

    val units = mutableListOf<String>()
    val currentWord = StringBuilder()
    val characterIterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    characterIterator.setText(this)

    fun flushCurrentWord() {
        if (currentWord.isNotEmpty()) {
            units += currentWord.toString()
            currentWord.clear()
        }
    }

    var start = characterIterator.first()
    var end = characterIterator.next()
    while (end != BreakIterator.DONE) {
        val grapheme = substring(start, end)
        val codePoint = grapheme.codePointAt(0)
        when {
            grapheme.all(Char::isWhitespace) -> {
                currentWord.append(grapheme)
                flushCurrentWord()
            }

            codePoint.isCjkCodePoint() -> {
                flushCurrentWord()
                units += grapheme
            }

            else -> {
                currentWord.append(grapheme)
            }
        }
        start = end
        end = characterIterator.next()
    }
    flushCurrentWord()

    return units
}

private fun Int.isCjkCodePoint(): Boolean =
    when (Character.UnicodeScript.of(this)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
            -> true

        else -> false
    }

private fun buildWrappingKaraokeSyllables(
    content: String,
    romanizedText: String,
    start: Int,
    end: Int,
): List<KaraokeSyllable> {
    val contentUnits = content.toLyricsWrappingUnits().ifEmpty { listOf(content) }
    val phoneticWords = romanizedText.split(Regex("\\s+")).filter(String::isNotEmpty)
    val phoneticAnchorIndices =
        contentUnits.indices.filter { index ->
            contentUnits[index].any(Char::isLetterOrDigit)
        }
    val phoneticsByUnit = MutableList<String?>(contentUnits.size) { null }

    if (phoneticAnchorIndices.isNotEmpty()) {
        phoneticWords.forEachIndexed { wordIndex, word ->
            val anchorIndex = wordIndex * phoneticAnchorIndices.size / phoneticWords.size
            val unitIndex = phoneticAnchorIndices[anchorIndex]
            phoneticsByUnit[unitIndex] = listOfNotNull(phoneticsByUnit[unitIndex], word).joinToString(" ")
        }
    }

    val duration = (end - start).coerceAtLeast(contentUnits.size)
    return contentUnits.mapIndexed { index, unit ->
        val unitStart = start + (duration.toLong() * index / contentUnits.size).toInt()
        val unitEnd = start + (duration.toLong() * (index + 1) / contentUnits.size).toInt()
        KaraokeSyllable(
            content = unit,
            start = unitStart,
            end = unitEnd.coerceAtLeast(unitStart + MIN_KARAOKE_SYLLABLE_DURATION_MS),
            phonetic = phoneticsByUnit[index],
        )
    }
}

/**
 * Cheap gate in front of [isTtml]: full TTML detection normalizes the whole payload
 * with regex passes before checking its markers, which is wasted work (and a
 * main-thread stall, when called during composition) for the overwhelmingly common
 * non-XML lyrics. A TTML document always carries its first '<' near the top, so
 * anything else can skip the expensive check.
 */
private fun isPotentiallyTtml(text: String): Boolean =
    text.indexOf('<') in 0 until TTML_GATE_WINDOW && isTtml(text)

private const val TTML_GATE_WINDOW = 128
