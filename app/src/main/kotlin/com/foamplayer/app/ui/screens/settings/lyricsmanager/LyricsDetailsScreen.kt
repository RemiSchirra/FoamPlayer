package com.foamplayer.app.ui.screens.settings.lyricsmanager

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.constants.LyricsSource
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.lyrics.LyricsResultType
import com.foamplayer.app.data.lyrics.LyricsSearchHelper
import com.foamplayer.app.data.lyrics.LyricsSearchResult
import com.foamplayer.app.data.lyrics.SavedLyricsEntry
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.lyrics.LyricsUtils
import com.foamplayer.app.ui.screens.settings.SettingsHeader
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.navigation.ToastViewModel
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurface
import com.foamplayer.app.ui.theme.foamSurfaceVariant
import com.foamplayer.app.ui.utils.TimeUtils

/** The two tabs of the lyrics details screen. */
private enum class LyricsDetailsTab { SAVED, SEARCH }

/** Height of the tab segmented control's labels and sliding pill. */
private val LyricsTabHeight = 40.dp

/**
 * Per-track lyrics details: a track header, then two tabs. "Saved lyrics"
 * lists every lyrics payload stored for the track — online cache, sidecar
 * file, embedded tag — each with a preview and a per-source delete action.
 * "Search" shows the results of the multi-provider search (run with the
 * track's own tags on load) with save-as-file / embed actions. Tabs slide +
 * fade with the nav-bar content convention, and a track without saved lyrics
 * opens directly on the search tab.
 */
@Composable
fun LyricsDetailsScreen(
    trackId: Long,
    onBack: () -> Unit,
    viewModel: LyricsSearchViewModel = hiltViewModel()
) {
    val track by viewModel.track.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val savedLyrics by viewModel.savedLyrics.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()

    var selectedTabChoice by remember { mutableStateOf<LyricsDetailsTab?>(null) }
    val selectedTab = selectedTabChoice ?: when (val state = savedLyrics) {
        is SavedLyricsUiState.Loaded ->
            if (state.entries.isEmpty()) LyricsDetailsTab.SEARCH else LyricsDetailsTab.SAVED
        SavedLyricsUiState.Loading -> null
    }

    val toastCenter = hiltViewModel<ToastViewModel>().toastCenter

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                LyricsEvent.SavedToFile -> toastCenter.pushRes(R.string.lyrics_results_saved)
                LyricsEvent.Embedded -> toastCenter.pushRes(R.string.lyrics_results_embedded)
                LyricsEvent.Deleted -> toastCenter.pushRes(R.string.lyrics_manager_deleted)
                is LyricsEvent.SaveFailed ->
                    toastCenter.pushRes(R.string.lyrics_results_save_failed, event.reason)
                is LyricsEvent.EmbedFailed ->
                    toastCenter.pushRes(R.string.lyrics_results_embed_failed, event.reason)
                is LyricsEvent.DeleteFailed ->
                    toastCenter.pushRes(R.string.lyrics_manager_delete_failed, event.reason)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium)
        ) {
            SettingsHeader(
                title = stringResource(R.string.lyrics_details_title),
                onBack = onBack
            )

            track?.let { currentTrack ->
                LyricsResultTrackHeader(track = currentTrack)
                Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            }

            if (selectedTab == null) {
                // The saved-lyrics scan is still deciding the opening tab.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = FoamDimens.paddingLarge),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = FoamTheme.accentColor)
                }
            } else {
                LyricsDetailsTabs(
                    selected = selectedTab,
                    onSelect = { selectedTabChoice = it }
                )

                Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))

                // Same slide+fade convention as the nav-bar slot content. The
                // opening tab is resolved before this first composes, so no
                // transition plays when the screen opens.
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(tween(FoamMotion.Duration.STANDARD)) { it } +
                                fadeIn(tween(FoamMotion.Duration.SNAP))) togetherWith
                                (slideOutHorizontally(tween(FoamMotion.Duration.STANDARD)) { -it } +
                                fadeOut(tween(FoamMotion.Duration.SNAP)))
                        } else {
                            (slideInHorizontally(tween(FoamMotion.Duration.STANDARD)) { -it } +
                                fadeIn(tween(FoamMotion.Duration.SNAP))) togetherWith
                                (slideOutHorizontally(tween(FoamMotion.Duration.STANDARD)) { it } +
                                fadeOut(tween(FoamMotion.Duration.SNAP)))
                        }
                    },
                    label = "lyricsTabContent"
                ) { tab ->
                    when (tab) {
                        LyricsDetailsTab.SAVED -> SavedLyricsTab(
                            state = savedLyrics,
                            track = track,
                            isDeleting = isDeleting,
                            onDelete = viewModel::deleteSavedLyrics
                        )
                        LyricsDetailsTab.SEARCH -> SearchTab(
                            state = uiState,
                            track = track,
                            isSaving = isSaving,
                            onSaveAsFile = viewModel::saveAsFile,
                            onEmbed = viewModel::embed
                        )
                    }
                }
            }
        }
    }
}

