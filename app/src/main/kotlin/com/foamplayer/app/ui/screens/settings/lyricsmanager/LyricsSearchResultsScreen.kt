package com.foamplayer.app.ui.screens.settings.lyricsmanager

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.lyrics.LyricsResultType
import com.foamplayer.app.data.lyrics.LyricsSearchHelper
import com.foamplayer.app.data.lyrics.LyricsSearchResult
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.lyrics.LyricsUtils
import com.foamplayer.app.ui.screens.settings.SettingsHeader
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.TimeUtils

@Composable
fun LyricsSearchResultsScreen(
    trackId: Long,
    onBack: () -> Unit,
    viewModel: LyricsSearchViewModel = hiltViewModel()
) {
    val track by viewModel.track.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                LyricsWriteEvent.SavedToFile -> context.getString(R.string.lyrics_results_saved)
                LyricsWriteEvent.Embedded -> context.getString(R.string.lyrics_results_embedded)
                is LyricsWriteEvent.SaveFailed ->
                    context.getString(R.string.lyrics_results_save_failed, event.reason)
                is LyricsWriteEvent.EmbedFailed ->
                    context.getString(R.string.lyrics_results_embed_failed, event.reason)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingMedium)
        ) {
            SettingsHeader(
                title = stringResource(R.string.lyrics_results_title),
                onBack = onBack
            )

            track?.let { currentTrack ->
                LyricsResultTrackHeader(track = currentTrack)
                Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))
            }

            when (val state = uiState) {
                is LyricsSearchUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = FoamTheme.accentColor)
                    }
                }
                is LyricsSearchUiState.NotFound -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = stringResource(R.string.lyrics_not_found),
                            style = FoamTheme.typography.body
                        )
                    }
                }
                is LyricsSearchUiState.Loaded -> {
                    LyricsResultsList(
                        results = state.results,
                        track = track,
                        isSaving = isSaving,
                        onSaveAsFile = viewModel::saveAsFile,
                        onEmbed = viewModel::embed
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = FoamDimens.paddingMedium + LocalBottomBarClearance.current)
        )
    }
}

@Composable
private fun LyricsResultTrackHeader(track: TrackEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .background(FoamTheme.colors.surface)
            .padding(FoamDimens.paddingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                .background(FoamTheme.colors.surfaceVariant),
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
                    .background(if (isSelected) FoamTheme.accentColor else FoamTheme.colors.surface)
                    .clickable { onToggle(type) }
                    .padding(horizontal = FoamDimens.paddingMedium, vertical = FoamDimens.spacingTiny)
            ) {
                BasicText(
                    text = label,
                    style = FoamTheme.typography.caption.copy(
                        color = if (isSelected) FoamTheme.colors.background else FoamTheme.colors.textPrimary
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
            .background(FoamTheme.colors.surface)
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
                    .background(FoamTheme.colors.surfaceVariant)
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
            .background(if (enabled) FoamTheme.colors.surfaceVariant else FoamTheme.colors.surface)
            .clickable(enabled = enabled, onClick = onClick)
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
