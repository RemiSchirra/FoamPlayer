package com.foamplayer.app.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.ripple
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import coil.request.ImageRequest
import com.foamplayer.app.ui.components.AlbumArtImage
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.AlbumArtBoundsTransform
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.AlbumArtShape
import com.foamplayer.app.ui.modals.CollectionInfo
import com.foamplayer.app.ui.modals.CollectionKind
import com.foamplayer.app.ui.components.ListRowArtSize
import com.foamplayer.app.ui.components.ListRowHighlightPadding
import com.foamplayer.app.ui.modals.LocalCollectionFoamModalState
import com.foamplayer.app.ui.components.LocalCollectionHeroLayoutLookup
import com.foamplayer.app.ui.modals.LocalTrackFoamModalState
import com.foamplayer.app.ui.components.TrackListItem
import com.foamplayer.app.ui.components.albumArtCornerRadius
import com.foamplayer.app.ui.components.albumArtMemoryCacheKey
import com.foamplayer.app.ui.components.collectionRowFlightArtwork
import com.foamplayer.app.ui.navigation.LocalBottomBarClearance
import com.foamplayer.app.ui.navigation.SharedElementEasing
import com.foamplayer.app.ui.navigation.SharedElementTransitionDurationMs
import com.foamplayer.app.ui.navigation.isActiveSharedCollection
import com.foamplayer.app.ui.theme.FoamTheme
import androidx.core.net.toUri
import com.foamplayer.app.constants.CollectionHeroTarget
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Colors of the browse cards shown when the search bar is idle (unfocused, empty query).
private data class BrowseCardData(
    val title: String,
    val color: Color,
    val icon: ImageVector,
    val onClick: () -> Unit
)

private val AllTracksCardColor = Color(0xFF4DB6AC)
private val AlbumCardColor = Color(0xFFEF5350)
private val FolderCardColor = Color(0xFFFFB300)
private val GenreCardColor = Color(0xFF00E676)
private val ArtistCardColor = Color(0xFF40C4FF)
private val YearCardColor = Color(0xFFB388FF)

private const val SwipeDismissThresholdFraction = 0.2f
private val SwipeDismissThreshold = 80.dp
private const val SwipeMaxFade = 0.3f
private const val SwipeDismissDurationMs = 150
private const val SwipeReturnDurationMs = 200

private val textFlightFadeSpec = tween<Float>(
    durationMillis = SharedElementTransitionDurationMs,
    easing = SharedElementEasing
)

/** Blends the color toward [overlayColor] using the given [factor] (0.0 = original, 1.0 = overlay color). */
private fun Color.overlay(overlayColor: Color, factor: Float): Color =
    copy(
        red = red + (overlayColor.red - red) * factor,
        green = green + (overlayColor.green - green) * factor,
        blue = blue + (overlayColor.blue - blue) * factor
    )

/** One colored entry card of the idle browse grid. */
@Composable
private fun BrowseCardItem(card: BrowseCardData, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        card.color.copy(alpha = 0.5f),
                        card.color.copy(alpha = 0.3f)
                    )
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onClick = card.onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = card.icon,
                contentDescription = null,
                tint = card.color.overlay(FoamTheme.colors.textPrimary, 0.1f).copy(alpha = 0.7f),
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            BasicText(text = card.title, style = FoamTheme.typography.titleMedium)
        }
        Icon(
            imageVector = card.icon,
            contentDescription = null,
            tint = card.color.overlay(FoamTheme.colors.textPrimary, 0.1f).copy(alpha = 0.4f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 0.dp, y = 4.dp)
                .size(44.dp)
                .graphicsLayer {
                    scaleX = 2f
                    scaleY = 2f
                    rotationZ = -20f
                }
        )
    }
}

