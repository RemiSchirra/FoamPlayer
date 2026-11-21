package com.foamplayer.app.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.net.toUri
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size as CoilSize
import com.foamplayer.app.constants.MediaDensity
import com.foamplayer.app.data.media.EmbeddedAlbumArt
import com.foamplayer.app.ui.navigation.LocalActiveSharedCollectionTagState
import com.foamplayer.app.ui.navigation.collectionFlightConfig
import com.foamplayer.app.ui.navigation.isActiveSharedCollectionFlightKey
import com.foamplayer.app.ui.screens.player.immersiveArtFadeSpan
import com.foamplayer.app.ui.screens.player.PlayerDimens
import com.foamplayer.app.ui.theme.FoamMotion
import com.foamplayer.app.ui.theme.FoamTheme
import com.foamplayer.app.ui.theme.LocalAutomaticColorEnabled
import com.foamplayer.app.ui.theme.adaptAccentForTheme
import com.foamplayer.app.ui.utils.AlbumArtPaletteCache
import kotlin.math.roundToInt

/** Corner radius of an album-art surface of [artSize]: half the size times the rounding percentage. */
@Composable
fun albumArtCornerRadius(artSize: Dp): Dp = (artSize / 2) * FoamTheme.shapes.miniPlayerRounding

/**
 * Corner shape of every album-art surface, percentage-based: the radius is
 * always a fraction of the artwork's own size, so 48dp rows, 120dp headers
 * and full-width grid cells round identically, and shared-element artwork
 * scales its corners with the animated bounds.
 */
@Composable
fun AlbumArtShape(): Shape = RoundedCornerShape(
    percent = (FoamTheme.shapes.miniPlayerRounding * 50).roundToInt().coerceIn(0, 50)
)

/**
 * Corner percentage of an artwork's own size that a collection flight morphs
 * from/to for fully round artwork (artists): 50f — a full circle.
 */
internal const val AlbumArtFlightFullyRoundedPercent = 50f

/**
 * Overlay clip of a collection flight whose flying artwork casts its own drop
 * shadow: the animated artwork shape expanded by [marginPx], so the RenderNode
 * shadow — drawn just outside the silhouette — survives the overlay clip. The
 * immersive morph path must keep a clip (without one the drawn side's first
 * overlay frame renders transparent); this variant grows by the margin the
 * shadow needs, nothing more. Returns null when the margin is 0.
 */
private class ExpandedAlbumArtFlightClip(
    private val restPercent: Float,
    private val shapeFraction: () -> Float,
    private val marginPx: () -> Float
) : SharedTransitionScope.OverlayClip {
    private val path = Path()

    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density
    ): Path? {
        val margin = marginPx()
        if (margin <= 0f) return null
        val expanded = bounds.inflate(margin)
        path.reset()
        path.addOutline(
            AlbumArtFlightShape(restPercent) { shapeFraction() }
                .createOutline(expanded.size, layoutDirection, density)
        )
        path.translate(expanded.topLeft)
        return path
    }
}

/**
 * Fixed decode size (px) for album art shared across the app: Coil's memory
 * cache key includes the requested size, so a shared value makes switching
 * between list images and header images an instant cache hit.
 */
val AlbumArtRequestSize = CoilSize(480, 480)

/**
 * Deterministic Coil memory-cache key for the app's shared album art.
 *
 * A shared-element flight draws the ENTERING side's artwork in the transition
 * overlay, so that side must render the image on its FIRST frame. Every request
 * taking part in a flight must therefore pair
 * `placeholderMemoryCacheKey(albumArtMemoryCacheKey(path))` with
 * `memoryCacheKey(albumArtMemoryCacheKey(path))`: Coil delivers even a
 * memory-cache hit's result asynchronously (a frame or more after the first
 * composition), and the placeholder key is what makes the painter draw the
 * match's cached bitmap immediately on frame one. With the cache key alone,
 * a first open shows the bare placeholder surface mid-flight and only
 * RE-opening the same collection — where the request has already warmed the
 * cache under this key — appears to animate.
 *
 * The full player's art surfaces are the one exception: they render the
 * artwork at its ORIGINAL size under [albumArtFullResMemoryCacheKey] (this
 * key would pin them to the cached 480px downscale), but pair that full-res
 * key with this one as their `placeholderMemoryCacheKey` via
 * [fullResAlbumArtRequest] — so a flight still paints this 480px bitmap on
 * frame one and upgrades to full-res once decoded.
 */
