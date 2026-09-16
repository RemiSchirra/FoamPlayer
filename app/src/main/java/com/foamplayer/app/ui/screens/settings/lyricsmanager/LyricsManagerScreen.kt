package com.foamplayer.app.ui.screens.settings.lyricsmanager

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.lyrics.LyricsSourceStatus
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.screens.settings.SettingsHeader
import com.foamplayer.app.ui.theme.DefaultPrimaryAccent
import com.foamplayer.app.ui.theme.FoamDimens
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.TimeUtils
import kotlinx.coroutines.launch

private enum class LyricsFilter { ALL, NO_LYRICS, CACHED, FILE, EMBEDDED }

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricsManagerScreen(
    onBack: () -> Unit,
    onOpenResults: (Long) -> Unit,
    viewModel: LyricsManagerViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsState()
    val cachedTrackIds by viewModel.cachedTrackIds.collectAsState()
    val statusMap by viewModel.statusMap.collectAsState()
    val stats by viewModel.stats.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LyricsFilter.ALL) }
    var modalTrack by remember { mutableStateOf<TrackEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val trackDetails by viewModel.trackDetails.collectAsState()
    val isDetailsLoading by viewModel.isDetailsLoading.collectAsState()

    // Compose keeps the text field focused after the keyboard is dismissed, so
    // drop focus explicitly once the IME is closed — otherwise the caret keeps
    // blinking in the search bar. Both the animation target and the live insets
    // are observed, and the platform view focus is cleared as well: on some
    // devices the compose-level clear alone leaves the caret visible.
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val view = LocalView.current
    val imeTargetBottom = WindowInsets.imeAnimationTarget.getBottom(density)
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeTargetBottom, imeBottom) {
        if (imeTargetBottom == 0 && imeBottom == 0) {
            focusManager.clearFocus()
            view.clearFocus()
        }
    }

    // Re-scan on every resume: a save/embed on the results screen changes the
    // sources of the track the user just came back from.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.deleteEvents.collect { event ->
            val message = when (event) {
                is LyricsDeleteEvent.Deleted ->
                    if (event.embeddedRemained) {
                        context.getString(R.string.lyrics_manager_deleted_partial)
                    } else {
                        context.getString(R.string.lyrics_manager_deleted)
                    }
                is LyricsDeleteEvent.Failed ->
                    context.getString(R.string.lyrics_manager_delete_failed, event.reason)
            }
            // Non-blocking: showSnackbar suspends until the snackbar is dismissed,
            // which must not delay the details-modal refresh below.
            launch { snackbarHostState.showSnackbar(message) }
            // Keep the details modal in sync with the deleted state.
            modalTrack?.let { viewModel.loadTrackDetails(it) }
        }
    }

    // Load the modal's lyrics details whenever a track is long-pressed.
    LaunchedEffect(modalTrack) {
        modalTrack?.let { viewModel.loadTrackDetails(it) }
    }

    val visibleTracks = remember(tracks, cachedTrackIds, statusMap, searchQuery, filter) {
        filterTracks(tracks, cachedTrackIds, statusMap, searchQuery, filter)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FoamTheme.colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FoamDimens.paddingSmall)
        ) {
        SettingsHeader(
            title = stringResource(R.string.lyrics_manager),
            onBack = onBack,
            trailing = {
                // Live indicator for the per-track lyrics source scan.
                if (stats.isScanning) {
                    CircularProgressIndicator(
                        color = FoamTheme.accentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .padding(horizontal = FoamDimens.paddingSmall)
                            .size(FoamDimens.iconMedium)
                    )
                }
            }
        )

        LyricsStatsRow(
            stats = stats,
        )

        Spacer(modifier = Modifier.height(FoamDimens.spacingStandard))

        LyricsSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
        )

        Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))

        LyricsFilterChips(
            selected = filter,
            onSelect = { filter = it },
        )

        Spacer(modifier = Modifier.height(FoamDimens.spacingSmall))

        when {
            stats.isScanning && stats.total == 0 -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FoamTheme.accentColor)
                }
            }
            visibleTracks.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(
                        text = stringResource(R.string.lyrics_manager_no_songs),
                        style = FoamTheme.typography.body
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        vertical = FoamDimens.spacingSmall
                    ),
                    verticalArrangement = Arrangement.spacedBy(FoamDimens.spacingTiny)
                ) {
                    items(visibleTracks, key = { it.id }) { track ->
                        LyricsManagerSongItem(
                            track = track,
                            cached = track.id in cachedTrackIds,
                            status = statusMap[track.id],
                            onOpenResults = { onOpenResults(track.id) },
                            onLongPress = { modalTrack = track }
                        )
                    }
                }
            }
        }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = FoamDimens.paddingMedium)
        )
    }

    LyricsTrackDetailsModal(
        visible = modalTrack != null,
        track = modalTrack,
        details = trackDetails?.takeIf { it.track.id == modalTrack?.id },
        isLoading = isDetailsLoading,
        onDismiss = { modalTrack = null },
        onDelete = { modalTrack?.let { viewModel.deleteLyrics(it) } },
        onSearch = {
            val trackId = modalTrack?.id
            modalTrack = null
            trackId?.let(onOpenResults)
        }
    )
}