/**
 * The search screen: idle state shows browse cards, active search shows
 * history, and a query shows live results across artists, albums, tracks and
 * lyrics (the lyrics scan streams in from the background).
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onArtistClick: (artist: String, imageUrl: String?, imageIsEmbeddedArt: Boolean) -> Unit,
    onAlbumClick: (AlbumSearchResult) -> Unit,
    onBrowseAllTracks: () -> Unit,
    onBrowseAlbums: () -> Unit,
    onBrowseFolders: () -> Unit,
    onBrowseGenres: () -> Unit,
    onBrowseArtists: () -> Unit,
    onBrowseYears: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    playerTransitionVisibilityScope: AnimatedVisibilityScope,
    sharedTrackId: Long?,
    activeTrackId: Long?,
    onTrackClick: (TrackEntity) -> Unit,
    searchViewModel: SearchViewModel = hiltViewModel()
) {
    val query by searchViewModel.query.collectAsState()
    val uiState by searchViewModel.uiState.collectAsState()
    val artistImages by searchViewModel.artistImages.collectAsState()
    var searchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val collectionModalState = LocalCollectionFoamModalState.current
    val trackModalState = LocalTrackFoamModalState.current
    val longPressScope = rememberCoroutineScope()
    val imeInsets = WindowInsets.ime

    // A modal's Dialog steals focus while the keyboard is up; close the IME
    // first so the sheet opens at its resting position.
    suspend fun awaitKeyboardClosed() {
        focusManager.clearFocus()
        snapshotFlow { imeInsets.getBottom(density) }.first { it == 0 }
    }

    val showTrackModal: (TrackEntity) -> Unit = { track ->
        longPressScope.launch {
            awaitKeyboardClosed()
            trackModalState?.show(track)
        }
    }
    val showAlbumModal: (AlbumSearchResult) -> Unit = { album ->
        longPressScope.launch {
            awaitKeyboardClosed()
            collectionModalState?.show(
                CollectionInfo(
                    title = album.album,
                    artist = album.artist,
                    imageUrl = album.albumArtPath,
                    kind = CollectionKind.Album,
                    tracks = searchViewModel.tracksForAlbum(album.album)
                )
            )
        }
    }
    val showArtistModal: (String) -> Unit = { artist ->
        longPressScope.launch {
            awaitKeyboardClosed()
            val cover = artistImages[artist]
            collectionModalState?.show(
                CollectionInfo(
                    title = artist,
                    // Either a Spotify cache file (plain) or an audio file
                    // (embedded art): the flag routes the header to the right
                    // Coil model.
                    imageUrl = cover?.imageUrl,
                    imageIsEmbeddedArt = cover?.isEmbeddedArt ?: false,
                    kind = CollectionKind.Artist,
                    tracks = searchViewModel.tracksForArtist(artist)
                )
            )
        }
    }
    // Track where the keyboard is *going* (target flips to 0 as soon as the hide
    // starts) so the caret stops over the idle cards during the animation.
    val imeTargetBottom = WindowInsets.imeAnimationTarget.getBottom(density)

    LaunchedEffect(imeTargetBottom) {
        if (imeTargetBottom == 0) focusManager.clearFocus()
    }

    BackHandler(enabled = searchActive && query.isEmpty()) {
        searchActive = false
        focusManager.clearFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Search Bar: text field pill with the always-visible clear icon inside.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(50))
                .background(FoamTheme.colors.surface)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    BasicText(
                        text = "Search tracks, artists, albums...",
                        style = FoamTheme.typography.titleMedium.copy(
                            color = FoamTheme.colors.textSecondary
                        )
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = {
                        searchViewModel.search(it)
                    },
                    textStyle = FoamTheme.typography.titleMedium,
                    cursorBrush = SolidColor(FoamTheme.accentColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) searchActive = true },
                    singleLine = true
                )
            }
            val clearInteractionSource = remember { MutableInteractionSource() }
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Clear",
                tint = FoamTheme.colors.textSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = clearInteractionSource,
                        indication = ripple(color = FoamTheme.colors.textPrimary),
                        onClick = { searchViewModel.search("") }
                    )
                    .padding(4.dp)
            )
        }

        Spacer(modifier = Modifier.height(if (query.isNotEmpty()) 8.dp else 16.dp))

        // Scope filters: only while actively searching, never over the idle browse cards.
        if (query.isNotEmpty()) {
            val filters = uiState.filters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchFilterChip(
                    label = "All",
                    selected = filters.allEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = { searchViewModel.setAllFiltersEnabled(!filters.allEnabled) },
                    onLongClick = { searchViewModel.setAllFiltersEnabled(!filters.allEnabled) }
                )
                SearchFilterChip(
                    label = "Artists",
                    selected = filters.artists,
                    modifier = Modifier.weight(1f),
                    onClick = { searchViewModel.setArtistsFilterEnabled(!filters.artists) },
                    onLongClick = { searchViewModel.soloSearchFilter(SearchFilter.ARTISTS) }
                )
                SearchFilterChip(
                    label = "Albums",
                    selected = filters.albums,
                    modifier = Modifier.weight(1f),
                    onClick = { searchViewModel.setAlbumsFilterEnabled(!filters.albums) },
                    onLongClick = { searchViewModel.soloSearchFilter(SearchFilter.ALBUMS) }
                )
                SearchFilterChip(
                    label = "Tracks",
                    selected = filters.tracks,
                    modifier = Modifier.weight(1f),
                    onClick = { searchViewModel.setTracksFilterEnabled(!filters.tracks) },
                    onLongClick = { searchViewModel.soloSearchFilter(SearchFilter.TRACKS) }
                )
                SearchFilterChip(
                    label = "Lyrics",
                    selected = filters.lyrics,
                    modifier = Modifier.weight(1f),
                    onClick = { searchViewModel.setLyricsFilterEnabled(!filters.lyrics) },
                    onLongClick = { searchViewModel.soloSearchFilter(SearchFilter.LYRICS) }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            when {
                // Results while typing
                query.isNotEmpty() -> {
                    // Artists Section
                    if (uiState.artists.isNotEmpty()) {
                        item(key = "artists_header") {
                            SectionHeader("Artists")
                        }
                        // Content-keyed: results change identity on every keystroke.
                        items(uiState.artists, key = { "artist_$it" }) { artist ->
                            val cover = artistImages[artist]
                            ArtistSearchResultItem(
                                artist = artist,
                                imageUrl = cover?.imageUrl,
                                imageIsEmbeddedArt = cover?.isEmbeddedArt ?: false,
                                onClick = {
                                    searchViewModel.addToHistory(artist)
                                    onArtistClick(
                                        artist,
                                        cover?.imageUrl,
                                        cover?.isEmbeddedArt ?: false
                                    )
                                },
                                onLongClick = { showArtistModal(artist) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    }

                    // Albums Section
                    if (uiState.albums.isNotEmpty()) {
                        item(key = "albums_header") {
                            SectionHeader("Albums")
                        }
                        items(uiState.albums, key = { "album_${it.album}_${it.artist}" }) { album ->
                            AlbumSearchResultItem(
                                album = album,
                                onClick = {
                                    searchViewModel.addToHistory(album)
                                    onAlbumClick(album)
                                },
                                onLongClick = { showAlbumModal(album) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    }

                    // Tracks Section
                    if (uiState.tracks.isNotEmpty()) {
                        item(key = "tracks_header") {
                            SectionHeader("Tracks")
                        }
                        items(uiState.tracks, key = { "track_${it.id}" }) { track ->
                            TrackListItem(
                                track = track,
                                onClick = {
                                    searchViewModel.addToHistory(track)
                                    onTrackClick(track)
                                },
                                onLongClick = { showTrackModal(track) },
                                active = track.id == activeTrackId,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = playerTransitionVisibilityScope,
                                isSharedTransitionSource = track.id == sharedTrackId
                            )
                        }
                    }

                    // Found in lyrics: the background scan streams matches in
                    // progressively until it completes.
                    if (uiState.lyricsMatches.isNotEmpty() || uiState.lyricsSearchInProgress) {
                        item(key = "lyrics_header") {
                            SectionHeader(
                                title = "Found in lyrics",
                                trailingVisible = uiState.lyricsSearchInProgress,
                                trailing = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = FoamTheme.accentColor
                                    )
                                }
                            )
                        }
                        items(uiState.lyricsMatches, key = { "lyrics_${it.id}" }) { track ->
                            TrackListItem(
                                track = track,
                                onClick = {
                                    searchViewModel.addToHistory(track)
                                    onTrackClick(track)
                                },
                                onLongClick = { showTrackModal(track) },
                                active = track.id == activeTrackId,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = playerTransitionVisibilityScope,
                                isSharedTransitionSource = track.id == sharedTrackId
                            )
                        }
                    }
                }

                // Search history while search mode is active but still empty;
                // stays up after the keyboard closes until the back button ends it
                searchActive -> {
                    if (uiState.history.isNotEmpty()) {
                        item(key = "history_header") {
                            SectionHeader(
                                title = "Recent Searches",
                                modifier = Modifier.animateItem()
                            )
                        }
                        items(
                            uiState.history,
                            key = { entry ->
                                when (entry) {
                                    is RecentSearch.Track -> "history_track_${entry.track.path}"
                                    is RecentSearch.Album -> "history_album_${entry.album.album}"
                                    is RecentSearch.Artist -> "history_artist_${entry.name}"
                                }
                            }
                        ) { entry ->
                            when (entry) {
                                is RecentSearch.Track -> TrackListItem(
                                    track = entry.track,
                                    onClick = {
                                        searchViewModel.addToHistory(entry.track)
                                        onTrackClick(entry.track)
                                    },
                                    onRemove = { searchViewModel.removeFromHistory(entry) },
                                    onLongClick = { showTrackModal(entry.track) },
                                    active = entry.track.id == activeTrackId,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = playerTransitionVisibilityScope,
                                    isSharedTransitionSource = entry.track.id == sharedTrackId,
                                    modifier = Modifier.animateItem()
                                )
                                is RecentSearch.Album -> SwipeDismissRow(
                                    onRemove = { searchViewModel.removeFromHistory(entry) },
                                    modifier = Modifier.animateItem()
                                ) {
                                    AlbumSearchResultItem(
                                        album = entry.album,
                                        onClick = {
                                            searchViewModel.addToHistory(entry.album)
                                            onAlbumClick(entry.album)
                                        },
                                        onLongClick = { showAlbumModal(entry.album) },
                                        sharedTransitionScope = sharedTransitionScope,
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                }
                                is RecentSearch.Artist -> SwipeDismissRow(
                                    onRemove = { searchViewModel.removeFromHistory(entry) },
                                    modifier = Modifier.animateItem()
                                ) {
                                    val cover = artistImages[entry.name]
                                    ArtistSearchResultItem(
                                        artist = entry.name,
                                        imageUrl = cover?.imageUrl,
                                        imageIsEmbeddedArt = cover?.isEmbeddedArt ?: false,
                                        onClick = {
                                            searchViewModel.addToHistory(entry.name)
                                            onArtistClick(
                                                entry.name,
                                                cover?.imageUrl,
                                                cover?.isEmbeddedArt ?: false
                                            )
                                        },
                                        onLongClick = { showArtistModal(entry.name) },
                                        sharedTransitionScope = sharedTransitionScope,
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                }
                            }
                        }
                        item(key = "history_clear") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .clickable { searchViewModel.clearHistory() }
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                BasicText(
                                    text = "Clear search history",
                                    style = FoamTheme.typography.body.copy(color = FoamTheme.accentColor)
                                )
                            }
                        }
                    }
                }

                // Idle: browse cards into the library list screens
                else -> {
                    val browseCards = listOf(
                        BrowseCardData("All tracks", AllTracksCardColor, Icons.Rounded.MusicNote, onBrowseAllTracks),
                        BrowseCardData("Albums", AlbumCardColor, Icons.Rounded.Album, onBrowseAlbums),
                        BrowseCardData("Folders", FolderCardColor, Icons.Rounded.Folder, onBrowseFolders),
                        BrowseCardData("Genres", GenreCardColor, Icons.Rounded.Category, onBrowseGenres),
                        BrowseCardData("Artists", ArtistCardColor, Icons.Rounded.Person, onBrowseArtists),
                        BrowseCardData("Years", YearCardColor, Icons.Rounded.CalendarToday, onBrowseYears)
                    )
                    browseCards.forEachIndexed { index, card ->
                        item(key = "browse_card_${card.title}") {
                            BrowseCardItem(
                                card = card,
                                modifier = if (index == 0) {
                                    Modifier.padding(top = 4.dp)
                                } else {
                                    Modifier
                                }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(LocalBottomBarClearance.current)) }
        }
    }
}

/** Accent-colored section title centered between two thin lines: ——— Artists ——— */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailingVisible: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val lineColor = FoamTheme.accentColor.copy(alpha = 0.35f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .background(lineColor)
        )
        Spacer(modifier = Modifier.width(12.dp))
        BasicText(
            text = title,
            style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.accentColor)
        )
        if (trailing != null) {
            // Fades + collapses toward the text so the title glides back to
            // the center instead of jumping when the trailing content goes.
            AnimatedVisibility(
                visible = trailingVisible,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    trailing()
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .background(lineColor)
        )
    }
}