fun albumArtMemoryCacheKey(path: String): String = "album-art:$path"

/**
 * Warms the shared album-art cache slot for [path] BEFORE a collection flight:
 * fire-and-forgets the exact request the destination hero issues, so the slot
 * is populated (or already decoding) by the time the detail screen's hero
 * composes and reads it synchronously for its first frame (see
 * [albumArtMemoryCacheKey]). Without this, a first open of a collection whose
 * artwork was never decoded under the shared key shows the placeholder surface
 * mid-flight while the decode runs.
 */
fun preloadAlbumArtForFlight(context: Context, path: String, isEmbeddedArt: Boolean) {
    if (path.isBlank()) return
    val request = ImageRequest.Builder(context)
        .data(if (isEmbeddedArt) EmbeddedAlbumArt(path.toUri()) else path)
        .size(AlbumArtRequestSize)
        .memoryCacheKey(albumArtMemoryCacheKey(path))
        .build()
    context.imageLoader.enqueue(request)
}

/**
 * Deterministic Coil memory-cache key for the full player's FULL-RESOLUTION
 * album art.
 *
 * The full player's art surfaces (hero, pager page, vinyl disc) decode the
 * artwork at its original size instead of the shared [AlbumArtRequestSize]
 * downscale, so they cannot use the shared `album-art:` slot: requests keyed
 * identically return whichever bitmap is cached there, so a warm 480px entry
 * would pin the full player to the downscale forever (and a full-res entry
 * would evict it for every other surface). A separate key keeps both bitmaps
 * resident; the quality split is bridged by the placeholder fallback in
 * [fullResAlbumArtRequest].
 */
fun albumArtFullResMemoryCacheKey(path: String): String = "album-art-full:$path"

/**
 * Builds the full player's artwork request: the ORIGINAL decode of [path]'s
 * artwork under [albumArtFullResMemoryCacheKey], falling back to the shared
 * 480px [albumArtMemoryCacheKey] slot as the placeholder. The placeholder
 * pairing is what keeps shared-element flights working: the entering
 * full-player art paints the flight-warmed 480px bitmap on its FIRST frame
 * (see [albumArtMemoryCacheKey]) and upgrades to the full-res bitmap once its
 * own decode completes.
 */
fun fullResAlbumArtRequest(context: Context, path: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(EmbeddedAlbumArt(path.toUri()))
        .size(CoilSize.ORIGINAL)
        .crossfade(false)
        .placeholderMemoryCacheKey(albumArtMemoryCacheKey(path))
        .memoryCacheKey(albumArtFullResMemoryCacheKey(path))
        .build()

/**
 * Bounds transform shared by all artwork shared elements: the same
 * duration/easing as the NavHost's screen fade, so background fades and
 * artwork flights finish on the same frame.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val AlbumArtBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = FoamMotion.Duration.FLIGHT, easing = FoamMotion.Easings.STANDARD)
}

/**
 * Animated flight variant of a percentage-based artwork shape: the radius is
 * [restPercent] of the artwork's own size, scaled by [fraction] (1f = the
 * source row's rounded rest look, 0f = the immersive hero's square look).
 * The fraction is read per outline creation, so no recomposition is involved.
 */
internal class AlbumArtFlightShape(
    private val restPercent: Float,
    private val fraction: () -> Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val percent = (restPercent * fraction().coerceIn(0f, 1f)).coerceIn(0f, 50f)
        val radius = CornerRadius(size.width * percent / 100f, size.height * percent / 100f)
        return Outline.Rounded(
            RoundRect(
                rect = size.toRect(),
                topLeft = radius,
                topRight = radius,
                bottomRight = radius,
                bottomLeft = radius
            )
        )
    }
}

/**
 * Flight progress of a collection row drawn as the shared side of a flight
 * with an immersive collection hero: 0f while departing (the hero's square,
 * faded look), 1f once landed at the row's rest look. Pinned to 1f when no
 * shared element is in flight, so the row always rests on its static look.
 */
