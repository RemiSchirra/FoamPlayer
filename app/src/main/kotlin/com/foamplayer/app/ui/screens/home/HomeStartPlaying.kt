package com.foamplayer.app.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import androidx.core.net.toUri
import com.foamplayer.app.R
import com.foamplayer.app.data.db.dao.AlbumSearchResult
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.data.scanner.ArtistCover
import com.foamplayer.app.ui.components.MediaArtKind
import com.foamplayer.app.ui.components.NoAlbumArtLetter
import com.foamplayer.app.ui.components.mediaArtPlaceholderIcon
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.foamSurfaceVariant
import kotlinx.coroutines.launch

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

/** Duration of the crossfade between two shuffled artworks. */
private const val ShuffledArtCrossfadeMs = 200

/** Icon size of the random cards' artwork placeholder. */
private val ShuffledArtPlaceholderSize = 20.dp

/**
 * One artwork the random grids can show: a Coil-loadable image or the letter
 * monogram of an artist without art.
 */
private sealed interface ShuffledArt {
    data class Image(val model: Any) : ShuffledArt
    data class Initial(val title: String) : ShuffledArt
}

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
        // No item keys: each slot's ShuffledArtwork state must survive a
        // reshuffle for the previous artwork to keep painting mid-crossfade.
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
            items(tracks) { track ->
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
            items(albums) { album ->
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
 * names the latest resolution pass covered; artists still being resolved keep
 * their previous card content instead of flashing a placeholder.
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
            items(artists) { artist ->
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
                .foamSurfaceVariant()
        ) {
            ShuffledArtwork(
                art = ShuffledArt.Image(
                    track.albumArtPath?.let { EmbeddedAlbumArt(it.toUri()) } ?: track.title
                ),
                placeholderKind = MediaArtKind.TRACK,
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
                .foamSurfaceVariant()
        ) {
            ShuffledArtwork(
                art = ShuffledArt.Image(
                    album.albumArtPath?.let { EmbeddedAlbumArt(it.toUri()) } ?: album.album
                ),
                placeholderKind = MediaArtKind.ALBUM,
                contentDescription = album.album,
                modifier = Modifier.fillMaxSize()
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
 * resolved portrait or letter fallback and the artist's name. Until the
 * artist's cover resolution answers, the previous card content keeps painting
 * ([art] null); only an artist whose resolution completed without art falls
 * back to the letter.
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
                .foamSurfaceVariant()
        ) {
            ShuffledArtwork(
                art = when {
                    cover != null -> ShuffledArt.Image(
                        if (cover.isEmbeddedArt) {
                            EmbeddedAlbumArt(cover.imageUrl.toUri())
                        } else {
                            cover.imageUrl
                        }
                    )
                    resolved -> ShuffledArt.Initial(title = artist.name)
                    // Resolution pending: the previous content keeps painting.
                    else -> null
                },
                placeholderKind = MediaArtKind.ARTIST,
                contentDescription = artist.name,
                modifier = Modifier.fillMaxSize()
            )
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

/**
 * Artwork surface of one random-section card, with swap crossfading. Two
 * layers: the last SETTLED artwork keeps painting at full alpha underneath
 * while the INCOMING artwork of a reshuffle loads, then crossfades over it —
 * the loading placeholder only ever shows before anything has settled (the
 * section's first load), never mid-swap.
 *
 * The grids compose their cards positionally (no item keys) so each slot's
 * layers survive a reshuffle and the previous artwork can keep painting.
 *
 * [art] null freezes both layers (an artist's cover resolution is still
 * pending); a settled-only surface re-renders statically with no layers.
 */
@Composable
private fun ShuffledArtwork(
    art: ShuffledArt?,
    placeholderKind: MediaArtKind,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    // The artwork fully painted (its crossfade, if any, completed).
    var settled by remember { mutableStateOf<ShuffledArt?>(null) }
    // The artwork currently loading/crossfading over [settled].
    var incoming by remember { mutableStateOf<ShuffledArt?>(null) }

    if (art != null && art != settled && art != incoming) {
        // A genuinely new shuffled item: stage it over the settled layer.
        // Nothing settled (first load) still stages it; its placeholder shows
        // until the first image resolves.
        incoming = art
    }
    if (art == null && incoming != null) {
        // Pending resolution after a swap was staged: drop the staged layer,
        // the settled content keeps painting.
        incoming = null
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val under = settled) {
            is ShuffledArt.Image -> StaticImage(model = under.model, modifier = Modifier.fillMaxSize())
            is ShuffledArt.Initial -> NoAlbumArtLetter(title = under.title)
            null -> Unit
        }

        when (val over = incoming) {
            is ShuffledArt.Image -> IncomingImage(
                model = over.model,
                contentDescription = contentDescription,
                placeholder = mediaArtPlaceholderIcon(placeholderKind),
                showPlaceholder = settled == null,
                onSettled = {
                    settled = over
                    incoming = null
                },
                modifier = Modifier.fillMaxSize()
            )
            is ShuffledArt.Initial -> IncomingLetter(
                title = over.title,
                onSettled = {
                    settled = over
                    incoming = null
                },
                modifier = Modifier.fillMaxSize()
            )
            null -> if (settled == null && art == null) {
                // Nothing settled and nothing staged: placeholder.
                Icon(
                    imageVector = mediaArtPlaceholderIcon(placeholderKind),
                    contentDescription = null,
                    tint = FoamTheme.colors.textSecondary,
                    modifier = Modifier.size(ShuffledArtPlaceholderSize)
                )
            }
        }
    }
}

/** A fully-loaded image layer, painted with no fade machinery. */
@Composable
private fun StaticImage(model: Any, modifier: Modifier = Modifier) {
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}

/**
 * The incoming artwork of a swap: it paints at alpha 0 while it loads (the
 * settled layer below stays visible), then crossfades in over 200ms once its
 * bitmap is ready — memory-cache hits are ready on the first frame and fade
 * right away. A failure settles so the placeholder can show (when nothing is
 * settled).
 */
@Composable
private fun IncomingImage(
    model: Any,
    contentDescription: String?,
    placeholder: ImageVector,
    showPlaceholder: Boolean,
    onSettled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    // 0f until the crossfade runs: the incoming bitmap never paints at full
    // alpha before its fade starts.
    val alphaState = remember(model) { mutableFloatStateOf(0f) }
    var settled by remember(model) { mutableStateOf(false) }

    Box(modifier = modifier) {
        if (showPlaceholder && !settled) {
            Icon(
                imageVector = placeholder,
                contentDescription = null,
                tint = FoamTheme.colors.textSecondary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(ShuffledArtPlaceholderSize)
            )
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = alphaState.floatValue },
            contentScale = ContentScale.Crop,
            onState = { state ->
                if (settled) return@AsyncImage
                when (state) {
                    is AsyncImagePainter.State.Loading ->
                        if (alphaState.floatValue != 0f) alphaState.floatValue = 0f
                    is AsyncImagePainter.State.Success -> {
                        settled = true
                        scope.launch {
                            Animatable(0f).animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = ShuffledArtCrossfadeMs,
                                    easing = EaseOut
                                )
                            ) { alphaState.floatValue = value }
                            onSettled()
                        }
                    }
                    is AsyncImagePainter.State.Error -> {
                        settled = true
                        onSettled()
                    }
                    else -> Unit
                }
            }
        )
    }
}

/** The incoming letter of a swap, crossfaded in over the settled layer. */
@Composable
private fun IncomingLetter(
    title: String,
    onSettled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val letterAlpha = remember(title) { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(title) {
        letterAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = ShuffledArtCrossfadeMs, easing = EaseOut)
        )
        onSettled()
    }
    Box(modifier = modifier.graphicsLayer { alpha = letterAlpha.value }) {
        NoAlbumArtLetter(title = title)
    }
}