/**
 * Segmented control between the saved-lyrics and search tabs: an accent pill
 * slides under the selected tab, and each label carries the standard ripple.
 */
@Composable
private fun LyricsDetailsTabs(
    selected: LyricsDetailsTab,
    onSelect: (LyricsDetailsTab) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(FoamTheme.tintedSurface)
            .padding(FoamDimens.spacingTiny)
    ) {
        val pillWidth = (maxWidth - FoamDimens.spacingTiny) / 2
        val pillOffset by animateDpAsState(
            targetValue = if (selected == LyricsDetailsTab.SAVED) {
                0.dp
            } else {
                pillWidth + FoamDimens.spacingTiny
            },
            animationSpec = tween(FoamMotion.Duration.STANDARD, easing = FoamMotion.Easings.STANDARD),
            label = "lyricsTabPill"
        )

        // Sliding selection pill, drawn behind the labels. The lambda offset
        // moves it without recomposition on every animation frame.
        Box(
            modifier = Modifier
                .offset { IntOffset(pillOffset.roundToPx(), 0) }
                .width(pillWidth)
                .height(LyricsTabHeight)
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .background(FoamTheme.accentColor)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingTiny)
        ) {
            TabLabel(
                label = stringResource(R.string.lyrics_details_tab_saved),
                selected = selected == LyricsDetailsTab.SAVED,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LyricsDetailsTab.SAVED) }
            )
            TabLabel(
                label = stringResource(R.string.lyrics_details_tab_search),
                selected = selected == LyricsDetailsTab.SEARCH,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LyricsDetailsTab.SEARCH) }
            )
        }
    }
}

/** One tab label of the segmented control, transparent so the pill shows through. */
@Composable
private fun TabLabel(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(LyricsTabHeight)
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary)
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.body.copy(
                color = if (selected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
            ),
            maxLines = 1
        )
    }
}

/** The saved-lyrics tab: one item per stored source (cache / file / embedded). */
@Composable
private fun SavedLyricsTab(
    state: SavedLyricsUiState,
    track: TrackEntity?,
    isDeleting: Boolean,
    onDelete: (SavedLyricsEntry) -> Unit
) {
    when (state) {
        SavedLyricsUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FoamTheme.accentColor)
            }
        }
        is SavedLyricsUiState.Loaded -> {
            if (state.entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(
                        text = stringResource(R.string.lyrics_details_saved_empty),
                        style = FoamTheme.typography.body
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = FoamDimens.spacingSmall,
                        bottom = FoamDimens.spacingLarge + LocalBottomBarClearance.current
                    ),
                    verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
                ) {
                    items(state.entries, key = { "${it.source.name}_${it.content.hashCode()}" }) { entry ->
                        SavedLyricsItem(
                            entry = entry,
                            track = track,
                            isDeleting = isDeleting,
                            onDelete = onDelete
                        )
                    }
                }
            }
        }
    }
}

/**
 * One stored lyrics payload: source + type badge, duration info, lyrics
 * preview with expand/format toggle, and a delete action that removes only
 * that source's lyrics.
 */