@Composable
internal fun rememberCollectionFlightProgress(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
): State<Float> {
    val flight = animatedVisibilityScope.transition.animateFloat(
        transitionSpec = { FoamMotion.Spec.sharedElementFlight },
        label = "collectionRowFlight"
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    return remember(sharedTransitionScope, flight) {
        derivedStateOf {
            if (sharedTransitionScope.isTransitionActive) flight.value else 1f
        }
    }
}

/**
 * Whether a collection close flight is currently landing on the given scopes'
 * row side (the artwork shrinking from the hero onto the clicked row). Open
 * flights do not count: there the whole page fades and the row's static shadow
 * hands off through that fade. Rests at false when either scope is missing.
 *
 * Used to step the tagged row's static rest shadow aside for the flight: the
 * overlay renders only the flying artwork (which casts the shadow itself),
 * while the row's own artwork slot is empty in place.
 */
@Composable
internal fun rememberCollectionCloseFlightActive(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): State<Boolean> {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) {
        val alwaysInactive = remember { mutableStateOf(false) }
        return alwaysInactive
    }
    return remember(animatedVisibilityScope) {
        derivedStateOf {
            animatedVisibilityScope.transition.targetState == EnterExitState.Visible &&
                    animatedVisibilityScope.transition.currentState != EnterExitState.Visible
        }
    }
}

/**
 * Flight-time look of a collection row that is the DRAWN side of a
 * shared-element flight with an immersive collection hero: the corner radius
 * rides the flight from the hero's square look to the row's own radius, and a
 * bottom fade mirrors the hero's, easing out as the art lands.
 *
 * [shadowElevationPx] lets the flying artwork cast the row's drop shadow while
 * the flight plays (the static one is suppressed for the flight); at rest it
 * reads 0. Every frame is draw-only.
 *
 * [isFlightRow] marks the row whose collection was actually clicked (see
 * [isActiveSharedCollectionFlightKey]) — the only source that rides the
 * flight. The progress is driven by the SCREEN's enter/exit transition, which
 * every row of the list shares, so other rows pin the fraction to their rest
 * look and skip the fade: their corners stay static while the flight plays.
 */
