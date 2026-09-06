package com.foamplayer.app.ui.screens.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
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
import com.foamplayer.app.constants.LyricsClickKey
import com.foamplayer.app.constants.LyricsLineBlurKey
import com.foamplayer.app.constants.LyricsLineSpacingKey
import com.foamplayer.app.constants.LyricsRomanizeChineseKey
import com.foamplayer.app.constants.LyricsRomanizeHindiKey
import com.foamplayer.app.constants.LyricsRomanizeJapaneseKey
import com.foamplayer.app.constants.LyricsRomanizeKoreanKey
import com.foamplayer.app.constants.LyricsRomanizeOtherLanguagesKey
import com.foamplayer.app.constants.LyricsTextSizeKey
import com.foamplayer.app.constants.PlayerBackgroundStyleKey
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
import kotlin.math.roundToLong

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
private const val SMOOTH_PLAYBACK_MAX_FORWARD_DRIFT_MS = 80L
// AudioEngine.positionMs now ticks every rendered frame while playing (see
// startProgressTracker), so under normal playback `rawPosition` should already sit
// within a frame or two of the interpolated `projectedPosition` below -- there's no
// longer a ~200ms staircase to tolerate. This just needs enough headroom to absorb
// ordinary frame jitter (a dropped frame, a GC pause) without treating it as a real
// desync, while still reacting quickly to genuine ones (track transitions, stalls).
private const val SMOOTH_PLAYBACK_MAX_BACKWARD_DRIFT_MS = 150L
private const val SMOOTH_PLAYBACK_DRIFT_CORRECTION = 0.55f
private const val LYRIC_FOCUS_SCROLL_DURATION_MS = 520
private const val LYRIC_FOCUS_POLL_INTERVAL_MS = 120L
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
    lyrics: String?
) {
    val context = LocalContext.current
    val animationsDisabled = false

    val lyricsClick = true
    val lyricsTextSize = 26f
    val lyricsLineSpacing = 1.3f
    // Word-synced (karaoke) rendering is inherently heavier than plain lyrics: it drives a
    // 60fps position feed for per-syllable highlighting and renders extra composables per
    // line (translation, phonetic, accompaniment). On top of that, a GPU blur effect was
    // being forced on unconditionally, which is one of the most expensive things Compose
    // can draw and gets recomputed as the highlighted line changes during playback. Until
    // this is wired up to a real user-configurable setting (see LyricsLineBlurKey), default
    // it off rather than always-on so word-synced lyrics aren't paying for it by default.
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

    val textColor = Color.White
    val lyricsLineBlur = lyricsLineBlurOverride ?: lyricsLineBlurPreference

    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedLineKeys = remember { mutableStateListOf<String>() }
    var showMaxSelectionToast by remember { mutableStateOf(false) }
    val maxSelectionLimit = 5

    val isSynced = remember(lyrics) { lyrics != null && (isLineSyncedLrc(lyrics!!) || isTtml(lyrics!!)) }
    val isTtmlFormat = remember(lyrics) { lyrics != null && isTtml(lyrics!!) }

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
            val builtEntries = when {
                isTtml(lyrics) -> parseTtml(lyrics)
                isLineSyncedLrc(lyrics) -> parseLyrics(lyrics)
                else -> lyrics
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { line -> LyricsEntry(time = -1L, text = line.trim()) }
            }
            val builtSynced = buildSyncedLyrics(builtEntries, isTtml(lyrics), emptyMap())
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
                            if (isTtmlFormat && entry.words != null) {
                                val mainWordCount = entry.words!!.count { !it.isBackground }
                                providedRomanizedWordsForEntry(entry, mainWordCount, romanizationPreferences)
                                    ?: entry.words!!.filter { !it.isBackground }.map { word ->
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
                            if (isTtmlFormat && entry.words != null) {
                                List(entry.words!!.count { !it.isBackground }) { null }
                            } else {
                                listOf(null)
                            }
                        }
                    index to romanized
                }
            }
        val tempMap = mutableMapOf<Int, List<String?>>()
        jobs.awaitAll().forEach { (index, romanized) ->
            tempMap[index] = romanized
        }
        syncedLyrics = buildSyncedLyrics(lyricsEntries, isTtmlFormat, tempMap)
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
        var anchorPlayerPositionMs = latestCurrentPositionMs.value().coerceAtLeast(0L)
        var anchorFrameNanos = 0L
        while (isActive) {
            val sliderPosition = latestSliderPositionProvider.value()
            val isSliderActive = sliderPosition != null
            if (isSliderActive && !wasSliderActive) {
                isManualScrolling = false
            }
            wasSliderActive = isSliderActive

            val rawPosition = (sliderPosition ?: latestCurrentPositionMs.value()).coerceAtLeast(0L)
            if (sliderPosition != null || !latestIsPlaying.value || animationsDisabled) {
                anchorPlayerPositionMs = rawPosition
                anchorFrameNanos = 0L
                if (playbackPositionMs.longValue != rawPosition) {
                    playbackPositionMs.longValue = rawPosition
                }
                if (sliderPosition == null) {
                    delay(100L)
                } else {
                    withFrameNanos { }
                }
            } else {
                val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
                if (anchorFrameNanos == 0L) {
                    anchorFrameNanos = frameNanos
                    anchorPlayerPositionMs = rawPosition
                }

                val elapsedMs = ((frameNanos - anchorFrameNanos) / 1_000_000f)
                val projectedPosition = anchorPlayerPositionMs + elapsedMs.roundToLong()
                val driftMs = rawPosition - projectedPosition
                val nextPosition =
                    when {
                        driftMs > SMOOTH_PLAYBACK_MAX_FORWARD_DRIFT_MS ||
                                driftMs < -SMOOTH_PLAYBACK_MAX_BACKWARD_DRIFT_MS -> {
                            anchorPlayerPositionMs = rawPosition
                            anchorFrameNanos = frameNanos
                            rawPosition
                        }

                        driftMs != 0L -> {
                            projectedPosition + (driftMs * SMOOTH_PLAYBACK_DRIFT_CORRECTION).roundToLong()
                        }

                        else -> {
                            projectedPosition
                        }
                    }.coerceAtLeast(0L)

                if (playbackPositionMs.longValue != nextPosition) {
                    playbackPositionMs.longValue = nextPosition
                }
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
                    if (!isSelectionModeActive && source == NestedScrollSource.UserInput) {
                        markManualScroll()
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    if (!isSelectionModeActive && isManualScrolling) {
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
                if (isManualScrolling || isSelectionModeActive) {
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

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedLineKeys.clear()
    }

    LaunchedEffect(showMaxSelectionToast) {
        if (showMaxSelectionToast) {
            Toast
                .makeText(
                    context,
                    "Selection limit is $maxSelectionLimit.",
                    Toast.LENGTH_SHORT,
                ).show()
            showMaxSelectionToast = false
        }
    }

    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val normalTextStyle =
        FoamTheme.typography.titleMedium.copy(
            fontSize = lyricsTextSize.sp,
            lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FoamTheme.typography.titleMedium.fontFamily,
        )
    val accompanimentTextStyle =
        FoamTheme.typography.titleLarge.copy(
            fontSize = (lyricsTextSize * 0.82f).sp,
            lineHeight = (lyricsTextSize * 0.82f * lyricsLineSpacing).sp,
            fontFamily = FoamTheme.typography.titleLarge.fontFamily,
        )
    val phoneticTextStyle =
        FoamTheme.typography.body.copy(
            fontSize = (lyricsTextSize * 0.55f).sp,
            lineHeight = (lyricsTextSize * 0.55f * lyricsLineSpacing).sp,
            fontWeight = FontWeight.Normal,
        )
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
                                val selectionId = "plain:$index:${text.hashCode()}"
                                val translation = if (showTranslations) providedTranslationTextForEntry(entry) else null
                                PlainLyricLine(
                                    itemId = "$selectionId#$index",
                                    selectionId = selectionId,
                                    text = text,
                                    translation = translation,
                                )
                            }
                        }
                    },
            )
        }
    // selectedLineKeys is already a SnapshotStateList, so reads of it are tracked and
    // recomposition happens exactly when its contents change. Wrapping it in
    // `.toList()` first creates a new list identity on every recomposition, which
    // defeated the `remember(selectedLineKeySnapshot)` below (its key never compared
    // equal to itself), so the `.toSet()` conversion ran unmemoized every time. A plain
    // derivedStateOf recomputes only when the underlying list's structural content
    // actually changes.
    val selectedLineKeySet by remember { derivedStateOf { selectedLineKeys.toSet() } }
    val dismissSelection = {
        isSelectionModeActive = false
        selectedLineKeys.clear()
    }
    val toggleSelectedLine: (String) -> Unit = { lineKey ->
        if (selectedLineKeys.contains(lineKey)) {
            selectedLineKeys.remove(lineKey)
            if (selectedLineKeys.isEmpty()) isSelectionModeActive = false
        } else if (selectedLineKeys.size < maxSelectionLimit) {
            selectedLineKeys.add(lineKey)
        } else {
            showMaxSelectionToast = true
        }
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier =
            modifier
                .fillMaxSize()
                .padding(bottom = 12.dp),
    ) {
        when {
            isPreparingLyrics -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FoamTheme.accentColor)
                }
            }

            lyrics == LYRICS_NOT_FOUND -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Lyrics not found",
                        style = FoamTheme.typography.titleMedium,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            isSynced && syncedLyrics.lines.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Lyrics not found",
                        style = FoamTheme.typography.titleMedium,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            !isSynced && plainLyrics.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Lyrics not found",
                        style = FoamTheme.typography.titleMedium,
                        color = textColor.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            !isSynced -> {
                PlainLyricsView(
                    lines = plainLyrics,
                    listState = listState,
                    selectedLineKeys = selectedLineKeySet,
                    textColor = textColor,
                    textStyle = normalTextStyle,
                    onLineClicked = { lineKey ->
                        if (isSelectionModeActive) toggleSelectedLine(lineKey)
                    },
                    onLinePressed = { lineKey ->
                        if (!isSelectionModeActive) {
                            isSelectionModeActive = true
                            if (!selectedLineKeys.contains(lineKey)) {
                                selectedLineKeys.add(lineKey)
                            }
                        } else if (!selectedLineKeys.contains(lineKey)) {
                            toggleSelectedLine(lineKey)
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp),
                )
            }

            else -> {
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(nestedScrollConnection),
                ) {
                    val lyricsViewportOffset = remember(maxHeight) { maxHeight * 0.32f }

                    KaraokeLyricsView(
                        listState = listState,
                        lyrics = syncedLyrics,
                        currentPosition = playbackSyncPosition,
                        onLineClicked = { line ->
                            if (isSelectionModeActive) {
                                toggleSelectedLine(line.selectionKey())
                            } else if (lyricsClick && isSynced && line.start > 0) {
                                onSeek(line.start.toLong())
                            }
                        },
                        onLinePressed = { line ->
                            val lineKey = line.selectionKey()
                            if (!isSelectionModeActive) {
                                isSelectionModeActive = true
                                if (!selectedLineKeys.contains(lineKey)) {
                                    selectedLineKeys.add(lineKey)
                                }
                            } else if (!selectedLineKeys.contains(lineKey)) {
                                toggleSelectedLine(lineKey)
                            }
                        },
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

@Immutable
private data class PlainLyrics(
    val items: List<PlainLyricLine>,
)

@Immutable
private data class PlainLyricLine(
    val itemId: String,
    val selectionId: String,
    val text: String,
    val translation: String? = null,
)

@Composable
private fun PlainLyricsView(
    lines: PlainLyrics,
    listState: LazyListState,
    selectedLineKeys: Set<String>,
    textColor: Color,
    textStyle: TextStyle,
    onLineClicked: (String) -> Unit,
    onLinePressed: (String) -> Unit,
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
                selected = line.selectionId in selectedLineKeys,
                textColor = textColor,
                textStyle = textStyle,
                onLineClicked = onLineClicked,
                onLinePressed = onLinePressed,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlainLyricLineItem(
    line: PlainLyricLine,
    selected: Boolean,
    textColor: Color,
    textStyle: TextStyle,
    onLineClicked: (String) -> Unit,
    onLinePressed: (String) -> Unit,
) {
    val contentColor =
        if (selected) {
            FoamTheme.colors.textPrimary
        } else {
            textColor
        }

    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .combinedClickable(
            onClick = { onLineClicked(line.selectionId) },
            onLongClick = { onLinePressed(line.selectionId) },
        )
        .padding(vertical = 8.dp)

    if (line.translation != null) {
        Column(modifier = modifier) {
            Text(
                text = line.text,
                style = textStyle,
                color = contentColor,
            )
            Text(
                text = line.translation,
                style = FoamTheme.typography.titleMedium.copy(
                    fontSize = (textStyle.fontSize.value * 0.55f).sp,
                    lineHeight = (textStyle.fontSize.value * 0.75f).sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = contentColor.copy(alpha = 0.76f),
                modifier = Modifier.padding(top = (textStyle.fontSize.value * 0.3f).dp),
            )
        }
    } else {
        Text(
            text = line.text,
            style = textStyle,
            color = contentColor,
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

private fun ISyncedLine.lineText(): String =
    when (this) {
        is KaraokeLine -> syllables.joinToString("") { it.content }
        is SyncedLine -> content
        else -> ""
    }

private fun ISyncedLine.selectionKey(text: String = lineText()): String = "$start:$end:${text.hashCode()}"

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
    isTtml: Boolean,
    romanizationMap: Map<Int, List<String?>>,
): SyncedLyrics {
    if (entries.isEmpty()) return SyncedLyrics(emptyList())
    val lines = mutableListOf<ISyncedLine>()

    entries.forEachIndexed { index, entry ->
        if (entry.time < 0L) return@forEachIndexed
        if (entry.isInstrumental) return@forEachIndexed
        if (entry.text.isBlank() && entry.words.isNullOrEmpty()) return@forEachIndexed

        if (isTtml && entry.words != null) {
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