private fun filterTracks(
    tracks: List<TrackEntity>,
    cachedTrackIds: Set<Long>,
    statusMap: Map<Long, LyricsSourceStatus>,
    query: String,
    filter: LyricsFilter
): List<TrackEntity> {
    val trimmedQuery = query.trim()
    return tracks.filter { track ->
        val matchesQuery = trimmedQuery.isEmpty() ||
                track.title.contains(trimmedQuery, ignoreCase = true) ||
                track.artist.contains(trimmedQuery, ignoreCase = true) ||
                track.album.contains(trimmedQuery, ignoreCase = true)
        if (!matchesQuery) return@filter false

        when (filter) {
            LyricsFilter.ALL -> true
            LyricsFilter.CACHED -> track.id in cachedTrackIds
            LyricsFilter.FILE -> statusMap[track.id]?.hasFile == true
            LyricsFilter.EMBEDDED -> statusMap[track.id]?.hasEmbedded == true
            LyricsFilter.NO_LYRICS -> track.id !in cachedTrackIds &&
                    statusMap[track.id]?.let { !it.hasFile && !it.hasEmbedded } == true
        }
    }
}

@Composable
private fun LyricsStatsRow(stats: LyricsManagerStats, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.cornerRadiusSmall))
            .background(FoamTheme.colors.surface)
            .padding(vertical = FoamDimens.paddingSmall)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(stringResource(R.string.lyrics_manager_cached), stats.cached)
            StatCell(stringResource(R.string.lyrics_manager_embedded), stats.embedded)
            StatCell(stringResource(R.string.lyrics_manager_file), stats.file)
            StatCell(stringResource(R.string.lyrics_manager_no_lyrics), stats.noLyrics)
            StatCell(stringResource(R.string.lyrics_manager_total), stats.total)
        }
    }
}

@Composable
private fun RowScope.StatCell(label: String, value: Int) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            text = value.toString(),
            style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.accentColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LyricsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(FoamDimens.searchBarRounding))
            .background(FoamTheme.colors.surface)
            .padding(horizontal = FoamDimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = FoamTheme.icons.current.search,
            contentDescription = null,
            tint = FoamTheme.colors.textSecondary,
            modifier = Modifier.size(FoamDimens.iconSmall)
        )
        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                BasicText(
                    text = stringResource(R.string.lyrics_manager_search_hint),
                    style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textSecondary)
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                cursorBrush = SolidColor(FoamTheme.accentColor),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = FoamTheme.icons.current.close,
                contentDescription = stringResource(R.string.clear_selection),
                tint = FoamTheme.colors.textSecondary,
                modifier = Modifier
                    .size(FoamDimens.iconMedium)
                    .clickable { onQueryChange("") }
                    .padding(FoamDimens.spacingTiny)
            )
        }
    }
}

@Composable
private fun LyricsFilterChips(
    selected: LyricsFilter,
    onSelect: (LyricsFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val filters = listOf(
        Triple(LyricsFilter.ALL, stringResource(R.string.lyrics_manager_filter_all), null),
        Triple(
            LyricsFilter.NO_LYRICS,
            stringResource(R.string.lyrics_manager_no_lyrics),
            DefaultPrimaryAccent
        ),
        Triple(
            LyricsFilter.CACHED,
            stringResource(R.string.lyrics_manager_cached),
            FoamTheme.colors.bitPerfectInactive
        ),
        Triple(
            LyricsFilter.FILE,
            stringResource(R.string.lyrics_manager_file),
            FoamTheme.colors.bitPerfectActive
        ),
        Triple(
            LyricsFilter.EMBEDDED,
            stringResource(R.string.lyrics_manager_embedded),
            FoamTheme.colors.bitPerfectActive
        )
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(FoamDimens.spacingSmall)
    ) {
        filters.forEach { (filterValue, label, dotColor) ->
            val isSelected = filterValue == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(FoamDimens.buttonRounding))
                    .background(
                        if (isSelected) FoamTheme.accentColor else FoamTheme.colors.surface
                    )
                    .clickable { onSelect(filterValue) }
                    .padding(horizontal = FoamDimens.paddingSmall, vertical = FoamDimens.spacingTiny)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dotColor != null) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(FoamDimens.spacingTiny))
                    }
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
}

/**
 * A lyrics-manager song row: album art, title, artists, album, duration and a
 * lyrics-presence dot (red = none, orange = cached only, green = file or
 * embedded). Tap opens the lyrics search results, long-press opens the lyrics
 * details modal; both carry the standard ripple. Deliberately its own
 * composable (not a shared MediaListRow / TrackListItem).
 */
@Composable
private fun LyricsManagerSongItem(
    track: TrackEntity,
    cached: Boolean,
    status: LyricsSourceStatus?,
    onOpenResults: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FoamDimens.itemRounding))
            .background(FoamTheme.colors.surface)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = onOpenResults,
                onLongClick = onLongPress
            )
            .padding(horizontal = FoamDimens.paddingSmall, vertical = FoamDimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
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
                text = "${track.artist} • ${track.album}",
                style = FoamTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(FoamDimens.spacingSmall))

        BasicText(
            text = TimeUtils.formatDurationCompact(track.duration),
            style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary)
        )

        Spacer(modifier = Modifier.width(FoamDimens.spacingTiny))

        // Lyrics presence: green = file/embedded, orange = cached only, red = none.
        // Neutral until the per-track scan reached this row.
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    when {
                        status?.hasFile == true || status?.hasEmbedded == true ->
                            FoamTheme.colors.bitPerfectActive
                        cached || status?.hasCached == true ->
                            FoamTheme.colors.bitPerfectInactive
                        status == null -> FoamTheme.colors.textSecondary
                        else -> DefaultPrimaryAccent
                    }
                )
        )

        Box(
            modifier = Modifier
                .size(FoamDimens.headerIconSize)
                .clip(RoundedCornerShape(FoamDimens.headerIconRounding))
                .clickable { onOpenResults() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = FoamTheme.icons.current.search,
                contentDescription = stringResource(R.string.search),
                tint = FoamTheme.colors.textPrimary,
                modifier = Modifier.size(FoamDimens.iconSmall)
            )
        }
    }
}