@Composable
private fun SavedLyricsItem(
    entry: SavedLyricsEntry,
    track: TrackEntity?,
    isDeleting: Boolean,
    onDelete: (SavedLyricsEntry) -> Unit
) {
    var expanded by remember(entry.content) { mutableStateOf(false) }
    var showRaw by remember(entry.content) { mutableStateOf(false) }
    val displayText = remember(entry.content, showRaw) {
        if (showRaw) entry.content else LyricsUtils.displayLyricsText(entry.content)
    }
    val previewLines = remember(displayText) {
        displayText.lines().filter { it.isNotBlank() }.take(RESULT_PREVIEW_LINES)
    }
    val typeLabel = stringResource(
        when (entry.type) {
            LyricsResultType.PLAIN -> R.string.lyrics_results_plain
            LyricsResultType.SYNCED -> R.string.lyrics_results_synced
            LyricsResultType.WORD_SYNCED -> R.string.lyrics_results_word_synced
        }
    )
    val sourceLabel = stringResource(
        when (entry.source) {
            LyricsSource.ONLINE -> R.string.lyrics_manager_cached
            LyricsSource.LYRICS_FILE -> R.string.lyrics_manager_file
            LyricsSource.EMBEDDED -> R.string.lyrics_manager_embedded
        }
    )
    val durationsMatch = track != null &&
            LyricsSearchHelper.durationsMatch(entry.derivedDurationMs, track.duration)
    val durationDelta = track?.let {
        LyricsSearchHelper.durationDeltaLabel(entry.derivedDurationMs, it.duration)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .foamSurface()
            .padding(FoamDimens.paddingSmall)
            .animateContentSize()
    ) {
        // Header: track metadata + source / provider / type badge.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = track?.title ?: "",
                    style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                BasicText(
                    text = listOfNotNull(track?.artist, track?.album)
                        .joinToString(" • ")
                        .ifEmpty { sourceLabel },
                    style = FoamTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                    .foamSurfaceVariant()
                    .padding(horizontal = FoamDimens.spacingSmall, vertical = FoamDimens.spacingTiny)
            ) {
                BasicText(
                    text = listOfNotNull(sourceLabel, entry.providerName, typeLabel).joinToString(" • "),
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))

        // Duration row: derived duration, then match checkmark or delta.
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = entry.derivedDurationMs?.let(TimeUtils::formatDurationCompact) ?: "—",
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
            when {
                durationsMatch -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.lyrics_results_duration_match),
                    tint = FoamTheme.colors.bitPerfectActive,
                    modifier = Modifier.size(16.dp)
                )
                durationDelta != null -> BasicText(
                    text = durationDelta,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
                )
            }
        }

        Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))

        // Lyrics preview (first lines) / full text when expanded, in readable
        // or raw form depending on the format toggle.
        if (displayText.isNotBlank()) {
            LyricsFormatToggle(
                showRaw = showRaw,
                onToggle = { showRaw = it }
            )

            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))

            if (expanded) {
                BasicText(
                    text = displayText,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                BasicText(
                    text = previewLines.joinToString("\n"),
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary),
                    maxLines = RESULT_PREVIEW_LINES,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                    .clickable { expanded = !expanded }
                    .padding(vertical = FoamDimens.spacingTiny),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = stringResource(
                            if (expanded) R.string.lyrics_results_collapse else R.string.lyrics_results_expand
                        ),
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = FoamTheme.accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))

        // Deletes only this source's lyrics (cache entry / sidecar files /
        // embedded tag); the other sources stay untouched.
        DeleteSavedLyricsButton(
            enabled = !isDeleting,
            onClick = { onDelete(entry) }
        )
    }
}

@Composable
private fun DeleteSavedLyricsButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(
                if (enabled) FoamTheme.accentColor.copy(alpha = 0.15f) else FoamTheme.tintedSurfaceVariant
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled
            ) { onClick() }
            .padding(vertical = FoamDimens.spacingSmall),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = stringResource(R.string.delete),
            style = FoamTheme.typography.body.copy(
                color = if (enabled) FoamTheme.accentColor else FoamTheme.colors.textSecondary
            )
        )
    }
}

