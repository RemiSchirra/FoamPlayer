package com.foamplayer.app.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.core.net.toUri
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.theme.FoamTheme

/**
 * "List of lists" screen: the TrackListScreen equivalent for screens that hold a list
 * of collections (albums, genres, years, folders...) instead of tracks. Same layout:
 * back arrow in a top bar, artwork + title header, then the rows supplied by [content].
 */
@Composable
fun CollectionListScreen(
    title: String,
    itemCount: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    headerImageUrl: String? = null,
    headerIcon: ImageVector? = null,
    content: LazyListScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = FoamTheme.colors.textPrimary,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { onBack() }
                        .padding(4.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "collection_header") {
                    CollectionHeaderSection(
                        title = title,
                        subtitle = subtitle,
                        itemCount = itemCount,
                        imageUrl = headerImageUrl,
                        icon = headerIcon
                    )
                }

                content()

                item(key = "collection_bottom_spacer") {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun CollectionHeaderSection(
    title: String,
    subtitle: String?,
    itemCount: Int,
    imageUrl: String?,
    icon: ImageVector?
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover Art
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(AlbumArtShape)
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                val headerArtRequest = remember(imageUrl) {
                    ImageRequest.Builder(context)
                        .data(EmbeddedAlbumArt(imageUrl.toUri()))
                        .size(AlbumArtRequestSize)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = headerArtRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                BasicText(
                    text = title.take(1).uppercase(),
                    style = FoamTheme.typography.titleLarge.copy(fontSize = 48.sp)
                )
            }
        }

        Spacer(modifier = Modifier.width(20.dp))

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.accentColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicText(
                text = "$itemCount items",
                style = FoamTheme.typography.body,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

/**
 * TrackListItem equivalent for a collection (album, folder, genre, year...):
 * the same [MediaListRow] scaffold — 48dp artwork + title + subtitle row. When
 * a [sharedElementKey] and the transition scopes are provided, the artwork and
 * texts animate into the matching album detail header using the same keys as
 * SearchScreen's album rows ("album-art-*", "album-title-*", "album-artist-*").
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CollectionListItem(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    imageUrl: String? = null,
    icon: ImageVector? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementKey: String? = null
) {
    val context = LocalContext.current

    // Long-press opens the collection actions modal when FoamNavHost provides
    // one at the root; an explicit [onLongClick] always wins over that default.
    val collectionModalState = LocalCollectionFoamModalState.current
    val resolvedOnLongClick: (() -> Unit)? = when {
        onLongClick != null -> onLongClick
        collectionModalState != null -> {
            val openModal: () -> Unit = {
                collectionModalState.show(
                    CollectionInfo(
                        title = title,
                        subtitle = subtitle,
                        imageUrl = imageUrl,
                        icon = icon
                    )
                )
            }
            openModal
        }
        else -> null
    }

    // Rows taking part in the shared-element transition must use the same fixed
    // corner radius as the album detail header; the others round like the
    // other list rows.
    val elementKey = sharedElementKey
    val artShape = if (elementKey != null) {
        AlbumArtShape
    } else {
        RoundedCornerShape(listRowArtCornerRadius())
    }

    MediaListRow(
        modifier = modifier,
        onClick = onClick,
        onLongClick = resolvedOnLongClick,
        artworkModifier = Modifier
            .rowSharedArtwork(
                key = "album-art-$elementKey",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                enabled = elementKey != null,
                overlayClip = AlbumArtShape,
                boundsTransform = AlbumArtBoundsTransform
            )
            .clip(artShape)
            .background(FoamTheme.colors.surfaceVariant),
        artworkContent = {
            if (imageUrl != null) {
                val albumArtRequest = remember(imageUrl) {
                    ImageRequest.Builder(context)
                        .data(EmbeddedAlbumArt(imageUrl.toUri()))
                        .size(AlbumArtRequestSize)
                        .build()
                }
                AsyncImage(
                    model = albumArtRequest,
                    contentDescription = "Album Art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = FoamTheme.accentColor,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                BasicText(
                    text = title.take(1).uppercase(),
                    style = FoamTheme.typography.titleMedium
                )
            }
        },
        title = title,
        subtitle = subtitle,
        titleModifier = Modifier.rowSharedText(
            key = "album-title-$elementKey",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            enabled = elementKey != null,
            boundsTransform = AlbumArtBoundsTransform
        ),
        subtitleModifier = if (subtitle != null) {
            Modifier.rowSharedText(
                key = "album-artist-$elementKey",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                enabled = elementKey != null,
                boundsTransform = AlbumArtBoundsTransform
            )
        } else {
            Modifier
        }
    )
}
