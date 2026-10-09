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
 * the image loads: the music note for generic/track art, the album disc for
 * albums, a person for artists. Adding a collection kind means adding one
 * enum value and one mapping arm -- no component or call-site churn.
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
 * Fraction of the artwork surface the no-art music note spans (see
 * [NoAlbumArtPlaceholder]). A proportion of the CURRENT bounds, not a fixed
 * dp value: shared-element flights re-measure the drawn side's content at the
 * interpolated bounds every frame, so the note scales with the flying artwork
 * instead of holding one endpoint's dp size.
 */
private const val NoAlbumArtNoteFraction = 0.7f

/**
 * Image-like placeholder for artwork surfaces that have NO artwork at all --
 * as opposed to [AlbumArtImage]'s placeholder, which only covers the window
 * while real artwork loads.
 *
 * The music note is laid out at [NoAlbumArtNoteFraction] of the CURRENT
 * bounds, not at a fixed dp size. That is what makes it behave like an image
 * rather than an icon: shared-element flights (44dp mini slot <-> 48dp subview
 * header <-> full-screen hero) re-measure the becoming-visible side's content
 * at the interpolated bounds every frame, so a bounds-fraction note scales
 * continuously with the flying artwork. A fixed-dp icon cannot do that --
 * it kept the size computed for the resting endpoint and snapped the moment
 * a flight started or ended. The baked-in surfaceVariant background also
 * makes every endpoint's rest look identical, so the slot's shade no longer
 * flips when a transition's [placeholderColor] hands over at settle.
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

/**
 * Fraction of the artwork surface the no-art title initial (the letter
 * monogram) spans, see [NoAlbumArtLetter]. Matches the compact hero's
 * historic 48sp-in-120dp look, so hero endpoints keep their rest size; the
 * [NoAlbumArtLetterMaxSize] cap keeps wide surfaces (full-width normal and
 * immersive heroes) at that same 48sp instead of a screen-filling glyph.
 */
private const val NoAlbumArtLetterFraction = 0.4f

/** Upper sp bound of the no-art letter, see [NoAlbumArtLetter]. */
private val NoAlbumArtLetterMaxSize = 48.sp

/**
 * The letter monogram shown on artwork surfaces that have NO artwork: the
 * collection title's initial, centered on the surface (the caller draws the
 * surfaceVariant background behind it).
 *
 * The glyph is drawn in the DRAW PHASE at [NoAlbumArtLetterFraction] of the
 * surface's CURRENT min dimension, capped at [NoAlbumArtLetterMaxSize]. Sizing
 * from current bounds is what makes it behave like an image rather than a
 * fixed-sp text: shared-element flights (row <-> detail hero) re-render the
 * drawn side's content against the interpolated bounds every frame, so a
 * bounds-fraction letter scales continuously with the flying artwork surface.
 * A fixed-sp BasicText cannot do that -- it held one endpoint's sp size for
 * the whole flight and popped to the other endpoint's size the instant a
 * flight started or settled (the letter visibly jumping while its surface
 * flew correctly). The 48sp cap reproduces the compact hero's historic
 * monogram exactly and stops normal/immersive heroes (much wider than
 * 120dp) from growing a giant glyph; every surface <= 120dp across renders
 * at exactly 40% of its own size, so all flight endpoints agree at settle.
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
 * composition; defaults to the music note. Screens and sections set it once
 * through [MediaArtKindScope] instead of threading placeholder icons through
 * every component between them and the image.
 */
val LocalMediaArtKind = staticCompositionLocalOf { MediaArtKind.TRACK }

/**
 * Overrides the placeholder kind of every [AlbumArtImage] composed inside
 * [content] (inner scopes override outer ones). Screens hosting one collection
 * kind wrap themselves once -- an artist page scopes itself to
 * [MediaArtKind.ARTIST] and re-scopes only its album grid to
 * [MediaArtKind.ALBUM] -- while mixed surfaces (search results, home cards)
 * wrap each self-describing item.
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
 * While the artwork loads, the placeholder resolved from [placeholderKind]
 * (or the surrounding [LocalMediaArtKind] scope when null) shows through: the
 * music note by default, or the collection's own icon on surfaces whose kind
 * is known -- the album disc for albums, a person for artists -- so the
 * placeholder reads as the collection rather than a generic track.
 *
 * Coil emits [AsyncImagePainter.State.Loading] for EVERY request -- memory-cache
 * hits included -- before delivering the result (RealImageLoader calls the
 * target's onStart unconditionally), so the fade is gated on the RESULT's data
 * source rather than on Loading: a [DataSource.MEMORY_CACHE] result snaps back
 * to full alpha 1f synchronously inside the same composition pass, so shared
 * element flights (row -> album header) and revisited list rows draw the image
 * on their very first frame with no placeholder flash and no fade. Only a
 * genuine load (disk / embedded art decode) shows the placeholder and fades
 * the decoded image in over 200ms when it arrives.
 *
 * The placeholder sits BEHIND the image and is removed on success, so the fade
 * runs cleanly over the artwork surface; on error the placeholder stays
 * visible. Every state is keyed on the model so recycled lazy-list rows reset
 * for their new item instead of inheriting the previous item's fade state.
 *
 * Scroll-cost notes (this composable is on every media-list row):
 *  - The Loading branch only writes state it is actually CHANGING. Coil fires
 *    Loading for every cache hit too; a no-op write would still invalidate the
 *    reading scopes, and a Success for a cache hit would write them right
 *    back -- two recompositions per row per scroll entry for zero visual
 *    difference. Guarding on the current values makes a cached hit write
 *    nothing at all.
 *  - The alpha `graphicsLayer` lambda is attached ONLY while a fade is running
 *    (or parked at a non-1 alpha). The lambda variant unconditionally promotes
 *    the image to its own render layer -- an extra offscreen buffer per row,
 *    even at full alpha. At rest the modifier chain is layer-free.
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
    // A fade is in flight: keep the alpha layer attached so its frames are
    // draw-only, then drop the layer entirely once the art rests at full alpha.
    var fadeActive by remember(model) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Explicit kinds win; otherwise the surrounding MediaArtKindScope decides.
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
                        // A request is in flight. Coil ALSO emits this for
                        // memory-cache hits; the Success branch restores the
                        // rest look there. Writing only real changes keeps a
                        // cache hit from invalidating anything.
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
                            // Memory-cache hit: draw at full alpha immediately,
                            // so the image is on the very first drawn frame.
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
