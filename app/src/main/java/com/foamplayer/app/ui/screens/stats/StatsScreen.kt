package com.foamplayer.app.ui.screens.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import coil.request.ImageRequest
import com.foamplayer.app.R
import com.foamplayer.app.data.db.dao.TopAlbumStat
import com.foamplayer.app.data.db.dao.TopTrackStat
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.AlbumArtImage
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.CollectionInfo
import com.foamplayer.app.ui.components.CollectionKind
import com.foamplayer.app.ui.components.FoamGlassCard
import com.foamplayer.app.ui.components.LocalCollectionFoamModalState
import com.foamplayer.app.ui.components.LocalTrackFoamModalState
import com.foamplayer.app.ui.components.MediaListRow
import com.foamplayer.app.ui.components.listRowArtCornerRadius
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** Rows of a ranked list shown before its "show more" toggle. */
private const val CollapsedListSize = 5

/** Rows of a ranked list shown when expanded (the full list lives on the see-all page). */
private const val TopListDisplaySize = 10

/**
 * Listening stats screen: period selector (1 week .. all time), headline
 * counters (listening time, plays, unique tracks, artists), an activity
 * timeline chart, a top-artists donut, a listening clock and ranked
 * track/artist/album/genre lists — all scoped to the selected period.
 * Reached from the nav bar's second bar by default.
 */