/** The search tab: the results of the multi-provider search run on load. */
@Composable
private fun SearchTab(
    state: LyricsSearchUiState,
    track: TrackEntity?,
    isSaving: Boolean,
    onSaveAsFile: (TrackEntity, LyricsSearchResult) -> Unit,
    onEmbed: (TrackEntity, LyricsSearchResult) -> Unit
) {
    when (val currentState = state) {
        LyricsSearchUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FoamTheme.accentColor)
            }
        }
        LyricsSearchUiState.NotFound -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    text = stringResource(R.string.lyrics_not_found),
                    style = FoamTheme.typography.body
                )
            }
        }
        is LyricsSearchUiState.Loaded -> {
            LyricsResultsList(
                results = currentState.results,
                track = track,
                isSaving = isSaving,
                onSaveAsFile = onSaveAsFile,
                onEmbed = onEmbed
            )
        }
    }
}

@Composable
private fun LyricsResultTrackHeader(track: TrackEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .foamSurface()
            .padding(FoamDimens.paddingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .foamSurfaceVariant(),
            contentAlignment = Alignment.Center
        ) {
            if (track.albumArtPath != null) {
                val context = LocalContext.current
                val albumArtRequest = remember(track.albumArtPath) {
                    ImageRequest.Builder(context)
                        .data(EmbeddedAlbumArt(track.albumArtPath.toUri()))
                        .size(128)
                        .build()
                }
                AsyncImage(
                    model = albumArtRequest,
                    contentDescription = stringResource(R.string.album_art),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = track.format,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
                )
            }
        }
        Spacer(modifier = Modifier.width(FoamDimens.paddingSmall))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = track.title,
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicText(
                text = "${track.artist} • ${track.album} • ${TimeUtils.formatDurationCompact(track.duration)}",
                style = FoamTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LyricsResultsList(
    results: List<LyricsSearchResult>,
    track: TrackEntity?,
    isSaving: Boolean,
    onSaveAsFile: (TrackEntity, LyricsSearchResult) -> Unit,
    onEmbed: (TrackEntity, LyricsSearchResult) -> Unit
) {
    // None selected = show every type; each chip toggles its type in/out.
    var selectedTypes by remember { mutableStateOf(emptySet<LyricsResultType>()) }

    val visibleResults = remember(results, selectedTypes) {
        if (selectedTypes.isEmpty()) results
        else results.filter { it.type in selectedTypes }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TypeFilterChips(
            selectedTypes = selectedTypes,
            onToggle = { type ->
                selectedTypes = if (type in selectedTypes) selectedTypes - type else selectedTypes + type
            }
        )
        Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))

        if (visibleResults.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    text = stringResource(R.string.lyrics_not_found),
                    style = FoamTheme.typography.body
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = FoamDimens.spacingSmall,
                bottom = FoamDimens.spacingLarge + LocalBottomBarClearance.current
            ),
            verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
        ) {
            items(visibleResults, key = { "${it.providerName}_${it.content.hashCode()}" }) { result ->
                LyricsResultItem(
                    result = result,
                    track = track,
                    isSaving = isSaving,
                    onSaveAsFile = onSaveAsFile,
                    onEmbed = onEmbed
                )
            }
        }
    }
}

@Composable
private fun TypeFilterChips(
    selectedTypes: Set<LyricsResultType>,
    onToggle: (LyricsResultType) -> Unit
) {
    val chips = listOf(
        LyricsResultType.PLAIN to stringResource(R.string.lyrics_results_plain),
        LyricsResultType.SYNCED to stringResource(R.string.lyrics_results_synced),
        LyricsResultType.WORD_SYNCED to stringResource(R.string.lyrics_results_word_synced)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
    ) {
        chips.forEach { (type, label) ->
            val isSelected = type in selectedTypes
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                    .background(if (isSelected) FoamTheme.accentColor else FoamTheme.tintedSurface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = FoamTheme.colors.textPrimary)
                    ) { onToggle(type) }
                    .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingTiny)
            ) {
                BasicText(
                    text = label,
                    style = FoamTheme.typography.caption.copy(
                        color = if (isSelected) FoamTheme.tintedBackground else FoamTheme.colors.textPrimary
                    )
                )
            }
        }
    }
}

