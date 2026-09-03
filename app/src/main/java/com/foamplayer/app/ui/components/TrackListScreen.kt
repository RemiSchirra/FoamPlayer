package com.foamplayer.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import androidx.compose.ui.platform.LocalContext
import com.foamplayer.app.data.db.entities.TrackEntity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import androidx.core.net.toUri
import com.foamplayer.app.ui.navigation.SharedElementEasing
import com.foamplayer.app.ui.navigation.SharedElementTransitionDurationMs
import com.foamplayer.app.ui.screens.library.TrackListItem
import com.foamplayer.app.ui.screens.player.DynamicAlbumArtBackground
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.utils.TimeUtils
import com.foamplayer.app.ui.utils.extractPaletteColor

// Used by both this header and the search/artist list rows for the shared-element
// transition. Keeping it a single shared shape means the artwork never has to snap
// between two different corner radii mid-animation.
private val AlbumArtShape = RoundedCornerShape(12.dp)

// Fixed decode size (px) for album art shared between this header and the search/artist
// list rows. Coil's memory-cache key includes the requested size, so using the same
// value on both ends means switching from the small list image to this big header image
// is an instant cache hit instead of a fresh decode (which otherwise shows briefly as a
// blank/transparent frame mid-transition).
val AlbumArtRequestSize = Size(480, 480)

// Drives the size/position animation for every shared element in this transition (both
// this file's and SearchScreen.kt's). Uses the SAME duration/easing as the NavHost's
// screen-level fade (SharedElementTransitionDurationMs/SharedElementEasing) so the
// background fading in and the artwork moving/resizing finish on the same frame instead
// of one lagging or overshooting the other, which is what read as a flicker.
@OptIn(ExperimentalSharedTransitionApi::class)
val AlbumArtBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = SharedElementTransitionDurationMs, easing = SharedElementEasing)
}

// Uses the list image cover if true
// TODO: hardcoded for now
private const val SHOW_CUSTOM_BACKGROUND = true

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TrackListScreen(
    title: String,
    tracks: List<TrackEntity>,
    onTrackClick: (List<TrackEntity>, Int) -> Unit,
    onPlayAllClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    artistName: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedElementKey: String? = null,
    sharedTrackId: Long? = null,
    activeTrackId: Long? = null,
    playerTransitionVisibilityScope: AnimatedVisibilityScope? = null,
    // TODO : use the background blur app settings
    backgroundBlur: Dp = 30.dp,
    backgroundIntensity: Float = 0.5f
) {
    val totalDurationMs = remember(tracks) { tracks.sumOf { it.duration } }
    val durationText = remember(totalDurationMs) { TimeUtils.formatDurationLong(totalDurationMs) }

    Box(modifier = modifier.fillMaxSize()) {
        if (SHOW_CUSTOM_BACKGROUND) {
            DynamicAlbumArtBackground(
                frontArtPath = imageUrl,
                backArtPath = null,
                crossfadeFraction = 0f,
                blur = backgroundBlur,
                intensity = backgroundIntensity,
                modifier = Modifier.fillMaxSize()
            )
        }
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
                item {
                    HeaderSection(
                        title = title,
                        subtitle = artistName,
                        trackCount = tracks.size,
                        durationText = durationText,
                        imageUrl = imageUrl,
                        onPlayAllClick = onPlayAllClick,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        sharedElementKey = sharedElementKey
                    )
                }

                itemsIndexed(tracks) { index, track ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        TrackListItem(
                            track = track,
                            onClick = { onTrackClick(tracks, index) },
                            active = track.id == activeTrackId,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = playerTransitionVisibilityScope ?: animatedVisibilityScope,
                            isSharedTransitionSource = track.id == sharedTrackId
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HeaderSection(
    title: String,
    subtitle: String?,
    trackCount: Int,
    durationText: String,
    imageUrl: String?,
    onPlayAllClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    sharedElementKey: String?
) {
    val context = LocalContext.current
    val defaultAccentColor = FoamTheme.colors.primaryAccent

    var paletteColor by remember(imageUrl) { mutableStateOf<Color?>(null) }

    LaunchedEffect(imageUrl) {
        paletteColor = if (imageUrl != null) {
            extractPaletteColor(context, imageUrl)
        } else {
            null
        }
    }

    // Fades to the extracted color once it's ready, rather than snapping to it.
    val accentColor by animateColorAsState(
        targetValue = paletteColor ?: defaultAccentColor,
        animationSpec = tween(durationMillis = 300),
        label = "trackListAccentColor"
    )

    // Helper: only wraps with a shared bounds modifier when a shared-element
    // context was actually provided (e.g. when navigating in from Search/Artist).
    // For the title/artist TEXT: content genuinely differs between screens (different
    // font sizes), so sharedBounds' cross-fade between the two is the right behavior.
    @Composable
    fun Modifier.sharedBoundsIfAvailable(key: String): Modifier {
        if (sharedTransitionScope == null || animatedVisibilityScope == null || sharedElementKey == null) {
            return this
        }
        return with(sharedTransitionScope) {
            this@sharedBoundsIfAvailable.sharedBounds(
                rememberSharedContentState(key = key),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = AlbumArtBoundsTransform
            )
        }
    }

    // For the ARTWORK: it's the same image on both screens, just a different size and
    // corner radius. sharedElement animates the bounds of a single element instead of
    // cross-fading two overlapping copies, which is what was causing the "two images
    // clipping together" / transparency glitch. clipInOverlayDuringTransition keeps the
    // rounded corners applied for every frame of the animation, not just the resting state.
    @Composable
    fun Modifier.sharedArtworkIfAvailable(key: String): Modifier {
        if (sharedTransitionScope == null || animatedVisibilityScope == null || sharedElementKey == null) {
            return this
        }
        return with(sharedTransitionScope) {
            this@sharedArtworkIfAvailable.sharedElement(
                rememberSharedContentState(key = key),
                animatedVisibilityScope = animatedVisibilityScope,
                clipInOverlayDuringTransition = OverlayClip(AlbumArtShape),
                boundsTransform = AlbumArtBoundsTransform
            )
        }
    }

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
                .sharedArtworkIfAvailable("album-art-$sharedElementKey")
                .clip(AlbumArtShape)
                .background(FoamTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                val albumArtRequest = remember(imageUrl) {
                    ImageRequest.Builder(context)
                        .data(EmbeddedAlbumArt(imageUrl.toUri()))
                        .size(AlbumArtRequestSize)
                        .crossfade(false)
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
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedBoundsIfAvailable("album-title-$sharedElementKey")
            )

            if (subtitle != null) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.titleMedium.copy(color = accentColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedBoundsIfAvailable("album-artist-$sharedElementKey")
                )
            }
            BasicText(
                text = "$trackCount Tracks • $durationText",
                style = FoamTheme.typography.body,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Play Button
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accentColor)
                    .clickable { onPlayAllClick() }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = FoamTheme.icons.current.play,
                        contentDescription = null,
                        tint = FoamTheme.colors.background,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicText(
                        text = "Play All",
                        style = FoamTheme.typography.titleMedium.copy(color = FoamTheme.colors.background)
                    )
                }
            }
        }
    }
}