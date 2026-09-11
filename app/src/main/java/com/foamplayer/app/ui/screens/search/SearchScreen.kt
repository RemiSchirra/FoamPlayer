package com.foamplayer.app.ui.screens.search

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.components.AlbumArtBoundsTransform
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.screens.library.TrackListItem
import com.foamplayer.app.ui.theme.FoamTheme
import androidx.core.net.toUri

// Must match TrackListScreen's AlbumArtShape — using the same shape on both ends of the
// shared-element transition means the corners never have to jump between two radii.
private val AlbumArtShape = RoundedCornerShape(12.dp)

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

/** Blends the color toward white, keeping the same hue — used for the card icons. */
private fun Color.lightened(factor: Float): Color =
    copy(
        red = red + (1f - red) * factor,
        green = green + (1f - green) * factor,
        blue = blue + (1f - blue) * factor
    )

@Composable
private fun BrowseCardItem(card: BrowseCardData, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        card.color.copy(alpha = 0.4f),
                        card.color.copy(alpha = 0.2f)
                    )
                )
            )
            .clickable { card.onClick() }
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
                tint = card.color.lightened(0.1f).copy(alpha = 0.65f),
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            BasicText(text = card.title, style = FoamTheme.typography.titleMedium)
        }
        Icon(
            imageVector = card.icon,
            contentDescription = null,
            tint = card.color.lightened(0.1f).copy(alpha = 0.3f),
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SearchScreen(
    onArtistClick: (String) -> Unit,
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
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

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
                        .onFocusChanged { isSearchFocused = it.isFocused },
                    singleLine = true
                )
            }
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Clear",
                tint = FoamTheme.colors.textSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .clickable { searchViewModel.search("") }
                    .padding(4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            when {
                // Results while typing
                query.isNotEmpty() -> {
                    // Artists Section
                    if (uiState.artists.isNotEmpty()) {
                        item {
                            SectionHeader("Artists")
                        }
                        items(uiState.artists) { artist ->
                            ArtistSearchResultItem(artist = artist, onClick = { onArtistClick(artist) })
                        }
                    }

                    // Albums Section
                    if (uiState.albums.isNotEmpty()) {
                        item {
                            SectionHeader("Albums")
                        }
                        items(uiState.albums) { album ->
                            AlbumSearchResultItem(
                                album = album,
                                onClick = { onAlbumClick(album) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    }

                    // Tracks Section
                    if (uiState.tracks.isNotEmpty()) {
                        item {
                            SectionHeader("Tracks")
                        }
                        items(uiState.tracks) { track ->
                            TrackListItem(
                                track = track,
                                onClick = {
                                    searchViewModel.addToHistory(track)
                                    onTrackClick(track)
                                },
                                active = track.id == activeTrackId,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = playerTransitionVisibilityScope,
                                isSharedTransitionSource = track.id == sharedTrackId
                            )
                        }
                    }
                }

                // Search history while the bar is selected but still empty
                isSearchFocused -> {
                    if (uiState.history.isNotEmpty()) {
                        item(key = "history_header") {
                            SectionHeader(
                                title = "Recent Searches",
                                modifier = Modifier.animateItem()
                            )
                        }
                        items(uiState.history, key = { "history_${it.path}" }) { track ->
                            TrackListItem(
                                track = track,
                                onClick = {
                                    searchViewModel.addToHistory(track)
                                    onTrackClick(track)
                                },
                                onRemove = { searchViewModel.removeFromHistory(track) },
                                active = track.id == activeTrackId,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = playerTransitionVisibilityScope,
                                isSharedTransitionSource = track.id == sharedTrackId,
                                modifier = Modifier.animateItem()
                            )
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

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    BasicText(
        text = title,
        style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.accentColor),
        modifier = modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun ArtistSearchResultItem(
    artist: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = artist.take(1).uppercase(),
                style = FoamTheme.typography.titleMedium
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        BasicText(
            text = artist,
            style = FoamTheme.typography.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumSearchResultItem(
    album: AlbumSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .let { base ->
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            base.sharedElement(
                                rememberSharedContentState(key = "album-art-${album.album}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                clipInOverlayDuringTransition = OverlayClip(AlbumArtShape),
                                boundsTransform = AlbumArtBoundsTransform
                            )
                        }
                    } else base
                }
                .clip(AlbumArtShape)
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (album.albumArtPath != null) {
                val context = LocalContext.current
                val albumArtRequest = remember(album.albumArtPath) {
                    ImageRequest.Builder(context)
                        .data(EmbeddedAlbumArt(album.albumArtPath.toUri()))
                        .size(AlbumArtRequestSize)
                        .build()
                }
                AsyncImage(
                    model = albumArtRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
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
                style = FoamTheme.typography.body.copy(color = FoamTheme.colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.let { base ->
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            base.sharedBounds(
                                rememberSharedContentState(key = "album-title-${album.album}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = AlbumArtBoundsTransform
                            )
                        }
                    } else base
                }
            )
            BasicText(
                text = album.artist,
                style = FoamTheme.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.let { base ->
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            base.sharedBounds(
                                rememberSharedContentState(key = "album-artist-${album.album}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = AlbumArtBoundsTransform
                            )
                        }
                    } else base
                }
            )
        }
    }
}
