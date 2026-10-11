package com.foamplayer.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.DataSource
import com.foamplayer.app.ui.theme.FoamTheme
import kotlinx.coroutines.launch
import kotlin.math.min

/** Duration of the fade-in a freshly decoded album art gets over its placeholder. */
private const val AlbumArtFadeInDurationMs = 200

/**
 * What an artwork surface depicts. Drives the placeholder icon shown while
 * the image loads.
 */
enum class MediaArtKind {
    /** Generic or track artwork: the default music note. */
    TRACK,

    /** Album covers: the disc. */
    ALBUM,

    /** Artist portraits: the person. */
    ARTIST
}

/** Placeholder icon shown while artwork of [kind] loads. */
fun mediaArtPlaceholderIcon(kind: MediaArtKind): ImageVector = when (kind) {
    MediaArtKind.TRACK -> Icons.Rounded.MusicNote
    MediaArtKind.ALBUM -> Icons.Rounded.Album
    MediaArtKind.ARTIST -> Icons.Rounded.Person
}

/**
 * Fraction of the artwork surface the no-art music note spans ([NoAlbumArtPlaceholder]).
 * A proportion of the CURRENT bounds, not a fixed dp value, so shared-element
 * flights scale the note with the flying artwork.
 */
private const val NoAlbumArtNoteFraction = 0.7f

/**
 * Image-like placeholder for artwork surfaces that have NO artwork at all (as
 * opposed to [AlbumArtImage]'s loading placeholder). The note is laid out at
 * [NoAlbumArtNoteFraction] of the current bounds, which makes it behave like
 * an image during shared-element flights: it scales continuously with the
 * flying artwork instead of holding one endpoint's dp size. The baked-in
 * background keeps every endpoint's rest look identical.
 */
@Composable
fun NoAlbumArtPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FoamTheme.colors.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = mediaArtPlaceholderIcon(MediaArtKind.TRACK),
            contentDescription = null,
            tint = FoamTheme.colors.textSecondary,
            modifier = Modifier.fillMaxSize(NoAlbumArtNoteFraction)
        )
    }
}

/** Fraction of the artwork surface the no-art title initial spans. */
private const val NoAlbumArtLetterFraction = 0.4f

/** Upper sp bound of the no-art letter, so wide surfaces don't grow a giant glyph. */
private val NoAlbumArtLetterMaxSize = 48.sp

/**
 * The title's initial, centered on an artwork surface with no artwork (the
 * caller draws the surfaceVariant background). The glyph is drawn in the DRAW
 * PHASE at [NoAlbumArtLetterFraction] of the surface's current min dimension,
 * capped at [NoAlbumArtLetterMaxSize] — bounds-fraction sizing makes it scale
 * continuously during shared-element flights instead of popping between fixed
 * sizes.
 */
@Composable
fun NoAlbumArtLetter(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = FoamTheme.colors.textPrimary
) {
    val textMeasurer = rememberTextMeasurer()
    val baseStyle = FoamTheme.typography.titleLarge
    val letter = remember(title) { title.take(1).uppercase() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val fontSizePx = min(
                    size.minDimension * NoAlbumArtLetterFraction,
                    NoAlbumArtLetterMaxSize.toPx()
                )
                val layout = textMeasurer.measure(
                    text = letter,
                    style = baseStyle.copy(
                        fontSize = fontSizePx.toSp(),
                        color = color
                    ),
                    maxLines = 1
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = (size.width - layout.size.width) / 2f,
                        y = (size.height - layout.size.height) / 2f
                    )
                )
            }
    )
}

/**
 * Placeholder kind inherited by every [AlbumArtImage] below this point in the
 * composition; defaults to the music note. Set once through [MediaArtKindScope]
 * instead of threading placeholder icons through every component.
 */
val LocalMediaArtKind = staticCompositionLocalOf { MediaArtKind.TRACK }

/**
 * Overrides the placeholder kind of every [AlbumArtImage] composed inside
 * [content] (inner scopes override outer ones). Screens hosting one collection
 * kind wrap themselves once; mixed surfaces wrap each self-describing item.
 */
@Composable
fun MediaArtKindScope(
    kind: MediaArtKind,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalMediaArtKind provides kind, content = content)
}

/**
 * Album artwork with a placeholder icon and a fade-in for real loads.
 *
 * Coil emits Loading for EVERY request — memory-cache hits included — so the
 * fade is gated on the RESULT's data source: a [DataSource.MEMORY_CACHE] result
 * snaps back to full alpha synchronously in the same composition pass, so
 * shared-element flights and revisited rows draw the image on their first
 * frame with no placeholder flash. Only a genuine load (disk / embedded art
 * decode) fades in over 200ms.
 *
 * The placeholder sits behind the image and is removed on success; on error it
 * stays visible. Every state is keyed on the model so recycled lazy rows reset
 * for their new item.
 *
 * Scroll-cost notes (this composable is on every media-list row):
 *  - The Loading branch only writes state it is actually changing — a cache
 *    hit would otherwise invalidate readers twice per row per scroll entry.
 *  - The alpha graphicsLayer is attached only while a fade runs; at rest the
 *    modifier chain is layer-free (the lambda variant promotes every row to
 *    its own render layer even at full alpha).
 */
@Composable
fun AlbumArtImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderSize: Dp = 20.dp,
    placeholderKind: MediaArtKind? = null
) {
    // Parked at 1f; only a running fade writes frames into it.
    val alphaState = remember(model) { mutableFloatStateOf(1f) }
    var loading by remember(model) { mutableStateOf(false) }
    var showPlaceholder by remember(model) { mutableStateOf(false) }
    // A fade in flight: keep the alpha layer attached, then drop it once the
    // art rests at full alpha.
    var fadeActive by remember(model) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val placeholderIcon = mediaArtPlaceholderIcon(placeholderKind ?: LocalMediaArtKind.current)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (showPlaceholder) {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = null,
                tint = FoamTheme.colors.textSecondary,
                modifier = Modifier.size(placeholderSize)
            )
        }
        val imageModifier = if (fadeActive) {
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = alphaState.floatValue }
        } else {
            Modifier.fillMaxSize()
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = imageModifier,
            contentScale = contentScale,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Loading -> {
                        if (!loading) loading = true
                        if (!showPlaceholder) showPlaceholder = true
                        if (alphaState.floatValue != 0f) alphaState.floatValue = 0f
                    }
                    is AsyncImagePainter.State.Success -> {
                        showPlaceholder = false
                        if (loading && state.result.dataSource != DataSource.MEMORY_CACHE) {
                            // Freshly decoded: fade in over the artwork surface.
                            loading = false
                            fadeActive = true
                            scope.launch {
                                val fade = Animatable(initialValue = 0f)
                                fade.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = AlbumArtFadeInDurationMs,
                                        easing = EaseOut
                                    )
                                ) {
                                    alphaState.floatValue = value
                                }
                                fadeActive = false
                                alphaState.floatValue = 1f
                            }
                        } else {
                            // Memory-cache hit: draw at full alpha immediately.
                            loading = false
                            fadeActive = false
                            if (alphaState.floatValue != 1f) alphaState.floatValue = 1f
                        }
                    }
                    else -> Unit
                }
            }
        )
    }
}