@Composable
internal fun Modifier.collectionFlightArtwork(
    progress: State<Float>,
    fullyRounded: Boolean = false,
    shadowElevationPx: () -> Float = { 0f },
    isFlightRow: Boolean = true
): Modifier {
    val restPercent = if (fullyRounded) {
        AlbumArtFlightFullyRoundedPercent
    } else {
        FoamTheme.shapes.miniPlayerRounding * 50f
    }
    return this
        .graphicsLayer {
            // Rows other than the clicked one never ride the flight: the
            // fraction stays at the rest look for the whole transition.
            val p = if (isFlightRow) progress.value else 1f
            shape = AlbumArtFlightShape(restPercent) { p }
            clip = true
            // Isolate the DstIn fade to this layer so it never erases what
            // sits behind the artwork.
            compositingStrategy = if (p < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            shadowElevation = shadowElevationPx()
        }
        .drawWithContent {
            drawContent()
            if (!isFlightRow) return@drawWithContent
            val fadeStrength = 1f - progress.value
            if (fadeStrength > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        CollectionHeroImmersiveFadeStartFraction to Color.Black,
                        1f to Color.Black.copy(alpha = 1f - fadeStrength)
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
        }
}

/**
 * Shared artwork modifier of a collection row: the static rest path, or — when
 * [immersiveFlightHero] is on — the flight variant whose clip, fade and
 * (optionally) flight shadow ride the transition, so a closing flight departs
 * from the hero's square, faded look and lands on the row's rounded,
 * unfaded one.
 *
 * [fullyRounded] mirrors a circular rest shape (artists) into the flight so it
 * lands on the circle instead of popping to it. [castsFlightShadow] hands the
 * row's static shadow to the flying artwork for the flight's duration (the
 * host suppresses the static one). IMMERSIVE heroes keep the animated overlay
 * clip — without it the drawn side's first overlay frame renders transparent —
 * and use the EXPANDED variant so the shadow survives it.
 */
@Composable
internal fun Modifier.collectionRowFlightArtwork(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    restShape: Shape,
    immersiveFlightHero: Boolean,
    fullyRounded: Boolean = false,
    castsFlightShadow: Boolean = false
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) {
        return this.clip(restShape)
    }
    val isFlightRow = isActiveSharedCollectionFlightKey(key)
    if (!immersiveFlightHero) {
        if (!castsFlightShadow) {
            return this
                .rowSharedArtwork(
                    key = key,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    collectionFlight = true,
                    overlayClip = restShape,
                    boundsTransform = AlbumArtBoundsTransform
                )
                .clip(restShape)
        }
        val flightProgress = rememberCollectionFlightProgress(
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope
        )
        val closeFlightActive = rememberCollectionCloseFlightActive(
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope
        )
        val restElevationPx = with(LocalDensity.current) { MediaItemArtElevation.toPx() }
        return this
            .rowSharedArtwork(
                key = key,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                collectionFlight = true,
                overlayClip = null,
                boundsTransform = AlbumArtBoundsTransform
            )
            .graphicsLayer {
                shape = restShape
                clip = true
                shadowElevation = if (isFlightRow && closeFlightActive.value) {
                    restElevationPx * flightProgress.value
                } else {
                    0f
                }
            }
            .clip(restShape)
    }
    val restPercent = if (fullyRounded) {
        AlbumArtFlightFullyRoundedPercent
    } else {
        FoamTheme.shapes.miniPlayerRounding * 50f
    }
    val flightProgress = rememberCollectionFlightProgress(
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
    val closeFlightActive = rememberCollectionCloseFlightActive(
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope
    )
    val restElevationPx = with(LocalDensity.current) { MediaItemArtElevation.toPx() }
    val flightShadowElevationPx: () -> Float = {
        if (castsFlightShadow && isFlightRow && closeFlightActive.value) {
            restElevationPx * flightProgress.value
        } else {
            0f
        }
    }
    return this
        .rowSharedArtwork(
            key = key,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            collectionFlight = true,
            overlayClipShape = ExpandedAlbumArtFlightClip(
                restPercent = restPercent,
                shapeFraction = { flightProgress.value },
                marginPx = flightShadowElevationPx
            ),
            boundsTransform = AlbumArtBoundsTransform
        )
        .collectionFlightArtwork(flightProgress, fullyRounded, flightShadowElevationPx, isFlightRow)
}

/**
 * Whether a player-dismiss artwork flight is currently landing on this list's
 * rows — the artwork shrinking from the full player's hero onto the playing
 * row. True from the first frame the dismiss gesture takes over until the
 * close flight settles; enum reads cannot overshoot. Used to step the rows'
 * static rest shadows aside for the flight, mirroring
 * [rememberCollectionCloseFlightActive].
 */
@Composable
internal fun rememberPlayerDismissFlightActive(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): State<Boolean> {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) {
        val alwaysInactive = remember { mutableStateOf(false) }
        return alwaysInactive
    }
    return remember(animatedVisibilityScope) {
        derivedStateOf {
            animatedVisibilityScope.transition.targetState == EnterExitState.Visible &&
                    animatedVisibilityScope.transition.currentState != EnterExitState.Visible
        }
    }
}

/**
 * Artwork modifier for a list row that is the shared-element ENDPOINT of a
 * player-dismiss flight. During such a flight the overlay renders this row's
 * content at the interpolated bounds, so the row must itself carry the
 * transition look:
 *
 *  - the corner radius lerps from the full player hero's radius down to the
 *    row's own percentage radius,
 *  - the paused-art shrink is mirrored (every endpoint of an album-art pair
 *    must render at the same scale or the art pops at hand-off),
 *  - the artwork casts the hero's drop shadow, easing from the hero's full
 *    elevation down to [restArtElevation] as it lands (the static shadow
 *    suppressed by [rememberPlayerDismissFlightActive] resumes at that value),
 *  - with [immersive] on, the alpha mirrors the hero's immersive fade.
 *
 * Everything is driven by [animatedVisibilityScope]'s enter/exit transition
 * (seeked by the player's SeekableTransitionState), so all values track the
 * finger during the interactive swipe. All reads happen inside the
 * graphicsLayer block: every frame of the flight is draw-only.
 */
@Composable
internal fun Modifier.playerFlightArtwork(
    animatedVisibilityScope: AnimatedVisibilityScope,
    fullArtRadius: Dp,
    pausedArtScale: () -> Float,
    immersive: Boolean = false,
    restArtElevation: Dp = MediaItemArtElevation
): Modifier {
    val flightProgress by animatedVisibilityScope.transition.animateFloat(label = "rowArtFlight") { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    val density = LocalDensity.current
    val fullRadiusPx = remember(fullArtRadius, density) { with(density) { fullArtRadius.toPx() } }
    val fullElevationPx =
        remember(density) { with(density) { PlayerDimens.fullPlayerArtElevation.toPx() } }
    val restElevationPx =
        remember(restArtElevation, density) { with(density) { restArtElevation.toPx() } }
    val rounding = FoamTheme.shapes.miniPlayerRounding
    return this.graphicsLayer {
        val progress = flightProgress
        // Rest radius is percentage-based on the animated size, so it scales
        // along with the bounds throughout the flight.
        val restRadiusPx = (size.minDimension / 2f) * rounding
        shape = RoundedCornerShape(with(density) { lerp(fullRadiusPx, restRadiusPx, progress).toDp() })
        clip = true
        val paused = pausedArtScale()
        val scale = paused + (1f - paused) * progress
        scaleX = scale
        scaleY = scale
        shadowElevation = if (!immersive) {
            lerp(fullElevationPx, restElevationPx, progress)
        } else {
            0f
        }
        // progress runs 0 (flight start) -> 1 (landed): replicate the hero's
        // immersive fade so the overlay art is as (in)visible as the hero.
        alpha = if (immersive) {
            1f - EaseOutCubic.transform(((1f - progress) / immersiveArtFadeSpan).coerceIn(0f, 1f))
        } else {
            1f
        }
    }
}

/**
 * Breathing room the player-dismiss highlight wraps the full player's hero (image +
 * text) with at the flight's start. Mirrors how a row's own highlight radius follows
 * its artwork plus [ListRowHighlightPadding], so the flight's start container rounds
 * the same way around the hero art.
 */
val PlayerFlightHighlightPadding = 12.dp

/**
 * Extra headroom the player-dismiss highlight draws above the player's image at the
 * flight's player end, easing down to the final padding as it lands (see
 * [playerFlightHighlightTarget]).
 */
val PlayerFlightHighlightExtraTopPadding = 36.dp

/**
 * Everything the row side of the player-dismiss highlight flight needs: the shared
 * element key matching the full player's source container, the player transition
 * scopes and the highlight color the morphing rect fades in with.
 */
internal class PlayerFlightHighlightSpec(
    val key: String,
    val sharedTransitionScope: SharedTransitionScope,
    val animatedVisibilityScope: AnimatedVisibilityScope,
    val highlightColor: Color,
)

/**
 * Expands a node's reported bounds by [paddingPx] on every side, drawing its content
 * [paddingPx] inside them: the node measures at the given constraints, then reports
 * size + 2 * padding with the content placed at the padding offset. The shared
 * transition machinery reads these lookahead bounds, so a node sized to a container
 * reports the container's bounds grown by the padding on all sides — the breathing
 * room the flight's start container wraps the player's image + text with.
 */
private fun Modifier.expandedHighlightBounds(paddingPx: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val padding = paddingPx.roundToInt()
    layout(
        width = placeable.width + padding * 2,
        height = placeable.height + padding * 2
    ) {
        placeable.placeRelative(padding, padding)
    }
}

/**
 * SOURCE side of the player-dismiss highlight flight: an invisible container whose
 * bounds wrap the full player's hero (image + text) expanded by [paddingPx]. Paired
 * with [playerFlightHighlightTarget] on the tagged row, its bounds are the flight's
 * start rect — the highlight appears to wrap the player's image + text and morphs
 * down into the row's own highlight rect as the flight lands.
 *
 * The content is empty: only the bounds take part in the pair. Registered for as long
 * as the hero is composed; without a tagged row on the entering screen the pair never
 * matches and nothing renders.
 */
@Composable
internal fun Modifier.playerFlightHighlightSource(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    key: String,
    paddingPx: Float
): Modifier = with(sharedTransitionScope) {
    this@playerFlightHighlightSource
        .expandedHighlightBounds(paddingPx)
        .sharedBounds(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
            // Pairs with the flying artwork and texts: the overlay sort forces
            // root-level 0f entries to -1f, so -2f keeps the highlight below the
            // artwork (-1f effective) and the texts (1f) render above both.
            zIndexInOverlay = -2f
        )
}

/**
 * TARGET side of the player-dismiss highlight flight (see
 * [playerFlightHighlightSource]): registers the row's highlight rect as the shared
 * bounds endpoint paired with the full player's art + text container, and draws the
 * highlight INSIDE the transition overlay while the player transition is in flight.
 *
 * The rect rides the shared bounds between the row and the player's art + text
 * container in BOTH directions: a dismiss flight lands it at the row's rect, fading
 * in over the flight, while an auto-open lifts it from the row into the player
 * container, fading out. The radius eases from the hero art's radius (plus the same
 * padding) to the row's own radius, and extra headroom is drawn above the player's
 * image at the player end, easing down to the final padding. Everything is driven by
 * the player's seekable transition, so the morph tracks the finger during the
 * interactive swipe.
 *
 * Draws nothing at rest — the row's own static highlight takes over, so the hand-off
 * at settle is exact: the overlay ends at the row's rect, radius and color on the
 * frame it drops. [gridCell] swaps the landing radius for a grid cell's size-derived
 * [MediaCellHighlightShape] radius, which converges on the cell's own radius at the
 * settle frame.
 */
@Composable
internal fun Modifier.playerFlightHighlightTarget(
    spec: PlayerFlightHighlightSpec,
    gridCell: Boolean = false
): Modifier {
    val localDensity = LocalDensity.current
    val rowEndRadiusPx = with(localDensity) {
        (listRowArtCornerRadius() + ListRowHighlightPadding).toPx()
    }
    val gridInsetPx = with(localDensity) { MediaGridCellPadding.toPx() }
    val gridRounding = FoamTheme.shapes.miniPlayerRounding
    val endRadiusPx: (Size) -> Float = if (gridCell) {
        { size ->
            val artSide = (size.width - gridInsetPx * 2).coerceAtLeast(0f)
            (artSide / 2f * gridRounding + gridInsetPx).coerceAtMost(size.minDimension / 2f)
        }
    } else {
        { _ -> rowEndRadiusPx }
    }
    val startRadiusPx = with(localDensity) {
        (fullPlayerArtRadius() + PlayerFlightHighlightPadding).toPx()
    }
    val extraTopPx = with(localDensity) { PlayerFlightHighlightExtraTopPadding.toPx() }
    val flightProgress by spec.animatedVisibilityScope.transition.animateFloat(
        label = "playerHighlightFlight"
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    // Mid-flight either direction (closing into the row or auto-opening out of it):
    // both directions render the morphing rect in the overlay; the open direction
    // fades the rect OUT while it rises into the player container.
    val inFlight = remember(spec.animatedVisibilityScope) {
        derivedStateOf {
            spec.animatedVisibilityScope.transition.targetState != EnterExitState.Visible ||
                    spec.animatedVisibilityScope.transition.currentState != EnterExitState.Visible
        }
    }
    return with(spec.sharedTransitionScope) {
        sharedBounds(
            rememberSharedContentState(key = spec.key),
            animatedVisibilityScope = spec.animatedVisibilityScope,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            // The overlay re-measures the content at the animated bounds every frame,
            // so the drawn rect fills them exactly instead of being scaled up from its
            // rest size (a scale would distort the radius across the aspect jump).
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            // Below the flying artwork: the overlay sort forces root-level 0f
            // entries to -1f, so the highlight needs less than that to draw under
            // the image and text.
            zIndexInOverlay = -2f
        )
    }
        .fillMaxSize()
        .drawBehind {
            if (!inFlight.value) return@drawBehind
            val progress = flightProgress.coerceIn(0f, 1f)
            if (progress <= 0f) return@drawBehind
            val radius = lerp(startRadiusPx, endRadiusPx(size), progress)
                .coerceAtMost(size.minDimension / 2f)
            // Extra headroom above the player's image at the flight's player end,
            // easing down to the final padding as the rect lands.
            val extraTop = extraTopPx * (1f - progress)
            drawRoundRect(
                color = spec.highlightColor.copy(alpha = spec.highlightColor.alpha * progress),
                cornerRadius = CornerRadius(radius),
                topLeft = Offset(0f, -extraTop),
                size = Size(size.width, size.height + extraTop)
            )
        }
}

/** Size of the artwork thumbnail at the leading edge of a list row. */
val ListRowArtSize = 48.dp

/**
 * Drop shadow elevation behind every media item's artwork at rest; pass 0.dp
 * for artwork that uses a different shape or has no surface.
 */
val MediaItemArtElevation = 4.dp

/**
 * Animated background of a list row's active highlight (currently playing
 * track, or the collection the queue was started from). The color comes from
 * the row's own image when automatic (dynamic accent) color is enabled, with
 * the app accent as fallback; always drawn at 40% alpha.
 */
@Composable
internal fun activeRowHighlight(
    active: Boolean,
    imagePath: String?,
    isEmbeddedArt: Boolean = true
): Color {
    val automaticColorEnabled = LocalAutomaticColorEnabled.current

    if (active && automaticColorEnabled) {
        val context = LocalContext.current
        LaunchedEffect(imagePath, isEmbeddedArt) {
            AlbumArtPaletteCache.ensure(
                scope = this,
                context = context,
                path = imagePath,
                isEmbeddedArt = isEmbeddedArt
            )
        }
    }

    val extractedColor = if (active && automaticColorEnabled && imagePath != null) {
        AlbumArtPaletteCache.colors[imagePath]
    } else {
        null
    }

    val target = when {
        !active -> Color.Transparent
        !automaticColorEnabled -> FoamTheme.accentColor.copy(alpha = 0.4f)
        else -> (
                extractedColor?.adaptAccentForTheme(FoamTheme.isDark)
                    ?: FoamTheme.accentColor
                ).copy(alpha = 0.4f)
    }

    return animateColorAsState(
        targetValue = target,
        animationSpec = tween(FoamMotion.Duration.SWIFT, easing = FoamMotion.Easings.EXIT),
        label = "activeRowHighlight"
    ).value
}

/** Artwork size of the larger RowBig density. */
val ListRowBigArtSize = 72.dp

/** Artwork size of a row at [density] (RowSmall has none). */
fun rowArtSize(density: MediaDensity): Dp = when (density) {
    MediaDensity.ROW_BIG -> ListRowBigArtSize
    else -> ListRowArtSize
}

/** Padding around a list row's content, which the press highlight extends beyond. */
val ListRowHighlightPadding = 6.dp

/** Tighter vertical padding of the compact RowSingle-line rows. */
val RowSmallVerticalPadding = 2.dp

/**
 * Vertical padding around a row's content AND the distance its highlight
 * extends beyond it: the two are the same value so the highlight fills the
 * row's bounds exactly.
 */
@Composable
internal fun rowVerticalPadding(density: MediaDensity): Dp =
    if (density == MediaDensity.ROW_SMALL) RowSmallVerticalPadding else ListRowHighlightPadding

/** Corner radius of a list row's artwork at rest. */
@Composable
internal fun listRowArtCornerRadius(density: MediaDensity = MediaDensity.ROW_NORMAL): Dp =
    albumArtCornerRadius(rowArtSize(density))

/**
 * Shared row scaffold for every media list row (TrackListItem,
 * CollectionListItem, ...): a full-width row with a rounded press/long-press
 * highlight, a leading artwork box and a title/subtitle column that
 * [trailingContent] can extend.
 *
 * [density] picks the layout: RowSmall is a compact single-line title without
 * artwork, RowNormal keeps the classic 48dp art + two-line row, RowBig grows
 * the art to 72dp and shows [tertiaryText] as a third line. [highlightBackground]
 * is drawn behind the content, clipped to the same shape as the press ripple.
 */
@Composable
internal fun MediaListRow(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    artworkModifier: Modifier,
    artworkContent: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    subtitleModifier: Modifier = Modifier,
    density: MediaDensity = MediaDensity.ROW_NORMAL,
    tertiaryText: String? = null,
    artworkElevation: Dp = MediaItemArtElevation,
    artworkShape: Shape = AlbumArtShape(),
    highlightBackground: Color = Color.Transparent,
    suppressHighlightBackground: Boolean = false,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    val artSize = rowArtSize(density)
    val artCornerRadius = listRowArtCornerRadius(density)
    val verticalPadding = rowVerticalPadding(density)
    val highlightShape = RoundedCornerShape(artCornerRadius + verticalPadding)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(rowModifier)
            .clip(highlightShape)
            // Steps aside while the player-dismiss highlight flight lands: the shared
            // overlay renders the morphing highlight instead, and this static one
            // resumes at the exact settle frame so the hand-off is seamless.
            .then(
                if (suppressHighlightBackground) {
                    Modifier
                } else {
                    Modifier.background(highlightBackground, highlightShape)
                }
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = FoamTheme.colors.textPrimary),
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(horizontal = ListRowHighlightPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (density != MediaDensity.ROW_SMALL) {
            Box(
                modifier = Modifier
                    .size(artSize)
                    .shadow(elevation = artworkElevation, shape = artworkShape)
                    .then(artworkModifier),
                contentAlignment = Alignment.Center
            ) {
                artworkContent()
            }

            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = FoamTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier
            )
            if (subtitle != null && density != MediaDensity.ROW_SMALL) {
                BasicText(
                    text = subtitle,
                    style = FoamTheme.typography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = subtitleModifier
                )
            }
            if (density == MediaDensity.ROW_BIG && tertiaryText != null) {
                BasicText(
                    text = tertiaryText,
                    style = FoamTheme.typography.caption.copy(color = FoamTheme.colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        trailingContent()
    }
}

/**
 * Marks a list row's artwork as the source of a shared-element transition.
 * No-ops when not [enabled] or when the transition scopes are missing.
 * [overlayClipShape] keeps the given OverlayClip applied to every frame;
 * [overlayClip] passes a raw Shape instead; [boundsTransform] overrides the
 * default spring.
 *
 * [collectionFlight] registers the element STRUCTURALLY STABLY (always applied)
 * and gates it dynamically through [collectionFlightConfig] to the row whose
 * collection was clicked — see collectionRowFlightArtwork.
 */
@Composable
internal fun Modifier.rowSharedArtwork(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    enabled: Boolean = true,
    collectionFlight: Boolean = false,
    overlayClip: Shape? = null,
    overlayClipShape: SharedTransitionScope.OverlayClip? = null,
    boundsTransform: BoundsTransform? = null
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return this
    if (!collectionFlight && !enabled) return this
    return with(sharedTransitionScope) {
        val tagState = LocalActiveSharedCollectionTagState.current
        val state = if (collectionFlight) {
            rememberSharedContentState(key = key, config = collectionFlightConfig(tagState, key))
        } else {
            rememberSharedContentState(key = key)
        }
        val resolvedOverlayClip: SharedTransitionScope.OverlayClip? =
            overlayClipShape ?: overlayClip?.let { OverlayClip(it) }
        when {
            resolvedOverlayClip != null && boundsTransform != null -> sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = boundsTransform,
                clipInOverlayDuringTransition = resolvedOverlayClip
            )
            resolvedOverlayClip != null -> sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                clipInOverlayDuringTransition = resolvedOverlayClip
            )
            boundsTransform != null -> sharedElement(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = boundsTransform
            )
            else -> sharedElement(state, animatedVisibilityScope = animatedVisibilityScope)
        }
    }
}

/**
 * Marks a list row's text as the source of a shared-bounds transition. The
 * enter/exit fades ride the same duration/easing tween as the bounds flight
 * instead of sharedBounds' spring defaults — with the defaults both texts
 * stayed opaque well into the flight, reading as a scale pop plus a dirty
 * crossfade; tweening in lock-step makes the sides swap cleanly at midpoint.
 * zIndexInOverlay 1f keeps the text above the flying artwork (0f).
 */
@Composable
internal fun Modifier.rowSharedText(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    enabled: Boolean = true,
    collectionFlight: Boolean = false,
    boundsTransform: BoundsTransform? = null
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return this
    if (!collectionFlight && !enabled) return this
    return with(sharedTransitionScope) {
        val tagState = LocalActiveSharedCollectionTagState.current
        val state = if (collectionFlight) {
            rememberSharedContentState(key = key, config = collectionFlightConfig(tagState, key))
        } else {
            rememberSharedContentState(key = key)
        }
        val fadeSpec = FoamMotion.Spec.sharedElementFlight
        val resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.Center
        )
        if (boundsTransform != null) {
            sharedBounds(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(fadeSpec),
                exit = fadeOut(fadeSpec),
                resizeMode = resizeMode,
                boundsTransform = boundsTransform,
                zIndexInOverlay = 1f
            )
        } else {
            sharedBounds(
                state,
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(fadeSpec),
                exit = fadeOut(fadeSpec),
                resizeMode = resizeMode,
                zIndexInOverlay = 1f
            )
        }
    }
}