@Composable
fun StatsScreen(
    onArtistClick: (artist: String, imageUrl: String?) -> Unit,
    onAlbumClick: (album: String, artUrl: String?, artist: String?) -> Unit,
    onSeeAll: (kind: StatsTopKind, period: StatsPeriod) -> Unit,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val selectedPeriod by viewModel.period.collectAsState()
    val artistImages by viewModel.artistImages.collectAsState()
    val trackModalState = LocalTrackFoamModalState.current
    val collectionModalState = LocalCollectionFoamModalState.current
    val longPressScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = LocalBottomBarClearance.current)
    ) {
        // ------------------------------------------------------------ header
        BasicText(text = stringResource(R.string.statistics), style = FoamTheme.typography.titleLarge)
        BasicText(text = periodSubtitle(selectedPeriod), style = FoamTheme.typography.caption)

        Spacer(modifier = Modifier.height(16.dp))

        // ----------------------------------------------------- period chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatsPeriod.entries.forEach { entry ->
                PeriodChip(
                    label = periodLabel(entry),
                    selected = entry == selectedPeriod,
                    onClick = { viewModel.setPeriod(entry) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            state.loading -> FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                BasicText(
                    text = stringResource(R.string.stats_loading),
                    style = FoamTheme.typography.body
                )
            }

            state.totalPlays == 0 -> FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = FoamTheme.icons.current.stats,
                        contentDescription = null,
                        tint = FoamTheme.colors.textSecondary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BasicText(
                        text = stringResource(R.string.stats_empty_title),
                        style = FoamTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    BasicText(
                        text = stringResource(R.string.stats_empty_body),
                        style = FoamTheme.typography.caption.copy(textAlign = TextAlign.Center)
                    )
                }
            }

            else -> {
                // ------------------------------------------------ overview 2x2
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Schedule,
                        value = formatListeningTime(state.totalDurationMs),
                        label = stringResource(R.string.stats_listening_time),
                        caption = stringResource(
                            R.string.stats_avg_per_day,
                            formatListeningTime(state.dailyAverageMs)
                        )
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.PlayArrow,
                        value = state.totalPlays.toString(),
                        label = stringResource(R.string.stats_plays),
                        caption = stringResource(R.string.stats_active_days, state.activeDays)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.MusicNote,
                        value = state.uniqueTracks.toString(),
                        label = stringResource(R.string.stats_unique_tracks)
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Person,
                        value = state.uniqueArtists.toString(),
                        label = stringResource(R.string.stats_unique_artists)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // -------------------------------------------- activity chart
                FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ChartTitle(stringResource(R.string.stats_activity))
                        Spacer(modifier = Modifier.height(12.dp))
                        StatsActivityChart(buckets = state.timeBuckets)
                    }
                }

                // ------------------------------------------------ donut chart
                if (state.topArtists.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            ChartTitle(stringResource(R.string.stats_artists_share))
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val othersLabel = stringResource(R.string.stats_others)
                                Box(contentAlignment = Alignment.Center) {
                                    StatsDonutChart(
                                        slices = artistSlices(state, othersLabel),
                                        modifier = Modifier.size(132.dp)
                                    )
                                    BasicText(
                                        text = formatListeningTime(state.totalDurationMs),
                                        style = FoamTheme.typography.titleMedium.copy(fontSize = 15.sp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(20.dp))
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    artistSlices(state, othersLabel).forEach { slice ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(slice.color)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            BasicText(
                                                text = slice.label,
                                                style = FoamTheme.typography.body,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            BasicText(
                                                text = "${(slice.fraction * 100).roundToInt()}%",
                                                style = FoamTheme.typography.caption
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // -------------------------------------------- listening clock
                if (state.hourHistogram.any { it > 0 }) {
                    Spacer(modifier = Modifier.height(16.dp))
                    FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            ChartTitle(stringResource(R.string.stats_listening_clock))
                            state.peakHour?.let { peak ->
                                Spacer(modifier = Modifier.height(2.dp))
                                BasicText(
                                    text = stringResource(
                                        R.string.stats_peak_hour,
                                        String.format(Locale.getDefault(), "%02d:00", peak)
                                    ),
                                    style = FoamTheme.typography.caption
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            StatsHourChart(hours = state.hourHistogram)
                        }
                    }
                }

                // -------------------------------------------------- top tracks
                TopSection(
                    title = stringResource(R.string.stats_top_tracks),
                    items = state.topTracks.take(TopListDisplaySize),
                    itemKey = { it.track.id },
                    onSeeAllClick = { onSeeAll(StatsTopKind.TRACKS, selectedPeriod) }
                ) { index, entry ->
                    TopTrackRow(
                        entry = entry,
                        // Tap plays from the ranked list; long-press opens the track modal.
                        onOpen = { onTrackClick(state.topTracks.map { it.track }, index) },
                        onLongClick = { trackModalState?.show(entry.track) }
                    )
                }

                // ------------------------------------------------- top artists
                TopSection(
                    title = stringResource(R.string.stats_top_artists),
                    items = state.topArtists.take(TopListDisplaySize),
                    itemKey = { it.name },
                    onSeeAllClick = { onSeeAll(StatsTopKind.ARTISTS, selectedPeriod) }
                ) { _, artist ->
                    TopArtistRow(
                        artist = artist,
                        imageUrl = artistImages[artist.name],
                        onOpen = { onArtistClick(artist.name, artistImages[artist.name]) },
                        onLongClick = {
                            longPressScope.launch {
                                collectionModalState?.show(
                                    CollectionInfo(
                                        title = artist.name,
                                        // The Spotify artist picture is a plain cache
                                        // file, not embedded art: the header must load
                                        // it directly instead of extracting a picture
                                        // from an audio container.
                                        imageUrl = artistImages[artist.name],
                                        imageIsEmbeddedArt = false,
                                        kind = CollectionKind.Artist,
                                        tracks = viewModel.tracksForArtist(artist.name)
                                    )
                                )
                            }
                        }
                    )
                }

                // -------------------------------------------------- top albums
                TopSection(
                    title = stringResource(R.string.stats_top_albums),
                    items = state.topAlbums.take(TopListDisplaySize),
                    itemKey = { it.album },
                    onSeeAllClick = { onSeeAll(StatsTopKind.ALBUMS, selectedPeriod) }
                ) { _, album ->
                    TopAlbumRow(
                        entry = album,
                        onOpen = { onAlbumClick(album.album, album.albumArtPath, album.artist) },
                        onLongClick = {
                            longPressScope.launch {
                                collectionModalState?.show(
                                    CollectionInfo(
                                        title = album.album,
                                        artist = album.artist,
                                        imageUrl = album.albumArtPath,
                                        kind = CollectionKind.Album,
                                        tracks = viewModel.tracksForAlbum(album.album)
                                    )
                                )
                            }
                        }
                    )
                }

                // -------------------------------------------------- top genres
                if (state.topGenres.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            ChartTitle(stringResource(R.string.stats_top_genres))
                            Spacer(modifier = Modifier.height(12.dp))
                            val maxPlays = state.topGenres.maxOf { it.playCount }
                            state.topGenres.forEach { genre ->
                                GenreBar(genre = genre, maxPlays = maxPlays)
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun PeriodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) FoamTheme.accentColor else FoamTheme.colors.surfaceVariant)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.caption.copy(
                color = if (selected) FoamTheme.colors.background else FoamTheme.colors.textPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        )
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    caption: String? = null
) {
    FoamGlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = FoamTheme.accentColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                BasicText(
                    text = value,
                    style = FoamTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    maxLines = 1
                )
                BasicText(text = label, style = FoamTheme.typography.caption)
                caption?.let {
                    BasicText(
                        text = it,
                        style = FoamTheme.typography.caption.copy(
                            color = FoamTheme.colors.textSecondary.copy(alpha = 0.7f)
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartTitle(text: String, modifier: Modifier = Modifier) {
    BasicText(text = text, style = FoamTheme.typography.titleMedium, modifier = modifier)
}

/**
 * Ranked-list card: [CollapsedListSize] standard media rows, header carrying a
 * "see all" arrow button (same affordance as the Home rows) opening the full
 * ranked list for the period. Rows are keyed on [itemKey]: when new listens
 * reshuffle the ranking, a row that changes slot glides from its old position
 * to the new one instead of jumping. Layout is untouched — the motion is a pure
 * graphicsLayer translation.
 */
@Composable
private fun <T> TopSection(
    title: String,
    items: List<T>,
    itemKey: (T) -> Any,
    onSeeAllClick: () -> Unit,
    content: @Composable (index: Int, item: T) -> Unit
) {
    if (items.isEmpty()) return

    Spacer(modifier = Modifier.height(16.dp))
    FoamGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChartTitle(title, modifier = Modifier.weight(1f))
                SeeAllButton(onClick = onSeeAllClick)
            }
            Spacer(modifier = Modifier.height(8.dp))
            items.take(CollapsedListSize).forEachIndexed { index, item ->
                key(itemKey(item)) {
                    AnimatedRankedRow {
                        content(index, item)
                    }
                }
            }
        }
    }
}

/**
 * Reorder animation for one ranked row: tracks the row's y inside the list's
 * Column; when it jumps (the ranking reshuffled), the layer starts from the
 * old slot's offset and springs back to 0, so the row visually glides into
 * place. Layout is untouched — the motion is a pure graphicsLayer translation.
 */
@Composable
private fun AnimatedRankedRow(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val glideOffset = remember { Animatable(0f) }
    var lastY by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = Modifier
            .onGloballyPositioned { coords ->
                val y = coords.positionInParent().y
                val previous = lastY
                lastY = y
                if (previous != null && kotlin.math.abs(previous - y) > 1f) {
                    scope.launch {
                        glideOffset.snapTo(glideOffset.value + previous - y)
                        glideOffset.animateTo(0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    }
                }
            }
            .graphicsLayer { translationY = glideOffset.value }
    ) {
        content()
    }
}

/** Circular arrow button opening a ranked list's "see all" page (Home-row affordance). */
@Composable
private fun SeeAllButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = FoamTheme.icons.current.arrowForward,
            contentDescription = stringResource(R.string.home_see_all),
            tint = FoamTheme.accentColor,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Shared album-art model: embedded-art fetcher at the app's list-art size. */
@Composable
internal fun albumArtModel(artPath: String?): Any? {
    if (artPath == null) return null
    val context = LocalContext.current
    return remember(artPath) {
        ImageRequest.Builder(context)
            .data(EmbeddedAlbumArt(artPath.toUri()))
            .size(AlbumArtRequestSize)
            .build()
    }
}

/** Ranked track row: artwork, title/artist, play count. Tap plays, long-press opens the track modal. */
@Composable
internal fun TopTrackRow(
    entry: TopTrackStat,
    onOpen: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    MediaListRow(
        onClick = onOpen,
        onLongClick = onLongClick,
        artworkModifier = Modifier
            .clip(RoundedCornerShape(listRowArtCornerRadius()))
            .background(FoamTheme.colors.surfaceVariant),
        artworkContent = {
            val model = albumArtModel(entry.track.albumArtPath)
            if (model != null) {
                AlbumArtImage(
                    model = model,
                    contentDescription = entry.track.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = entry.track.format,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.accentColor)
                )
            }
        },
        title = entry.track.title,
        subtitle = entry.track.artist,
        trailingContent = { RankedPlayBadge(entry.periodPlays) }
    )
}

/** Ranked artist row: picture (or monogram disc), name, listening time. Opens the artist page. */
@Composable
internal fun TopArtistRow(
    artist: ArtistStat,
    imageUrl: String?,
    onOpen: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    MediaListRow(
        onClick = onOpen,
        onLongClick = onLongClick,
        artworkModifier = Modifier
            .clip(CircleShape)
            .background(FoamTheme.colors.surfaceVariant),
        artworkContent = {
            if (imageUrl != null) {
                val context = LocalContext.current
                val artRequest = remember(imageUrl) {
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .size(AlbumArtRequestSize)
                        .build()
                }
                AlbumArtImage(
                    model = artRequest,
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxSize(),
                    placeholderSize = 20.dp
                )
            } else {
                BasicText(
                    text = artist.name.take(1).uppercase(),
                    style = FoamTheme.typography.titleMedium
                )
            }
        },
        title = artist.name,
        subtitle = formatListeningTime(artist.totalDurationMs),
        // The monogram disc is circular: the scaffolds' rounded-square
        // album shadow would peek out behind its corners.
        artworkElevation = 0.dp,
        trailingContent = { RankedPlayBadge(artist.playCount) }
    )
}

/** Ranked album row: artwork, album/artist, play count. Opens the album page. */
@Composable
internal fun TopAlbumRow(
    entry: TopAlbumStat,
    onOpen: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    MediaListRow(
        onClick = onOpen,
        onLongClick = onLongClick,
        artworkModifier = Modifier
            .clip(RoundedCornerShape(listRowArtCornerRadius()))
            .background(FoamTheme.colors.surfaceVariant),
        artworkContent = {
            val model = albumArtModel(entry.albumArtPath)
            if (model != null) {
                AlbumArtImage(
                    model = model,
                    contentDescription = entry.album,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                BasicText(
                    text = entry.album.take(1).uppercase(),
                    style = FoamTheme.typography.caption
                )
            }
        },
        title = entry.album,
        subtitle = entry.artist,
        trailingContent = { RankedPlayBadge(entry.playCount) }
    )
}

/** Trailing "×N" play-count badge of a ranked media row. */
@Composable
private fun RowScope.RankedPlayBadge(count: Int) {
    BasicText(
        text = "×$count",
        style = FoamTheme.typography.body.copy(
            color = FoamTheme.accentColor,
            fontWeight = FontWeight.Bold
        ),
        modifier = Modifier.padding(start = 8.dp)
    )
}

/** Horizontal intensity bar of one genre's play-count share. */
@Composable
private fun GenreBar(genre: GenreStat, maxPlays: Int) {
    val fraction = genre.playCount.toFloat() / maxPlays.coerceAtLeast(1)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "genreBarFraction"
    )
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = genre.name,
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            BasicText(text = "×${genre.playCount}", style = FoamTheme.typography.caption)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(FoamTheme.colors.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(FoamTheme.accentColor)
            )
        }
    }
}

/**
 * Donut slices: the top five artists' share of the period's listening time,
 * plus an "others" remainder so the ring always fills the full circle.
 */
@Composable
private fun artistSlices(state: StatsUiState, othersLabel: String): List<DonutSlice> {
    val accent = FoamTheme.accentColor
    val mutedColor = FoamTheme.colors.textSecondary.copy(alpha = 0.35f)
    return remember(state.topArtists, accent, mutedColor, othersLabel) {
        val total = state.totalDurationMs.coerceAtLeast(1L)
        val top = state.topArtists.take(5)
        val colors = donutPalette(accent, top.size)
        val slices = top.mapIndexed { index, artist ->
            DonutSlice(
                label = artist.name,
                fraction = artist.totalDurationMs.toFloat() / total,
                color = colors[index]
            )
        }
        val others = (1f - slices.fold(0f) { acc, slice -> acc + slice.fraction }).coerceIn(0f, 1f)
        if (others > 0.01f) {
            slices + DonutSlice(label = othersLabel, fraction = others, color = mutedColor)
        } else {
            slices
        }
    }
}

// ---------------------------------------------------------------- helpers

/** Chip label of an analysis period. */
@Composable
private fun periodLabel(period: StatsPeriod): String = stringResource(
    when (period) {
        StatsPeriod.WEEK -> R.string.stats_period_week
        StatsPeriod.MONTH -> R.string.stats_period_month
        StatsPeriod.THREE_MONTHS -> R.string.stats_period_three_months
        StatsPeriod.SIX_MONTHS -> R.string.stats_period_six_months
        StatsPeriod.YEAR -> R.string.stats_period_year
        StatsPeriod.ALL_TIME -> R.string.stats_period_all
    }
)

/** Header subtitle describing the active window. */
@Composable
internal fun periodSubtitle(period: StatsPeriod): String = stringResource(
    when (period) {
        StatsPeriod.WEEK -> R.string.stats_subtitle_week
        StatsPeriod.MONTH -> R.string.stats_subtitle_month
        StatsPeriod.THREE_MONTHS -> R.string.stats_period_three_months
        StatsPeriod.SIX_MONTHS -> R.string.stats_subtitle_six_months
        StatsPeriod.YEAR -> R.string.stats_subtitle_year
        StatsPeriod.ALL_TIME -> R.string.stats_subtitle_all
    }
)

/** "12 h 34 m" style listening-time formatting (seconds dropped from 1h up). */
internal fun formatListeningTime(durationMs: Long): String {
    val totalMinutes = durationMs / 60000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> String.format(Locale.getDefault(), "%d h %02d m", hours, minutes)
        totalMinutes > 0 -> String.format(Locale.getDefault(), "%d min", totalMinutes)
        else -> String.format(Locale.getDefault(), "%d s", durationMs / 1000)
    }
}
