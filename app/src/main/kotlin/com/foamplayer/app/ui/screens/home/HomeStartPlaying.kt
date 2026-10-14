package com.foamplayer.app.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.foamplayer.app.R
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.scanner.ArtistCover
import com.foamplayer.app.ui.components.AlbumArtImage
import com.foamplayer.app.ui.components.AlbumArtRequestSize
import com.foamplayer.app.ui.components.MediaArtKind
import com.foamplayer.app.ui.components.MediaArtKindScope
import com.foamplayer.app.ui.components.NoAlbumArtLetter
import com.foamplayer.app.ui.theme.FoamTheme

private val StartPlayingGridSpacing = 8.dp
private val StartPlayingGridPadding = 8.dp
private val StartPlayingRowEdgePadding = 24.dp
private val StartPlayingHeaderButtonGap = 16.dp
private val StartPlayingHeaderSpacing = 4.dp
private val StartPlayingArtSize = 40.dp

/** Square side of one grid card: art + padding. */
private val StartPlayingCardSize = StartPlayingArtSize + StartPlayingGridPadding * 2

/** Alpha of the cards' translucent surface tint. */
private const val StartPlayingCardSurfaceAlpha = 0.2f

/**
 * Home's "Random tracks" section: a title with a shuffle button on its right,
 * above a fixed 2x3 grid of random songs. Tapping a card starts the track;
 * long-pressing opens the track actions modal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StartPlayingSection(
    tracks: List<TrackEntity>,
    onTrackClick: (TrackEntity) -> Unit,
    onTrackLongClick: (TrackEntity) -> Unit,
    onRefresh: () -> Unit
) {
    if (tracks.isEmpty()) return

    RandomSectionScaffold(
        title = stringResource(R.string.home_row_random_tracks),
        onRefresh = onRefresh
    ) {
        // Static 2x3 grid inside the Home LazyColumn: six fixed slots, no
        // internal scrolling, so it can't fight the LazyColumn's own scroll.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height(StartPlayingCardSize * 3 + StartPlayingGridSpacing * 2),
            contentPadding = PaddingValues(horizontal = StartPlayingRowEdgePadding - StartPlayingGridPadding),
            verticalArrangement = Arrangement.spacedBy(StartPlayingGridSpacing),
            horizontalArrangement = Arrangement.spacedBy(StartPlayingGridSpacing),
            userScrollEnabled = false
        ) {
            items(tracks, key = { it.id }) { track ->
                StartPlayingCard(
                    track = track,
                    onClick = { onTrackClick(track) },
                    onLongClick = { onTrackLongClick(track) }
                )
            }
        }
    }
}

/**
 * Home's "Random albums" section, in the same 2x3 shuffle-card style as
 * [StartPlayingSection]: tapping a card opens the album, long-pressing opens
 * the collection actions modal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RandomAlbumsSection(
    albums: List<AlbumSearchResult>,
    onAlbumClick: (AlbumSearchResult) -> Unit,
    onAlbumLongClick: (AlbumSearchResult) -> Unit,
    onRefresh: () -> Unit
) {
    if (albums.isEmpty()) return

    RandomSectionScaffold(
        title = stringResource(R.string.home_row_random_albums),
        onRefresh = onRefresh
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height(StartPlayingCardSize * 3 + StartPlayingGridSpacing * 2),
            contentPadding = PaddingValues(horizontal = StartPlayingRowEdgePadding - StartPlayingGridPadding),
            verticalArrangement = Arrangement.spacedBy(StartPlayingGridSpacing),
            horizontalArrangement = Arrangement.spacedBy(StartPlayingGridSpacing),
            userScrollEnabled = false
        ) {
            items(albums, key = { it.album }) { album ->
                RandomAlbumCard(
                    album = album,
                    onClick = { onAlbumClick(album) },
                    onLongClick = { onAlbumLongClick(album) }
                )
            }
        }
    }
}

/**
 * Home's "Random artists" section, in the same 2x3 shuffle-card style as
 * [StartPlayingSection]: tapping a card opens the artist, long-pressing opens
 * the collection actions modal. [covers] pairs the resolved portraits with the
 * names the latest resolution pass covered, so cards whose resolution is still
 * pending show the person placeholder instead of flashing the letter fallback.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RandomArtistsSection(
    artists: List<FavoriteArtist>,
    covers: RandomArtistCovers,
    onArtistClick: (FavoriteArtist) -> Unit,
    onArtistLongClick: (FavoriteArtist) -> Unit,
    onRefresh: () -> Unit
) {
    if (artists.isEmpty()) return

    RandomSectionScaffold(
        title = stringResource(R.string.home_row_random_artists),
        onRefresh = onRefresh
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height(StartPlayingCardSize * 3 + StartPlayingGridSpacing * 2),
            contentPadding = PaddingValues(horizontal = StartPlayingRowEdgePadding - StartPlayingGridPadding),
            verticalArrangement = Arrangement.spacedBy(StartPlayingGridSpacing),
            horizontalArrangement = Arrangement.spacedBy(StartPlayingGridSpacing),
            userScrollEnabled = false
        ) {
            items(artists, key = { it.name }) { artist ->
                RandomArtistCard(
                    artist = artist,
                    cover = covers.covers[artist.name],
                    resolved = artist.name in covers.resolved,
                    onClick = { onArtistClick(artist) },
                    onLongClick = { onArtistLongClick(artist) }
                )
            }
        }
    }
}

/** Shared header of the random sections: title left, reshuffle button right. */
@Composable
private fun RandomSectionScaffold(
    title: String,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StartPlayingRowEdgePadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium.copy(fontSize = 20.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .padding(start = StartPlayingHeaderButtonGap)
                    .size(40.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = FoamTheme.colors.textPrimary),
                        onClick = onRefresh
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FoamTheme.icons.current.shuffle,
                    contentDescription = stringResource(R.string.home_start_playing_refresh),
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(StartPlayingHeaderSpacing))

        content()
    }
}