@Composable
private fun LyricsResultItem(
    result: LyricsSearchResult,
    track: TrackEntity?,
    isSaving: Boolean,
    onSaveAsFile: (TrackEntity, LyricsSearchResult) -> Unit,
    onEmbed: (TrackEntity, LyricsSearchResult) -> Unit
) {
    var expanded by remember(result.content) { mutableStateOf(false) }
    var showRaw by remember(result.content) { mutableStateOf(false) }
    val displayText = remember(result.content, showRaw) {
        if (showRaw) result.content else LyricsUtils.displayLyricsText(result.content)
    }
    val previewLines = remember(displayText) { displayText.lines().filter { it.isNotBlank() }.take(RESULT_PREVIEW_LINES) }
    val typeLabel = stringResource(
        when (result.type) {
            LyricsResultType.PLAIN -> R.string.lyrics_results_plain
            LyricsResultType.SYNCED -> R.string.lyrics_results_synced
            LyricsResultType.WORD_SYNCED -> R.string.lyrics_results_word_synced
        }
    )
    val currentTrack = track
    val durationsMatch = currentTrack != null &&
            LyricsSearchHelper.durationsMatch(result.derivedDurationMs, currentTrack.duration)
    val durationDelta = currentTrack?.let {
        LyricsSearchHelper.durationDeltaLabel(result.derivedDurationMs, it.duration)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .foamSurface()
            .padding(FoamDimens.paddingSmall)
            .animateContentSize()
    ) {
        // Result header: metadata, provider, duration + delta.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = track?.title ?: "",
                    style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                BasicText(
                    text = listOfNotNull(track?.artist, track?.album)
                        .joinToString(" • ")
                        .ifEmpty { typeLabel },
                    style = FoamTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                    .foamSurfaceVariant()
                    .padding(horizontal = FoamDimens.spacingSmall, vertical = FoamDimens.spacingTiny)
            ) {
                BasicText(
                    text = "${result.providerName} • $typeLabel",
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))

        // Duration row: derived duration, then match checkmark or delta.
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = result.derivedDurationMs?.let(TimeUtils::formatDurationCompact) ?: "—",
                style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
            )
            Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
            when {
                durationsMatch -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.lyrics_results_duration_match),
                    tint = FoamTheme.colors.bitPerfectActive,
                    modifier = Modifier.size(16.dp)
                )
                durationDelta != null -> BasicText(
                    text = durationDelta,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
                )
            }
        }

        Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))

        // Lyrics preview (first lines) / full text when expanded, in readable
        // or raw form depending on the format toggle.
        if (displayText.isNotBlank()) {
            LyricsFormatToggle(
                showRaw = showRaw,
                onToggle = { showRaw = it }
            )

            Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))

            if (expanded) {
                BasicText(
                    text = displayText,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                BasicText(
                    text = previewLines.joinToString("\n"),
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textPrimary),
                    maxLines = RESULT_PREVIEW_LINES,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                    .clickable { expanded = !expanded }
                    .padding(vertical = FoamDimens.spacingTiny),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = stringResource(
                            if (expanded) R.string.lyrics_results_collapse else R.string.lyrics_results_expand
                        ),
                        style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = FoamTheme.accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(FoamDimens.spacingTiny))

        // Actions: sidecar file write / tag embed. Word-synced (TTML) lyrics
        // cannot be embedded — the reader only surfaces plain text from tags —
        // so they are restricted to the sidecar file.
        Row(horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)) {
            ActionButton(
                label = stringResource(R.string.lyrics_results_save_as_file),
                enabled = track != null && !isSaving,
                onClick = { track?.let { onSaveAsFile(it, result) } }
            )
            ActionButton(
                label = stringResource(R.string.lyrics_results_embed),
                enabled = currentTrack != null && !isSaving &&
                        result.type != LyricsResultType.WORD_SYNCED,
                onClick = { currentTrack?.let { onEmbed(it, result) } }
            )
        }
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(FoamDimens.buttonRounding))
            .background(if (enabled) FoamTheme.tintedSurfaceVariant else FoamTheme.tintedSurface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                enabled = enabled
            ) { onClick() }
            .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingSmall)
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(
                color = if (enabled) FoamTheme.colors.textPrimary else FoamTheme.colors.textSecondary
            )
        )
    }
}

private const val RESULT_PREVIEW_LINES = 5