/** One scope filter chip; long-press solos that scope (All + one). */
@Composable
private fun SearchFilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(FoamTheme.colors.surface)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.accentColor),
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = FoamTheme.typography.body.copy(
                color = if (selected) {
                    FoamTheme.colors.textPrimary
                } else {
                    FoamTheme.colors.textPrimary.copy(alpha = 0.4f)
                }
            ),
            maxLines = 1
        )
    }
}

/**
 * One artist result row: circular portrait (image or initial) plus the name.
 * When this row is the one clicked to start the current navigation, its art
 * and title join the shared-element flight into the artist detail hero.
 *
 * The portrait loads either a plain file (Spotify picture) or an audio file's
 * embedded art ([imageIsEmbeddedArt]) — the automatic first-track fallback.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ArtistSearchResultItem(
    artist: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageIsEmbeddedArt: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val highlightShape = RoundedCornerShape(albumArtCornerRadius(ListRowArtSize) + ListRowHighlightPadding)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(highlightShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(horizontal = ListRowHighlightPadding, vertical = ListRowHighlightPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(ListRowArtSize)
                .let { base ->
                    if (sharedTransitionScope != null &&
                        animatedVisibilityScope != null &&
                        isActiveSharedCollection("artist", artist)
                    ) {
                        base.collectionRowFlightArtwork(
                            key = "artist-art-$artist",
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            enabled = true,
                            restShape = CircleShape,
                            immersiveFlightHero = LocalCollectionHeroLayoutLookup
                                .current(CollectionHeroTarget.Artist)
                                .isImmersive,
                            fullyRounded = true
                        )
                    } else base.clip(CircleShape)
                }
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                val context = LocalContext.current
                val artRequest = remember(imageUrl, imageIsEmbeddedArt) {
                    ImageRequest.Builder(context)
                        .data(
                            if (imageIsEmbeddedArt) {
                                EmbeddedAlbumArt(imageUrl.toUri())
                            } else {
                                imageUrl
                            }
                        )
                        .size(AlbumArtRequestSize)
                        .memoryCacheKey(albumArtMemoryCacheKey(imageUrl))
                        .build()
                }
                AlbumArtImage(
                    model = artRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                BasicText(
                    text = artist.take(1).uppercase(),
                    style = FoamTheme.typography.caption
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        BasicText(
            text = artist,
            style = FoamTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .let { base ->
                    if (sharedTransitionScope != null &&
                        animatedVisibilityScope != null &&
                        isActiveSharedCollection("artist", artist)
                    ) {
                        with(sharedTransitionScope) {
                            base.sharedBounds(
                                rememberSharedContentState(key = "artist-title-$artist"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                enter = fadeIn(textFlightFadeSpec),
                                exit = fadeOut(textFlightFadeSpec),
                                boundsTransform = AlbumArtBoundsTransform
                            )
                        }
                    } else base
                }
        )
    }
}

/**
 * One album result row: square art (or initial) plus album and artist lines.
 * Joins the shared-element flight into the album detail hero when clicked.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumSearchResultItem(
    album: AlbumSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val highlightShape = RoundedCornerShape(albumArtCornerRadius(ListRowArtSize) + ListRowHighlightPadding)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(highlightShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(horizontal = ListRowHighlightPadding, vertical = ListRowHighlightPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(ListRowArtSize)
                .let { base ->
                    if (sharedTransitionScope != null &&
                        animatedVisibilityScope != null &&
                        isActiveSharedCollection("album", album.album)
                    ) {
                        base.collectionRowFlightArtwork(
                            key = "album-art-${album.album}",
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            enabled = true,
                            restShape = AlbumArtShape(),
                            immersiveFlightHero = LocalCollectionHeroLayoutLookup
                                .current(CollectionHeroTarget.Album)
                                .isImmersive
                        )
                    } else base.clip(AlbumArtShape())
                }
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (album.albumArtPath != null) {
                val context = LocalContext.current
                val albumArtRequest = remember(album.albumArtPath) {
                    ImageRequest.Builder(context)
                        .data(EmbeddedAlbumArt(album.albumArtPath.toUri()))
                        .size(AlbumArtRequestSize)
                        .memoryCacheKey(albumArtMemoryCacheKey(album.albumArtPath))
                        .build()
                }
                AlbumArtImage(
                    model = albumArtRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                BasicText(
                    text = album.album.take(1).uppercase(),
                    style = FoamTheme.typography.caption
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            BasicText(
                text = album.album,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.let { base ->
                    if (sharedTransitionScope != null &&
                        animatedVisibilityScope != null &&
                        isActiveSharedCollection("album", album.album)
                    ) {
                        with(sharedTransitionScope) {
                            base.sharedBounds(
                                rememberSharedContentState(key = "album-title-${album.album}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                enter = fadeIn(textFlightFadeSpec),
                                exit = fadeOut(textFlightFadeSpec),
                                boundsTransform = AlbumArtBoundsTransform
                            )
                        }
                    } else base
                }
            )
            BasicText(
                text = album.artist,
                style = FoamTheme.typography.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.let { base ->
                    if (sharedTransitionScope != null &&
                        animatedVisibilityScope != null &&
                        isActiveSharedCollection("album", album.album)
                    ) {
                        with(sharedTransitionScope) {
                            base.sharedBounds(
                                rememberSharedContentState(key = "album-artist-${album.album}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                enter = fadeIn(textFlightFadeSpec),
                                exit = fadeOut(textFlightFadeSpec),
                                boundsTransform = AlbumArtBoundsTransform
                            )
                        }
                    } else base
                }
            )
        }
    }
}

/** Swipe-to-dismiss container for removable rows (search history entries). */
@Composable
private fun SwipeDismissRow(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    var dismissOffsetValue by remember { mutableFloatStateOf(0f) }
    val gestureAnimJob = remember { mutableStateOf<Job?>(null) }
    var itemWidthPx by remember { mutableFloatStateOf(0f) }
    val latestOnRemove by rememberUpdatedState(onRemove)
    val density = LocalDensity.current
    val fallbackDismissThresholdPx = with(density) { SwipeDismissThreshold.toPx() }

    fun swipeThresholdPx(): Float =
        if (itemWidthPx > 0f) itemWidthPx * SwipeDismissThresholdFraction
        else fallbackDismissThresholdPx

    fun animateSwipeTo(target: Float, durationMs: Int, onEnd: () -> Unit = {}) {
        gestureAnimJob.value?.cancel()
        gestureAnimJob.value = scope.launch {
            val animatable = Animatable(dismissOffsetValue)
            animatable.animateTo(target, tween(durationMillis = durationMs)) {
                dismissOffsetValue = value
            }
            onEnd()
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { itemWidthPx = it.width.toFloat() }
            .graphicsLayer {
                translationX = dismissOffsetValue
                val threshold = max(swipeThresholdPx(), 1f)
                val progress = (abs(dismissOffsetValue) / threshold).coerceIn(0f, 1f)
                alpha = 1f - progress * SwipeMaxFade
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        gestureAnimJob.value?.cancel()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dismissOffsetValue += dragAmount
                    },
                    onDragEnd = {
                        if (abs(dismissOffsetValue) >= swipeThresholdPx()) {
                            val exitDistance =
                                if (itemWidthPx > 0f) itemWidthPx else fallbackDismissThresholdPx
                            val exitTarget =
                                if (dismissOffsetValue > 0f) exitDistance else -exitDistance
                            animateSwipeTo(exitTarget, SwipeDismissDurationMs) {
                                latestOnRemove.invoke()
                            }
                        } else {
                            animateSwipeTo(0f, SwipeReturnDurationMs)
                        }
                    },
                    onDragCancel = {
                        animateSwipeTo(0f, SwipeReturnDurationMs)
                    }
                )
            }
    ) {
        content()
    }
}