/**
 * One "Random tracks" card: artwork bleeding to the card's rounded corners on
 * the left, vertically centered up-to-two-line title on the right, over a
 * semi-transparent surface tint.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StartPlayingCard(
    track: TrackEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(FoamTheme.shapes.cardRounding)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StartPlayingCardSize)
            .clip(cardShape)
            .background(FoamTheme.colors.textPrimary.copy(alpha = StartPlayingCardSurfaceAlpha))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onLongClick = onLongClick,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(StartPlayingCardSize)
                .aspectRatio(1f)
                .background(FoamTheme.colors.surfaceVariant)
        ) {
            val artRequest = remember(track.albumArtPath) {
                ImageRequest.Builder(context)
                    .data(track.albumArtPath?.let { EmbeddedAlbumArt(it.toUri()) } ?: track.title)
                    .size(AlbumArtRequestSize)
                    .build()
            }
            AlbumArtImage(
                model = artRequest,
                contentDescription = track.title,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = StartPlayingGridPadding)
        ) {
            BasicText(
                text = track.title,
                style = FoamTheme.typography.titleMedium.copy(fontSize = 13.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One "Random albums" card: same layout as [StartPlayingCard], with the
 * album's resolved cover (first track with embedded art) and album name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RandomAlbumCard(
    album: AlbumSearchResult,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(FoamTheme.shapes.cardRounding)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StartPlayingCardSize)
            .clip(cardShape)
            .background(FoamTheme.colors.textPrimary.copy(alpha = StartPlayingCardSurfaceAlpha))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onLongClick = onLongClick,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(StartPlayingCardSize)
                .aspectRatio(1f)
                .background(FoamTheme.colors.surfaceVariant)
        ) {
            val artRequest = remember(album.albumArtPath, album.album) {
                ImageRequest.Builder(context)
                    .data(album.albumArtPath?.let { EmbeddedAlbumArt(it.toUri()) } ?: album.album)
                    .size(AlbumArtRequestSize)
                    .build()
            }
            AlbumArtImage(
                model = artRequest,
                contentDescription = album.album,
                modifier = Modifier.fillMaxSize(),
                placeholderKind = MediaArtKind.ALBUM
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = StartPlayingGridPadding)
        ) {
            BasicText(
                text = album.album,
                style = FoamTheme.typography.titleMedium.copy(fontSize = 13.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One "Random artists" card: same layout as the other random cards, with a
 * resolved portrait or letter fallback and the artist's name. Before the
 * artist's cover resolution has answered (right after a reshuffle), the
 * loading placeholder is the person icon rather than the letter — only an
 * artist whose resolution completed without art falls back to the letter.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RandomArtistCard(
    artist: FavoriteArtist,
    cover: ArtistCover?,
    resolved: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(FoamTheme.shapes.cardRounding)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StartPlayingCardSize)
            .clip(cardShape)
            .background(FoamTheme.colors.textPrimary.copy(alpha = StartPlayingCardSurfaceAlpha))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onLongClick = onLongClick,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(StartPlayingCardSize)
                .aspectRatio(1f)
                .background(FoamTheme.colors.surfaceVariant)
        ) {
            if (cover != null) {
                val artRequest = remember(cover.imageUrl, cover.isEmbeddedArt) {
                    ImageRequest.Builder(context)
                        .data(
                            if (cover.isEmbeddedArt) {
                                EmbeddedAlbumArt(cover.imageUrl.toUri())
                            } else {
                                cover.imageUrl
                            }
                        )
                        .size(AlbumArtRequestSize)
                        .build()
                }
                AlbumArtImage(
                    model = artRequest,
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxSize(),
                    placeholderKind = MediaArtKind.ARTIST
                )
            } else if (resolved) {
                NoAlbumArtLetter(title = artist.name)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = StartPlayingGridPadding)
        ) {
            BasicText(
                text = artist.name,
                style = FoamTheme.typography.titleMedium.copy(fontSize = 13.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
